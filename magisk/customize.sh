#!/system/bin/sh
# Installs the effect library and registers it with the audio HAL.
#
# Only one copy of audio_effects.xml can be mounted at a time, so a module that
# overlays it directly fights with every other audio mod on the device. When
# Audio Modification Library is present we hand it a patch script and let it do
# the merging; only without AML do we overlay the file ourselves.

SKIPUNZIP=0

# --- what are we installing under, and onto? ------------------------------
API=$(getprop ro.build.version.sdk)
case "$API" in ''|*[!0-9]*) API=0 ;; esac

if [ -d /data/adb/ksu ] || [ -n "$KSU" ]; then
  ROOT_IMPL="KernelSU"
elif [ -d /data/adb/ap ] || [ -n "$APATCH" ]; then
  ROOT_IMPL="APatch"
else
  ROOT_IMPL="Magisk"
fi
ui_print "- Root: $ROOT_IMPL, Android API $API"

# A module that disabled itself after two bad boots keeps that flag across an
# upgrade, so installing the build that fixes the problem produced a cheerful
# "reboot to load the engine" and a module that was still switched off.
if [ -f /data/adb/modules/rv4a_engine/disable ]; then
  rm -f /data/adb/modules/rv4a_engine/disable
  ui_print "- Cleared a previous self-disable"
  if [ -f /data/adb/modules/rv4a_engine/boot.log ]; then
    grep -i "quarantined\|rejected" /data/adb/modules/rv4a_engine/boot.log 2>/dev/null |
      tail -3 | while read -r l; do ui_print "  was: $l"; done
  fi
fi

# The effect is a legacy audio-effect library: the audio server discovers it
# through audio_effects.xml or .conf. Android 15 moved effects to an AIDL HAL,
# and devices that ship only the AIDL path never read those files, so nothing
# would load the engine no matter where it is placed.
#
# Ask the device what it is running rather than guessing from file paths. The
# old check listed six hardcoded paths, which missed /odm and the Qualcomm sku
# directories entirely - so it aborted on Snapdragon devices that were fine -
# and it passed on AIDL devices that still carry a stale xml nobody reads, so
# those installed something inert and said it had worked.
HAS_AIDL=0
if command -v service >/dev/null 2>&1; then
  service list 2>/dev/null | grep -q "android.hardware.audio.effect.IFactory" && HAS_AIDL=1
fi

HAS_LEGACY_CONFIG=0
for f in /odm/etc/audio_effects.xml /odm/etc/audio_effects.conf \
         /vendor/etc/audio/audio_effects.xml /vendor/etc/audio/audio_effects.conf \
         /vendor/etc/audio_effects.xml /vendor/etc/audio_effects.conf \
         /system/vendor/etc/audio_effects.xml /system/vendor/etc/audio_effects.conf \
         /system_ext/etc/audio_effects.xml /product/etc/audio_effects.xml \
         /system/etc/audio_effects.xml /system/etc/audio_effects.conf; do
  [ -f "$f" ] && HAS_LEGACY_CONFIG=1
done

if [ "$HAS_AIDL" = "1" ] && [ "$HAS_LEGACY_CONFIG" = "0" ]; then
  ui_print " "
  ui_print "! This device runs the AIDL audio effects HAL and has"
  ui_print "  no legacy effects config. It does not load effect"
  ui_print "  libraries like this one. Android 15 moved effects to"
  ui_print "  AIDL and this engine implements the older interface,"
  ui_print "  so it cannot be found here."
  ui_print "  Rootless mode still works and is unaffected."
  abort "! Aborting rather than installing something inert"
fi

if [ "$HAS_LEGACY_CONFIG" = "0" ]; then
  ui_print " "
  ui_print "! No legacy audio effects config was found on this"
  ui_print "  device, and the AIDL HAL was not detected either."
  ui_print "  There is nowhere to register the engine."
  abort "! Aborting rather than installing something inert"
fi

if [ "$HAS_AIDL" = "1" ]; then
  ui_print "- Note: this device has BOTH the AIDL HAL and a legacy"
  ui_print "  config. Installing against the legacy path; if the"
  ui_print "  effect does not appear, the AIDL HAL is the one in use."
fi

case "$ARCH" in
  arm64) ABI_DIR="arm64-v8a";   LIBDIR="lib64" ;;
  arm)   ABI_DIR="armeabi-v7a"; LIBDIR="lib"   ;;
  x64)   ABI_DIR="x86_64";      LIBDIR="lib64" ;;
  x86)   ABI_DIR="x86";         LIBDIR="lib"   ;;
  *)     abort "! Unsupported architecture: $ARCH" ;;
esac

ui_print "- Architecture: $ARCH ($ABI_DIR)"

SO="$MODPATH/libs/$ABI_DIR/libjamesdsp.so"
[ -f "$SO" ] || abort "! No engine build for $ABI_DIR in this package"

# Install to both system and vendor soundfx: which one the audio server loads
# from depends on where the device's config lives, and this costs one file.
#
# The context is set explicitly. The comment here used to claim set_perm leaves
# the label to the mount point; it does not, it hardcodes system_file in
# Magisk, KernelSU and APatch alike. system_file happens to be loadable by the
# audio server, so this has been working by luck rather than design, and
# system_lib_file is the type that actually describes the file. It does not
# exist before Android 10, so ask for it only where it exists and fall back
# rather than leaving set_perm to fail unnoticed.
for D in "$MODPATH/system/$LIBDIR/soundfx" "$MODPATH/system/vendor/$LIBDIR/soundfx"; do
  mkdir -p "$D"
  cp "$SO" "$D/libjamesdsp.so" || abort "! Could not stage the engine into $D"
  set_perm "$D" 0 0 0755
  if [ "$API" -ge 29 ]; then
    set_perm "$D/libjamesdsp.so" 0 0 0644 u:object_r:system_lib_file:s0 ||
      set_perm "$D/libjamesdsp.so" 0 0 0644 ||
      ui_print "! Could not set permissions on $D/libjamesdsp.so"
  else
    set_perm "$D/libjamesdsp.so" 0 0 0644 ||
      ui_print "! Could not set permissions on $D/libjamesdsp.so"
  fi
done
rm -rf "$MODPATH/libs"
ui_print "- Engine installed to $LIBDIR/soundfx"

. "$MODPATH/common/patch_audio_config.sh"

if [ -d /data/adb/modules/aml ] || [ -d /data/adb/modules/AML ] ||
   [ -d /data/adb/modules/pixaml ] || [ -d /data/adb/modules/PIXAML ]; then
  # AML sweeps modules for audio config files and merges them into one set,
  # so leaving ours to it is exactly what it expects. Patching /vendor
  # ourselves as well would give the device two competing mounts.
  ui_print "- Audio Modification Library detected"
  ui_print "  Leaving the merge to AML; both mods can coexist"
  write_effect_fragment
else
  patch_audio_config || abort "! Aborting rather than installing something inert"
fi

ui_print "- Reboot to load the engine"
ui_print " "
ui_print "  If the device fails to boot, boot into Safe Mode,"
ui_print "  which disables every module, or connect it and run:"
case "$ROOT_IMPL" in
  KernelSU) ui_print "    adb wait-for-device shell su -c 'ksud module disable rv4a_engine'" ;;
  APatch)   ui_print "    adb wait-for-device shell su -c 'apd module disable rv4a_engine'" ;;
  # Not "magisk --remove-modules": that deletes every module on the device,
  # and it is refused from an unprivileged adb shell anyway, so the one line
  # a panicking user would copy used to both fail and, if it worked, take
  # their unrelated modules with it.
  *)        ui_print "    adb wait-for-device shell su -c 'touch /data/adb/modules/rv4a_engine/disable'" ;;
esac
ui_print "  (su over adb has to be granted in the manager already.)"
ui_print " "
ui_print "  This module also disables itself after two boots that"
ui_print "  do not complete, so a loop caused by this alone should"
ui_print "  clear itself by the third boot."
