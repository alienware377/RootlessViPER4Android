#!/system/bin/sh
# Runs late in boot, once Android is up.
#
# Clearing the marker tells the next boot that this one succeeded. If the device
# never gets this far, post-fs-data.sh sees the marker still present and, on the
# second such boot, disables the module. A bad install therefore costs two
# failed boots rather than an unrecoverable loop.

MODDIR=${0%/*}
[ -n "$MODDIR" ] || exit 0

log() { echo "$(date 2>/dev/null): $*" >> "$MODDIR/boot.log" 2>/dev/null; }

# Wait for boot to be genuinely complete rather than just for this script to
# run. Ten minutes, not two: the first boot after an OTA or a factory reset
# spends a long time in dex2oat, and treating that as a failure would disable a
# module that was working perfectly well.
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 600 ]; do
  sleep 1
  i=$((i + 1))
done

if [ "$(getprop sys.boot_completed)" != "1" ]; then
  # The marker used to be cleared here regardless of the outcome, which meant
  # that in the one situation this watchdog exists for, a boot that never
  # completes because of us, it quietly disarmed itself. A timeout is a failed
  # boot, not a slow success.
  log "boot did not complete within ${i}s; leaving the marker for post-fs-data"
  exit 0
fi

# Booting is not the same as working. The module's whole job is an audio
# effect, and a device can boot perfectly well with its audio server in a crash
# loop, which is just as unusable and considerably more confusing.
j=0
while [ $j -lt 30 ]; do
  pidof audioserver >/dev/null 2>&1 && break
  sleep 1
  j=$((j + 1))
done

# Sampled twice, because audioserver is an init service of class core: when it
# crashes, init respawns it within seconds, so a single check finds a pid and
# calls a crash loop healthy. A different pid fifteen seconds later means it
# went down and came back.
p1=$(pidof audioserver 2>/dev/null)
if [ -z "$p1" ]; then
  log "boot completed but audioserver is not running; leaving the marker"
  exit 0
fi
sleep 15
p2=$(pidof audioserver 2>/dev/null)
if [ -z "$p2" ] || [ "$p1" != "$p2" ]; then
  log "audioserver restarted during boot (pid $p1 then $p2); leaving the marker"
  exit 0
fi

rm -f "$MODDIR/.boot_incomplete" "$MODDIR/.boot_strikes"
log "boot completed in ${i}s, audioserver steady at pid $p1, watchdog cleared"
exit 0
