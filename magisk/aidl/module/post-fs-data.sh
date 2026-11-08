#!/system/bin/sh
# Boot watchdog and safety checks. Runs before the root manager mounts the
# module's files and before the audio HAL starts, so anything set aside here
# keeps the effect factory from ever seeing our configs on this boot.
#
# The device is back to stock whenever our config copies are not mounted:
# the factory then reads its own files, which do not mention our library,
# and never loads it. So every safety action here is a rename of the folder
# that holds them.
#
#  1. Put back copies the watchdog set aside after a bad boot.
#  2. Two boots in a row that did not complete: switch the module off.
#  3. Anything about the device changed since install (system update, a
#     different effect HAL version, a different stock config): set our copies
#     aside for good. They were made for a device that no longer exists.
#  4. Check our own copies before they are mounted.
#
# Then, on Magisk, mount them (KernelSU and APatch do that in post-mount.sh).

MODDIR=${0%/*}
[ -n "$MODDIR" ] && [ -d "$MODDIR" ] || exit 0
. "$MODDIR/common/aidl_common.sh"
rv4a_boot_check "$MODDIR"
rv4a_mount_stage "$MODDIR" post-fs-data
exit 0
