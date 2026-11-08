#!/system/bin/sh
# Runs late in boot, once Android is up.
#
# Clearing the marker tells the next boot that this one succeeded. If the device
# never gets this far, post-fs-data.sh sees the marker still present and, on the
# second such boot, disables the module. A bad install therefore costs two
# failed boots rather than an unrecoverable loop.

MODDIR=${0%/*}
[ -n "$MODDIR" ] || exit 0

# Empty on a phone; a sample device tree in magisk/tests.
RV4A_ROOT="${RV4A_ROOT:-}"
UUID="f27317f4-c984-4de6-9a90-545759495bf2"

log() { echo "$(date 2>/dev/null): $*" >> "$MODDIR/boot.log" 2>/dev/null; }

# What the audio HAL can see of this module, now that every mount is done.
# An install that succeeded but was never mounted (KernelSU 3 or APatch
# without a working metamodule, for one) looks exactly like a missing module
# in the app, and this is where the difference shows.
report() {
  for d in lib64 lib; do
    ours="$MODDIR/system/vendor/$d/soundfx/libjamesdsp.so"
    [ -f "$ours" ] || continue
    if cmp -s "$RV4A_ROOT/vendor/$d/soundfx/libjamesdsp.so" "$ours"; then
      log "engine visible at /vendor/$d/soundfx"
    else
      log "engine NOT visible at /vendor/$d/soundfx: the root manager did not mount this module"
    fi
  done
  if [ -f "$MODDIR/.source_config" ]; then
    while read -r src rest; do
      case "$src" in ''|'#'*) continue ;; esac
      if grep -q "$UUID" "$RV4A_ROOT$src" 2>/dev/null; then
        log "$src lists the engine"
      else
        log "$src does NOT list the engine: our copy is not mounted over it"
      fi
    done < "$MODDIR/.source_config"
  elif [ -f "$MODDIR/aml.sh" ]; then
    log "configs are merged by AML"
  fi
  [ -f "$MODDIR/skip_mount" ] && log "skip_mount is set (see above), so nothing of this module is mounted"
  # Only processes with "audio" in their command line: reading every
  # process's memory map takes seconds.
  p=""
  for c in $(grep -l audio "$RV4A_ROOT"/proc/[0-9]*/cmdline 2>/dev/null); do
    if grep -q '/soundfx/libjamesdsp\.so' "${c%/cmdline}/maps" 2>/dev/null; then
      p="${c%/cmdline}"; break
    fi
  done
  if [ -n "$p" ]; then
    log "engine loaded by pid ${p##*/} ($(readlink "$p/exe" 2>/dev/null))"
  else
    log "engine not loaded by the audio HAL"
  fi
  # The loader says why it skipped a library or a file, and SELinux says what
  # it refused; both are gone from the buffers soon after boot.
  logcat -d 2>/dev/null | grep -E 'EffectsFactory|EffectsConfig|libjamesdsp|RV4A-HAL' | tail -n 20 |
    while read -r l; do log "logcat: $l"; done
  dmesg 2>/dev/null | grep -E 'avc: +denied.*(jamesdsp|soundfx|audio_effects)' | tail -n 10 |
    while read -r l; do log "dmesg: $l"; done
}

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
  # A timeout is a failed boot, not a slow success, so the marker stays.
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
  report
  exit 0
fi
sleep 15
p2=$(pidof audioserver 2>/dev/null)
if [ -z "$p2" ] || [ "$p1" != "$p2" ]; then
  log "audioserver restarted during boot (pid $p1 then $p2); leaving the marker"
  report
  exit 0
fi

rm -f "$MODDIR/.boot_incomplete" "$MODDIR/.boot_strikes"
log "boot completed in ${i}s, audioserver steady at pid $p1, watchdog cleared"
report
exit 0
