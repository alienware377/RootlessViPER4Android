#!/system/bin/sh
# Which root manager is installing this, and whether it will put the module's
# files in place before the audio HAL starts.
#
# The engine and our config copy only reach /vendor through the manager's
# mount. Magisk mounts module files itself. KernelSU from 3.0 and APatch from
# 11219 leave that to a separate "metamodule" (meta-overlayfs, for one), and
# with none active nothing under system/ is mounted at all: the install
# succeeds, the engine never appears and the app reports no module. KernelSU's
# late-load mode starts after boot, when the audio HAL has already read its
# effect list.
#
# Device paths go through $RV4A_ROOT, as in patch_audio_config.sh.

RV4A_ROOT="${RV4A_ROOT:-}"

# The variables the manager sets come first. A folder can be left behind by a
# manager that was replaced, and KernelSU sets MAGISK_VER_CODE as well, so
# neither of those can decide on its own.
root_impl() {
  if [ -n "$KSU" ] && [ "$KSU" != false ]; then echo KernelSU
  elif [ -n "$APATCH" ] && [ "$APATCH" != false ]; then echo APatch
  elif [ -n "$MAGISK_VER_CODE" ]; then echo Magisk
  elif [ -d "$RV4A_ROOT/data/adb/ksu" ]; then echo KernelSU
  elif [ -d "$RV4A_ROOT/data/adb/ap" ]; then echo APatch
  else echo Magisk; fi
}

# "3.0.0 (30123)", or "version unknown".
root_version() {
  case "$1" in
    KernelSU)
      v="$KSU_VER"; c="$KSU_VER_CODE"
      [ -n "$v" ] || v="$(ksud -V 2>/dev/null | head -n 1 | sed 's/^ksud //')"
      ;;
    APatch) v="$APATCH_VER"; c="$APATCH_VER_CODE" ;;
    *)      v="$MAGISK_VER"; c="$MAGISK_VER_CODE" ;;
  esac
  [ -n "$v$c" ] || { echo "version unknown"; return; }
  if [ -n "$v" ] && [ -n "$c" ]; then echo "$v ($c)"; else echo "$v$c"; fi
}

# yes, no or unknown: does this manager mount module files only through a
# metamodule? Its daemon names the metamodule's mount hook when it does, which
# also holds for forks whose version numbers follow their own scheme. The
# version code is the fallback, and only official numbering is known.
needs_metamodule() {
  case "$1" in
    KernelSU) b="$RV4A_ROOT/data/adb/ksud"; c="$KSU_VER_CODE"; min=30000
              v="$KSU_VER"
              [ -n "$v" ] || v="$(ksud -V 2>/dev/null | head -n 1 | sed 's/^ksud //')"
              major="$(echo "$v" | sed -n 's/^v*\([0-9][0-9]*\)\..*/\1/p')" ;;
    APatch)   b="$RV4A_ROOT/data/adb/apd";  c="$APATCH_VER_CODE"; min=11219; major="" ;;
    *) echo no; return ;;
  esac
  if [ -r "$b" ]; then
    if grep -q 'metamount\.sh' "$b" 2>/dev/null; then echo yes; else echo no; fi
    return
  fi
  case "$c" in
    ''|*[!0-9]*) ;;
    *) if [ "$c" -ge "$min" ]; then echo yes; else echo no; fi; return ;;
  esac
  case "$major" in
    ''|*[!0-9]*) echo unknown ;;
    *) if [ "$major" -ge 3 ]; then echo yes; else echo no; fi ;;
  esac
}

# The metamodule's folder, found the way KernelSU finds it: the
# /data/adb/metamodule link first, then any module declaring metamodule=1.
metamodule_dir() {
  m="$RV4A_ROOT/data/adb/metamodule"
  if [ -L "$m" ] || [ -d "$m" ]; then
    d="$(readlink -f "$m" 2>/dev/null)"
    if [ -n "$d" ] && [ -d "$d" ]; then echo "${d#"$RV4A_ROOT"}"; return 0; fi
  fi
  for p in "$RV4A_ROOT"/data/adb/modules/*/module.prop; do
    [ -f "$p" ] || continue
    v="$(sed -n 's/^metamodule=//p' "$p" | head -n 1 | tr -d ' \r')"
    case "$v" in
      1|[Tt][Rr][Uu][Ee]) d="${p%/module.prop}"; echo "${d#"$RV4A_ROOT"}"; return 0 ;;
    esac
  done
  return 1
}

# Prints the metamodule's id and returns 0 when it will mount this boot.
# Otherwise prints what is wrong and returns 1: a metamodule that is
# disabled, being removed or has no mount script mounts nothing.
metamodule_state() {
  d="$(metamodule_dir)" || { echo "none installed"; return 1; }
  id="${d##*/}"
  if [ -f "$RV4A_ROOT$d/disable" ]; then echo "$id is disabled"; return 1; fi
  if [ -f "$RV4A_ROOT$d/remove" ]; then echo "$id is being removed"; return 1; fi
  if [ ! -f "$RV4A_ROOT$d/metamount.sh" ]; then echo "$id has no metamount.sh"; return 1; fi
  echo "$id"
}

late_load() { [ "$KSU_LATE_LOAD" = 1 ] || [ "$KSU_RUNTIME_MODE" = late-load ]; }

# APatch before metamodules: true when lite mode leaves module files
# unmounted. Before 11131 it skips mounting them altogether. From 11131 (first
# released as 11142) it only skips APatch's own bind mounts, and modules
# still go in through OverlayFS when that option is on and the kernel has
# overlayfs. APATCH_LITE_OVERLAY says whether this version would.
apatch_lite() {
  [ -f "$RV4A_ROOT/data/adb/.litemode_enable" ] || return 1
  c="$APATCH_VER_CODE"
  case "$c" in ''|*[!0-9]*) c=11131 ;; esac
  APATCH_LITE_OVERLAY=0
  [ "$c" -ge 11131 ] || return 0
  APATCH_LITE_OVERLAY=1
  if [ -f "$RV4A_ROOT/data/adb/.overlayfs_enable" ] &&
     grep -q overlay "$RV4A_ROOT/proc/filesystems" 2>/dev/null; then
    return 1
  fi
  return 0
}
