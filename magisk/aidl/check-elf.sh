#!/usr/bin/env bash
# Checks one plugin library the way the device's effect factory and linker
# will treat it, so a library that would be skipped or would misbehave inside
# the audio HAL fails here instead.
#
#   check-elf.sh <librv4a_aidl_vN.so> <NDK llvm bin dir>
set -euo pipefail

LIB="$1"
BIN="$2"
RE="$BIN/llvm-readelf"; [ -x "$RE" ] || RE="$BIN/llvm-readelf.exe"
name="$(basename "$LIB")"
fail=0
bad() { echo "  FAIL $name: $*"; fail=1; }

dyn="$("$RE" -d "$LIB")"
syms="$("$RE" --dyn-syms -W "$LIB")"
prog="$("$RE" -l -W "$LIB")"

# Exactly the three functions the factory looks up, nothing else defined and
# global: a leaked symbol could bind to, or be bound by, the factory's own copy.
exports="$(echo "$syms" | awk '($5=="GLOBAL"||$5=="WEAK") && $7!="UND" {print $8}' | sed 's/@.*//' | sort | tr '\n' ' ')"
[ "$exports" = "createEffect destroyEffect queryEffect " ] || bad "exports are '$exports'"

# Only libraries any vendor process may load (LLNDK). Anything else can fail
# to resolve in the factory's linker namespace, which skips the library.
needed="$(echo "$dyn" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')"
for n in $needed; do
  case "$n" in
    libbinder_ndk.so|liblog.so|libc.so|libm.so|libdl.so) ;;
    *) bad "depends on $n" ;;
  esac
done

echo "$dyn" | grep -q "(SONAME).*\[$name\]" || bad "soname is not $name"
echo "$dyn" | grep -q "TEXTREL" && bad "has text relocations"

# 16 KB pages: every LOAD segment aligned to at least 0x4000.
while read -r align; do
  [ $((align)) -ge 16384 ] || bad "LOAD segment aligned to $align"
done < <(echo "$prog" | awk '$1=="LOAD" {print $NF}')
echo "$prog" | awk '$1=="GNU_STACK"' | grep -q "RWE" && bad "executable stack"

# Nothing from libbinder_ndk newer than Android 14, the first release with an
# AIDL effect HAL.
newer="$(echo "$syms" | awk '$7=="UND" {print $8}' | grep -o '@LIBBINDER_NDK[0-9]*' | sort -u |
         awk -F'NDK' '$2!="" && $2+0>34' | tr '\n' ' ')"
[ -z "$newer" ] || bad "needs libbinder_ndk $newer"

if [ $fail -eq 0 ]; then
  echo "  ok $name: exports [$exports], needs [$needed], 16 KB aligned"
else
  exit 1
fi
