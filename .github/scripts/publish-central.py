"""Package and publish one Maven component family, or resume its deployment."""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

GROUP = "io.github.rwpp-team"
GROUP_PATH = GROUP.replace(".", "/")
RWPP_PROJECTS = ("rwpp-api", "rwpp-client")
MODULES = {
    project: {project + variant for variant in ("", "-jvm", "-android")}
    for project in RWPP_PROJECTS
}
MODULES["rwpp"] = set.union(*(MODULES[project] for project in RWPP_PROJECTS))
MODULES["rwml"] = {"rwml-core", "game-lib"}
# These releases already have a Portal deployment created before GitHub Actions took over.
PREVIOUSLY_SUBMITTED = {"rwml": {"1.0.4"}, "rwpp": {"2.0.1.1"}}
RECORD_DIR = Path(os.environ.get("CENTRAL_RECORD_DIR", "build"))
RECORD = RECORD_DIR / "central-deployment.json"
NEW_RECORD = RECORD_DIR / "new-central-deployment"
PORTAL = "https://central.sonatype.com/api/v1/publisher/"
REPOSITORY = "https://repo.maven.apache.org/maven2/"


def release_version(tag):
    version = tag.removeprefix("v")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", version):
        raise ValueError(f"Expected a stable version tag, got {tag!r}")
    return version


def archive_entries(archives):
    for path in archives:
        with ZipFile(path) as archive:
            for entry in archive.infolist():
                if not entry.is_dir():
                    yield entry.filename, archive.read(entry)


def staging_entries(staging, project, version):
    for module in sorted(MODULES[project]):
        directory = staging / GROUP_PATH / module / version
        for file in directory.iterdir():
            if file.is_file() and ".asc." not in file.name:
                yield file.relative_to(staging).as_posix(), file.read_bytes()


def prepare_bundle(project, version, entries):
    files = {}
    expected = MODULES[project]
    for name, content in entries:
        suffix = name.split("/")[len(GROUP_PATH.split("/")):]
        if (len(suffix) != 3 or not name.startswith(GROUP_PATH + "/")
                or suffix[0] not in expected or suffix[1] != version
                or not suffix[2].startswith((f"{suffix[0]}-{version}.", f"{suffix[0]}-{version}-"))):
            raise ValueError(f"Unexpected file in the Central bundle: {name}")
        if name in files:
            raise ValueError(f"Duplicate file in the Central bundle: {name}")
        files[name] = content

    for module in expected:
        base = f"{GROUP_PATH}/{module}/{version}/{module}-{version}"
        extension = ".aar" if module.endswith("-android") else ".jar"
        required = (".pom", extension, "-sources.jar", "-javadoc.jar")
        if project in RWPP_PROJECTS:
            required += (".module",)
        for suffix in required:
            path = base + suffix
            if not files.get(path) or not files.get(path + ".asc"):
                raise ValueError(f"Missing artifact or signature: {path}")
            for algorithm in ("md5", "sha1"):
                digest = hashlib.new(algorithm, files[path]).hexdigest()
                if digest != files.get(path + "." + algorithm, b"").decode("ascii").strip():
                    raise ValueError(f"Incorrect {algorithm} checksum: {path}")
        pom = ET.fromstring(files[base + ".pom"])
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        coordinates = tuple(pom.findtext(f"m:{part}", namespaces=namespace)
                            for part in ("groupId", "artifactId", "version"))
        if coordinates != (GROUP, module, version):
            raise ValueError(f"Unexpected coordinates in {base}.pom: {coordinates}")

    result = io.BytesIO()
    with ZipFile(result, "w", compression=ZIP_DEFLATED) as archive:
        for name in sorted(files):
            archive.writestr(name, files[name])
    return result.getvalue()


def request_portal(path, body=b"", content_type=None):
    username, password = central_credentials()
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    headers = {"Authorization": "Bearer " + token, "User-Agent": "RWPP GitHub release"}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(PORTAL + path, body, headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.read().decode().strip()
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Central returned HTTP {error.code}") from None


def central_credentials():
    username = os.environ.get("CENTRAL_USERNAME")
    password = os.environ.get("CENTRAL_TOKEN")
    if not username or not password:
        raise ValueError("Configure CENTRAL_USERNAME and CENTRAL_TOKEN in GitHub Actions")
    return username, password


def published_modules(project, version):
    present = set()
    for module in sorted(MODULES[project]):
        pom = f"{GROUP_PATH}/{module}/{version}/{module}-{version}.pom"
        request = urllib.request.Request(REPOSITORY + pom, method="HEAD")
        try:
            with urllib.request.urlopen(request, timeout=30):
                present.add(module)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    return present


def record_name(project, version):
    return f"central-deployment-{project}-{version}"


def restore_record(project, version):
    if RECORD.exists():
        return
    repo = os.environ["GITHUB_REPOSITORY"]
    projects = (project, "rwpp") if project in RWPP_PROJECTS else (project,)
    for candidate in projects:
        result = subprocess.check_output([
            "gh", "api", "--method", "GET", f"repos/{repo}/actions/artifacts",
            "-f", f"name={record_name(candidate, version)}",
        ])
        artifacts = [entry for entry in json.loads(result)["artifacts"] if not entry["expired"]]
        if not artifacts:
            continue
        latest = max(artifacts, key=lambda entry: entry["id"])
        archive = subprocess.check_output(["gh", "api", f"repos/{repo}/actions/artifacts/{latest['id']}/zip"])
        with ZipFile(io.BytesIO(archive)) as artifact:
            write_record(json.loads(artifact.read(RECORD.name)))
        return


def write_record(record):
    RECORD.parent.mkdir(parents=True, exist_ok=True)
    RECORD.write_text(json.dumps(record) + "\n", encoding="utf-8")


def deployment_state(deployment_id):
    query = urllib.parse.urlencode({"id": str(uuid.UUID(deployment_id))})
    return json.loads(request_portal("status?" + query))["deploymentState"]


def submit(project, version, archives):
    bundle = prepare_bundle(project, version, archive_entries(archives))
    historical_project = "rwpp" if project in RWPP_PROJECTS else project
    if version in PREVIOUSLY_SUBMITTED[historical_project]:
        print("This release already has a pre-CI Central deployment. No deployment submitted.", flush=True)
        return
    restore_record(project, version)
    if RECORD.exists():
        record = json.loads(RECORD.read_text(encoding="utf-8"))
        failed_combined = (project in RWPP_PROJECTS and record["project"] == "rwpp"
                           and record["version"] == version and record["id"] is not None
                           and deployment_state(record["id"]) == "FAILED")
        if not failed_combined:
            print("Resuming the stored deployment for this version.", flush=True)
            return
        print("The previous combined deployment failed. Checking this component's coordinates.", flush=True)
        RECORD.unlink()
    present = published_modules(project, version)
    if present == MODULES[project]:
        print("All release coordinates already exist in Central. No deployment submitted.", flush=True)
        return
    if present:
        raise ValueError(f"Release is partially published. No deployment submitted: {sorted(present)}")

    central_credentials()
    # Preserve a record even if the request times out after Central accepted it.
    # The rerun then requires a Portal check rather than risking another deployment.
    record = {"project": project, "version": version, "id": None}
    write_record(record)
    NEW_RECORD.touch()
    boundary = "GitHub" + uuid.uuid4().hex
    package = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="{project}-{version}.zip"\r\n'
        "Content-Type: application/zip\r\n\r\n"
    ).encode() + bundle + f"\r\n--{boundary}--\r\n".encode()
    query = urllib.parse.urlencode({"publishingType": "AUTOMATIC", "name": f"{project}-{version}"})
    record["id"] = str(uuid.UUID(request_portal("upload?" + query, package, f"multipart/form-data; boundary={boundary}")))
    write_record(record)
    print(f"Central deployment {record['id']} accepted.", flush=True)


def wait_for_publish(project, version):
    record = json.loads(RECORD.read_text(encoding="utf-8"))
    projects = (project, "rwpp") if project in RWPP_PROJECTS else (project,)
    if record["project"] not in projects or record["version"] != version:
        raise ValueError("Stored Central deployment belongs to another release")
    if record["id"] is None:
        raise ValueError("Submission result is unknown. Check the Central Portal before retrying.")
    deadline = time.monotonic() + 1200
    previous = None
    while time.monotonic() < deadline:
        state = deployment_state(record["id"])
        if state != previous:
            print(f"Central deployment {record['id']}: {state}", flush=True)
            previous = state
        if state == "PUBLISHED":
            return
        if state == "FAILED":
            raise RuntimeError(f"Central rejected deployment {record['id']}; inspect its validation errors in the Portal")
        time.sleep(20)
    raise TimeoutError("Central has not finished publishing. Rerunning the workflow resumes the same deployment.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("pack", "verify", "submit", "wait"))
    parser.add_argument("project", choices=MODULES)
    parser.add_argument("tag")
    parser.add_argument("archives", nargs="*", type=Path)
    args = parser.parse_args()
    version = release_version(args.tag)
    if args.command == "pack":
        if len(args.archives) != 2:
            parser.error("pack requires a local staging directory and an output ZIP")
        staging, output = args.archives
        bundle = prepare_bundle(args.project, version, staging_entries(staging, args.project, version))
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(bundle)
        print(f"Packaged {args.project} {version} as {output}.")
    elif args.command == "verify":
        prepare_bundle(args.project, version, archive_entries(args.archives))
        print(f"Verified {args.project} {version} Maven coordinates, signatures and checksums.")
    elif args.command == "submit":
        submit(args.project, version, args.archives)
    else:
        wait_for_publish(args.project, version)


if __name__ == "__main__":
    main()
