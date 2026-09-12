#!/usr/bin/env bash
# Static verification for macOS compatibility changes (no full RW install required).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0

check() {
    local desc="$1"
    shift
    if "$@"; then
        echo "OK  $desc"
    else
        echo "FAIL $desc"
        fail=1
    fi
}

check "launcher.sh is executable" test -x "$ROOT/launcher.sh"
check "steam-macos.sh is executable" test -x "$ROOT/steam-macos.sh"
check "Platform.kt exists" test -f "$ROOT/rwpp-desktop/src/main/kotlin/io/github/rwpp/desktop/Platform.kt"
check "build.gradle uses OS classpath separator" grep -q 'classpathSeparator' "$ROOT/rwpp-desktop/build.gradle.kts"
check "build.gradle has macOS icon block" grep -q 'macOS {' "$ROOT/rwpp-desktop/build.gradle.kts"
check "Main.kt uses Platform.restartCurrentProcess" grep -q 'Platform.restartCurrentProcess' "$ROOT/rwpp-desktop/src/main/kotlin/io/github/rwpp/desktop/Main.kt"
check "Main.kt uses Platform.warnIfNativesMissing" grep -q 'Platform.warnIfNativesMissing' "$ROOT/rwpp-desktop/src/main/kotlin/io/github/rwpp/desktop/Main.kt"
check "Main.kt warns Apple Silicon" grep -q 'Platform.warnAppleSiliconIfNeeded' "$ROOT/rwpp-desktop/src/main/kotlin/io/github/rwpp/desktop/Main.kt"
check "no hardcoded RWPP.exe restart" bash -c '! grep -q "RWPP\\.exe\")" "$0"' "$ROOT/rwpp-desktop/src/main/kotlin/io/github/rwpp/desktop/Main.kt"
check "GameLibraries has natives-macos" grep -q 'natives-macos' "$ROOT/rwpp-core-api/src/main/kotlin/io/github/rwpp/inject/GameLibraries.kt"
check "README documents macOS" grep -q '## macOS' "$ROOT/README.md"
check "README documents DMG" grep -q 'packageDmg' "$ROOT/README.md"
check "README documents Rosetta / x86_64" grep -q 'x86_64' "$ROOT/README.md"
check "launcher.sh matches RWPP-macos" grep -q 'RWPP-macos' "$ROOT/launcher.sh"
check "steam-macos.sh references RWPP.app" grep -q 'RWPP.app' "$ROOT/steam-macos.sh"

# Classpath must not be Windows-only ';' on this host when building Unix packages
if [[ "$(uname -s)" == "Darwin" || "$(uname -s)" == "Linux" ]]; then
    check "Unix build uses ':' classpath in gradle" grep -q 'classpathSeparator' "$ROOT/rwpp-desktop/build.gradle.kts"
fi

# bash -n syntax check
check "launcher.sh syntax" bash -n "$ROOT/launcher.sh"
check "steam-macos.sh syntax" bash -n "$ROOT/steam-macos.sh"

if [[ "$fail" -ne 0 ]]; then
    echo "Verification failed."
    exit 1
fi
echo "All static macOS compatibility checks passed."
