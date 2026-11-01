#!/system/bin/sh
# Installs the rooted engine as an AIDL effect plugin. EXPERIMENTAL.
#
# On phones whose audio effects run behind the AIDL effect HAL, the vendor's
# effect factory reads audio_effects_config.xml and loads every library listed
# in it. This module adds one library and two lines to that file. It does not
# replace or restart any service, add SELinux policy or patch any system
# library. If the library fails to load, our effect is missing and everything
# else keeps working.
#
# It refuses to install whenever anything does not match what it was built
# and tested for, because the library is loaded inside the audio HAL itself:
# a library built for a different effect HAL version would write a wrongly
# laid out structure into the HAL's memory.

SKIPUNZIP=0

. "$MODPATH/common/aidl_common.sh"

refuse() {
  rm -rf "$MODPATH/system" "$MODPATH/install.prop"
  ui_print " "
  for l in "$@"; do ui_print "! $l"; done
  ui_print " "
  abort "! Nothing was installed."
}

API=$(getprop ro.build.version.sdk)
case "$API" in ''|*[!0-9]*) API=0 ;; esac
if [ -d /data/adb/ksu ] || [ -n "$KSU" ]; then ROOT_IMPL="KernelSU"
elif [ -d /data/adb/ap ] || [ -n "$APATCH" ]; then ROOT_IMPL="APatch"
else ROOT_IMPL="Magisk"; fi

ui_print "- RootlessViPER4Android AIDL engine (experimental test build)"
ui_print "- Root: $ROOT_IMPL, Android API $API, $(getprop ro.product.model)"

case "$ARCH" in
  arm64) ABI=arm64-v8a ;;
  x64)   ABI=x86_64 ;;
  *) refuse "This build is for 64-bit phones only (this one is $ARCH)." ;;
esac

[ "$API" -ge 34 ] || refuse "This phone does not use the AIDL effect HAL." \
  "Install the normal RV4A engine module instead."

# --- 1. is there an AIDL effect factory at all? ----------------------------
if ! service list 2>/dev/null | grep -q "android.hardware.audio.effect.IFactory/default"; then
  refuse "No AIDL audio effect factory is running on this phone." \
    "It most likely uses the older effect HAL, which the normal" \
    "RV4A engine module supports. Install that one instead."
fi
ui_print "- AIDL effect factory found"

# --- 2. which version is it? -----------------------------------------------
VER="$(rv4a_vintf_effect_version)"
ENTRIES="$(rv4a_vintf_effect_entries)"
if [ -z "$VER" ]; then
  [ -n "$ENTRIES" ] && echo "$ENTRIES" | while read -r l; do ui_print "  declared: $l"; done
  refuse "Could not tell which effect HAL version this phone runs." \
    "Its manifests declare none, or more than one."
fi
VINTF_FILE="$(echo "$ENTRIES" | head -n 1 | cut -d' ' -f2-)"
ui_print "- Effect HAL version $VER (from ${VINTF_FILE#$RV4A_ROOT})"

FPID="$(rv4a_factory_pid)"
FEXE=""; FAPEX=""; FNDK=""
if [ -n "$FPID" ]; then
  FEXE="$(readlink /proc/$FPID/exe 2>/dev/null)"
  FAPEX="$(rv4a_factory_apex "$FPID")"
  FNDK="$(rv4a_factory_ndk_versions "$FPID" | tr '\n' ' ')"
  ui_print "- Factory runs in pid $FPID: $FEXE"
  # The interface library the factory actually has loaded must agree with
  # the manifest. When it links statically there is nothing to compare.
  if [ -n "$FNDK" ]; then
    for v in $FNDK; do
      [ "$v" = "$VER" ] || refuse "The factory has effect HAL V$v loaded, but the" \
        "manifest says V$VER. Not installing on a phone that disagrees with itself."
    done
    ui_print "  and has the V$VER interface library loaded, matching"
  else
    ui_print "  (links its interface code statically; using the manifest)"
  fi
else
  ui_print "- Could not find the factory's process; using the manifest"
fi

case " $RV4A_SUPPORTED_VERSIONS " in
  *" $VER "*) ;;
  *) if [ "$VER" -lt 2 ]; then
       refuse "Effect HAL V$VER (Android 14 vendor) is not supported yet."
     else
       refuse "Effect HAL V$VER is newer than this build knows (V2 to V4)." \
         "Check for a newer test build."
     fi ;;
esac
LIB="librv4a_aidl_v$VER.so"
[ -f "$MODPATH/libs/$ABI/$LIB" ] || refuse "This package has no $ABI build for V$VER."

# --- 3. anything that would fight us? -------------------------------------
CONFLICTS="$(rv4a_conflicting_modules | tr '\n' ' ')"
if [ -n "$CONFLICTS" ]; then
  refuse "Remove these modules first: $CONFLICTS" \
    "They use the same effect, or replace the effect factory," \
    "so only one of them can work. Uninstall, reboot, then try again."
fi
if rv4a_aml_present; then
  refuse "Audio Modification Library is installed." \
    "This test build does not work through AML yet. Disable AML," \
    "reboot, then install this again."
fi
ui_print "- No conflicting modules"

# --- 4. the config the factory reads ---------------------------------------
CONFIG="$(rv4a_find_config "$FAPEX")"
case "$CONFIG" in
  "") refuse "Could not find the audio_effects_config.xml this phone uses." \
        "On Pixel phones the factory has none outside its APEX;" \
        "Pixel support needs a separate test step." ;;
  apex:*) refuse "The factory reads ${CONFIG#apex:}," \
        "which is inside a read-only APEX this module cannot change." \
        "This is the Pixel layout; it needs a separate test step." ;;
esac
OVERLAY="$(rv4a_overlay_path "$CONFIG")"
[ -n "$OVERLAY" ] || refuse "The factory reads $CONFIG, on a partition" \
  "this module cannot overlay."
ui_print "- Factory config: $CONFIG"

# The device's own file. When an older copy of this module is mounted over
# it, that copy is what we read, so our lines are stripped before anything
# else looks at it.
SRC="$TMPDIR/source_config.xml"
rv4a_strip "$RV4A_ROOT$CONFIG" > "$SRC" || refuse "Could not read $CONFIG."
if grep -q "$RV4A_UUID" "$SRC"; then
  refuse "$CONFIG already lists this effect for another library." \
    "Another JamesDSP-based mod, or the ROM itself, provides it." \
    "Only one can be installed."
fi

mkdir -p "$MODPATH/$(dirname "$OVERLAY")"
if ! rv4a_patch "$SRC" "$MODPATH/$OVERLAY" "$LIB"; then
  refuse "$CONFIG is laid out in a way this build does not patch" \
    "(the closing tags are not on lines of their own)."
fi
if ! WHY="$(rv4a_validate "$SRC" "$MODPATH/$OVERLAY" "$LIB")"; then
  refuse "The patched config failed its own check: $WHY"
fi
ui_print "- Added the engine to a copy of it ($(wc -l < "$MODPATH/$OVERLAY") lines, 2 new)"

# --- 5. files ---------------------------------------------------------------
LIBDIR="$MODPATH/system/vendor/lib64/soundfx"
mkdir -p "$LIBDIR"
cp "$MODPATH/libs/$ABI/$LIB" "$LIBDIR/$LIB"
rm -rf "$MODPATH/libs"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
# The factory runs in a vendor domain. Libraries it loads and configs it
# reads carry these labels on the real partition, so the copies do too.
set_perm "$LIBDIR/$LIB" 0 0 0644 u:object_r:vendor_file:s0
set_perm "$MODPATH/$OVERLAY" 0 0 0644 u:object_r:vendor_configs_file:s0
ui_print "- Installed $LIB"

{
  echo "hal_version=$VER"
  echo "lib=$LIB"
  echo "config=$CONFIG"
  echo "overlay=$OVERLAY"
  echo "source_sig=$(rv4a_config_sig "$SRC")"
  echo "vendor_fingerprint=$(getprop ro.vendor.build.fingerprint)"
  echo "vintf_file=${VINTF_FILE#$RV4A_ROOT}"
  echo "factory_exe=$FEXE"
  echo "factory_ndk=$FNDK"
  echo "installed=$(date 2>/dev/null)"
} > "$MODPATH/install.prop"

# A self-disable from an earlier test build would otherwise survive the
# upgrade and leave this one switched off.
rm -f /data/adb/modules/rv4a_engine_aidl/disable

ui_print " "
ui_print "- Done. Reboot, then open RootfulViPER4Android."
ui_print " "
ui_print "  If sound stops or the phone will not boot properly,"
ui_print "  the module switches itself off: after the audio HAL"
ui_print "  restarts three times, or after two boots that do not"
ui_print "  complete. To switch it off yourself:"
case "$ROOT_IMPL" in
  KernelSU) ui_print "    adb shell su -c 'ksud module disable rv4a_engine_aidl'" ;;
  APatch)   ui_print "    adb shell su -c 'apd module disable rv4a_engine_aidl'" ;;
  *)        ui_print "    adb shell su -c 'touch /data/adb/modules/rv4a_engine_aidl/disable'" ;;
esac
ui_print "  then reboot. Safe mode also turns every module off."
ui_print "  Logs: /data/adb/modules/rv4a_engine_aidl/boot.log"
