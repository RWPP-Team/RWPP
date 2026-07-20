#!/bin/bash

#
# Copyright 2023-2025 RWPP contributors
# Optional Steam / macOS launch wrapper for Rusted Warfare.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#

# Place this script in the Rusted Warfare macOS install directory, then either:
#   1) Run it directly, or
#   2) Point Steam launch options / rename alongside rustedWarfareMac.sh so Steam starts RWPP.
#
# This does NOT replace Windows WiX Steam integration (Rusted Warfare - 64.exe).

set -euo pipefail

GAME_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$GAME_DIR"

find_rwpp_jar() {
    local matches=()
    local f
    shopt -s nullglob
    for f in RWPP-macos*.jar RWPP-multiplatform*.jar; do
        [[ -f "$f" ]] || continue
        matches+=("$f")
    done
    shopt -u nullglob
    if [[ "${#matches[@]}" -ne 1 ]]; then
        echo "Expected exactly one RWPP jar in $GAME_DIR (found ${#matches[@]})." >&2
        exit 1
    fi
    echo "${matches[0]}"
}

if [[ -x "$GAME_DIR/RWPP.app/Contents/MacOS/RWPP" ]]; then
    exec "$GAME_DIR/RWPP.app/Contents/MacOS/RWPP" -native
fi

if [[ -x "$GAME_DIR/bin/RWPP" ]]; then
    exec "$GAME_DIR/bin/RWPP" -native
fi

if [[ -x "$GAME_DIR/launcher.sh" ]]; then
    exec "$GAME_DIR/launcher.sh"
fi

JAR="$(find_rwpp_jar)"
exec java \
    -Djava.net.preferIPv4Stack=true \
    -Xmx2000M \
    -Dfile.encoding=UTF-8 \
    -Dprism.allowhidpi=false \
    -Djava.library.path=. \
    -cp "$JAR:generated_lib/*:extension/*:libs/*" \
    io.github.rwpp.desktop.MainKt
