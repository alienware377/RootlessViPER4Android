#!/system/bin/sh
# Installs the effect library and registers it with the audio HAL.
#
# Only one copy of an effects config can be mounted at a time, so a module
# that overlays it directly fights with every other audio mod on the device.
# When Audio Modification Library is present we hand it a patch script and let
# it do the merging; only without AML do we overlay the file ourselves.

SKIPUNZIP=0

RV4A_ROOT="${RV4A_ROOT:-}"
. "$MODPATH/common/root_manager.sh"
. "$MODPATH/common/patch_audio_config.sh"
rm -f "$MODPATH/install.log"

# --- what are we installing under, and onto? ------------------------------
API=$(getprop ro.build.version.sdk)
case "$API" in ''|*[!0-9]*) API=0 ;; esac

ROOT_IMPL="$(root_impl)"
say "- Phone: $(getprop ro.product.model), Android $(getprop ro.build.version.release) (API $API)"
say "- Root: $ROOT_IMPL $(root_version "$ROOT_IMPL")"

# A module that disabled itself after two bad boots keeps that flag across an
# upgrade, so clear it here or the new build installs switched off.
OLD="$RV4A_ROOT/data/adb/modules/rv4a_engine"
if [ -f "$OLD/disable" ]; then
  rm -f "$OLD/disable"
  say "- Cleared a previous self-disable"
  if [ -f "$OLD/boot.log" ]; then
    grep -i "quarantined\|rejected" "$OLD/boot.log" 2>/dev/null |
      tail -3 | while read -r l; do say "  was: $l"; done
  fi
fi

# --- will the manager put our files where the audio HAL looks? ------------
# Checked before anything is staged: on these setups the install would
# otherwise succeed and the engine would simply never appear.
if late_load; then
  say " "
  say "! KernelSU is running in late-load mode. It starts after"
  say "  the phone has booted, and by then the audio system has"
  say "  already read its list of effects, so the engine cannot"
  say "  be added to it in this mode. Rootless mode still works."
  abort "! Aborting rather than installing something inert"
fi

case "$ROOT_IMPL" in
  KernelSU|APatch)
    NEED_META="$(needs_metamodule "$ROOT_IMPL")"
    META="$(metamodule_state)"; META_OK=$?
    if [ "$META_OK" = 0 ]; then
      say "- Metamodule: $META"
    elif [ "$NEED_META" = yes ]; then
      say " "
      say "! This $ROOT_IMPL only mounts module files through a"
      say "  metamodule, and none is active ($META)."
      say "  Without one this module would install, but the engine"
      say "  would never reach the audio system. Install a metamodule"
      say "  such as meta-overlayfs, reboot, then install this again."
      abort "! Aborting rather than installing something inert"
    elif [ "$NEED_META" = unknown ]; then
      case "$ROOT_IMPL" in KernelSU) FROM="KernelSU 3" ;; *) FROM="APatch 11219" ;; esac
      say "- Metamodule: $META. Could not tell which $ROOT_IMPL"
      say "  version this is. $FROM and later need a metamodule"
      say "  (such as meta-overlayfs); without one the engine will not"
      say "  appear after the reboot."
    else
      say "- Metamodule: $META (not needed by this version)"
      if [ "$ROOT_IMPL" = APatch ] && apatch_lite; then
        say " "
        say "! APatch is in lite mode, which does not mount module"
        say "  files, so the engine would never reach the audio system."
        say "  Turn lite mode off in APatch, reboot, then install again."
        abort "! Aborting rather than installing something inert"
      fi
    fi
    ;;
esac

case "$ARCH" in
  arm64) ABI_DIR="arm64-v8a";   LIBDIR="lib64" ;;
  arm)   ABI_DIR="armeabi-v7a"; LIBDIR="lib"   ;;
  x64)   ABI_DIR="x86_64";      LIBDIR="lib64" ;;
  x86)   ABI_DIR="x86";         LIBDIR="lib"   ;;
  *)     abort "! Unsupported architecture: $ARCH" ;;
esac

# --- which effect HAL, and which config does its loader read? ------------
# The effect is a legacy audio-effect library: the audio HAL discovers it
# through an effects config (audio_effects.xml, or a vendor's own name for
# it) or the older .conf. Android 15 moved effects to an AIDL HAL, and
# devices that ship only the AIDL path never read those files, so nothing
# would load the engine no matter where it is placed.
#
# Detect the HAL from the running services rather than from file paths: AIDL
# devices may still carry an unused config that nothing reads.
HAS_AIDL=0
if command -v service >/dev/null 2>&1; then
  service list 2>/dev/null | grep -q "android.hardware.audio.effect.IFactory" && HAS_AIDL=1
fi
detect_loader
CONFIGS="$(loader_configs)"

if [ "$HAS_AIDL" = 1 ]; then
  say "- Effect HAL: AIDL"
else
  HV="$(hidl_version)"
  say "- Effect HAL: ${HV:-no effect HAL process found}"
fi

if [ "$LOADER_GUESS" = 1 ]; then
  say "- Could not read the effect loader; trying $LOADER_NAMES"
else
  NAMED=0
  for b in $LOADER_BINS; do
    n="$(names_in "$b" | tr '\n' ' ')"; n="${n% }"
    [ -n "$n" ] || continue
    say "- Loader: ${b##*/} asks for $n"
    NAMED=1
  done
  [ "$NAMED" = 1 ] || say "- Loader: names no config; using audio_effects.xml"
  case "$LOADER_BINS:$LIBDIR" in
    *"/lib/libeffects.so"*:lib64)
      say "! The audio HAL is 32-bit and this installs the $ABI_DIR engine only" ;;
  esac
fi

if [ -z "$CONFIGS" ]; then
  if [ "$HAS_AIDL" = 1 ]; then
    say " "
    say "! This device runs the AIDL audio effects HAL and has"
    say "  no legacy effects config. It does not load effect"
    say "  libraries like this one. Android 15 moved effects to"
    say "  AIDL and this engine implements the older interface,"
    say "  so it cannot be found here."
    say "  Rootless mode still works and is unaffected."
  else
    say " "
    say "! None of the effects configs this phone's loader asks for"
    say "  ($LOADER_NAMES) exists, and the AIDL HAL was not detected"
    say "  either. There is nowhere to register the engine."
  fi
  abort "! Aborting rather than installing something inert"
fi

if [ "$HAS_AIDL" = 1 ]; then
  say "- Note: this device has BOTH the AIDL HAL and a legacy"
  say "  config. Installing against the legacy path; if the"
  say "  effect does not appear, the AIDL HAL is the one in use."
fi

N=0
for c in $CONFIGS; do
  N=$((N + 1))
  say "- Effects list in use: $c"
done
if [ "$N" -gt 1 ]; then
  say "  (which of these the loader picks cannot be told, so each is patched)"
fi

# Files loaded on top of the main list (Samsung's spatializer list, for one)
# are read first, so an entry for our effect in one of them, left by another
# JamesDSP mod, is the one that counts.
for x in $LOADER_EXTRA; do
  case " $CONFIGS " in *" $x "*) continue ;; esac
  if grep -q "$EFFECT_UUID" "$RV4A_ROOT$x" 2>/dev/null; then
    say "! $x already lists this effect and is loaded first;"
    say "  that entry wins. Remove the mod that added it."
  fi
done

say "- Architecture: $ARCH ($ABI_DIR)"

SO="$MODPATH/libs/$ABI_DIR/libjamesdsp.so"
[ -f "$SO" ] || abort "! No engine build for $ABI_DIR in this package"

# Install to both system and vendor soundfx: which one the audio server loads
# from depends on where the device's config lives, and this costs one file.
#
# The context is set explicitly: set_perm otherwise hardcodes system_file on
# Magisk, KernelSU and APatch. system_lib_file is the right type but only exists
# from Android 10, so fall back where it is missing.
for D in "$MODPATH/system/$LIBDIR/soundfx" "$MODPATH/system/vendor/$LIBDIR/soundfx"; do
  mkdir -p "$D"
  cp "$SO" "$D/libjamesdsp.so" || abort "! Could not stage the engine into $D"
  set_perm "$D" 0 0 0755
  if [ "$API" -ge 29 ]; then
    set_perm "$D/libjamesdsp.so" 0 0 0644 u:object_r:system_lib_file:s0 ||
      set_perm "$D/libjamesdsp.so" 0 0 0644 ||
      say "! Could not set permissions on $D/libjamesdsp.so"
  else
    set_perm "$D/libjamesdsp.so" 0 0 0644 ||
      say "! Could not set permissions on $D/libjamesdsp.so"
  fi
done
rm -rf "$MODPATH/libs"
say "- Engine installed to $LIBDIR/soundfx"

AML=0
for m in aml AML pixaml PIXAML; do
  [ -d "$RV4A_ROOT/data/adb/modules/$m" ] && AML=1
done
if [ "$AML" = 1 ]; then
  # AML sweeps modules for audio config files and merges them into one set,
  # so leaving ours to it is exactly what it expects. Patching /vendor
  # ourselves as well would give the device two competing mounts.
  say "- Audio Modification Library detected"
  say "  Leaving the merge to AML; both mods can coexist"
  write_effect_fragment
else
  patch_audio_config || abort "! Aborting rather than installing something inert"
  for c in $CONFIGS; do
    OTHERS="$(modules_with_file "$c" | tr '\n' ' ')"
    [ -n "$OTHERS" ] || continue
    say "! Also replaced by: $OTHERS"
    say "  Only one copy of $c can be in place. If the"
    say "  engine does not appear, install AML or remove those."
  done
fi

say "- Reboot to load the engine"
ui_print " "
ui_print "  If the device fails to boot, boot into Safe Mode,"
ui_print "  which disables every module, or connect it and run:"
case "$ROOT_IMPL" in
  KernelSU) ui_print "    adb wait-for-device shell su -c 'ksud module disable rv4a_engine'" ;;
  APatch)   ui_print "    adb wait-for-device shell su -c 'apd module disable rv4a_engine'" ;;
  # Not "magisk --remove-modules": that deletes every module on the device
  # and is refused from an unprivileged adb shell.
  *)        ui_print "    adb wait-for-device shell su -c 'touch /data/adb/modules/rv4a_engine/disable'" ;;
esac
ui_print "  (su over adb has to be granted in the manager already.)"
ui_print " "
ui_print "  This module also disables itself after two boots that"
ui_print "  do not complete, so a loop caused by this alone should"
ui_print "  clear itself by the third boot."
ui_print " "
ui_print "  What this install found is kept in install.log, and what"
ui_print "  each boot found in boot.log, both in the module folder."
