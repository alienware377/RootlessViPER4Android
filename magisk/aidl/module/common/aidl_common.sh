#!/system/bin/sh
# Shared by customize.sh, post-fs-data.sh and service.sh of the experimental
# AIDL module, and by the host-side tests in magisk/aidl/tests.
#
# Everything here reads the device through $RV4A_ROOT (empty on a device, a
# fixture tree in the tests) and rv4a_getprop, so the same functions that run
# on a phone run against sample configs on a PC.
#
# Written for POSIX sh with the toybox/busybox tools Magisk and KernelSU
# provide: sh, awk, sed, grep, cmp, sha1sum or md5sum.

RV4A_ROOT="${RV4A_ROOT:-}"
RV4A_PROC="${RV4A_PROC:-/proc}"
RV4A_UUID="f27317f4-c984-4de6-9a90-545759495bf2"
RV4A_TYPE="f98765f4-c321-5de6-9a45-123459495ab2"
RV4A_LIBNAME="rv4a_aidl"
RV4A_SUPPORTED_VERSIONS="2 3 4"

rv4a_getprop() {
  if [ -n "$RV4A_PROPS" ]; then
    sed -n "s/^$1=//p" "$RV4A_PROPS" | head -n 1
  else
    getprop "$1"
  fi
}

# ---------------------------------------------------------------------------
# Which effect HAL version does the device's factory implement?
#
# The VINTF manifest is where a vendor declares it, and the framework refuses
# to talk to a HAL whose manifest entry is wrong, so it is trustworthy. An
# AIDL entry without <version> means version 1.
# ---------------------------------------------------------------------------
rv4a_vintf_files() {
  for f in "$RV4A_ROOT"/vendor/etc/vintf/manifest.xml \
           "$RV4A_ROOT"/vendor/etc/vintf/manifest_*.xml \
           "$RV4A_ROOT"/vendor/etc/vintf/manifest/*.xml \
           "$RV4A_ROOT"/odm/etc/vintf/manifest.xml \
           "$RV4A_ROOT"/odm/etc/vintf/manifest_*.xml \
           "$RV4A_ROOT"/odm/etc/vintf/manifest/*.xml \
           "$RV4A_ROOT"/vendor/manifest.xml \
           "$RV4A_ROOT"/odm/manifest.xml \
           "$RV4A_ROOT"/apex/*/etc/vintf/*.xml; do
    [ -f "$f" ] && echo "$f"
  done
}

# Prints "<version> <file>" for every AIDL declaration of the effect HAL.
rv4a_vintf_effect_entries() {
  rv4a_vintf_files | while read -r f; do
    awk -v file="$f" '
      { buf = buf " " $0 }
      END {
        # Comments first: a commented-out <hal> block is not a declaration.
        while ((i = index(buf, "<!--")) > 0) {
          rest = substr(buf, i + 4)
          j = index(rest, "-->")
          if (j == 0) { buf = substr(buf, 1, i - 1); break }
          buf = substr(buf, 1, i - 1) substr(rest, j + 3)
        }
        s = buf
        while ((i = index(s, "<hal")) > 0) {
          s = substr(s, i + 4)
          j = index(s, "</hal>")
          if (j == 0) break
          h = substr(s, 1, j - 1)
          s = substr(s, j + 6)
          if (h !~ /format="aidl"/) continue
          if (h !~ /<name>[ \t]*android\.hardware\.audio\.effect[ \t]*<\/name>/) continue
          v = 1
          if (match(h, /<version>[ \t]*[0-9]+/)) {
            t = substr(h, RSTART, RLENGTH)
            gsub(/[^0-9]/, "", t)
            v = t + 0
          }
          print v " " file
        }
      }' "$f"
  done
}

# Prints the version when every declaration agrees, nothing otherwise.
rv4a_vintf_effect_version() {
  rv4a_vintf_effect_entries | awk '{ print $1 }' | sort -u | awk '
    { n++; v = $1 } END { if (n == 1) print v }'
}

# ---------------------------------------------------------------------------
# The factory's process. It registers IFactory/default, and on Qualcomm and
# Pixel it is the same process as the core audio HAL, so if our library takes
# it down every sound on the device stops with it.
# ---------------------------------------------------------------------------
rv4a_factory_pid() {
  # Tests set this; "none" stands for a factory that could not be found.
  if [ -n "$RV4A_FACTORY_PID" ]; then
    [ "$RV4A_FACTORY_PID" = none ] || echo "$RV4A_FACTORY_PID"
    return
  fi
  p="$(dumpsys --pid android.hardware.audio.effect.IFactory/default 2>/dev/null | tr -dc '0-9')"
  if [ -n "$p" ] && [ -d "/proc/$p" ]; then echo "$p"; return; fi
  # Older dumpsys without --pid: the process that maps an effect HAL
  # interface library is the factory's host.
  for m in /proc/[0-9]*/maps; do
    if grep -q "android\.hardware\.audio\.effect-V[0-9]*-ndk" "$m" 2>/dev/null; then
      p="${m#/proc/}"; echo "${p%/maps}"; return
    fi
  done
}

# Interface library versions mapped by a process, e.g. "3". Empty when the
# factory links its interface code statically.
rv4a_factory_ndk_versions() {
  grep -o "android\.hardware\.audio\.effect-V[0-9]*-ndk" "$RV4A_PROC/$1/maps" 2>/dev/null |
    sed 's/.*-V\([0-9]*\)-ndk/\1/' | sort -u
}

# Name of the APEX the factory runs from, if any.
rv4a_factory_apex() {
  exe="$(readlink "$RV4A_PROC/$1/exe" 2>/dev/null)"
  case "$exe" in
    /apex/*) n="${exe#/apex/}"; echo "${n%%/*}" ;;
  esac
}

# ---------------------------------------------------------------------------
# The config file the factory reads: its own APEX first, then the first
# readable of /odm/etc, /vendor/etc/audio/sku_<sku>, /vendor/etc, /system/etc
# (AOSP EffectMain.cpp and audio_config.h). Prints the path, or
# "apex:<path>" when the factory reads one inside its APEX, which an overlay
# cannot replace.
# ---------------------------------------------------------------------------
rv4a_find_config() {
  apex="$1"
  if [ -n "$apex" ] && [ -f "$RV4A_ROOT/apex/$apex/etc/audio_effects_config.xml" ]; then
    echo "apex:/apex/$apex/etc/audio_effects_config.xml"
    return
  fi
  sku="$(rv4a_getprop ro.boot.product.vendor.sku)"
  for d in /odm/etc ${sku:+/vendor/etc/audio/sku_$sku} /vendor/etc /system/etc; do
    if [ -r "$RV4A_ROOT$d/audio_effects_config.xml" ]; then
      echo "$d/audio_effects_config.xml"
      return
    fi
  done
}

# Where a module puts its copy of a file so it is mounted over the original.
# /vendor and /system are overlaid through system/ on Magisk, KernelSU and
# APatch alike. /odm is only reachable when it lives inside /vendor.
rv4a_overlay_path() {
  case "$1" in
    /vendor/*) echo "system$1" ;;
    /system/*) echo "$1" | sed 's|^/||' ;;
    /odm/*)
      real="$(readlink -f "$RV4A_ROOT/odm" 2>/dev/null)"
      case "$real" in
        "$RV4A_ROOT"/vendor/odm) echo "system/vendor$1" ;;
      esac ;;
  esac
}

# ---------------------------------------------------------------------------
# Patching. Two whole lines are added, one before </libraries> and one before
# </effects>, each with the line ending the file already uses. Every other
# byte is kept. A file whose closing tags do not sit on lines of their own is
# refused rather than rewritten.
# ---------------------------------------------------------------------------
rv4a_lib_line() { echo "<library name=\"$RV4A_LIBNAME\" path=\"$1\"/>"; }
rv4a_effect_line() {
  echo "<effect name=\"$RV4A_LIBNAME\" library=\"$RV4A_LIBNAME\" uuid=\"$RV4A_UUID\" type=\"$RV4A_TYPE\"/>"
}

# The file with our lines removed. Our lines are identified by our library
# name only, so nothing that belongs to the device can ever match.
rv4a_strip() {
  awk '
    index($0, "<library name=\"rv4a_aidl\"") { next }
    index($0, "library=\"rv4a_aidl\"") { next }
    { print }' "$1"
}

# A fingerprint of a config with our lines removed, so the same device file
# gives the same answer whether or not our copy is mounted over it.
rv4a_config_sig() {
  if command -v sha1sum >/dev/null 2>&1; then
    rv4a_strip "$1" | sha1sum | awk '{ print $1 }'
  else
    rv4a_strip "$1" | md5sum | awk '{ print $1 }'
  fi
}

# rv4a_patch <original> <output> <library file name>
# Returns 0 on success, 3 when the file's layout is not one we patch.
rv4a_patch() {
  rv4a_strip "$1" | awk -v lib="$(rv4a_lib_line "$3")" -v eff="$(rv4a_effect_line)" '
    {
      line = $0
      cr = ""
      core = line
      if (substr(line, length(line), 1) == "\r") { cr = "\r"; core = substr(line, 1, length(line) - 1) }
      if (!incomment && !donelib && core ~ /^[ \t]*<\/libraries>[ \t]*$/) {
        ind = core; sub(/<.*/, "", ind)
        print ind "    " lib cr
        donelib = 1
      }
      if (!incomment && !doneeff && core ~ /^[ \t]*<\/effects>[ \t]*$/) {
        ind = core; sub(/<.*/, "", ind)
        print ind "    " eff cr
        doneeff = 1
      }
      print line
      # Track comments so a closing tag inside one is never used.
      s = core
      while (1) {
        if (incomment) { k = index(s, "-->"); if (!k) break; incomment = 0; s = substr(s, k + 3) }
        else { k = index(s, "<!--"); if (!k) break; incomment = 1; s = substr(s, k + 4) }
      }
    }
    END { if (!donelib || !doneeff) exit 3 }' > "$2"
}

# rv4a_validate <original> <patched> <library file name>
# Prints the first problem found and returns 1, or returns 0.
rv4a_validate() {
  orig="$1"; out="$2"; lib="$3"
  [ -s "$out" ] || { echo "patched config is empty"; return 1; }
  # Everything that was there is still there, unchanged and in order.
  t1="$out.orig.$$"; t2="$out.strip.$$"
  rv4a_strip "$orig" > "$t1"; rv4a_strip "$out" > "$t2"
  if ! cmp -s "$t1" "$t2"; then rm -f "$t1" "$t2"; echo "the original content was altered"; return 1; fi
  rm -f "$t1" "$t2"
  n="$(grep -c "<library name=\"$RV4A_LIBNAME\" path=\"$lib\"/>" "$out")"
  [ "$n" = 1 ] || { echo "library line present $n times"; return 1; }
  n="$(grep -c "uuid=\"$RV4A_UUID\"" "$out")"
  [ "$n" = 1 ] || { echo "our uuid present $n times"; return 1; }
  # Our lines sit inside the sections they belong to. Text inside comments
  # is ignored, as the factory's XML parser ignores it.
  awk -v lib="<library name=\"$RV4A_LIBNAME\"" -v eff="library=\"$RV4A_LIBNAME\"" '
    function visible(line,   out, k) {
      out = ""
      while (1) {
        if (incomment) { k = index(line, "-->"); if (!k) return out; incomment = 0; line = substr(line, k + 3) }
        else { k = index(line, "<!--"); if (!k) return out line; out = out substr(line, 1, k - 1); incomment = 1; line = substr(line, k + 4) }
      }
    }
    {
      v = visible($0)
      if (index(v, "<libraries>") && !lo) lo = NR
      if (index(v, "</libraries>") && !lc) lc = NR
      if (index(v, "<effects>") && !eo) eo = NR
      if (index(v, "</effects>") && !ec) ec = NR
      if (index(v, lib)) ll = NR
      if (index(v, eff)) el = NR
      if (index(v, "</audio_effects_conf>")) root = 1
    }
    END { exit !(lo && lo < ll && ll < lc && eo && eo < el && el < ec && root) }' "$out" ||
    { echo "our lines are not inside <libraries> and <effects>"; return 1; }
  return 0
}

# Modules that answer to the same effect uuid, or replace the factory.
rv4a_conflicting_modules() {
  for m in ainur_jamesdsp jamesdsp ainur_lightningdsp lightningdsp rv4a_engine \
           sv_apixaml pixaml PIXAML; do
    d="$RV4A_ROOT/data/adb/modules/$m"
    [ -d "$d" ] || continue
    [ -f "$d/remove" ] && continue
    if [ -f "$d/disable" ]; then echo "$m(disabled)"; else echo "$m"; fi
  done
}

rv4a_aml_present() {
  for m in aml AML ainur_aml; do
    d="$RV4A_ROOT/data/adb/modules/$m"
    [ -d "$d" ] && [ ! -f "$d/remove" ] && [ ! -f "$d/disable" ] && return 0
  done
  return 1
}

# install.prop: what the install was based on, read back on every boot.
rv4a_prop() { sed -n "s/^$1=//p" "$2" 2>/dev/null | head -n 1; }

# ---------------------------------------------------------------------------
# Status, quarantine and notices
# ---------------------------------------------------------------------------
RV4A_DESC="Experimental: the RootfulViPER4Android engine for phones with the AIDL audio effect HAL. Test build."

# Shown in the root manager's module list, so whoever installed it can see
# what the module last decided without reading any log.
rv4a_set_status() {
  echo "$2" > "$1/status"
  if [ -f "$1/module.prop" ]; then
    sed -i "s|^description=.*|description=$RV4A_DESC Status: $2|" "$1/module.prop" 2>/dev/null
  fi
}

# Sets our config copy aside for good. Without it the factory reads only the
# device's own file and never loads our library. .rejected files are never
# put back automatically; reinstalling the module makes a fresh copy.
rv4a_quarantine() {
  ov="$1/$(rv4a_prop overlay "$1/install.prop")"
  if [ -f "$ov" ]; then
    mv -f "$ov" "$ov.rejected" && echo "set our config aside: $2"
  fi
  rv4a_set_status "$1" "switched off ($2). Reinstall to try again."
}

# Best effort, and only ever for our own mount: if our config copy is
# mounted over the device's file, take it away, so the next time the audio
# HAL restarts it reads the stock file and the restarts stop without
# waiting for a reboot.
rv4a_unmount_ours() {
  cfg="$(rv4a_prop config "$1/install.prop")"
  [ -n "$cfg" ] || return 0
  if awk -v m="$cfg" '$5 == m && index($4, "rv4a_engine_aidl") { f = 1 } END { exit !f }' \
       "$RV4A_PROC/self/mountinfo" 2>/dev/null; then
    umount -l "$cfg" 2>/dev/null && echo "unmounted our copy of $cfg"
  fi
}

rv4a_notify() {
  title="RV4A AIDL engine"
  cmd notification post -S bigtext -t "$title" rv4a_aidl "$1" >/dev/null 2>&1 ||
    su 2000 -c "cmd notification post -S bigtext -t '$title' rv4a_aidl '$1'" >/dev/null 2>&1 ||
    echo "could not post a notification"
}

# ---------------------------------------------------------------------------
# post-fs-data: watchdog and drift checks. Prints its log to stdout.
# ---------------------------------------------------------------------------
rv4a_boot_check_body() {
  MODDIR="$1"
  P="$MODDIR/install.prop"
  MARK="$MODDIR/.boot_incomplete"
  STRIKES="$MODDIR/.boot_strikes"
  echo "--- boot check $(date 2>/dev/null) ---"
  if [ ! -f "$P" ]; then
    echo "no install.prop; nothing to check"
    return 0
  fi
  OV="$MODDIR/$(rv4a_prop overlay "$P")"
  LIB="$(rv4a_prop lib "$P")"
  VER="$(rv4a_prop hal_version "$P")"
  CFG="$(rv4a_prop config "$P")"

  # 1. A config the watchdog set aside comes back once a boot is attempted
  #    again, so re-enabling the module works on this boot.
  if [ -f "$OV.disabled" ] && [ ! -f "$OV" ]; then
    mv "$OV.disabled" "$OV" && echo "restored our config after a watchdog stop"
  fi

  # 2. Two strikes, as in the legacy module: one unfinished boot can be a
  #    power cut or a long first boot after an update.
  if [ -f "$MARK" ]; then
    n="$(cat "$STRIKES" 2>/dev/null)"
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    n=$((n + 1))
    echo "$n" > "$STRIKES"
    echo "previous boot did not complete (strike $n)"
    if [ "$n" -ge 2 ]; then
      touch "$MODDIR/disable" || echo "WARNING: could not create the disable flag"
      [ -f "$OV" ] && mv "$OV" "$OV.disabled" && echo "set our config aside (watchdog)"
      rm -f "$MARK" "$STRIKES"
      rv4a_set_status "$MODDIR" "switched off after $n boots that did not complete. Re-enable it to try again"
      echo "module disabled"
      return 0
    fi
  else
    rm -f "$STRIKES"
  fi
  touch "$MARK" || echo "WARNING: could not write the boot marker; watchdog is off"

  if [ ! -f "$OV" ]; then
    echo "our config is not active ($(cat "$MODDIR/status" 2>/dev/null)); the device runs stock"
    return 0
  fi

  # 3. Is this still the device we were installed on?
  fp="$(rv4a_getprop ro.vendor.build.fingerprint)"
  ofp="$(rv4a_prop vendor_fingerprint "$P")"
  if [ -n "$ofp" ] && [ -n "$fp" ] && [ "$fp" != "$ofp" ]; then
    echo "vendor build changed: $ofp -> $fp"
    rv4a_quarantine "$MODDIR" "the system was updated"
    return 0
  fi
  now="$(rv4a_vintf_effect_version)"
  if [ -z "$now" ]; then
    # Manifests from an APEX may not be readable this early. service.sh
    # checks the running factory instead.
    echo "effect HAL version not readable yet; service.sh will check the running factory"
  elif [ "$now" != "$VER" ]; then
    echo "effect HAL version changed: V$VER -> V$now"
    rv4a_quarantine "$MODDIR" "the effect HAL is now V$now, this install is V$VER"
    return 0
  fi
  if [ ! -r "$RV4A_ROOT$CFG" ]; then
    echo "$CFG is gone"
    rv4a_quarantine "$MODDIR" "the stock config $CFG is gone"
    return 0
  fi
  sig="$(rv4a_config_sig "$RV4A_ROOT$CFG")"
  if [ "$sig" != "$(rv4a_prop source_sig "$P")" ]; then
    echo "$CFG differs from the one this install was made from"
    rv4a_quarantine "$MODDIR" "the stock config changed"
    return 0
  fi

  # 4. Our copy: the stock file plus our two lines, nothing else, and the
  #    library it names is the one for this version.
  if [ "$LIB" != "librv4a_aidl_v$VER.so" ] ||
     [ ! -f "$MODDIR/system/vendor/lib64/soundfx/$LIB" ]; then
    echo "library $LIB missing or not for V$VER"
    rv4a_quarantine "$MODDIR" "the library is missing"
    return 0
  fi
  if ! why="$(rv4a_validate "$RV4A_ROOT$CFG" "$OV" "$LIB")"; then
    echo "our config failed validation: $why"
    rv4a_quarantine "$MODDIR" "its config copy failed a check"
    return 0
  fi
  echo "ok: V$VER, $LIB, config $CFG"
  rv4a_set_status "$MODDIR" "installed for effect HAL V$VER, checking this boot"
}

rv4a_boot_check() {
  [ -f "$1/boot.log" ] && mv -f "$1/boot.log" "$1/boot.log.1"
  rv4a_boot_check_body "$1" > "$1/boot.log" 2>&1
}

# ---------------------------------------------------------------------------
# service.sh: after boot, is the audio HAL healthy with our library in it?
# ---------------------------------------------------------------------------
rv4a_log() { echo "$(date 2>/dev/null): $*" >> "$MODDIR/boot.log" 2>/dev/null; }

# Our library has made the audio HAL unusable: set our config aside for the
# next boot, take our mount away now if we can, and post a notification.
rv4a_give_up() {
  rv4a_log "$1"
  rv4a_quarantine "$MODDIR" "$2" >> "$MODDIR/boot.log" 2>&1
  rv4a_unmount_ours "$MODDIR" >> "$MODDIR/boot.log" 2>&1
  rv4a_notify "The AIDL engine test build switched itself off: $2. Please reboot, then send boot.log from /data/adb/modules/rv4a_engine_aidl." >> "$MODDIR/boot.log" 2>&1
}

rv4a_pid_of_exe() { [ -n "$1" ] && pidof "$(basename "$1")" 2>/dev/null | awk '{ print $1 }'; }

rv4a_service_check() {
  MODDIR="$1"
  P="$MODDIR/install.prop"
  OV="$MODDIR/$(rv4a_prop overlay "$P")"
  LIB="$(rv4a_prop lib "$P")"
  VER="$(rv4a_prop hal_version "$P")"

  # Ten minutes: the first boot after an update spends a long time optimising
  # apps, and a slow boot is not a failed one.
  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 600 ]; do sleep 1; i=$((i + 1)); done
  if [ "$(getprop sys.boot_completed)" != "1" ]; then
    rv4a_log "boot did not complete within ${i}s; leaving the marker for post-fs-data"
    return 0
  fi

  if [ ! -f "$OV" ]; then
    rm -f "$MODDIR/.boot_incomplete" "$MODDIR/.boot_strikes"
    rv4a_log "booted in ${i}s with our config inactive; nothing to watch"
    return 0
  fi

  # The factory registers its service only after it has loaded every library
  # in its config. If it never appears, the likeliest reason on this boot is us.
  j=0
  while [ $j -lt 60 ]; do
    service check android.hardware.audio.effect.IFactory/default 2>/dev/null | grep -q "found" && break
    sleep 1; j=$((j + 1))
  done
  if [ $j -ge 60 ]; then
    rv4a_give_up "IFactory/default did not appear within 60s of boot" "the effect factory did not start"
    return 0
  fi

  host="$(rv4a_factory_pid)"
  exe="$(readlink "$RV4A_PROC/$host/exe" 2>/dev/null)"
  as="$(pidof audioserver 2>/dev/null | awk '{ print $1 }')"
  loaded=no
  grep -q "$LIB" "$RV4A_PROC/$host/maps" 2>/dev/null && loaded=yes
  ndk="$(rv4a_factory_ndk_versions "$host" | tr '\n' ' ')"
  rv4a_log "factory pid $host ($exe), interface V${ndk:-static}, audioserver pid $as, $LIB loaded: $loaded"

  for v in $ndk; do
    if [ "$v" != "$VER" ]; then
      rv4a_give_up "the factory has the V$v interface loaded, this install is V$VER" \
        "the effect HAL version does not match"
      return 0
    fi
  done

  # Init restarts a crashed HAL or audioserver within seconds, so a single
  # look finds a pid either way. A different pid fifteen seconds later means
  # it went down in between.
  sleep 15
  host2="$(rv4a_factory_pid)"
  as2="$(pidof audioserver 2>/dev/null | awk '{ print $1 }')"
  if [ "$host" != "$host2" ] || [ "$as" != "$as2" ]; then
    if [ "$loaded" = yes ]; then
      rv4a_give_up "restart right after boot: factory $host -> $host2, audioserver $as -> $as2" \
        "the audio HAL restarted right after boot"
    else
      rv4a_log "restart right after boot (factory $host -> $host2, audioserver $as -> $as2), our library was not loaded; leaving the marker"
    fi
    return 0
  fi

  rm -f "$MODDIR/.boot_incomplete" "$MODDIR/.boot_strikes"
  if [ "$loaded" = yes ]; then
    rv4a_set_status "$MODDIR" "running, effect HAL V$VER, loaded by the effect factory"
    rv4a_log "steady for 15s with $LIB loaded; watchdog cleared"
  else
    rv4a_set_status "$MODDIR" "installed, but the effect factory did not load the library (see boot.log)"
    rv4a_log "steady for 15s, but $LIB is not in the factory's memory: it did not read our config or could not load the library"
  fi

  # Effects are created when the app starts, which can be any time after
  # boot, so keep watching for the rest of this boot at almost no cost.
  # Three restarts of the HAL or audioserver within three minutes is a crash
  # loop; one restart on its own is not ours to judge.
  restarts=""
  k=0
  while [ -f "$OV" ]; do
    # Only the tests set a limit; on a device this runs until shutdown.
    if [ -n "$RV4A_MONITOR_LIMIT" ]; then k=$((k + 1)); [ $k -le "$RV4A_MONITOR_LIMIT" ] || break; fi
    sleep 5
    h="$(rv4a_pid_of_exe "$exe")"
    a="$(pidof audioserver 2>/dev/null | awk '{ print $1 }')"
    [ -n "$h" ] && [ -n "$a" ] || continue
    if [ "$h" != "$host" ] || [ "$a" != "$as" ]; then
      now="$(date +%s)"
      rv4a_log "restart: factory $host -> $h, audioserver $as -> $a"
      host="$h"; as="$a"
      restarts="$restarts $now"
      n=0
      for t in $restarts; do [ $((now - t)) -le 180 ] && n=$((n + 1)); done
      if [ $n -ge 3 ]; then
        rv4a_give_up "$n restarts within 180s" "the audio HAL kept restarting"
        return 0
      fi
    fi
  done
}
