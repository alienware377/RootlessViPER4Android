#!/system/bin/sh
# Runs once Android is up. Clears the boot marker only when boot completed
# AND the audio HAL and audioserver stayed up with our library in them, then
# keeps watching for a crash loop for the rest of this boot (see
# rv4a_service_check in common/aidl_common.sh).

MODDIR=${0%/*}
[ -n "$MODDIR" ] && [ -d "$MODDIR" ] || exit 0
. "$MODDIR/common/aidl_common.sh"
rv4a_service_check "$MODDIR"
exit 0
