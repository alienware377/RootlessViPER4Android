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

# Empty on a phone; a sample device tree in magisk/tests.
RV4A_ROOT="${RV4A_ROOT:-}"

UUID="f27317f4-c984-4de6-9a90-545759495bf2"
LIB_NAME="jdsp"
EFFECT_NAME="jamesdsp"
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

# The same as strip_ours in common/patch_audio_config.sh, kept here so the
# boot safety net depends on no other file. The two must stay the same.
strip_ours() {
  case "$1" in
    *.conf)
      awk -v lib="$LIB_NAME" -v eff="$EFFECT_NAME" '
        skip { if ($0 ~ /^  \}/) skip = 0; next }
        $0 ~ "^  (" lib "|jamesdsp|" eff ") \\{" { skip = 1; next }
        { print }
      ' "$1"
      ;;
    *)
      grep -v "<effect .*uuid=\"$UUID\"" "$1" |
        grep -v "<library name=\"$LIB_NAME\"" |
        grep -v "<library name=\"jamesdsp\"" |
        grep -v "<effect name=\"$EFFECT_NAME\""
      ;;
  esac
}

# Our copies are renamed aside above, but a KernelSU or APatch metamodule
# mounts its own copy of the module's files, made at install time, and a
# rename here never reaches it. skip_mount does: every manager and
# meta-overlayfs leave a module with it unmounted. Like a rejection, it lasts
# until the module is installed again.
stop_mounting() {
  [ -f "$MODDIR/skip_mount" ] && return 0
  touch "$MODDIR/skip_mount" && echo "skip_mount set: nothing of this module is mounted until it is reinstalled"
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
  # .source_config has one line per config the install patched: its path,
  # then its size and line count with our own lines left out. Installs before
  # this format wrote a single line of the same shape, measured as read, which
  # is the same thing whenever our copy was not mounted at the time.
  #
  # On most managers this runs before our files are mounted, so the file read
  # here is the device's own. APatch with a metamodule mounts first, and then
  # it is our copy; with our lines left out that is the file as it was at
  # install. That never fails the check wrongly, though it cannot see an
  # update underneath either.
  if [ -f "$MODDIR/.source_config" ]; then
    stale=""
    while read -r src osize olines; do
      case "$src" in ''|'#'*) continue ;; esac
      if [ ! -f "$RV4A_ROOT$src" ]; then
        echo "SOURCE CONFIG GONE: $src"
        stale=1
        continue
      fi
      nsize=$(strip_ours "$RV4A_ROOT$src" | wc -c | tr -dc '0-9')
      nlines=$(strip_ours "$RV4A_ROOT$src" | wc -l | tr -dc '0-9')
      rsize=$(wc -c < "$RV4A_ROOT$src" 2>/dev/null | tr -dc '0-9')
      rlines=$(wc -l < "$RV4A_ROOT$src" 2>/dev/null | tr -dc '0-9')
      if [ "$nsize $nlines" = "$osize $olines" ] || [ "$rsize $rlines" = "$osize $olines" ]; then
        echo "source unchanged: $src"
      else
        echo "SOURCE CONFIG CHANGED: $src was $osize bytes/$olines lines, now $nsize/$nlines"
        stale=1
      fi
    done < "$MODDIR/.source_config"
    if [ -n "$stale" ]; then
      echo "the system has probably been updated; our overlay is stale"
      find_configs | while read -r f; do
        mv "$f" "$f.rejected" 2>/dev/null && echo "rejected $f (stale after a system update)"
      done
      stop_mounting
      echo "reinstall the module to pick up the new configuration"
    fi
  fi

  # --- 3. validate our own configs -------------------------------------
  find_configs | while read -r f; do
      bad=0
      # Checked per format: the XML names the library by bare filename, so
      # only a .conf file mentions soundfx.
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
          # including effects that have nothing to do with us. Bracketed:
          # busybox awk refuses a bare { as a pattern and fails the file.
          awk '{ n += gsub(/[{]/, "{") - gsub(/[}]/, "}"); if (n < 0) exit 1 } END { exit (n != 0) }' "$f" || bad=1
          ;;
      esac
      [ "$(wc -c < "$f" 2>/dev/null || echo 0)" -gt 100 ] || bad=1

      if [ "$bad" = "1" ]; then
        mv "$f" "$f.rejected" 2>/dev/null
        echo "rejected $f (failed validation; not restored automatically)"
        stop_mounting
      else
        echo "ok $f"
      fi
    done

  # These are the module's own copies. Whether the audio HAL can see them is
  # only known once the manager has mounted them; service.sh reports that.
  for d in "$MODDIR"/system/*/soundfx "$MODDIR"/system/vendor/*/soundfx \
           "$MODDIR"/vendor/*/soundfx; do
    [ -f "$d/libjamesdsp.so" ] &&
      echo "engine in the module: $d ($(wc -c < "$d/libjamesdsp.so") bytes)"
  done
} > "$LOG" 2>&1

exit 0
