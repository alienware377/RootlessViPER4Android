#!/system/bin/sh
# KernelSU and APatch: runs once the manager has mounted the module's files,
# still before the audio HAL starts. Mounts our config copies on top, so that
# they can be taken away again without a reboot (see rv4a_mount_stage).

MODDIR=${0%/*}
[ -n "$MODDIR" ] && [ -d "$MODDIR" ] || exit 0
. "$MODDIR/common/aidl_common.sh"
rv4a_mount_stage "$MODDIR" post-mount
exit 0
