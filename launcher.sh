#!/bin/bash

#
# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
#

# Run from the Rusted Warfare game root so java.library.path can find LWJGL natives.
DestPath="$(cd "$(dirname "$0")" && pwd)"
DestExt="*.jar*"

count=0
lastName=""

is_rwpp_jar() {
    local filename="$1"
    [[ "$filename" == RWPP-multiplatform* ]] ||
        [[ "$filename" == RWPP-macos* ]] ||
        [[ "$filename" == RWPP-linux* ]] ||
        [[ "$filename" == RWPP-windows* ]]
}

is_x86_64_java() {
    local bin="$1"
    [[ -x "$bin" ]] || return 1
    file -b "$bin" 2>/dev/null | grep -Eq 'x86_64|i386'
}

while IFS= read -r file; do
    filename="$(basename "$file")"
    if is_rwpp_jar "$filename"; then
        count=$((count + 1))
        lastName="$file"
    fi
done < <(find "$DestPath" -maxdepth 1 -type f -name "$DestExt")

if [[ "$count" -eq 0 ]]; then
    echo "No RWPP jar found (expected RWPP-macos.jar / RWPP-multiplatform* / RWPP-linux*)."
    echo "Copy the Compose uber-jar from:"
    echo "  rwpp-desktop/build/compose/jars/RWPP-macos.jar"
    echo "(~70MB+). Do NOT use rwpp-desktop/build/libs/rwpp-desktop.jar (thin module jar)."
    read -r -p "Press Enter to continue..."
    exit 1
elif [[ "$count" -gt 1 ]]; then
    echo "Multiple RWPP jars found; keep only one:"
    find "$DestPath" -maxdepth 1 -type f -name "$DestExt" -exec basename {} \;
    read -r -p "Press Enter to continue..."
    exit 1
fi

# Thin module jars (~hundreds of KB) lack kotlin-stdlib and fail with NoClassDefFoundError: Function1.
jar_size="$(wc -c < "$lastName" | tr -d ' ')"
if [[ "$jar_size" -lt 5000000 ]]; then
    echo "ERROR: $(basename "$lastName") is only ${jar_size} bytes — this is not the uber-jar."
    echo "Use rwpp-desktop/build/compose/jars/RWPP-macos.jar (usually ~70MB+), built with:"
    echo "  ./gradlew :rwpp-desktop:packageReleaseUberJarForCurrentOS"
    read -r -p "Press Enter to continue..."
    exit 1
fi

# Prefer java.library.path over DYLD_LIBRARY_PATH (SIP often strips DYLD_LIBRARY_PATH).
# DYLD_FALLBACK_LIBRARY_PATH still works and is what rustedWarfareMac.sh uses so
# librocketConnector can resolve @rpath/libRocketCore.1.dylib etc.
export LD_LIBRARY_PATH="${DestPath}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if [[ "$(uname -s)" == "Darwin" ]]; then
    export DYLD_FALLBACK_LIBRARY_PATH="${DestPath}${DYLD_FALLBACK_LIBRARY_PATH:+:$DYLD_FALLBACK_LIBRARY_PATH}"
fi

JAVA_CMD=()
JAVA_EXTRA_ARGS=()

# Apple Silicon: RW ships proprietary x86_64 natives (librocketConnector, libRocket*, steamworks).
# Pure arm64 JDK cannot load those. Prefer Rosetta + x86_64 JDK 17+.
if [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]]; then
    X86_JAVA=""
    for candidate in \
        "$DestPath/jvm-rwpp-x64/bin/java" \
        "$JAVA_HOME/bin/java" \
        /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java \
        /Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/java \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java
    do
        if is_x86_64_java "$candidate"; then
            # Skip the game's bundled Java 8 (too old for RWPP / Compose).
            ver="$("$candidate" -version 2>&1 | head -1 || true)"
            if echo "$ver" | grep -Eq 'version "1\.8|version "8'; then
                continue
            fi
            X86_JAVA="$candidate"
            break
        fi
    done

    if [[ -n "$X86_JAVA" ]] && command -v arch >/dev/null 2>&1; then
        JAVA_CMD=(arch -x86_64 "$X86_JAVA")
        echo "Using x86_64 Java under Rosetta (required for librocketConnector):"
        echo "  $X86_JAVA"
    else
        echo "ERROR: Apple Silicon needs an x86_64 JDK 17+ under Rosetta."
        echo
        echo "Why: librocketConnector.dylib / libRocket*.dylib / Steam natives are"
        echo "Corroding/Steam x86_64 binaries — they cannot run in an arm64 JVM."
        echo "(LWJGL/OpenAL/JInput can be patched for arm64; LibRocket connector cannot.)"
        echo
        echo "Install Temurin 17 (x64), then re-run:"
        echo "  1) Download macOS x64 JDK 17 from https://adoptium.net/"
        echo "  2) Extract to this folder as:  jvm-rwpp-x64/"
        echo "     so that this exists:       jvm-rwpp-x64/bin/java"
        echo "  3) ./launcher.sh"
        echo
        echo "Or set JAVA_HOME to an x86_64 JDK 17 and ensure 'file \$JAVA_HOME/bin/java' shows x86_64."
        read -r -p "Press Enter to continue..."
        exit 1
    fi
else
    if ! command -v java >/dev/null 2>&1; then
        echo "Java 17+ is required. Install OpenJDK 17 and ensure java is on PATH."
        read -r -p "Press Enter to continue..."
        exit 1
    fi
    JAVA_CMD=(java)
fi

# JDK 17+ only; ignore if older JVM rejects the flag.
if "${JAVA_CMD[@]}" --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
    JAVA_EXTRA_ARGS+=(--enable-native-access=ALL-UNNAMED)
fi

cd "$DestPath" || exit 1

# Uber-jar already contains Kotlin/Compose deps. Only append dirs that exist
# (generated_lib is created after inject/reload).
CP="$lastName"
for dir in generated_lib extension libs; do
    if [[ -d "$DestPath/$dir" ]]; then
        CP="$CP:$DestPath/$dir/*"
    fi
done

echo "Launching: $(basename "$lastName") ($(du -h "$lastName" | awk '{print $1}'))"
# Quote JAVA_CMD: game path often contains spaces ("Application Support").
echo "Java: $("${JAVA_CMD[@]}" -version 2>&1 | head -1)"

"${JAVA_CMD[@]}" \
    "${JAVA_EXTRA_ARGS[@]}" \
    -Djava.net.preferIPv4Stack=true \
    -Xmx2000M \
    -Dfile.encoding=UTF-8 \
    -Dprism.allowhidpi=false \
    -Djava.library.path=. \
    -cp "$CP" \
    io.github.rwpp.desktop.MainKt

read -r -p "Press Enter to continue..."
