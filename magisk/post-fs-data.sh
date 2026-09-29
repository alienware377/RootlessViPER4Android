#!/system/bin/sh
# Boot watchdog and safety net. Runs before the audio server starts, and before
# the module's files are mounted, which is what makes a rename here able to
# stop the current boot rather than the next one.
#
# Three jobs:
#  1. Put back anything a previous bad boot set aside, so re-enabling the
#     module works on the boot the user re-enables it, not the one after.
#  2. If the previous boot never finished, disable this module so the next boot
#     comes up clean.
#  3. Sanity-check our own audio configs and reject any that look wrong.

MODDIR=${0%/*}
# Every rm and mv below is built from this. If it were empty they would be
# rooted at / instead, so stop rather than find out.
[ -n "$MODDIR" ] && [ -d "$MODDIR" ] || exit 0

UUID="f27317f4-c984-4de6-9a90-545759495bf2"
LIB_NAME="jdsp"
LOG="$MODDIR/boot.log"
MARK="$MODDIR/.boot_incomplete"
STRIKES="$MODDIR/.boot_strikes"

# On KernelSU and APatch, system/vendor is a symlink by the time this runs, and
# find does not follow symlinks without -L. The vendor copy is the one most
# likely to take the audio server down, and it was the one never being checked.
roots() {
  R="$MODDIR/system"
  [ -e "$MODDIR/vendor" ] && R="$R $MODDIR/vendor"
  [ -e "$MODDIR/odm" ] && R="$R $MODDIR/odm"
  echo "$R"
}

find_configs() {
  find -L $(roots) -maxdepth 6 -type f \
    \( -name "audio_effects*.xml" -o -name "audio_effects*.conf" \) 2>/dev/null
}

# Two suffixes, deliberately. .disabled means the watchdog set a file aside
# because the BOOT failed, and it goes back once a boot goes through. .rejected
# means we examined the file and decided it was malformed, and it never goes
# back: restoring a file we ourselves called broken is how a device ends up
# flapping between quarantined and restored on every single boot.
find_disabled() {
  find -L $(roots) -maxdepth 6 -type f -name "audio_effects*.disabled" 2>/dev/null
}

{
  echo "--- boot check $(date 2>/dev/null) ---"

  # --- 1. put back anything a previous bad boot set aside ---------------
  # This belongs here rather than at the end of the boot: the mount is built
  # right after this script runs, so a file restored late does not take effect
  # until the boot after that, and a user who just re-enabled the module sees
  # it still doing nothing.
  find_disabled | while read -r f; do
    mv "$f" "${f%.disabled}" 2>/dev/null && echo "restored ${f%.disabled}"
  done

  # --- 2. did the last boot finish? ------------------------------------
  if [ -f "$MARK" ]; then
    # Two strikes, not one. A power cut, or a long first boot after an OTA,
    # leaves exactly the same marker as a genuine failure, and disabling the
    # module over that is a support ticket rather than a save.
    n=$(cat "$STRIKES" 2>/dev/null || echo 0)
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    n=$((n + 1))
    echo "$n" > "$STRIKES"
    echo "previous boot did not complete (strike $n)"

    if [ "$n" -ge 2 ]; then
      echo "disabling module after $n incomplete boots"
      # Magisk skips any module containing a file named 'disable'
      touch "$MODDIR/disable" || echo "WARNING: could not create the disable flag"
      # Move our overrides aside too, so even a stale mount cannot be used.
      find_configs | while read -r f; do
        mv "$f" "$f.disabled" 2>/dev/null && echo "quarantined $f"
      done
      rm -f "$MARK" "$STRIKES"
      echo "module disabled; reinstall once the cause is known"
      exit 0
    fi
  else
    rm -f "$STRIKES"
  fi

  # Mark the boot as in progress. service.sh clears this once Android is up
  # AND the audio server has stayed up.
  touch "$MARK" || echo "WARNING: could not write the boot marker; watchdog is off"

  # --- 2b. has the system been updated under us? ------------------------
  # This runs before our files are mounted, so the config read here is the
  # device's own, not our copy of it.
  if [ -f "$MODDIR/.source_config" ]; then
    read -r src osize olines < "$MODDIR/.source_config"
    if [ -n "$src" ] && [ -f "$src" ]; then
      nsize=$(wc -c < "$src" 2>/dev/null || echo 0)
      nlines=$(wc -l < "$src" 2>/dev/null || echo 0)
      if [ "$nsize" != "$osize" ] || [ "$nlines" != "$olines" ]; then
        echo "SOURCE CONFIG CHANGED: $src was $osize bytes/$olines lines, now $nsize/$nlines"
        echo "the system has probably been updated; our overlay is stale"
        find_configs | while read -r f; do
          mv "$f" "$f.rejected" 2>/dev/null && echo "rejected $f (stale after a system update)"
        done
        echo "reinstall the module to pick up the new configuration"
      fi
    fi
  fi

  # --- 3. validate our own configs -------------------------------------
  find_configs | while read -r f; do
      bad=0
      # Checked per format. A blanket grep for "soundfx" used to be applied to
      # both, and the XML we generate names the library by bare filename, so
      # every correct XML we ever wrote failed this and was set aside on every
      # boot for ever, with one line in this log as the only trace.
      grep -q "$UUID" "$f" || bad=1
      case "$f" in
        *.xml)
          grep -q "name=\"$LIB_NAME\"" "$f" || bad=1
          grep -q "</audio_effects_conf>" "$f" || bad=1
          # our entries must sit inside the sections they belong to
          grep -q "</libraries>" "$f" || bad=1
          grep -q "</effects>" "$f" || bad=1
          ;;
        *.conf)
          grep -q "soundfx" "$f" || bad=1
          grep -q "$LIB_NAME {" "$f" || bad=1
          # Braces must balance and must never close below zero. One stray
          # brace closes a section early, and everything after it is lost,
          # including effects that have nothing to do with us.
          awk '{ n += gsub(/{/,"{") - gsub(/}/,"}"); if (n < 0) exit 1 } END { exit (n != 0) }' "$f" || bad=1
          ;;
      esac
      [ "$(wc -c < "$f" 2>/dev/null || echo 0)" -gt 100 ] || bad=1

      if [ "$bad" = "1" ]; then
        mv "$f" "$f.rejected" 2>/dev/null
        echo "rejected $f (failed validation; not restored automatically)"
      else
        echo "ok $f"
      fi
    done

  for d in "$MODDIR"/system/*/soundfx "$MODDIR"/system/vendor/*/soundfx \
           "$MODDIR"/vendor/*/soundfx; do
    [ -f "$d/libjamesdsp.so" ] &&
      echo "engine present: $d ($(wc -c < "$d/libjamesdsp.so") bytes)"
  done
} > "$LOG" 2>&1

exit 0
