#!/usr/bin/env bash
#
# Builds librubberband.so for all four Android ABIs, plus the checksums the app
# verifies a download against.
#
#   bash third_party/rubberband/android/build.sh [outdir]
#
# Output: <outdir>/<abi>/librubberband.so and <outdir>/SHA256SUMS
# Default outdir is build/rubberband.
#
# The app will not load a downloaded library whose hash is not the one it was
# built expecting, so SHA256SUMS is part of the artefact rather than a courtesy:
# it is what stops a half-finished download, a corrupted mirror or a substituted
# file from being handed to dlopen.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="${1:-$root/build/rubberband}"

: "${ANDROID_NDK_HOME:=${ANDROID_NDK_ROOT:-${LOCALAPPDATA:-$HOME}/Android/Sdk/ndk}}"
if [ -d "$ANDROID_NDK_HOME" ] && [ ! -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]; then
    # Pointed at the ndk/ directory rather than one version inside it.
    if [ ! -d "$ANDROID_NDK_HOME/toolchains" ]; then
        ANDROID_NDK_HOME="$(ls -d "$ANDROID_NDK_HOME"/* 2>/dev/null | sort -V | tail -1)"
    fi
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) hosttag="windows-x86_64"; sfx=".cmd" ;;
    Darwin)               hosttag="darwin-x86_64"; sfx="" ;;
    *)                    hosttag="linux-x86_64";  sfx="" ;;
esac
bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$hosttag/bin"
[ -d "$bin" ] || { echo "No NDK toolchain at $bin" >&2; exit 1; }

# 21 is below the app's own minimum either way, so the library never rules out
# a device the app itself would have run on.
API=21
abis=("armeabi-v7a:armv7a-linux-androideabi" \
      "arm64-v8a:aarch64-linux-android" \
      "x86:i686-linux-android" \
      "x86_64:x86_64-linux-android")

mkdir -p "$out"
for entry in "${abis[@]}"; do
    abi="${entry%%:*}"
    triple="${entry##*:}"
    cxx="$bin/${triple}${API}-clang++${sfx}"
    [ -x "$cxx" ] || cxx="$bin/${triple}${API}-clang++"
    echo "==> $abi"
    mkdir -p "$out/$abi"
    # A version script, not -fvisibility=hidden. The flag looked like it did the
    # job - the library shrank and still linked - but it hid the C API too, so
    # the result loaded fine and had nothing the app could call. The script
    # names what stays reachable instead of leaving it to a blanket rule.
    "$cxx" -shared -O2 -fPIC -std=c++14 \
        -Wl,--version-script="$here/exports.map" \
        -ffunction-sections -fdata-sections -Wl,--gc-sections \
        -static-libstdc++ \
        -I "$here/.." -I "$here/../rubberband" \
        "$here/rubberband_android.cpp" \
        -o "$out/$abi/librubberband.so"
    "$bin/llvm-strip" --strip-unneeded "$out/$abi/librubberband.so"
    ls -la "$out/$abi/librubberband.so" | awk '{print "    " $5 " bytes"}'
done

( cd "$out" && sha256sum */librubberband.so > SHA256SUMS )
echo
echo "checksums:"
cat "$out/SHA256SUMS"
