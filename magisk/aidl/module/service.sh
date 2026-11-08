#!/system/bin/sh
# Watches the audio HAL from the late_start stage on: the factory must
# register, and the HAL and audioserver must not restart three times in three
# minutes. Once boot has completed and things are quiet, checks that our
# library is loaded and clears the boot marker. See rv4a_service_check in
# common/aidl_common.sh.

MODDIR=${0%/*}
[ -n "$MODDIR" ] && [ -d "$MODDIR" ] || exit 0
. "$MODDIR/common/aidl_common.sh"
rv4a_service_check "$MODDIR"
exit 0
