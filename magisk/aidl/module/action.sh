#!/system/bin/sh
# The manager's Action button, or by hand:
#   su -c sh /data/adb/modules/rv4a_engine_aidl/action.sh
# Switches the engine off now: sets our config copies aside and unmounts
# them. The audio HAL keeps our library until it restarts, so reboot.

MODDIR=${0%/*}
[ -n "$MODDIR" ] && [ -d "$MODDIR" ] || exit 0
. "$MODDIR/common/aidl_common.sh"
if ! rv4a_active "$MODDIR"; then
  echo "Already off: $(cat "$MODDIR/status" 2>/dev/null)"
  echo "Reinstall the module to switch it on again."
  exit 0
fi
{
  echo "$(date 2>/dev/null): switched off by hand"
  rv4a_quarantine "$MODDIR" "switched off by hand"
  rv4a_unmount_configs "$MODDIR"
} | tee -a "$MODDIR/boot.log"
echo
echo "Off. Reboot now to take the library out of the audio HAL."
echo "Reinstall the module to switch it on again."
exit 0
