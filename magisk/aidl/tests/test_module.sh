#!/usr/bin/env bash
# Host-side tests for the AIDL module's installer and boot scripts.
#
#   bash magisk/aidl/tests/test_module.sh              # with the host's sh
#   SH="busybox sh" bash magisk/aidl/tests/test_module.sh   # as Magisk/KernelSU run it
#
# Builds fake device trees (VINTF manifests, effect configs, module folders)
# and runs the real scripts against them through $RV4A_ROOT, with the few
# device commands (getprop, service, ui_print, set_perm...) stubbed.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
MOD="$HERE/../module"
SH="${SH:-sh}"
T="$(mktemp -d)"
trap '[ -n "${KEEP:-}" ] || rm -rf "$T"' EXIT
pass=0; fail=0
ok() { if [ "$2" = 0 ]; then pass=$((pass + 1)); printf '  ok    %s\n' "$1"; else fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; fi; }
expect() { [ "$2" = "$3" ]; ok "$1 (got '$2')" $?; }
run() { $SH -c ". '$MOD/common/aidl_common.sh'; $1"; }   # one function call in the shell under test
CR="$(printf '\r')"

# ---------------------------------------------------------------- fixtures
mkcfg() {  # mkcfg <file> [crlf] [nofinalnewline]
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
  if [ "${2:-}" = crlf ]; then printf '%s' "$body" | sed "s/\$/$CR/" > "$1"; else printf '%s\n' "$body" > "$1"; fi
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

echo "== shell under test: $SH =="

# ---------------------------------------------------------------- VINTF
echo; echo "[1] effect HAL version from the VINTF manifests"
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

# ---------------------------------------------------------------- config choice
echo; echo "[2] which config the factory reads"
R="$T/c1"; mkcfg "$R/vendor/etc/audio_effects_config.xml"; mkcfg "$R/vendor/etc/audio/sku_kalama/audio_effects_config.xml"
mkcfg "$R/system/etc/audio_effects_config.xml"
printf 'ro.boot.product.vendor.sku=kalama\n' > "$T/c1.props"
expect "Qualcomm sku folder wins over /vendor/etc" "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1.props run 'rv4a_find_config ""')" /vendor/etc/audio/sku_kalama/audio_effects_config.xml
printf 'ro.boot.product.vendor.sku=\n' > "$T/c1b.props"
expect "without a sku, /vendor/etc" "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1b.props run 'rv4a_find_config ""')" /vendor/etc/audio_effects_config.xml
mkcfg "$R/odm/etc/audio_effects_config.xml"
expect "/odm/etc wins over everything" "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1.props run 'rv4a_find_config ""')" /odm/etc/audio_effects_config.xml
R="$T/c2"; mkcfg "$R/apex/com.google.pixel.audio.hal/etc/audio_effects_config.xml"
expect "Pixel: the factory's APEX copy is reported, not patched" "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1b.props run 'rv4a_find_config com.google.pixel.audio.hal')" apex:/apex/com.google.pixel.audio.hal/etc/audio_effects_config.xml
R="$T/c3"; mkdir -p "$R"
expect "no config anywhere" "$(RV4A_ROOT=$R RV4A_PROPS=$T/c1b.props run 'rv4a_find_config ""')" ""
expect "overlay path for /vendor" "$(run 'rv4a_overlay_path /vendor/etc/audio/sku_kalama/audio_effects_config.xml')" system/vendor/etc/audio/sku_kalama/audio_effects_config.xml
expect "overlay path for /system" "$(run 'rv4a_overlay_path /system/etc/audio_effects_config.xml')" system/etc/audio_effects_config.xml
R="$T/c4"; mkdir -p "$R/odm/etc"
expect "a real /odm partition cannot be overlaid" "$(RV4A_ROOT=$R run 'rv4a_overlay_path /odm/etc/audio_effects_config.xml')" ""

# ---------------------------------------------------------------- patching
echo; echo "[3] patching keeps every byte and adds two lines"
for kind in lf crlf nofinal; do
  f="$T/p-$kind.xml"; o="$T/p-$kind.out"
  case $kind in lf) mkcfg "$f" ;; crlf) mkcfg "$f" crlf ;; nofinal) mkcfg "$f" "" nofinal ;; esac
  run "rv4a_patch '$f' '$o' librv4a_aidl_v3.so"; ok "$kind: patch succeeds" $?
  run "rv4a_validate '$f' '$o' librv4a_aidl_v3.so" >/dev/null; ok "$kind: patched file passes validation" $?
  expect "$kind: exactly two lines added" "$(( $(wc -l < "$o") - $(awk 'END { print NR }' "$f") ))" 2
  run "rv4a_strip '$o'" > "$o.s"; run "rv4a_strip '$f'" > "$f.s"
  cmp -s "$o.s" "$f.s"; ok "$kind: removing our lines gives the original back byte for byte" $?
  lib_line="$(grep 'name="rv4a_aidl"' "$o")"
  if [ $kind = crlf ]; then
    case "$lib_line" in *"$CR") r=0 ;; *) r=1 ;; esac; ok "crlf: our lines end in CRLF like the rest" $r
    expect "crlf: every line still has its CR" "$(grep -c "$CR\$" "$o")" "$(wc -l < "$o")"
  fi
  # the commented </effects> must not have been used
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

# ---------------------------------------------------------------- installer
echo; echo "[4] the installer, end to end"
STUBS="$T/stubs.sh"
cat > "$STUBS" <<'EOF'
ui_print() { echo "$*"; }
abort() { echo "$*"; exit 1; }
set_perm() { echo "set_perm $*" >> "$MODPATH/.perms"; }
set_perm_recursive() { :; }
getprop() { rv4a_getprop "$1"; }
service() { [ -n "$NO_FACTORY" ] || echo "12	android.hardware.audio.effect.IFactory/default: [android.hardware.audio.effect.IFactory]"; }
dumpsys() { :; }
EOF
install_case() {  # install_case <name> <root> <props>; leaves $MP
  MP="$T/mod-$1"; rm -rf "$MP"; mkdir -p "$MP"
  cp -r "$MOD/." "$MP/"
  for v in 2 3 4; do mkdir -p "$MP/libs/arm64-v8a"; echo "lib v$v" > "$MP/libs/arm64-v8a/librv4a_aidl_v$v.so"; done
  mkdir -p "$T/tmp-$1"
  ( export RV4A_ROOT="$2" RV4A_PROPS="$3" MODPATH="$MP" TMPDIR="$T/tmp-$1" ARCH=arm64 RV4A_FACTORY_PID=none NO_FACTORY="${NO_FACTORY:-}"
    $SH -c ". '$MOD/common/aidl_common.sh'; . '$STUBS'; . '$MP/customize.sh'" ) > "$T/install-$1.log" 2>&1
}
props() { printf 'ro.build.version.sdk=35\nro.vendor.build.fingerprint=%s\nro.boot.product.vendor.sku=%s\n' "$1" "${2:-}" > "$3"; }

R="$T/d1"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 2; mkcfg "$R/vendor/etc/audio/sku_pineapple/audio_effects_config.xml" crlf
props nothing/phone:15/AP3A/1:user/release-keys pineapple "$T/d1.props"
install_case ok "$R" "$T/d1.props"; rc=$?
ok "Qualcomm V2 phone with a CRLF sku config installs" $rc
O="$MP/system/vendor/etc/audio/sku_pineapple/audio_effects_config.xml"
[ -f "$O" ]; ok "config copy placed under system/vendor/etc/audio/sku_pineapple" $?
expect "only the V2 library is installed" "$(ls "$MP/system/vendor/lib64/soundfx")" librv4a_aidl_v2.so
[ ! -d "$MP/libs" ]; ok "the other builds are removed" $?
expect "install.prop records V2" "$(sed -n 's/^hal_version=//p' "$MP/install.prop")" 2
grep -q "vendor_file:s0" "$MP/.perms" && grep -q "vendor_configs_file:s0" "$MP/.perms"; ok "library and config get vendor labels" $?
run "rv4a_validate '$R/vendor/etc/audio/sku_pineapple/audio_effects_config.xml' '$O' librv4a_aidl_v2.so" >/dev/null; ok "installed copy validates against the stock file" $?

refused() {  # refused <label> <case> <root> <props> <message fragment>
  install_case "$2" "$3" "$4"; rc=$?
  grep -q "$5" "$T/install-$2.log"; m=$?
  [ $rc != 0 ] && [ $m = 0 ] && [ ! -d "$MP/system" ]; ok "$1" $?
}
R="$T/d2"; mkvintf "$R/vendor/etc/vintf/manifest.xml" none; mkcfg "$R/vendor/etc/audio_effects_config.xml"
refused "Android 14 vendor (V1) is refused" v1 "$R" "$T/d1.props" "not supported yet"
R="$T/d3"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 5; mkcfg "$R/vendor/etc/audio_effects_config.xml"
refused "an unknown newer version is refused" v5 "$R" "$T/d1.props" "newer than this build"
R="$T/d4"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 3; mkcfg "$R/vendor/etc/audio_effects_config.xml"
mkdir -p "$R/data/adb/modules/ainur_jamesdsp"
refused "stock JamesDSP installed is refused" jdsp "$R" "$T/d1.props" "ainur_jamesdsp"
rm -rf "$R/data/adb/modules/ainur_jamesdsp"; mkdir -p "$R/data/adb/modules/rv4a_engine"; touch "$R/data/adb/modules/rv4a_engine/remove"
install_case removed "$R" "$T/d1.props"; ok "a conflicting module already marked for removal does not block" $?
mkdir -p "$R/data/adb/modules/aml"
refused "AML installed is refused (not supported in this test build)" aml "$R" "$T/d1.props" "Audio Modification Library"
rm -rf "$R/data/adb/modules"
R="$T/d5"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 4; mkdir -p "$R/vendor/etc"
sed 's|</libraries>|    <library name="jdsp" path="libjamesdsp.so"/>\n    </libraries>|; s|</effects>|    <effect name="jamesdsp" library="jdsp" uuid="f27317f4-c984-4de6-9a90-545759495bf2"/>\n    </effects>|' "$T/p-lf.xml" > "$R/vendor/etc/audio_effects_config.xml"
refused "a config that already lists our uuid is refused" uuid "$R" "$T/d1.props" "already lists this effect"
R="$T/d6"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 4
refused "no config outside an APEX (Pixel) is refused" pixel "$R" "$T/d1.props" "Pixel"
R="$T/d7"; mkvintf "$R/vendor/etc/vintf/manifest.xml" 4; mkdir -p "$R/vendor/etc"; cp "$T/min.xml" "$R/vendor/etc/audio_effects_config.xml"
refused "an unusual layout is refused" minified "$R" "$T/d1.props" "laid out"
R="$T/d8"; mkcfg "$R/vendor/etc/audio_effects_config.xml"
refused "no effect HAL version declared is refused" noversion "$R" "$T/d1.props" "Could not tell"
NO_FACTORY=1; refused "no AIDL factory running is refused" nofactory "$T/d1" "$T/d1.props" "No AIDL audio effect factory"; NO_FACTORY=

# ---------------------------------------------------------------- boot checks
echo; echo "[5] boot watchdog and drift checks (post-fs-data)"
R="$T/d1"; props nothing/phone:15/AP3A/1:user/release-keys pineapple "$T/b.props"
fresh() { install_case boot "$R" "$T/b.props" >/dev/null; B="$MP"; OV="$B/system/vendor/etc/audio/sku_pineapple/audio_effects_config.xml"; }
boot() { ( export RV4A_ROOT="$R" RV4A_PROPS="$T/b.props"; $SH -c ". '$MOD/common/aidl_common.sh'; rv4a_boot_check '$B'" ); }
fresh; boot
grep -q "^ok: V2" "$B/boot.log"; ok "clean boot passes every check" $?
[ -f "$B/.boot_incomplete" ]; ok "boot marker set for service.sh to clear" $?
boot; grep -q "strike 1" "$B/boot.log" && [ -f "$OV" ]; ok "one unfinished boot is a strike, nothing more" $?
boot; [ -f "$B/disable" ] && [ -f "$OV.disabled" ] && [ ! -f "$OV" ]; ok "two unfinished boots: module disabled, config set aside" $?
grep -q "^description=.*switched off after 2 boots" "$B/module.prop"; ok "the manager shows why" $?
rm -f "$B/disable"; boot; [ -f "$OV" ] && grep -q "restored" "$B/boot.log"; ok "re-enabled: config put back on the next boot" $?
[ -f "$B/boot.log.1" ]; ok "previous boot's log kept as boot.log.1" $?
fresh; props nothing/phone:16/BP1A/2:user/release-keys pineapple "$T/b.props"; boot
[ -f "$OV.rejected" ] && [ ! -f "$OV" ] && grep -q "vendor build changed" "$B/boot.log"; ok "system update: config set aside for good" $?
boot; [ ! -f "$OV" ]; ok "a set-aside config is never restored automatically" $?
props nothing/phone:15/AP3A/1:user/release-keys pineapple "$T/b.props"
fresh; mkvintf "$R/vendor/etc/vintf/manifest.xml" 3; boot
[ -f "$OV.rejected" ] && grep -q "V2 -> V3" "$B/boot.log"; ok "effect HAL version changed: config set aside" $?
mkvintf "$R/vendor/etc/vintf/manifest.xml" 2
fresh; cp "$R/vendor/etc/audio/sku_pineapple/audio_effects_config.xml" "$T/keep.xml"
sed -i "s|bassboost\"|bassboost2\"|" "$R/vendor/etc/audio/sku_pineapple/audio_effects_config.xml"; boot
[ -f "$OV.rejected" ] && grep -q "differs" "$B/boot.log"; ok "stock config changed: config set aside" $?
cp "$T/keep.xml" "$R/vendor/etc/audio/sku_pineapple/audio_effects_config.xml"
fresh; printf '<!-- extra -->\n' >> "$OV"; boot
[ -f "$OV.rejected" ] && grep -q "failed validation" "$B/boot.log"; ok "a damaged config copy is set aside" $?
fresh; rm -f "$B/system/vendor/lib64/soundfx/librv4a_aidl_v2.so"; boot
[ -f "$OV.rejected" ]; ok "a missing library: config set aside" $?
fresh; boot; grep -q "^ok" "$B/boot.log"; ok "and a fresh install after all that is fine again" $?

echo; echo "[6] after boot: health checks and crash loop watch (service.sh)"
svc_case() {  # svc_case <factory pids> <audioserver pids>: service check on a fresh install
  fresh; boot >/dev/null
  PR="$T/proc"
  case "$PR" in /*/proc) ;; *) echo "refusing odd fake proc path '$PR'"; exit 1 ;; esac
  rm -rf "$PR"; mkdir -p "$PR/self"
  for h in $1; do
    mkdir -p "$PR/$h"; ln -sf /vendor/bin/hw/android.hardware.audio.service "$PR/$h/exe"
    printf '7f00 r-xp /vendor/lib64/android.hardware.audio.effect-V%s-ndk.so\n7f01 r-xp /vendor/lib64/soundfx/%s\n' \
      "${NDKV:-2}" "${LOADED:-librv4a_aidl_v2.so}" > "$PR/$h/maps"
  done
  m=/vendor/etc/audio/sku_pineapple/audio_effects_config.xml
  printf '1 2 0:1 /adb/modules/rv4a_engine_aidl/system%s %s rw - ext4 /dev/x rw\n' "$m" "$m" > "$PR/self/mountinfo"
  printf '%s\n' $1 > "$T/seq_host"; printf '%s\n' $2 > "$T/seq_as"
  rm -f "$T/notify" "$T/umount"
  printf 'sys.boot_completed=1\n' >> "$T/b.props"
  ( export RV4A_ROOT="$R" RV4A_PROPS="$T/b.props" RV4A_PROC="$PR" RV4A_MONITOR_LIMIT=12 \
           SEQ_HOST="$T/seq_host" SEQ_AS="$T/seq_as" NOTIFY="$T/notify" UMOUNT="$T/umount" \
           NO_FACTORY="${SVC_NO_FACTORY:-}"
    $SH -c ". '$MOD/common/aidl_common.sh'; . '$HERE/service_stubs.sh'; rv4a_service_check '$B'" ) >/dev/null 2>&1
}
props nothing/phone:15/AP3A/1:user/release-keys pineapple "$T/b.props"
svc_case "100" "200"
[ ! -f "$B/.boot_incomplete" ] && [ -f "$OV" ] && grep -q "running" "$B/status"; ok "healthy: marker cleared, status says running" $?
grep -q "loaded: yes" "$B/boot.log"; ok "it sees our library in the factory's memory" $?
LOADED=libother.so; svc_case "100" "200"; LOADED=
grep -q "did not load" "$B/status" && [ -f "$OV" ]; ok "library not loaded: reported, nothing switched off" $?
svc_case "100 101" "200"
[ -f "$OV.rejected" ] && [ -f "$B/.boot_incomplete" ] && grep -q "restarted right after boot" "$B/status"; ok "factory restarted within 15s with us loaded: switched off, marker kept" $?
grep -q "notification" "$T/notify"; ok "the tester gets a notification" $?
grep -q "umount -l /vendor/etc/audio/sku_pineapple/audio_effects_config.xml" "$T/umount"; ok "our mount is taken away so the restarts stop now" $?
svc_case "100" "200 201"
[ -f "$OV.rejected" ]; ok "audioserver restarted within 15s: switched off" $?
NDKV=3; svc_case "100" "200"; NDKV=
[ -f "$OV.rejected" ] && grep -q "version does not match" "$B/status"; ok "factory has another interface version loaded: switched off" $?
SVC_NO_FACTORY=1; svc_case "100" "200"; SVC_NO_FACTORY=
[ -f "$OV.rejected" ] && grep -q "did not start" "$B/status"; ok "factory never registered: switched off" $?
svc_case "100 100 100 100 101 102 103" "200"
[ -f "$OV.rejected" ] && grep -q "kept restarting" "$B/status"; ok "three restarts later on: crash loop caught" $?
svc_case "100 100 100 101 101 101 101 101 101 101 101 101 101 101" "200"
[ -f "$OV" ] && grep -q "running" "$B/status"; ok "a single later restart is not called a crash loop" $?

echo; echo "[7] scripts"
for s in "$MOD"/*.sh "$MOD"/common/*.sh; do
  $SH -n "$s"; ok "$(basename "$s") parses" $?
  grep -q "$CR" "$s"; [ $? != 0 ]; ok "$(basename "$s") has unix line endings" $?
done

echo; echo "$pass passed, $fail failed"
[ $fail = 0 ]
