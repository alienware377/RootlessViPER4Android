#!/usr/bin/env bash
# Packages the experimental AIDL module from libraries built by build.sh.
#
#   magisk/aidl/package.sh <libs dir> <output zip> [abis] [versions]
#
# By default the zip holds every effect HAL version's library for each ABI;
# the installer copies only the one matching the phone and deletes the rest.
# With versions (e.g. "3") it holds only those, and refuses a phone that
# needs another.
# This is never part of the normal rv4a_engine release module.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBS="$1"
ZIP="$2"
ABIS="${3:-arm64-v8a}"
VERSIONS="${4:-2 3 4}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

cp -r "$HERE/module/." "$STAGE/"
for abi in $ABIS; do
  for v in $VERSIONS; do
    f="$LIBS/$abi/librv4a_aidl_v$v.so"
    [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
    mkdir -p "$STAGE/libs/$abi"
    cp "$f" "$STAGE/libs/$abi/"
  done
done

# A carriage return in any of these is not cosmetic: in post-fs-data.sh it
# would create a file named "disable<CR>", which no root manager recognises,
# so the boot watchdog would be dead while the module stayed active.
for f in "$STAGE"/*.sh "$STAGE"/common/*.sh "$STAGE/module.prop"; do
  if grep -q "$(printf '\r')" "$f"; then
    echo "REFUSING TO PACKAGE: carriage returns in $(basename "$f")" >&2
    exit 1
  fi
  case "$f" in *.sh) sh -n "$f" || { echo "REFUSING TO PACKAGE: $(basename "$f") is not valid shell" >&2; exit 1; } ;; esac
done

rm -f "$ZIP"
mkdir -p "$(dirname "$ZIP")"
ZIPABS="$(cd "$(dirname "$ZIP")" && pwd)/$(basename "$ZIP")"
# zip keeps forward slashes; archives made with backslashes install as files
# with backslashes in their names and silently do nothing.
if command -v zip >/dev/null 2>&1; then
  (cd "$STAGE" && zip -qr "$ZIPABS" .)
else
  PY=""
  for c in python3 python; do "$c" -c "import zipfile" >/dev/null 2>&1 && { PY="$c"; break; }; done
  [ -n "$PY" ] || { echo "need zip or python to package" >&2; exit 1; }
  (cd "$STAGE" && "$PY" - "$ZIPABS" <<'PY'
import os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk("."):
        for name in files:
            p = os.path.join(root, name)
            z.write(p, os.path.relpath(p, ".").replace(os.sep, "/"))
PY
  )
fi
echo "packaged $ZIP ($(wc -c < "$ZIP") bytes)"
