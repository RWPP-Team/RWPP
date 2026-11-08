#!/usr/bin/env python3
"""Publish the verified release archives to Cloudflare R2 and refresh the runtime registry.

This runs inside the GitHub Actions release job, after the same four archives have
been attached to the GitHub release. One build therefore supplies both download
sources. No second build, local workstation, or manual download is involved.

Required environment:
    R2_ENDPOINT           https://<account>.r2.cloudflarestorage.com
    R2_ACCESS_KEY_ID      R2 S3 access key id
    R2_SECRET_ACCESS_KEY  R2 S3 secret access key
    RELEASE_TAG           release tag, for example v2.0.0-beta04
    GITHUB_REPOSITORY     owner/repo, used for the fallback address

Optional environment:
    R2_BUCKET             defaults to rwpp
    ASSETS_DIR            defaults to release-assets
    REGISTRY_KEY          defaults to extensions/registry.json

Usage:
    python3 scripts/publish-r2.py [--dry-run]
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import hmac
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REGION = "auto"
SERVICE = "s3"
ARCHIVE_BASE = "https://rwpp-team.top/api/archives"
ENTRY_ID = "io.github.rwpp"
DESCRIPTOR = "META-INF/extension.properties"
PLATFORM_ORDER = ("jvm", "android")
ARTIFACT_ORDER = ("rwpp-api", "rwpp-client")


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"missing environment variable: {name}")
    return value


ENDPOINT = os.environ.get("R2_ENDPOINT", "").rstrip("/")
ACCESS_KEY = os.environ.get("R2_ACCESS_KEY_ID", "")
SECRET_KEY = os.environ.get("R2_SECRET_ACCESS_KEY", "")
BUCKET = os.environ.get("R2_BUCKET", "rwpp").strip() or "rwpp"


def hmac_sha256(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()


def signed_headers(method: str, canonical_uri: str, canonical_query: str,
                   payload_hash: str, extra: dict[str, str] | None = None) -> dict[str, str]:
    host = urllib.parse.urlsplit(ENDPOINT).netloc
    now = datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    headers = {
        "host": host,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
    }
    if extra:
        headers.update({key.lower(): value for key, value in extra.items()})
    canonical_headers = "".join(f"{key}:{headers[key].strip()}\n" for key in sorted(headers))
    signed = ";".join(sorted(headers))
    canonical_request = "\n".join([
        method, canonical_uri, canonical_query, canonical_headers, signed, payload_hash,
    ])
    scope = f"{date_stamp}/{REGION}/{SERVICE}/aws4_request"
    string_to_sign = "\n".join([
        "AWS4-HMAC-SHA256", amz_date, scope,
        hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
    ])
    key = hmac_sha256(("AWS4" + SECRET_KEY).encode("utf-8"), date_stamp)
    for part in (REGION, SERVICE, "aws4_request"):
        key = hmac_sha256(key, part)
    signature = hmac.new(key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    headers = {key: value for key, value in headers.items() if key != "host"}
    headers["Authorization"] = (
        f"AWS4-HMAC-SHA256 Credential={ACCESS_KEY}/{scope}, "
        f"SignedHeaders={signed}, Signature={signature}"
    )
    return headers


def s3_request(method: str, key: str, body: bytes | None = None,
               content_type: str | None = None, attempts: int = 3) -> tuple[int, bytes]:
    canonical_uri = "/" + BUCKET + "/" + urllib.parse.quote(key, safe="/")
    payload = body if body is not None else b""
    payload_hash = hashlib.sha256(payload).hexdigest()
    extra = {"Content-Type": content_type} if content_type else None
    last_error: Exception | None = None
    for attempt in range(attempts):
        # Sign each attempt: the x-amz-date header must stay inside R2's clock window.
        headers = signed_headers(method, canonical_uri, "", payload_hash, extra)
        request = urllib.request.Request(
            ENDPOINT + canonical_uri,
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=900) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            status, response_body = error.code, error.read()
            if 500 <= status < 600 and attempt + 1 < attempts:
                print(f"retry {method} {key}: HTTP {status}", file=sys.stderr)
                time.sleep(2 ** attempt)
                continue
            return status, response_body
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
            if attempt + 1 < attempts:
                print(f"retry {method} {key}: {type(error).__name__}", file=sys.stderr)
                time.sleep(2 ** attempt)
                continue
    raise SystemExit(f"{method} {key} failed after {attempts} attempts: {last_error}")


def ok(status: int, body: bytes, action: str) -> None:
    if not 200 <= status < 300:
        raise SystemExit(f"{action} failed: HTTP {status} {body[:400].decode('utf-8', 'replace')}")


def digest(path: pathlib.Path) -> tuple[str, int]:
    value = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            value.update(chunk)
            size += len(chunk)
    return value.hexdigest(), size


def descriptor(path: pathlib.Path) -> dict[str, str]:
    with zipfile.ZipFile(path) as archive:
        raw = archive.read(DESCRIPTOR)
    properties = {}
    for line in raw.decode("utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    if not properties.get("id") or not properties.get("version"):
        raise SystemExit(f"{path.name} declares no id or version")
    return properties


def collect(assets: pathlib.Path, version: str) -> list[tuple[str, pathlib.Path, dict[str, str], str, int]]:
    found = []
    for platform in PLATFORM_ORDER:
        for prefix in ARTIFACT_ORDER:
            matches = sorted(assets.glob(f"{prefix}-*-{platform}.jar"))
            if len(matches) != 1:
                raise SystemExit(f"expected one {prefix}-*-{platform}.jar, found {matches}")
            path = matches[0]
            properties = descriptor(path)
            if properties["version"] != version:
                raise SystemExit(f"{path.name} declares {properties['version']}, expected {version}")
            sha, size = digest(path)
            found.append((platform, path, properties, sha, size))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="compute the registry without writing to R2")
    arguments = parser.parse_args()

    for name in ("R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY",
                 "RELEASE_TAG", "GITHUB_REPOSITORY"):
        required(name)

    tag = required("RELEASE_TAG")
    version = tag.lstrip("v")
    repository = required("GITHUB_REPOSITORY")
    assets = pathlib.Path(os.environ.get("ASSETS_DIR", "release-assets"))
    registry_key = os.environ.get("REGISTRY_KEY", "extensions/registry.json")
    github_base = f"https://github.com/{repository}/releases/download/{tag}"

    archives = collect(assets, version)
    status, body = s3_request("GET", registry_key)
    ok(status, body, f"read {registry_key}")
    registry = json.loads(body)

    entries = registry.get("extensions")
    if not isinstance(entries, list):
        raise SystemExit("registry has no extensions array")
    entry = next((item for item in entries if item.get("id") == ENTRY_ID), None)
    if entry is None:
        raise SystemExit(f"registry has no entry for {ENTRY_ID}")

    artifacts = []
    sources = {}
    for platform, path, properties, sha, size in archives:
        name = path.name
        github = f"{github_base}/{urllib.parse.quote(name)}"
        artifacts.append({
            "id": properties["id"],
            "platform": platform,
            "url": f"{ARCHIVE_BASE}/{urllib.parse.quote(name)}",
            "sources": {"github": github},
            "sha256": sha,
            "size": size,
        })
        sources[name] = {"github": github, "size": size, "sha256": sha}
        print(f"{platform:7} {properties['id']:24} {size:>10} bytes  {sha[:12]}…")

    entry["version"] = version
    api_versions = [properties.get("apiVersion") for _, _, properties, _, _ in archives if properties.get("apiVersion")]
    if api_versions:
        entry["apiVersion"] = api_versions[0]
    entry["platforms"] = list(PLATFORM_ORDER)
    entry["updatedAt"] = datetime.date.today().isoformat()
    entry["artifacts"] = artifacts
    registry["sources"] = sources
    registry["generatedAt"] = (
        datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0).isoformat().replace("+00:00", "Z")
    )

    payload = (json.dumps(registry, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    print(f"registry: {entry['version']}, {len(artifacts)} archives, {len(payload)} bytes")
    if arguments.dry_run:
        dump = os.environ.get("DRY_RUN_PAYLOAD")
        if dump:
            pathlib.Path(dump).write_bytes(payload)
        print("dry run: nothing written")
        return 0

    for platform, path, _, _, size in archives:
        status, body = s3_request("PUT", f"extensions/{path.name}", path.read_bytes(),
                                  "application/java-archive")
        ok(status, body, f"upload {path.name}")
        print(f"PUT extensions/{path.name}: {size} bytes HTTP {status}")

    status, body = s3_request("PUT", registry_key, payload, "application/json")
    ok(status, body, f"write {registry_key}")
    wanted = hashlib.sha256(payload).hexdigest()
    status, readback = s3_request("GET", registry_key)
    ok(status, readback, f"read back {registry_key}")
    if hashlib.sha256(readback).hexdigest() != wanted:
        raise SystemExit("registry read-back digest mismatch")
    print(f"registry published: {wanted}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
