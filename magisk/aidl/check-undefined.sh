#!/bin/bash
# Fails if the service needs a symbol that nothing on the device will give it.
#
#   bash magisk/aidl/check-undefined.sh <binary> <ndk dir> <abi> [api]
#
# The service links with --unresolved-symbols=ignore-in-object-files (see
# CMakeLists.txt), so a source file left out of the build still links, and the
# binary then refuses to start with "cannot locate symbol". This catches that
# at build time: every undefined symbol must be defined by one of the
# libraries the binary names as NEEDED (as the NDK describes them), or be one
# of the few platform-only symbols listed below that the NDK copies leave out
# on purpose.
set -e
BIN=$1; NDK=$2; ABI=$3; API=${4:-33}
case "$ABI" in
    arm64-v8a) TRIPLE=aarch64-linux-android ;;
    x86_64) TRIPLE=x86_64-linux-android ;;
    *) echo "unknown ABI $ABI"; exit 2 ;;
esac
TOOLS=$(dirname "$(find "$NDK/toolchains/llvm/prebuilt" -name 'llvm-nm*' | head -1)")
NM=$TOOLS/llvm-nm
READELF=$TOOLS/llvm-readelf
[ -x "$NM" ] || NM=$NM.exe
[ -x "$READELF" ] || READELF=$READELF.exe
LIBS=$NDK/toolchains/llvm/prebuilt/$(basename "$(dirname "$TOOLS")")/sysroot/usr/lib/$TRIPLE/$API

# Known to be left for the platform today: service registration, which the
# NDK's libbinder_ndk leaves out because an app has no business publishing a
# service, and what the vendored FMQ code takes from the platform libraries
# libbinder_ndk itself loads.
KNOWN="ABinderProcess_joinThreadPool
ABinderProcess_setThreadPoolMaxThreadCount
AServiceManager_addService
AServiceManager_checkService
_ZN7android8hardware7details13errorWriteLogEiPKc
_ZN7android8hardware7details5checkEbPKc
_ZN7android8hardware7details8logErrorERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE
native_handle_close
native_handle_create
native_handle_delete"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
"$READELF" -d "$BIN" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr -d '\r' > "$TMP/needed"
: > "$TMP/defined"
while read -r lib; do
    if [ -f "$LIBS/$lib" ]; then
        "$NM" -D --defined-only "$LIBS/$lib" | awk '{print $NF}' >> "$TMP/defined"
    else
        echo "warning: $lib is not in the NDK for API $API; its symbols are not counted"
    fi
done < "$TMP/needed"
echo "$KNOWN" >> "$TMP/defined"
sed 's/@.*//' "$TMP/defined" | tr -d '\r' | sort -u > "$TMP/have"
"$NM" -D --undefined-only "$BIN" | awk '{print $NF}' | sed 's/@.*//' | tr -d '\r' | sort -u > "$TMP/want"
comm -23 "$TMP/want" "$TMP/have" > "$TMP/missing"
if [ -s "$TMP/missing" ]; then
    echo "$BIN needs symbols nothing on the device provides; it would not start:"
    sed 's/^/  /' "$TMP/missing"
    exit 1
fi
echo "$BIN: all $(wc -l < "$TMP/want") undefined symbols are provided"
