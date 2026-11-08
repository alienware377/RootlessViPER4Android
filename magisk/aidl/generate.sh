#!/usr/bin/env bash
# Generates the NDK backend for one version of the AIDL effect HAL.
#
#   generate.sh <hal version 2|3|4> [output dir]
#
# The plugin is loaded inside the device's own effect factory and hands it C++
# objects (a Descriptor, an IEffect pointer). Those only line up when both sides
# were built from the same frozen interface version, so every version gets its
# own complete set of generated code, from its own frozen snapshots, and never
# shares a header with another version.
#
# Which packages make up each version is copied from versions_with_info in
# AOSP's hardware/interfaces/audio/aidl/Android.bp (android-17.0.0_r1).
set -euo pipefail

VER="${1:-}"
case "$VER" in
  2) SET="android.hardware.audio.effect-V2 android.hardware.audio.common-V3 android.media.audio.common.types-V3" ;;
  3) SET="android.hardware.audio.effect-V3 android.hardware.audio.common-V4 android.media.audio.common.types-V4 android.media.audio.eraser.types-V1" ;;
  4) SET="android.hardware.audio.effect-V4 android.hardware.audio.common-V5 android.media.audio.common.types-V5 android.media.audio.eraser.types-V2" ;;
  *) echo "usage: $0 <2|3|4> [output dir]" >&2; exit 2 ;;
esac
SET="$SET android.hardware.common-V2 android.hardware.common.fmq-V1"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
# build-tools 34 must not be used: it does not recognise a suppression
# annotation in AudioChannelLayout.aidl, reports it as an error, and silently
# emits only part of the package. 37 is the version this was checked with.
AIDL=""
for cand in "$SDK"/build-tools/*/aidl "$SDK"/build-tools/*/aidl.exe; do
  [ -x "$cand" ] && AIDL="$cand"
done
[ -n "$AIDL" ] || { echo "No aidl in $SDK/build-tools" >&2; exit 1; }
case "$AIDL" in
  */build-tools/3[0-4].*) echo "aidl from $AIDL is too old, install build-tools 37" >&2; exit 1 ;;
esac

HERE="$(cd "$(dirname "$0")" && pwd)"
IFACES="$HERE/interfaces"
OUT="${2:-$HERE/generated/v$VER}"

rm -rf "$OUT"; mkdir -p "$OUT/src" "$OUT/include"

# aidl.exe on Windows compares a file's directory against its import paths
# with backslashes, so hand it native paths there. Elsewhere this is a no-op.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s
' "$1"; fi; }

# Only this version's packages are visible to the generator. With every
# version on the include path, the same type name would be found in two
# snapshots and either one could win.
INC=()
for pkg in $SET; do
  [ -d "$IFACES/$pkg" ] || { echo "missing interfaces/$pkg" >&2; exit 1; }
  INC+=(-I "$(native "$IFACES/$pkg")")
done

for pkg in $SET; do
  dir="$IFACES/$pkg"
  ver="${pkg##*-V}"
  # A frozen version's hash is the last line of its .hash file, the same value
  # AOSP builds into getInterfaceHash().
  hash="$(tail -n 1 "$dir/.hash" | tr -d '\r\n ')"
  [ ${#hash} -eq 40 ] || { echo "interfaces/$pkg/.hash is not a frozen hash" >&2; exit 1; }
  files=()
  while IFS= read -r f; do files+=("$(native "$f")"); done < <(find "$dir" -name "*.aidl" | sort)
  echo "generating ${pkg%-V*} V$ver ($hash)"
  # min_sdk_version 34: the AIDL effect HAL first shipped with Android 14.
  "$AIDL" --lang=ndk --structured --stability=vintf \
      --version="$ver" --hash="$hash" --min_sdk_version=34 \
      "${INC[@]}" -o "$(native "$OUT/src")" -h "$(native "$OUT/include")" "${files[@]}"
done

echo "generated $(find "$OUT/src" -type f | wc -l) sources, $(find "$OUT/include" -type f | wc -l) headers for effect HAL V$VER"
