#!/usr/bin/env bash
# Builds the x86_64 AIDL plugin and tools/aidl_plugin_harness.cpp for each
# effect HAL version, pushes them to an emulator under /data/local/tmp and
# runs the harness against each. Nothing is installed on the device.
#
#   tools/run_aidl_harness.sh [serial] [versions]    e.g. emulator-5554 "2 3 4"
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-emulator-5554}"
VERSIONS="${2:-2 3 4}"
OUT="${RV4A_AIDL_OUT:-$ROOT/magisk/aidl/out}"
DEV=/data/local/tmp/rv4a_aidl
# Git Bash on Windows rewrites anything that looks like a Unix path; device
# paths must reach adb untouched, local ones must not.
dadb() { MSYS_NO_PATHCONV=1 adb -s "$SERIAL" "$@"; }

bash "$ROOT/magisk/aidl/build.sh" -o "$OUT" -a x86_64 -v "$VERSIONS" --harness
dadb shell mkdir -p "$DEV"
fail=0
for v in $VERSIONS; do
  dadb push "$OUT/libs/x86_64/librv4a_aidl_v$v.so" "$DEV/" >/dev/null
  dadb push "$OUT/build/v$v-x86_64/aidl_plugin_harness" "$DEV/aidl_plugin_harness_v$v" >/dev/null
  dadb shell chmod 755 "$DEV/aidl_plugin_harness_v$v"
  dadb shell "$DEV/aidl_plugin_harness_v$v $DEV/librv4a_aidl_v$v.so" || fail=1
done
exit $fail
