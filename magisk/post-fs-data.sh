#!/system/bin/sh
# Boot watchdog and safety net. Runs before the audio server starts and, on
# most root managers, before the module's files are mounted, which is what
# makes a rename here able to stop the current boot rather than the next one.
# APatch from 11219 has its metamodule mount first, so there whatever is
# stopped here takes effect from the next boot, and the watchdog acts one
# boot sooner to make up for it.
#
# Three jobs:
#  1. Put back anything a previous bad boot set aside, so re-enabling the
#     module works on the boot the user re-enables it, not the one after.
#  2. If the previous boot never finished, disable this module so the next boot
#     comes up clean.
#  3. Sanity-check our own audio configs and reject any that look wrong.
#
# KernelSU's late-load mode does not run this script. Its late-load.sh runs
# it instead, after the phone has booted but still before the mount, with
# RV4A_LATE_LOAD set: jobs 1 and 3 still apply, the boot watchdog does not.

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

# The same entries, drop_entries and strip_ours as in
# common/patch_audio_config.sh, kept here so the boot safety net depends on
# no other file. The two copies must stay the same.
XML_LIB="<library name=\"$LIB_NAME\" path=\"libjamesdsp.so\"/>"
XML_EFF="<effect name=\"$EFFECT_NAME\" library=\"$LIB_NAME\" uuid=\"$UUID\"/>"
XML_OLD="<library name=\"jamesdsp\" path=\"libjamesdsp.so\"/>|<effect name=\"$EFFECT_NAME\" library=\"jamesdsp\" uuid=\"$UUID\"/>"
CONF_LIB="$LIB_NAME {\npath /system/lib/soundfx/libjamesdsp.so\n}"
CONF_EFF="$EFFECT_NAME {\nlibrary $LIB_NAME\nuuid $UUID\n}"
CONF_OLD="jamesdsp {\npath /system/lib/soundfx/libjamesdsp.so\n}|$EFFECT_NAME {\nlibrary jamesdsp\nuuid $UUID\n}"

drop_entries() {
  awk -v entries="$2" -v once="${3:-}" '
    function bare(s) { sub(/^[ \t]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
    { raw[NR] = $0; t[NR] = bare($0) }
    END {
      n = split(entries, e, "|")
      for (x = 1; x <= n; x++) {
        len[x] = split(e[x], part, "\n")
        for (y = 1; y <= len[x]; y++) want[x, y] = part[y]
      }
      i = 1
      while (i <= NR) {
        hit = 0
        for (x = 1; x <= n && !hit; x++) {
          hit = x
          for (y = 1; y <= len[x]; y++) if (t[i + y - 1] != want[x, y]) { hit = 0; break }
        }
        if (hit) { seen[hit]++; i += len[hit] } else { print raw[i]; i++ }
      }
      if (once) for (x = 1; x <= n; x++) if (seen[x] != 1) exit 1
    }
  ' "$1"
}

strip_ours() {
  case "$1" in
    *.conf) drop_entries "$1" "$CONF_LIB|$CONF_EFF|$CONF_OLD" ;;
    *)      drop_entries "$1" "$XML_LIB|$XML_EFF|$XML_OLD" ;;
  esac
}

# The same as build_id in common/patch_audio_config.sh.
build_id() {
  v="$(sed -n 's/^ro\.vendor\.build\.fingerprint=//p' "$RV4A_ROOT/vendor/build.prop" 2>/dev/null | head -n 1)"
  s="$(sed -n 's/^ro\.system\.build\.fingerprint=//p; s/^ro\.build\.fingerprint=//p' \
       "$RV4A_ROOT/system/build.prop" 2>/dev/null | head -n 1)"
  [ -n "$v$s" ] && echo "${v:-unknown} ${s:-unknown}"
  return 0
}

# This module's own copy of a device config.
our_copy() {
  case "$1" in
    /system/*) echo "$MODDIR$1" ;;
    *)         echo "$MODDIR/system$1" ;;
  esac
}

# Whether this manager has already mounted the module: APatch from 11219
# runs its metamodule's mount before the modules' post-fs-data scripts. Its
# daemon names the mount script when it does, which also covers forks; the
# version code is the fallback.
mounted_first() {
  [ -n "${APATCH:-}" ] && [ "$APATCH" != false ] || return 1
  if [ -r "$RV4A_ROOT/data/adb/apd" ]; then
    grep -q 'metamount\.sh' "$RV4A_ROOT/data/adb/apd" 2>/dev/null
    return
  fi
  case "${APATCH_VER_CODE:-}" in ''|*[!0-9]*) return 1 ;; esac
  [ "$APATCH_VER_CODE" -ge 11219 ]
}
MOUNTED_FIRST=0
mounted_first && MOUNTED_FIRST=1

# Our copies are renamed aside above, but a KernelSU or APatch metamodule
# mounts its own copy of the module's files, made at install time, and a
# rename here never reaches it. skip_mount does: every manager and
# meta-overlayfs leave a module with it unmounted. Like a rejection, it lasts
# until the module is installed again. Where the module was mounted before
# this ran, it only takes effect from the next boot.
stop_mounting() {
  [ -f "$MODDIR/skip_mount" ] && return 0
  touch "$MODDIR/skip_mount" && echo "skip_mount set: nothing of this module is mounted until it is reinstalled"
  [ "$MOUNTED_FIRST" = 1 ] &&
    echo "  it was mounted before this check ran, so this boot still uses it; the next boot does not"
  return 0
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
  if [ "${RV4A_LATE_LOAD:-}" = 1 ]; then
    # This boot is over already, and a marker left by an earlier one says
    # nothing about it; service.sh clears it once the audio server is steady.
    echo "KernelSU late-load: the phone has already booted, so the boot watchdog is skipped"
  elif [ -f "$MARK" ]; then
    # Two strikes, not one. A power cut, or a long first boot after an OTA,
    # leaves exactly the same marker as a genuine failure, and disabling the
    # module over that is a support ticket rather than a save. Where the
    # module is already mounted by now, disabling it only helps the boot
    # after this one, so a second strike would let a loop run a fourth boot;
    # there one is enough, and a loop still clears by the third.
    limit=2
    [ "$MOUNTED_FIRST" = 1 ] && limit=1
    n=$(cat "$STRIKES" 2>/dev/null || echo 0)
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    n=$((n + 1))
    echo "$n" > "$STRIKES"
    echo "previous boot did not complete (strike $n of $limit)"

    if [ "$n" -ge "$limit" ]; then
      echo "disabling module after $n incomplete boots"
      # Magisk skips any module containing a file named 'disable'
      touch "$MODDIR/disable" || echo "WARNING: could not create the disable flag"
      # Move our overrides aside too, so even a stale mount cannot be used.
      find_configs | while read -r f; do
        mv "$f" "$f.disabled" 2>/dev/null && echo "quarantined $f"
      done
      rm -f "$MARK" "$STRIKES"
      [ "$MOUNTED_FIRST" = 1 ] &&
        echo "it was mounted before this check ran, so it is off from the next boot"
      echo "module disabled; reinstall once the cause is known"
      exit 0
    fi
  else
    rm -f "$STRIKES"
  fi

  # Mark the boot as in progress. service.sh clears this once Android is up
  # AND the audio server has stayed up.
  if [ "${RV4A_LATE_LOAD:-}" != 1 ]; then
    touch "$MARK" || echo "WARNING: could not write the boot marker; watchdog is off"
  fi

  # --- 2b. has the system been updated under us? ------------------------
  # .source_config has one line per config the install patched: its path,
  # then its size and line count with our own lines left out. Installs before
  # this format wrote a single line of the same shape, measured as read, which
  # is the same thing whenever our copy was not mounted at the time. A
  # "#build" line holds the builds the install saw, or the last boot that
  # found every config unchanged.
  #
  # On most managers this runs before our files are mounted, so the file read
  # here is the device's own. APatch with a metamodule mounts first, and then
  # it is our copy, which with our lines left out is the file as it was at
  # install: an update underneath cannot be seen that way. So when the file
  # is our copy, a different build is taken as an update instead.
  if [ -f "$MODDIR/.source_config" ]; then
    stale=""; mounted=""
    then_build="$(sed -n 's/^#build //p' "$MODDIR/.source_config" | head -n 1)"
    now_build="$(build_id)"
    while read -r src osize olines; do
      case "$src" in ''|'#'*) continue ;; esac
      if [ ! -f "$RV4A_ROOT$src" ]; then
        echo "SOURCE CONFIG GONE: $src"
        stale=1
        continue
      fi
      if cmp -s "$RV4A_ROOT$src" "$(our_copy "$src")"; then
        mounted=1
        if [ -n "$then_build" ] && [ -n "$now_build" ] && [ "$then_build" != "$now_build" ]; then
          echo "SYSTEM UPDATED: $src is our copy, already mounted, and the build has changed since it was made"
          echo "  was: $then_build"
          echo "  now: $now_build"
          stale=1
        else
          echo "source unchanged: $src (our copy is already mounted over it; the build is the same)"
        fi
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
      # Our copy in place already says the manager mounted first, whatever
      # it is.
      [ -n "$mounted" ] && MOUNTED_FIRST=1
      stop_mounting
      echo "reinstall the module to pick up the new configuration"
    elif [ -z "$mounted" ] && [ -n "$now_build" ] && [ "$then_build" != "$now_build" ]; then
      # Every config was the phone's own and unchanged, so this build is
      # known to match our copies.
      { grep -v '^#build ' "$MODDIR/.source_config"; echo "#build $now_build"; } > "$MODDIR/.source_config.new" &&
        mv -f "$MODDIR/.source_config.new" "$MODDIR/.source_config" &&
        echo "build recorded: $now_build"
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
