#!/system/bin/sh
# Boot watchdog and safety checks. Runs before the module's files are mounted
# and before the audio HAL starts, so anything set aside here keeps the
# effect factory from ever seeing our config on this boot.
#
# The device is back to stock whenever our config copy is missing: the
# factory then reads its own file, which does not mention our library, and
# never loads it. So every safety action here is a rename of that one file.
#
#  1. Put back a config the watchdog set aside after a bad boot.
#  2. Two boots in a row that did not complete: switch the module off.
#  3. Anything about the device changed since install (system update, a
#     different effect HAL version, a different stock config): set our config
#     aside for good. It was made for a device that no longer exists.
#  4. Check our own config copy before it is mounted.

MODDIR=${0%/*}
[ -n "$MODDIR" ] && [ -d "$MODDIR" ] || exit 0
. "$MODDIR/common/aidl_common.sh"
rv4a_boot_check "$MODDIR"
exit 0
