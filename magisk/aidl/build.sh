#!/usr/bin/env bash
# Builds the AIDL effect plugin for every effect HAL version and ABI, strips
# it and checks each library the way the device's effect factory will treat it.
#
#   magisk/aidl/build.sh [-o out] [-v "2 3 4"] [-a "arm64-v8a x86_64"] [--harness]
#
# Output: <out>/libs/<abi>/librv4a_aidl_v<N>.so, stripped and checked.
# With --harness, x86_64 builds also produce <out>/build/v<N>-x86_64/aidl_plugin_harness
# (see tools/run_aidl_harness.sh).
#
# Finds the NDK from ANDROID_NDK_HOME, ANDROID_NDK_LATEST_HOME or the newest
# one under the SDK, and uses cmake and ninja from PATH or from the SDK.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
VERSIONS="2 3 4"
ABIS="arm64-v8a x86_64"
HARNESS=OFF
while [ $# -gt 0 ]; do
  case "$1" in
    -o) OUT="$2"; shift 2 ;;
    -v) VERSIONS="$2"; shift 2 ;;
    -a) ABIS="$2"; shift 2 ;;
    --harness) HARNESS=ON; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ]; then
  NDK="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
  NDK="${NDK%/}"
fi
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "no NDK found (tried '$NDK')" >&2; exit 1; }

pick() {  # first of: on PATH, then under the SDK's cmake
  if command -v "$1" >/dev/null 2>&1; then command -v "$1"; return; fi
  local f; f="$(ls "$SDK"/cmake/*/bin/"$1"* 2>/dev/null | sort -V | tail -1)"
  [ -n "$f" ] || { echo "no $1 found" >&2; exit 1; }
  echo "$f"
}
CMAKE="$(pick cmake)"
NINJA="$(pick ninja)"
BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
STRIP="$BIN/llvm-strip"; [ -x "$STRIP" ] || STRIP="$BIN/llvm-strip.exe"

echo "NDK $NDK"
mkdir -p "$OUT/libs" "$OUT/build"
for v in $VERSIONS; do
  bash "$HERE/generate.sh" "$v" "$HERE/generated/v$v" >/dev/null
  for abi in $ABIS; do
    bd="$OUT/build/v$v-$abi"
    h=OFF; [ "$abi" = x86_64 ] && h=$HARNESS
    "$CMAKE" -S "$HERE" -B "$bd" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-34 -DANDROID_STL=c++_static \
      -DCMAKE_BUILD_TYPE=Release -DRV4A_HAL_VERSION="$v" -DRV4A_HARNESS="$h" >/dev/null
    "$CMAKE" --build "$bd"
    mkdir -p "$OUT/libs/$abi"
    "$STRIP" --strip-unneeded "$bd/librv4a_aidl_v$v.so" -o "$OUT/libs/$abi/librv4a_aidl_v$v.so"
    bash "$HERE/check-elf.sh" "$OUT/libs/$abi/librv4a_aidl_v$v.so" "$BIN"
  done
done
echo "built: $(cd "$OUT/libs" && find . -name '*.so' | sort | tr '\n' ' ')"
