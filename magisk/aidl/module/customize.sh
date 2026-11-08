#!/system/bin/sh
# Installs the rooted engine as an AIDL effect plugin. EXPERIMENTAL.
#
# On phones whose audio effects run behind the AIDL effect HAL, the vendor's
# effect factory reads audio_effects_config.xml and loads every library
# listed in it. This module adds one library, and two lines to copies of the
# config files the factory may read. It does not replace or restart any
# service, add SELinux policy or patch any system library.
#
# It refuses to install whenever anything does not match what it was built
# and tested for, because the library is loaded inside the audio HAL itself:
# a library built for a different effect HAL version would write a wrongly
# laid out structure into the HAL's memory.

SKIPUNZIP=0

. "$MODPATH/common/aidl_common.sh"

refuse() {
  rm -rf "$MODPATH/system" "$MODPATH/configs" "$MODPATH/install.prop"
  ui_print " "
  for l in "$@"; do ui_print "! $l"; done
  ui_print " "
  ui_print "  To see what this phone has, run rv4a-aidl-diag.sh from"
  ui_print "  this zip in a root shell and send the file it writes."
  abort "! Nothing was installed."
}

API=$(rv4a_getprop ro.build.version.sdk)
case "$API" in ''|*[!0-9]*) API=0 ;; esac
ROOT_IMPL="$(rv4a_root_impl)"
case "$ROOT_IMPL" in
  KernelSU) ROOT_VER="${KSU_VER:-$(ksud -V 2>/dev/null)}" ;;
  APatch)   ROOT_VER="${APATCH_VER:-}" ;;
  *)        ROOT_VER="${MAGISK_VER:-}" ;;
esac

ui_print "- RootfulViPER4Android AIDL engine (experimental test build)"
ui_print "- $(rv4a_getprop ro.product.model), Android $(rv4a_getprop ro.build.version.release) (API $API)"
ui_print "- Root: $ROOT_IMPL $ROOT_VER"

case "$ARCH" in
  arm64) ABI=arm64-v8a ;;
  x64)   ABI=x86_64 ;;
  *) refuse "This build is for 64-bit phones only (this one is $ARCH)." ;;
esac

# --- 0. root that can show the HAL a file before it starts ------------------
if rv4a_late_load; then
  refuse "Your root is loaded after the phone has booted (late-load" \
    "or a temporary root). The audio system reads its effect list" \
    "during boot, before this module could add to it, so this" \
    "test build cannot work with it yet."
fi
if [ "$ROOT_IMPL" = KernelSU ] && ! rv4a_metamodule; then
  if [ "$(rv4a_ksu_major)" -ge 3 ]; then
    refuse "KernelSU 3 and later only mount module files through a" \
      "metamodule, and none is installed. Install meta-overlayfs" \
      "(or another metamodule), reboot, then install this again."
  fi
  ui_print "- Note: no KernelSU metamodule found. If your KernelSU is"
  ui_print "  version 3 or later, this module stays inactive until one"
  ui_print "  (such as meta-overlayfs) is installed. It will say so."
fi

[ "$API" -ge 34 ] || refuse "This phone does not use the AIDL effect HAL." \
  "Install the normal RV4A engine module instead."

# --- 1. which effect HAL? ---------------------------------------------------
KIND="$(rv4a_hal_kind)"
case "$KIND" in
  aidl) ui_print "- Effect HAL: AIDL" ;;
  hidl) refuse "This phone runs the older (HIDL) audio effect HAL." \
          "The normal RV4A engine module is the one for it." ;;
  *)    refuse "No audio effect HAL is registered on this phone." ;;
esac

FPID="$(rv4a_factory_pid)"
FEXE=""; FAPEX=""; FNDK=""
if [ -n "$FPID" ]; then
  FEXE="$(rv4a_factory_exe "$FPID")"
  FAPEX="$(rv4a_factory_apex "$FPID")"
  FNDK="$(rv4a_factory_ndk_versions "$FPID")"
  ui_print "- Effect factory: pid $FPID, $FEXE"
else
  ui_print "- Could not find the effect factory's process"
fi

# --- 2. which version? ------------------------------------------------------
BV="$(rv4a_binder_version)"
VV="$(rv4a_vintf_effect_version)"
ui_print "- Version reported by the factory: ${BV:-no answer}"
ui_print "  interface library it has loaded: ${FNDK:-none (linked in)}"
ui_print "  declared in the manifests: ${VV:-none or several}"
CHOICE="$(rv4a_choose_version "$BV" "$FNDK" "$VV")"
case "$CHOICE" in
  none) refuse "Could not tell which effect HAL version this phone runs." ;;
  conflict*) refuse "The phone disagrees with itself about its effect HAL:" \
               "${CHOICE#conflict }." "Not installing a library that could be the wrong one." ;;
esac
VER="${CHOICE%% *}"
VSRC="${CHOICE#* }"
[ -n "$VV" ] && [ "$VV" != "$VER" ] &&
  ui_print "  (the manifest says V$VV; going by what the factory runs)"

case " $RV4A_SUPPORTED_VERSIONS " in
  *" $VER "*) ui_print "- Using effect HAL V$VER" ;;
  *) if [ "$VER" -lt 2 ]; then
       refuse "Effect HAL V$VER belongs to Android 14 vendors. Their" \
         "factory only accepts effects Android already knows by" \
         "name, so a new engine cannot be added through its config."
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

# --- 4. the configs the factory reads --------------------------------------
NAMES="$(rv4a_config_names "$FPID" | tr '\n' ' ')"
[ "$(rv4a_getprop vendor.audio.hal.stubmode)" = true ] &&
  ui_print "- The audio HAL is in stub mode; its stub config is included"
PRIMARY="$(rv4a_primary_config "$FAPEX")"
CANDIDATES="$(rv4a_candidate_configs "$FAPEX" "$NAMES")"
CREATE=0
if [ -z "$CANDIDATES" ]; then
  BUILTIN="$(rv4a_builtin_config_path "$FPID")"
  if [ -n "$BUILTIN" ] && [ ! -e "$BUILTIN" ]; then
    if [ -f "$RV4A_ROOT$RV4A_CREATE_FLAG" ]; then
      CREATE=1; CANDIDATES="$BUILTIN"; PRIMARY="$BUILTIN"
      ui_print "- This phone ships no effects config; its factory looks"
      ui_print "  for $BUILTIN. Creating one (Pixel test)."
    else
      refuse "This phone ships no audio effects config. Its factory" \
        "looks for $BUILTIN, which does not exist (the Pixel layout)." \
        "Creating it is a separate test that may hide the phone's own" \
        "effects until the module is removed. To try it anyway, run" \
        "  su -c touch $RV4A_CREATE_FLAG" \
        "and install again."
    fi
  else
    refuse "Could not find the audio effects config this phone uses."
  fi
fi
[ -n "$PRIMARY" ] || PRIMARY="$(echo "$CANDIDATES" | head -n 1)"
ui_print "- The factory reads $PRIMARY"

# Something already mounted over these files would be what we read, and
# would be hidden by our copy, or hide it.
for c in $CANDIDATES; do
  [ "$CREATE" = 1 ] && break
  OWNERS="$(rv4a_modules_with_file "$c" | tr '\n' ' ')"
  [ -n "$OWNERS" ] && refuse "Another module also replaces $c: $OWNERS" \
    "Only one of them can. Remove it, reboot, then install this again."
  FM="$(rv4a_foreign_mount "$c")"
  [ -n "$FM" ] && refuse "$c is already replaced by a mount from $FM." \
    "Only one copy can be in place. Find what put it there, remove" \
    "it, reboot, then install this again."
  NEAR="$(rv4a_modules_in_dir "$c" | tr '\n' ' ')"
  [ -n "$NEAR" ] && [ "$ROOT_IMPL" = Magisk ] &&
    ui_print "  Note: $NEAR adds files next to $c; on Magisk that can hide our copy. The status will say so."
done

# --- 5. patch every copy ----------------------------------------------------
rm -rf "$MODPATH/configs" "$MODPATH/configs.rejected" "$MODPATH/configs.disabled" \
       "$MODPATH/created_config.rejected" "$MODPATH/created_config.disabled"
PROPS="$TMPDIR/configs.prop"
: > "$PROPS"
N=0
for c in $CANDIDATES; do
  if [ "$CREATE" = 1 ]; then
    OUT="$MODPATH/system$c"
    mkdir -p "$(dirname "$OUT")"
    rv4a_minimal_config "$LIB" > "$OUT"
    N=$((N + 1))
    { echo "config_$N=$c"; echo "sig_$N=none"; echo "label_$N=u:object_r:vendor_configs_file:s0"; } >> "$PROPS"
    ui_print "  created $c"
    continue
  fi
  SRC="$TMPDIR/source_$N.xml"
  # When an older copy of this module is mounted over the file, that copy is
  # what we read, so our lines are stripped before anything else looks at it.
  rv4a_strip "$RV4A_ROOT$c" > "$SRC" || refuse "Could not read $c."
  if grep -q "$RV4A_UUID" "$SRC"; then
    refuse "$c already lists this effect for another library." \
      "Another JamesDSP-based mod, or the ROM itself, provides it." \
      "Only one can be installed."
  fi
  OUT="$MODPATH/configs$c"
  mkdir -p "$(dirname "$OUT")"
  if ! rv4a_patch "$SRC" "$OUT" "$LIB"; then
    rm -f "$OUT"
    [ "$c" = "$PRIMARY" ] && refuse "$c is laid out in a way this build does not patch" \
      "(the closing tags are not on lines of their own)."
    ui_print "  skipped $c (a layout this build does not patch)"
    continue
  fi
  if ! WHY="$(rv4a_validate "$SRC" "$OUT" "$LIB")"; then
    refuse "The patched copy of $c failed its own check: $WHY"
  fi
  N=$((N + 1))
  {
    echo "config_$N=$c"
    echo "sig_$N=$(rv4a_config_sig "$SRC")"
    echo "label_$N=$(rv4a_label_of "$c")"
  } >> "$PROPS"
  CRS="$(grep -c "$(printf '\r')" "$SRC")"
  ui_print "  patched a copy of $c ($(wc -l < "$OUT") lines${CRS:+, $CRS with CRLF})"
done
[ "$N" -ge 1 ] || refuse "No config could be patched."

# --- 6. files and labels ----------------------------------------------------
LIBDIR="$MODPATH/system/vendor/lib64/soundfx"
mkdir -p "$LIBDIR"
cp "$MODPATH/libs/$ABI/$LIB" "$LIBDIR/$LIB"
rm -rf "$MODPATH/libs"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
# The factory runs in a vendor domain. The library carries the label the
# soundfx folder's own libraries have; each config copy carries its
# original's (recorded in install.prop and set again at every boot).
set_perm "$LIBDIR/$LIB" 0 0 0644 u:object_r:vendor_file:s0
if [ "$CREATE" = 1 ]; then
  set_perm "$MODPATH/system$PRIMARY" 0 0 0644 u:object_r:vendor_configs_file:s0
else
  i=1
  for c in $CANDIDATES; do
    [ -f "$MODPATH/configs$c" ] || continue
    set_perm "$MODPATH/configs$c" 0 0 0644 "$(rv4a_prop "label_$i" "$PROPS")"
    i=$((i + 1))
  done
fi
ui_print "- Installed $LIB"

{
  echo "hal_version=$VER"
  echo "version_source=$VSRC"
  echo "vintf_version=$VV"
  echo "lib=$LIB"
  echo "primary=$PRIMARY"
  echo "configs=$N"
  echo "create=$CREATE"
  cat "$PROPS"
  echo "vendor_fingerprint=$(rv4a_getprop ro.vendor.build.fingerprint)"
  echo "factory_exe=$FEXE"
  echo "factory_ndk=$FNDK"
  echo "root=$ROOT_IMPL $ROOT_VER"
  echo "installed=$(date 2>/dev/null)"
} > "$MODPATH/install.prop"

# A self-disable from an earlier test build would otherwise survive the
# upgrade and leave this one switched off.
rm -f "$RV4A_ROOT/data/adb/modules/$RV4A_MODID/disable"

ui_print " "
ui_print "- Done. Reboot, then open RootfulViPER4Android."
ui_print " "
ui_print "  The module switches itself off if the audio system"
ui_print "  restarts three times within three minutes, or after two"
ui_print "  boots that do not complete. To switch it off yourself:"
ui_print "    su -c sh /data/adb/modules/$RV4A_MODID/action.sh"
ui_print "  (or the Action button in the manager), then reboot."
ui_print "  To remove it: tap Remove in the manager and reboot."
ui_print "  Status and logs: /data/adb/modules/$RV4A_MODID/"
