#!/usr/bin/env bash
# Host-side tests for the AIDL module's installer and boot scripts.
#
#   bash magisk/aidl/tests/test_module.sh                    # with the host's sh
#   SH="busybox sh" bash magisk/aidl/tests/test_module.sh    # as Magisk/KernelSU run it
#
# Builds fake device trees (VINTF manifests, effect configs, a fake /proc with
# the factory's maps, module folders) and runs the real scripts against them
# through $RV4A_ROOT and $RV4A_PROC, with the device commands (getprop,
# service, mount, ui_print, set_perm...) stubbed. Needs real symlinks, so run
# it on Linux or WSL.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
MOD="$HERE/../module"
STUB="$HERE/service_stubs.sh"
SH="${SH:-sh}"
T="$(mktemp -d)"
trap '[ -n "${KEEP:-}" ] || rm -rf "$T"' EXIT
pass=0; fail=0
ok() { if [ "$2" = 0 ]; then pass=$((pass + 1)); printf '  ok    %s\n' "$1"; else fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; fi; }
expect() { [ "$2" = "$3" ]; ok "$1 (got '$2')" $?; }
run() { $SH -c ". '$MOD/common/aidl_common.sh'; $1"; }   # one function call in the shell under test
CR="$(printf '\r')"
export CLOCK="$T/clock"; echo 1000 > "$CLOCK"

# ---------------------------------------------------------------- fixtures
mkcfg() {  # mkcfg <file> [crlf|mixed] [nofinal]
  mkdir -p "$(dirname "$1")"
  body='<?xml version="1.0" encoding="UTF-8"?>
<audio_effects_conf version="2.0" xmlns="http://schemas.android.com/audio/audio_effects_conf/v2_0">
    <libraries>
        <library name="bundle" path="libbundleaidl.so"/>
        <library name="dynamics_processing" path="libdynamicsprocessingaidl.so"/>
    </libraries>
    <!-- a note
    </effects>
    -->
    <effects>
        <effect name="bassboost" library="bundle" uuid="8631f300-72e2-11df-b57e-0002a5d5c51b" type="0634f220-ddd4-11db-a0fc-0002a5d5c51b"/>
        <effectProxy name="visualizer" uuid="1d0a1a53-7d5d-48f2-8e71-27fbd10d842c">
            <libsw library="visualizer_sw" uuid="d069d9e0-8329-11df-9168-0002a5d5c51b"/>
        </effectProxy>
    </effects>
    <postprocess>
        <stream type="music">
            <apply effect="bassboost"/>
        </stream>
    </postprocess>
</audio_effects_conf>'
  case "${2:-}" in
    crlf) printf '%s' "$body" | sed "s/\$/$CR/" > "$1" ;;
    mixed) printf '%s\n' "$body" | sed "3,9s/\$/$CR/" > "$1" ;;
    *) printf '%s\n' "$body" > "$1" ;;
  esac
  if [ "${3:-}" = nofinal ]; then printf '%s' "$(cat "$1")" > "$1.t" && mv "$1.t" "$1"; fi
}
mkvintf() {  # mkvintf <file> <version or "none"> [format]
  mkdir -p "$(dirname "$1")"
  v=""; [ "$2" != none ] && v="<version>$2</version>"
  cat > "$1" <<EOF
<manifest version="8.0" type="device">
    <hal format="hidl">
        <name>android.hardware.audio.effect</name>
        <transport>hwbinder</transport>
        <version>7.0</version>
    </hal>
    <!-- <hal format="aidl"><name>android.hardware.audio.effect</name><version>9</version></hal> -->
    <hal format="${3:-aidl}">
        <name>android.hardware.audio.effect</name>
        $v
        <fqname>IFactory/default</fqname>
    </hal>
    <hal format="aidl">
        <name>android.hardware.audio.core</name>
        <version>3</version>
    </hal>
</manifest>
EOF
}
# mkfactory <root> <exe path> <mapped ndk versions> [strings in the binary]
# A fake /proc/100 for the factory and a fake binary under the device root.
mkfactory() {
  mkdir -p "$1/proc/100" "$1/proc/1" "$(dirname "$1$2")"
  ln -sfn "$2" "$1/proc/100/exe"
  : > "$1/proc/100/maps"
  for v in $3; do echo "7f00 r-xp 0 0:0 1 /vendor/lib64/android.hardware.audio.effect-V$v-ndk.so" >> "$1/proc/100/maps"; done
  echo "7f01 r-xp 0 0:0 1 /vendor/lib64/libaudioeffecthal.qti.so" >> "$1/proc/100/maps"
  printf 'ELF\001\002%s\001\002' "${4:-}" | tr ' ' '\001' > "$1$2"
  : > "$1/proc/1/mountinfo"
}
props() {  # props <file> <fingerprint> [sku] [extra lines]
  printf 'ro.build.version.sdk=35\nro.build.version.release=15\nro.product.model=Phone\nro.vendor.build.fingerprint=%s\nro.boot.product.vendor.sku=%s\n%s' \
    "$2" "${3:-}" "${4:-}" > "$1"
}

echo "== shell under test: $SH =="

# ---------------------------------------------------------------- VINTF
echo; echo "[1] effect HAL version: manifests, binder and the loaded library"
R="$T/v1"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 3
expect "single declaration, V3 (hidl entry and commented block ignored)" "$(RV4A_ROOT=$R run rv4a_vintf_effect_version)" 3
R="$T/v2"; mkvintf "$R/vendor/etc/vintf/manifest/audio.xml" none
expect "no <version> means V1" "$(RV4A_ROOT=$R run rv4a_vintf_effect_version)" 1
R="$T/v3"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 2; mkvintf "$R/odm/etc/vintf/manifest.xml" 4
expect "two manifests disagreeing gives no answer" "$(RV4A_ROOT=$R run rv4a_vintf_effect_version)" ""
R="$T/v4"; mkvintf "$R/apex/com.vendor.audio/etc/vintf/audio.xml" 4
expect "a declaration in an APEX fragment counts" "$(RV4A_ROOT=$R run rv4a_vintf_effect_version)" 4
R="$T/v5"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 2 hidl
expect "only an HIDL declaration gives no answer" "$(RV4A_ROOT=$R run rv4a_vintf_effect_version)" ""
expect "binder answer wins over the manifest" "$(run 'rv4a_choose_version 2 2 4')" "2 binder"
expect "binder and loaded library disagree: conflict" "$(run 'rv4a_choose_version 3 2 3' | cut -d' ' -f1)" conflict
expect "binder picks among several loaded libraries" "$(run "rv4a_choose_version 3 '2 3' ''")" "3 binder"
expect "no binder answer: the loaded library" "$(run "rv4a_choose_version '' 2 4")" "2 mapped"
expect "no binder answer, two libraries loaded: conflict" "$(run "rv4a_choose_version '' '2 3' 3" | cut -d' ' -f1)" conflict
expect "linked statically: the manifest" "$(run "rv4a_choose_version '' '' 3")" "3 manifest"
expect "nothing at all" "$(run "rv4a_choose_version '' '' ''")" none
expect "binder answer parsed from service call" "$(BINDER_V=3 $SH -c ". '$MOD/common/aidl_common.sh'; . '$STUB'; rv4a_binder_version")" 3
expect "an error from service call gives no answer" "$($SH -c ". '$MOD/common/aidl_common.sh'; service() { echo 'Result: Parcel(fffffffc 00000000 ...)'; }; rv4a_binder_version")" ""
expect "a missing factory is not 'found'" "$(NO_FACTORY=1 $SH -c ". '$MOD/common/aidl_common.sh'; . '$STUB'; rv4a_service_registered x && echo yes || echo no")" no

# ---------------------------------------------------------------- config choice
echo; echo "[2] which configs the factory may read"
R="$T/c1"; mkcfg "$R/vendor/etc/audio_effects_config.xml"; mkcfg "$R/vendor/etc/audio/sku_kalama/audio_effects_config.xml"
mkcfg "$R/system/etc/audio_effects_config.xml"; mkfactory "$R" /vendor/bin/hw/audiohalservice.qti 2
props "$T/c1.props" fp kalama
expect "primary: Qualcomm sku folder wins over /vendor/etc" "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1.props run 'rv4a_primary_config ""')" /vendor/etc/audio/sku_kalama/audio_effects_config.xml
props "$T/c1b.props" fp ""
expect "primary without a sku: /vendor/etc" "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1b.props run 'rv4a_primary_config ""')" /vendor/etc/audio_effects_config.xml
expect "every copy is a candidate, primary first" \
  "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1.props run 'rv4a_candidate_configs "" audio_effects_config.xml' | tr '\n' ' ')" \
  "/vendor/etc/audio/sku_kalama/audio_effects_config.xml /vendor/etc/audio_effects_config.xml /system/etc/audio_effects_config.xml "
mkcfg "$R/odm/etc/audio_effects_config.xml"
expect "/odm/etc wins over everything" "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1.props run 'rv4a_primary_config ""')" /odm/etc/audio_effects_config.xml
R="$T/c2"; mkdir -p "$R/vendor/odm/etc"; ln -s vendor/odm "$R/odm"; mkcfg "$R/vendor/odm/etc/audio_effects_config.xml"
expect "/odm linked into /vendor is listed once, by its real path" \
  "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1b.props run 'rv4a_candidate_configs "" audio_effects_config.xml' | tr '\n' ' ')" "/vendor/odm/etc/audio_effects_config.xml "
R="$T/c3"; mkfactory "$R" /apex/com.android.hardware.audio.exynos/bin/hw/android.hardware.audio.service-aidl.exynos "" \
  "audio_effects_config_sec.xml /vendor/etc/audio/"
mkcfg "$R/vendor/etc/audio_effects_config.xml"; mkcfg "$R/vendor/etc/audio_effects_config_sec.xml" mixed
expect "Samsung: the _sec name written in the factory is read too" \
  "$(RV4A_ROOT=$R RV4A_PROC=$R/proc RV4A_PROPS=$T/c1b.props run 'rv4a_config_names 100' | tr '\n' ' ')" "audio_effects_config.xml audio_effects_config_sec.xml "
expect "the factory's APEX name" "$(RV4A_ROOT=$R RV4A_PROC=$R/proc run 'rv4a_factory_apex 100')" com.android.hardware.audio.exynos
R="$T/c4"; mkfactory "$R" /vendor/bin/hw/audiohalservice.qti 2 "audio_effects_config_stub.xml"
expect "the stub config is ignored outside stub mode" "$(RV4A_ROOT=$R RV4A_PROC=$R/proc RV4A_PROPS=$T/c1b.props run 'rv4a_config_names 100' | tr '\n' ' ')" "audio_effects_config.xml "
props "$T/c4.props" fp "" "vendor.audio.hal.stubmode=true"
expect "and read in stub mode" "$(RV4A_ROOT=$R RV4A_PROC=$R/proc RV4A_PROPS=$T/c4.props run 'rv4a_config_names 100' | tr '\n' ' ')" "audio_effects_config.xml audio_effects_config_stub.xml "
R="$T/c5"; mkfactory "$R" /vendor/bin/hw/android.hardware.audio.service-aidl.aoc 3 "/vendor/etc/audio_effects_config.xml external_library.cc"
expect "Pixel: the path built into the factory" "$(RV4A_ROOT=$R RV4A_PROC=$R/proc run 'rv4a_builtin_config_path 100')" /vendor/etc/audio_effects_config.xml
mkdir -p "$R/proc/200"; ln -sfn /system/bin/audioserver "$R/proc/200/exe"
echo "7f00 r-xp 0 0:0 1 /system/lib64/android.hardware.audio.effect-V3-ndk.so" > "$R/proc/200/maps"
mv "$R/proc/100" "$R/proc/300"
expect "fallback pid search skips audioserver" "$(RV4A_ROOT=$R RV4A_PROC=$R/proc $SH -c ". '$MOD/common/aidl_common.sh'; dumpsys() { :; }; rv4a_factory_pid")" 300

# ---------------------------------------------------------------- patching
echo; echo "[3] patching keeps every byte and adds two lines"
for kind in lf crlf mixed nofinal; do
  f="$T/p-$kind.xml"; o="$T/p-$kind.out"
  case $kind in lf) mkcfg "$f" ;; crlf) mkcfg "$f" crlf ;; mixed) mkcfg "$f" mixed ;; nofinal) mkcfg "$f" "" nofinal ;; esac
  run "rv4a_patch '$f' '$o' librv4a_aidl_v3.so"; ok "$kind: patch succeeds" $?
  run "rv4a_validate '$f' '$o' librv4a_aidl_v3.so" >/dev/null; ok "$kind: patched file passes validation" $?
  expect "$kind: exactly two lines added" "$(( $(wc -l < "$o") - $(awk 'END { print NR }' "$f") ))" 2
  run "rv4a_strip '$o'" > "$o.s"; run "rv4a_strip '$f'" > "$f.s"
  cmp -s "$o.s" "$f.s"; ok "$kind: removing our lines gives the original back byte for byte" $?
  if [ $kind = crlf ]; then
    lib_line="$(grep 'name="rv4a_aidl"' "$o")"
    case "$lib_line" in *"$CR") r=0 ;; *) r=1 ;; esac; ok "crlf: our lines end in CRLF like the rest" $r
    expect "crlf: every line still has its CR" "$(grep -c "$CR\$" "$o")" "$(wc -l < "$o")"
  fi
  ln_eff="$(grep -n 'library="rv4a_aidl"' "$o" | cut -d: -f1)"
  ln_open="$(grep -n '^    <effects>' "$o" | cut -d: -f1)"
  [ "$ln_eff" -gt "$ln_open" ]; ok "$kind: the </effects> inside a comment is skipped" $?
done
f="$T/p-lf.xml"; o="$T/p-lf.out"
run "rv4a_patch '$o' '$T/again.out' librv4a_aidl_v3.so"
cmp -s "$o" "$T/again.out"; ok "patching an already patched file gives the same file (update install)" $?
expect "signature ignores our lines" "$(run "rv4a_config_sig '$o'")" "$(run "rv4a_config_sig '$f'")"
printf '<audio_effects_conf><libraries><library name="a" path="a.so"/></libraries><effects></effects></audio_effects_conf>\n' > "$T/min.xml"
run "rv4a_patch '$T/min.xml' '$T/min.out' librv4a_aidl_v3.so"; expect "a one-line config is refused" $? 3
sed 's/0634f220/dead0000/' "$o" > "$T/tamper.out"
run "rv4a_validate '$f' '$T/tamper.out' librv4a_aidl_v3.so" >/dev/null; [ $? != 0 ]; ok "a change to a stock line is caught" $?
grep -v 'library="rv4a_aidl"' "$o" > "$T/half.out"
run "rv4a_validate '$f' '$T/half.out' librv4a_aidl_v3.so" >/dev/null; [ $? != 0 ]; ok "a missing effect line is caught" $?
run "rv4a_minimal_config librv4a_aidl_v3.so" > "$T/minimal.xml"
grep -q 'path="librv4a_aidl_v3.so"' "$T/minimal.xml" && grep -q "type=\"f98765f4" "$T/minimal.xml"; ok "a created config names our library and type" $?

# ---------------------------------------------------------------- installer
echo; echo "[4] the installer, end to end"
INSTUBS="$T/instubs.sh"
cat > "$INSTUBS" <<'EOF'
ui_print() { echo "$*"; }
abort() { echo "$*"; exit 1; }
set_perm() { echo "set_perm $*" >> "$MODPATH/.perms"; }
set_perm_recursive() { :; }
EOF
install_case() {  # install_case <name> <root> <props>; leaves $MP
  MP="$T/mod-$1"; rm -rf "$MP"; mkdir -p "$MP"
  cp -r "$MOD/." "$MP/"
  for v in 2 3 4; do mkdir -p "$MP/libs/arm64-v8a"; echo "lib v$v" > "$MP/libs/arm64-v8a/librv4a_aidl_v$v.so"; done
  mkdir -p "$T/tmp-$1"
  ( export RV4A_ROOT="$2" RV4A_PROPS="$3" RV4A_PROC="$2/proc" MODPATH="$MP" TMPDIR="$T/tmp-$1" ARCH=arm64 \
           RV4A_FACTORY_PID="${FPID:-100}" NO_FACTORY="${NO_FACTORY:-}" HIDL="${HIDL:-}" BINDER_V="${BINDER_V:-}" \
           KSU="${KSU:-}" KSU_VER="${KSU_VER:-}" KSU_LATE_LOAD="${KSU_LATE_LOAD:-}"
    $SH -c ". '$MOD/common/aidl_common.sh'; . '$STUB'; . '$INSTUBS'; . '$MP/customize.sh'" ) > "$T/install-$1.log" 2>&1
}
refused() {  # refused <label> <case> <root> <props> <message fragment>
  install_case "$2" "$3" "$4"; rc=$?
  grep -q "$5" "$T/install-$2.log"; m=$?
  [ $rc != 0 ] && [ $m = 0 ] && [ ! -d "$MP/system" ] && [ ! -d "$MP/configs" ]; ok "$1" $?
  [ $rc != 0 ] && [ $m = 0 ] || sed 's/^/        | /' "$T/install-$2.log"
}

# A Nothing Phone 3 like device: Qualcomm, effect HAL V2, a CRLF config in its
# sku folder and a plain one in /vendor/etc.
R="$T/d1"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 2
mkcfg "$R/vendor/etc/audio/sku_tuna/audio_effects_config.xml" crlf; mkcfg "$R/vendor/etc/audio_effects_config.xml"
mkfactory "$R" /vendor/bin/hw/audiohalservice.qti 2
props "$T/d1.props" nothing/metroid:15/AP3A/1:user/release-keys tuna
BINDER_V=2 install_case ok "$R" "$T/d1.props"; rc=$?
ok "Qualcomm V2 phone installs" $rc
[ $rc = 0 ] || sed 's/^/        | /' "$T/install-ok.log"
for c in /vendor/etc/audio/sku_tuna/audio_effects_config.xml /vendor/etc/audio_effects_config.xml; do
  [ -f "$MP/configs$c" ]; ok "a patched copy of $c" $?
  run "rv4a_validate '$R$c' '$MP/configs$c' librv4a_aidl_v2.so" >/dev/null; ok "  validates against the stock file" $?
done
[ ! -d "$MP/system/vendor/etc" ]; ok "configs are not left for the manager to mount" $?
expect "only the V2 library is installed" "$(ls "$MP/system/vendor/lib64/soundfx")" librv4a_aidl_v2.so
[ ! -d "$MP/libs" ]; ok "the other builds are removed" $?
expect "install.prop: V2 from the factory's answer" "$(sed -n 's/^hal_version=//p;s/^version_source=//p' "$MP/install.prop" | tr '\n' ' ')" "2 binder "
expect "install.prop: two configs, the sku one primary" "$(sed -n 's/^configs=//p;s/^primary=//p' "$MP/install.prop" | tr '\n' ' ')" "/vendor/etc/audio/sku_tuna/audio_effects_config.xml 2 "
grep -q "vendor_file:s0" "$MP/.perms" && grep -q "vendor_configs_file:s0" "$MP/.perms"; ok "library and configs get vendor labels" $?
grep -q "with CRLF" "$T/install-ok.log"; ok "it says which copy uses CRLF" $?

R="$T/d1"
BINDER_V=2 FPID=none install_case manifest4 "$R" "$T/d1.props"
expect "no process found: binder answer still used" "$(sed -n 's/^hal_version=//p' "$MP/install.prop")" 2
mkvintf "$R/vendor/etc/vintf/manifest.xml" 4
BINDER_V=2 install_case disagree "$R" "$T/d1.props"; rc=$?
[ $rc = 0 ] && grep -q "going by what the factory runs" "$T/install-disagree.log" && [ "$(sed -n 's/^hal_version=//p' "$MP/install.prop")" = 2 ]
ok "manifest says V4, the factory runs V2: V2, with a note" $?
mkvintf "$R/vendor/etc/vintf/manifest.xml" 2
BINDER_V=3 refused "factory answers V3 but loads V2: refused" conflict "$R" "$T/d1.props" "disagrees with itself"
mkfactory "$R" /vendor/bin/hw/audiohalservice.qti "2 3"
refused "two interface libraries and no answer: refused" twolibs "$R" "$T/d1.props" "several interface versions"
mkfactory "$R" /vendor/bin/hw/audiohalservice.qti ""; mkvintf "$R/vendor/etc/vintf/manifest.xml" 3
install_case static "$R" "$T/d1.props"
expect "linked statically, no answer: the manifest's V3" "$(sed -n 's/^hal_version=//p;s/^version_source=//p' "$MP/install.prop" | tr '\n' ' ')" "3 manifest "
mkfactory "$R" /vendor/bin/hw/audiohalservice.qti 2; mkvintf "$R/vendor/etc/vintf/manifest.xml" 2

R="$T/d2"; mkvintf "$R/vendor/etc/vintf/manifest.xml" none; mkcfg "$R/vendor/etc/audio_effects_config.xml"; mkfactory "$R" /vendor/bin/hw/x ""
refused "Android 14 vendor (V1) is refused" v1 "$R" "$T/d1.props" "Android 14 vendors"
R="$T/d3"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 5; mkcfg "$R/vendor/etc/audio_effects_config.xml"; mkfactory "$R" /vendor/bin/hw/x ""
refused "an unknown newer version is refused" v5 "$R" "$T/d1.props" "newer than this build"
R="$T/d4"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 3; mkcfg "$R/vendor/etc/audio_effects_config.xml"; mkfactory "$R" /vendor/bin/hw/x ""
mkdir -p "$R/data/adb/modules/ainur_jamesdsp"
refused "stock JamesDSP installed is refused" jdsp "$R" "$T/d1.props" "ainur_jamesdsp"
rm -rf "$R/data/adb/modules/ainur_jamesdsp"; mkdir -p "$R/data/adb/modules/rv4a_engine"; touch "$R/data/adb/modules/rv4a_engine/remove"
install_case removed "$R" "$T/d1.props"; ok "a conflicting module already marked for removal does not block" $?
mkdir -p "$R/data/adb/modules/aml"
refused "AML installed is refused (not supported in this test build)" aml "$R" "$T/d1.props" "Audio Modification Library"
rm -rf "$R/data/adb/modules"
mkdir -p "$R/data/adb/modules/dolby/system/vendor/etc"; cp "$R/vendor/etc/audio_effects_config.xml" "$R/data/adb/modules/dolby/system/vendor/etc/"
refused "another module shipping the same config is refused, by name" other "$R" "$T/d1.props" "Another module also replaces /vendor/etc/audio_effects_config.xml: dolby"
rm -rf "$R/data/adb/modules"
echo "40 30 0:1 /dsp/audio_effects_config.xml /vendor/etc/audio_effects_config.xml rw - ext4 /dev/x rw" > "$R/proc/1/mountinfo"
refused "a config someone else mounted over is refused" mounted "$R" "$T/d1.props" "already replaced by a mount"
: > "$R/proc/1/mountinfo"
R="$T/d5"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 4; mkdir -p "$R/vendor/etc"; mkfactory "$R" /vendor/bin/hw/x ""
sed 's|</libraries>|    <library name="jdsp" path="libjamesdsp.so"/>\n    </libraries>|; s|</effects>|    <effect name="jamesdsp" library="jdsp" uuid="f27317f4-c984-4de6-9a90-545759495bf2"/>\n    </effects>|' "$T/p-lf.xml" > "$R/vendor/etc/audio_effects_config.xml"
refused "a config that already lists our uuid is refused" uuid "$R" "$T/d1.props" "already lists this effect"
R="$T/d7"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 4; mkdir -p "$R/vendor/etc"; cp "$T/min.xml" "$R/vendor/etc/audio_effects_config.xml"; mkfactory "$R" /vendor/bin/hw/x ""
refused "an unusual layout is refused" minified "$R" "$T/d1.props" "laid out"
R="$T/d8"; mkcfg "$R/vendor/etc/audio_effects_config.xml"; mkfactory "$R" /vendor/bin/hw/x ""
refused "no version from anywhere is refused" noversion "$R" "$T/d1.props" "Could not tell"
NO_FACTORY=1 HIDL=1 refused "an HIDL phone is sent to the normal module" hidl "$T/d1" "$T/d1.props" "older (HIDL)"
NO_FACTORY=1 refused "no effect HAL at all is refused" nohal "$T/d1" "$T/d1.props" "No audio effect HAL"
KSU=true KSU_LATE_LOAD=1 refused "late-load root is refused" late "$T/d1" "$T/d1.props" "loaded after the phone has booted"
KSU=true KSU_VER=v3.0.1 BINDER_V=2 refused "KernelSU 3 without a metamodule is refused" ksu3 "$T/d1" "$T/d1.props" "metamodule"
mkdir -p "$T/d1/data/adb/metamodule"
KSU=true KSU_VER=v3.0.1 BINDER_V=2 install_case ksu3meta "$T/d1" "$T/d1.props"; ok "KernelSU 3 with a metamodule installs" $?
rm -rf "$T/d1/data/adb/metamodule"
KSU=true KSU_VER=v1.0.5 BINDER_V=2 install_case ksu1 "$T/d1" "$T/d1.props"; rc=$?
[ $rc = 0 ] && grep -q "no KernelSU metamodule found" "$T/install-ksu1.log"; ok "older KernelSU without one installs, with a note" $?

# Samsung Exynos on AIDL: the factory in a vendor APEX names a _sec config.
R="$T/d9"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 2
mkfactory "$R" /apex/com.android.hardware.audio.exynos/bin/hw/android.hardware.audio.service-aidl.exynos 2 "audio_effects_config_sec.xml"
mkcfg "$R/vendor/etc/audio_effects_config.xml"; mkcfg "$R/vendor/etc/audio_effects_config_sec.xml" mixed
install_case samsung "$R" "$T/d1.props"; rc=$?
[ $rc = 0 ] && [ -f "$MP/configs/vendor/etc/audio_effects_config.xml" ] && [ -f "$MP/configs/vendor/etc/audio_effects_config_sec.xml" ]
ok "Samsung: the plain and the _sec config are both patched" $?
run "rv4a_validate '$R/vendor/etc/audio_effects_config_sec.xml' '$MP/configs/vendor/etc/audio_effects_config_sec.xml' librv4a_aidl_v2.so" >/dev/null
ok "  the mixed line ending _sec copy validates" $?

# Pixel: no config anywhere, the factory has the path built in.
R="$T/d6"; mkvintf "$R/apex/com.google.pixel.audio.hal/etc/vintf/audio.xml" 3
mkfactory "$R" /vendor/bin/hw/android.hardware.audio.service-aidl.aoc 3 "/vendor/etc/audio_effects_config.xml"
mkdir -p "$R/vendor/etc"
refused "Pixel without the opt-in is refused, with how to opt in" pixel "$R" "$T/d1.props" "rv4a-aidl-pixel"
mkdir -p "$R/data/local/tmp"; touch "$R/data/local/tmp/rv4a-aidl-pixel"
install_case pixelok "$R" "$T/d1.props"; rc=$?
[ $rc = 0 ] && [ -f "$MP/system/vendor/etc/audio_effects_config.xml" ] && [ ! -d "$MP/configs" ] &&
  grep -q '^create=1' "$MP/install.prop" && grep -q 'path="librv4a_aidl_v3.so"' "$MP/system/vendor/etc/audio_effects_config.xml"
ok "Pixel with the opt-in: a V3 config is created for the manager to mount" $?

# ---------------------------------------------------------------- boot checks
echo; echo "[5] boot watchdog, drift checks and mounting"
R="$T/d1"; props "$T/b.props" nothing/metroid:15/AP3A/1:user/release-keys tuna
fresh() { mkfactory "$R" /vendor/bin/hw/audiohalservice.qti 2; BINDER_V=2 install_case boot "$R" "$T/b.props" >/dev/null; B="$MP"; CF="$B/configs"; }
boot() { ( export RV4A_ROOT="$R" RV4A_PROPS="$T/b.props" RV4A_PROC="$R/proc" KSU="${KSU:-}" MOUNTS="$T/mounts"
           $SH -c ". '$MOD/common/aidl_common.sh'; . '$STUB'; rv4a_boot_check '$B'; rv4a_mount_stage '$B' post-fs-data" ); }
postmount() { ( export RV4A_ROOT="$R" RV4A_PROPS="$T/b.props" RV4A_PROC="$R/proc" KSU="${KSU:-}" MOUNTS="$T/mounts"
           $SH -c ". '$MOD/common/aidl_common.sh'; . '$STUB'; rv4a_mount_stage '$B' post-mount" ); }
rm -f "$T/mounts"; fresh; boot
grep -q "^ok: V2" "$B/boot.log"; ok "clean boot passes every check" $?
[ -f "$B/.boot_incomplete" ]; ok "boot marker set for service.sh to clear" $?
expect "Magisk: both copies mounted in post-fs-data" "$(grep -c "mount -o bind $B/configs" "$T/mounts")" 2
rm -f "$T/mounts"; KSU=true boot
[ ! -f "$T/mounts" ]; ok "KernelSU: nothing mounted in post-fs-data" $?
KSU=true postmount
[ ! -f "$T/mounts" ] && grep -q "did not mount the library" "$B/status"; ok "KernelSU: library not visible after the manager's mount: nothing mounted, status says why" $?
mkdir -p "$R/vendor/lib64/soundfx"; cp "$B/system/vendor/lib64/soundfx/librv4a_aidl_v2.so" "$R/vendor/lib64/soundfx/"
KSU=true postmount
expect "KernelSU: library visible: both copies mounted in post-mount" "$(grep -c "mount -o bind" "$T/mounts")" 2
rm -f "$T/mounts"; cp "$R/vendor/etc/audio_effects_config.xml" "$T/keep.xml"
sed -i 's|bundle"|bundle2"|' "$R/vendor/etc/audio_effects_config.xml"; KSU=true postmount
[ ! -f "$T/mounts" ] && grep -q "replaced by something else" "$B/status"; ok "a config another module replaced at boot is not covered" $?
cp "$T/keep.xml" "$R/vendor/etc/audio_effects_config.xml"; rm -rf "$R/vendor/lib64"
rm -f "$B/.boot_incomplete"; fresh; boot >/dev/null
boot; grep -q "strike 1" "$B/boot.log" && [ -d "$CF" ]; ok "one unfinished boot is a strike, nothing more" $?
boot; [ -f "$B/disable" ] && [ -d "$B/configs.disabled" ] && [ ! -d "$CF" ]; ok "two unfinished boots: module disabled, configs set aside" $?
grep -q "^description=.*switched off after 2 boots" "$B/module.prop"; ok "the manager shows why" $?
rm -f "$B/disable"; boot; [ -d "$CF" ] && grep -q "restored" "$B/boot.log"; ok "re-enabled: configs put back on the next boot" $?
[ -f "$B/boot.log.1" ]; ok "previous boot's log kept as boot.log.1" $?
fresh; props "$T/b.props" nothing/metroid:16/BP1A/2:user/release-keys tuna; boot
[ -d "$B/configs.rejected" ] && [ ! -d "$CF" ] && grep -q "vendor build changed" "$B/boot.log"; ok "system update: configs set aside for good" $?
boot; [ ! -d "$CF" ]; ok "set-aside configs are never restored automatically" $?
props "$T/b.props" nothing/metroid:15/AP3A/1:user/release-keys tuna
fresh; mkvintf "$R/vendor/etc/vintf/manifest.xml" 3; boot
[ -d "$B/configs.rejected" ] && grep -q "V2 -> V3" "$B/boot.log"; ok "declared effect HAL version changed: configs set aside" $?
mkvintf "$R/vendor/etc/vintf/manifest.xml" 2
fresh; cp "$R/vendor/etc/audio/sku_tuna/audio_effects_config.xml" "$T/keep.xml"
sed -i "s|bassboost\"|bassboost2\"|" "$R/vendor/etc/audio/sku_tuna/audio_effects_config.xml"; boot
[ -d "$B/configs.rejected" ] && grep -q "differs" "$B/boot.log"; ok "a stock config changed: configs set aside" $?
cp "$T/keep.xml" "$R/vendor/etc/audio/sku_tuna/audio_effects_config.xml"
fresh; printf '<!-- extra -->\n' >> "$CF/vendor/etc/audio_effects_config.xml"; boot
[ -d "$B/configs.rejected" ] && grep -q "failed validation" "$B/boot.log"; ok "a damaged config copy: configs set aside" $?
fresh; rm -f "$B/system/vendor/lib64/soundfx/librv4a_aidl_v2.so"; boot
[ -d "$B/configs.rejected" ]; ok "a missing library: configs set aside" $?
rm -f "$T/mounts"; fresh; boot; grep -q "^ok" "$B/boot.log" && [ -f "$T/mounts" ]; ok "and a fresh install after all that boots and mounts again" $?
PX="$T/d6"; PB="$T/mod-pixelok"
pxboot() { ( export RV4A_ROOT="$PX" RV4A_PROPS="$T/d1.props" RV4A_PROC="$PX/proc"
             $SH -c ". '$MOD/common/aidl_common.sh'; . '$STUB'; rv4a_boot_check '$PB'" ); }
pxboot; grep -q "^ok: V3" "$PB/boot.log"; ok "Pixel: the created config passes the boot check" $?
cp "$PB/system/vendor/etc/audio_effects_config.xml" "$PX/vendor/etc/"; rm -f "$PB/.boot_incomplete"; pxboot
grep -q "^ok: V3" "$PB/boot.log"; ok "Pixel: already mounted by the manager (it carries our uuid): still fine" $?
mkcfg "$PX/vendor/etc/audio_effects_config.xml"; rm -f "$PB/.boot_incomplete"; pxboot
[ -f "$PB/created_config.rejected" ] && grep -q "now has its own" "$PB/status"; ok "Pixel: the phone gained its own config: ours set aside" $?
rm -f "$PX/vendor/etc/audio_effects_config.xml"

echo; echo "[6] the watch from late_start on (service.sh)"
svc_case() {  # svc_case <factory pids> <audioserver pids>: service check on a fresh install
  fresh; boot >/dev/null
  PR="$R/proc"
  for h in $1; do
    mkdir -p "$PR/$h"; ln -sfn /vendor/bin/hw/audiohalservice.qti "$PR/$h/exe"
    printf '7f00 r-xp 0 0:0 1 /vendor/lib64/android.hardware.audio.effect-V%s-ndk.so\n7f01 r-xp 0 0:0 1 /vendor/lib64/soundfx/%s\n' \
      "${NDKV:-2}" "${LOADED:-librv4a_aidl_v2.so}" > "$PR/$h/maps"
  done
  : > "$PR/1/mountinfo"
  for c in /vendor/etc/audio/sku_tuna/audio_effects_config.xml /vendor/etc/audio_effects_config.xml; do
    printf '1 2 0:1 /adb/modules/rv4a_engine_aidl/configs%s %s rw - ext4 /dev/x rw\n' "$c" "$c" >> "$PR/1/mountinfo"
    cp "$R$c" "$T/stock.$(basename "$(dirname "$c")")"
    [ -n "${HIDDEN:-}" ] || cp "$CF$c" "$R$c"   # what the factory sees through our mount
  done
  printf '%s\n' $1 > "$T/seq_host"; printf '%s\n' $2 > "$T/seq_as"
  rm -f "$T/notify" "$T/umount"
  grep -v boot_completed "$T/b.props" > "$T/s.props"
  [ -n "${NOBOOT:-}" ] || printf 'sys.boot_completed=1\n' >> "$T/s.props"
  ( export RV4A_ROOT="$R" RV4A_PROPS="$T/s.props" RV4A_PROC="$PR" RV4A_MONITOR_LIMIT=40 \
           SEQ_HOST="$T/seq_host" SEQ_AS="$T/seq_as" NOTIFY="$T/notify" UMOUNT="$T/umount" \
           NO_FACTORY="${SVC_NO_FACTORY:-}" BINDER_V="${BINDER_V:-}"
    $SH -c ". '$MOD/common/aidl_common.sh'; . '$STUB'; rv4a_service_check '$B'" ) >/dev/null 2>&1
  for c in /vendor/etc/audio/sku_tuna/audio_effects_config.xml /vendor/etc/audio_effects_config.xml; do
    cp "$T/stock.$(basename "$(dirname "$c")")" "$R$c"
  done
}
BINDER_V=2 svc_case "100" "200"
[ ! -f "$B/.boot_incomplete" ] && [ -d "$CF" ] && grep -q "running" "$B/status"; ok "healthy: marker cleared, status says running" $?
grep -q "loaded: yes" "$B/boot.log" && grep -q "binder V2" "$B/boot.log"; ok "it sees our library and the factory's version" $?
[ -s "$B/logcat.boot.txt" ]; ok "a filtered logcat is kept after boot" $?
LOADED=libother.so svc_case "100" "200"
grep -q "did not load" "$B/status" && [ -d "$CF" ]; ok "library not loaded: reported, nothing switched off" $?
HIDDEN=1 LOADED=libother.so svc_case "100" "200"
grep -q "none of our config copies is visible" "$B/status"; ok "our copies hidden: reported as such" $?
NOBOOT=1 svc_case "100 101 102 103" "200"
[ -d "$B/configs.rejected" ] && [ -f "$B/.boot_incomplete" ] && grep -q "kept restarting" "$B/status"
ok "three restarts before boot completes: switched off, marker kept" $?
grep -q "notification" "$T/notify"; ok "a notification is posted" $?
expect "both of our mounts are taken away so the restarts stop now" "$(grep -c "umount -l" "$T/umount")" 2
[ -s "$B/logcat.restart1.txt" ] && [ -s "$B/logcat.giveup.txt" ]; ok "logcat kept after each restart and on giving up" $?
svc_case "100" "200 201 202 203"
[ -d "$B/configs.rejected" ]; ok "audioserver restarting three times: switched off" $?
svc_case "100 101 101 101 101 101 101 101 101 101 101 101 101 101 101 101" "200"
[ -d "$CF" ] && grep -q "running" "$B/status" && [ ! -f "$B/.boot_incomplete" ]; ok "a single restart is not a crash loop" $?
NDKV=3 svc_case "100" "200"
[ -d "$B/configs.rejected" ] && grep -q "version does not match" "$B/status"; ok "factory has another interface version loaded: switched off" $?
BINDER_V=4 svc_case "100" "200"
[ -d "$B/configs.rejected" ] && grep -q "V4 over binder" "$B/boot.log"; ok "factory answers another version: switched off" $?
SVC_NO_FACTORY=1 svc_case "100" "200"
[ -d "$B/configs.rejected" ] && grep -q "did not start" "$B/status"; ok "factory never registered: switched off" $?
NOBOOT=1 svc_case "100" "200"
[ -d "$CF" ] && [ -f "$B/.boot_incomplete" ]; ok "boot not complete yet: nothing judged, marker kept" $?

echo; echo "[7] action.sh switches it off by hand"
fresh; boot >/dev/null
printf '1 2 0:1 /adb/modules/rv4a_engine_aidl/configs/vendor/etc/audio_effects_config.xml /vendor/etc/audio_effects_config.xml rw - ext4 /dev/x rw\n' > "$R/proc/1/mountinfo"
rm -f "$T/umount"
echo ". '$STUB'" >> "$B/common/aidl_common.sh"   # the installed copy, run as the manager runs it
( export RV4A_ROOT="$R" RV4A_PROPS="$T/b.props" RV4A_PROC="$R/proc" UMOUNT="$T/umount"
  $SH "$B/action.sh" ) > "$T/action.log" 2>&1
[ -d "$B/configs.rejected" ] && grep -q "Reboot now" "$T/action.log" && grep -q "umount -l" "$T/umount"; ok "configs set aside and unmounted, reboot asked for" $?

echo; echo "[8] scripts"
for s in "$MOD"/*.sh "$MOD"/common/*.sh; do
  $SH -n "$s"; ok "$(basename "$s") parses" $?
  grep -q "$CR" "$s"; [ $? != 0 ]; ok "$(basename "$s") has unix line endings" $?
done

echo; echo "$pass passed, $fail failed"
[ $fail = 0 ]
