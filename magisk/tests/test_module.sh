#!/usr/bin/env bash
# Host-side tests for the rooted engine module's installer and boot scripts.
#
#   bash magisk/tests/test_module.sh                     # with the host's sh
#   SH=dash bash magisk/tests/test_module.sh
#   BUSYBOX=/bin/busybox bash magisk/tests/test_module.sh
#
# BUSYBOX runs everything the way Magisk and KernelSU run module scripts:
# busybox's sh, with busybox's own awk, grep, sed and the rest first on PATH.
# Their behaviour differs from the GNU tools in ways that matter here.
#
# Builds sample device trees (effects configs laid out as real phones ship
# them, the effect loader's libraries with the names and folders written in
# them, a /proc with the audio HAL's process, root manager folders) and runs
# the real scripts against them through $RV4A_ROOT, with the device commands
# (getprop, service, ui_print, set_perm...) stubbed. Needs real symlinks, so
# run it on Linux or WSL.
set -u
# File lists are compared as text, so sort the same way everywhere.
export LC_ALL=C

HERE="$(cd "$(dirname "$0")" && pwd)"
MOD="$(cd "$HERE/.." && pwd)"
STUB="$HERE/stubs.sh"
SH="${SH:-sh}"
T="$(mktemp -d)"
trap '[ -n "${KEEP:-}" ] || rm -rf "$T"' EXIT
if [ -n "${BUSYBOX:-}" ]; then
  mkdir -p "$T/bin"
  for a in sh awk sed grep cmp head tail cut tr sort wc cat cp mv rm mkdir ln date \
           readlink basename dirname touch printf find ls; do
    ln -s "$BUSYBOX" "$T/bin/$a"
  done
  PATH="$T/bin:$PATH"
  SH="$BUSYBOX sh"
fi
pass=0; fail=0
ok() { if [ "$2" = 0 ]; then pass=$((pass + 1)); printf '  ok    %s\n' "$1"; else fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; fi; }
expect() { [ "$2" = "$3" ]; ok "$1 (got '$2')" $?; }
CR="$(printf '\r')"
UUID=f27317f4-c984-4de6-9a90-545759495bf2

# ---------------------------------------------------------------- fixtures
write() {  # write <file> <text> [crlf]
  mkdir -p "$(dirname "$1")"
  if [ "${3:-}" = crlf ]; then printf '%s\n' "$2" | sed "s/\$/$CR/" > "$1"; else printf '%s\n' "$2" > "$1"; fi
}

# The AOSP list most phones ship as audio_effects.xml.
AOSP_XML='<?xml version="1.0" encoding="UTF-8"?>
<audio_effects_conf version="2.0" xmlns="http://schemas.android.com/audio/audio_effects_conf/v2_0">
    <libraries>
        <library name="bundle" path="libbundlewrapper.so"/>
        <library name="reverb" path="libreverbwrapper.so"/>
        <library name="visualizer" path="libvisualizer.so"/>
    </libraries>
    <effects>
        <effect name="bassboost" library="bundle" uuid="8631f300-72e2-11df-b57e-0002a5d5c51b"/>
        <effect name="equalizer" library="bundle" uuid="ce772f20-847d-11df-bb17-0002a5d5c51b"/>
        <effect name="reverb_env_aux" library="reverb" uuid="4a387fc0-8ab3-11df-8bad-0002a5d5c51b"/>
        <effect name="visualizer" library="visualizer" uuid="d069d9e0-8329-11df-9168-0002a5d5c51b"/>
    </effects>
</audio_effects_conf>'

# Laid out like Samsung's audio_effects_sec.xml: blank lines between the
# sections, comments inside them, effect proxies with SW and HW halves, and
# pre and post processing chains after the effects.
SEC_XML='<?xml version="1.0" encoding="UTF-8"?>
<audio_effects_conf version="2.0" xmlns="http://schemas.android.com/audio/audio_effects_conf/v2_0">

    <libraries>
    <!-- A proxy library in front of the HW and SW halves of an effect -->
        <library name="proxy" path="libeffectproxy.so"/>
        <library name="offload" path="libaudioeffectoffload.so"/>
        <library name="bundle" path="libbundlewrapper.so"/>
        <library name="soundalive_sec" path="libaudiosaplus_sec.so"/>
        <library name="pre_processing" path="libaudiopreprocessing.so"/>
        <library name="dap" path="libswdap.so"/>
    </libraries>

    <effects>
        <effectProxy name="soundalive" library="proxy" uuid="05227ea0-50bb-11e3-ac69-0002a5d5c51b">
            <libsw library="soundalive_sec" uuid="cf65eb39-ce2f-48a8-a903-ceb818c06745"/>
            <libhw library="offload" uuid="0b2dbc60-50bb-11e3-988b-0002a5d5c51b"/>
        </effectProxy>
        <effect name="bassboost" library="bundle" uuid="8631f300-72e2-11df-b57e-0002a5d5c51b"/>
    <!-- Echo canceller -->
        <effect name="aec" library="pre_processing" uuid="62678340-e92c-11e2-91e2-0800200c9a66"/>
    <!-- Dolby offload -->
        <effectProxy name="dap_proxy" library="proxy" uuid="9d4921da-8225-4f29-aefa-39537a04bcaa">
            <libsw library="dap" uuid="6ab06da4-c516-4611-8166-452799218539"/>
            <libhw library="offload" uuid="a0c30891-8246-4aef-b8ad-d53e26da0253"/>
        </effectProxy>
    </effects>

    <preprocess>
        <stream type="voice_communication">
            <apply effect="aec"/>
        </stream>
    </preprocess>

    <postprocess>
        <stream type="music">
            <apply effect="soundalive"/>
        </stream>
    </postprocess>
</audio_effects_conf>'

# A plain list with nothing in <effects>, next to the real one.
EMPTY_XML='<?xml version="1.0" encoding="UTF-8"?>
<audio_effects_conf version="2.0" xmlns="http://schemas.android.com/audio/audio_effects_conf/v2_0">
    <libraries>
        <library name="bundle" path="libbundlewrapper.so"/>
    </libraries>
    <effects>
    </effects>
</audio_effects_conf>'

SPATIAL_XML='<?xml version="1.0" encoding="UTF-8"?>
<audio_effects_conf version="2.0" xmlns="http://schemas.android.com/audio/audio_effects_conf/v2_0">

    <libraries>
        <library name="spatializer" path="libswspatializer.so"/>
    </libraries>

    <effects>
        <effect name="spatializer" library="spatializer" uuid="ccd4cf09-a79d-46c2-9aae-06a1698d6c8f"/>
    </effects>

</audio_effects_conf>'

AOSP_CONF='# Default effects library configuration
libraries {
  bundle {
    path /vendor/lib/soundfx/libbundlewrapper.so
  }
  reverb {
    path /vendor/lib/soundfx/libreverbwrapper.so
  }
}

effects {
  bassboost {
    library bundle
    uuid 8631f300-72e2-11df-b57e-0002a5d5c51b
  }
  reverb_env_aux {
    library reverb
    uuid 4a387fc0-8ab3-11df-8bad-0002a5d5c51b
  }
}'

# fakebin <file> <strings...>: a file holding those strings between binary
# bytes, the way they sit in a library's string table.
fakebin() {
  f="$1"; shift
  mkdir -p "$(dirname "$f")"
  { printf '\177ELF\002\001\001'; for s in "$@"; do printf '\000%s' "$s"; done; printf '\000\377\376'; } > "$f"
}

# The strings each family of loader carries, as read from real firmware.
SAMSUNG_CFG="/system/etc /vendor/etc /vendor/etc/audio /vendor/etc/audio/sku_ _qssi audio_effects_conf audio_effects_sec.xml %s/%s"
SAMSUNG_EFF="/system/etc/audio_effects.conf /system/etc/audio_effects_model.xml /system/etc/audio_effects_tdm.xml /vendor/etc/audio_effects.conf /vendor/etc/audio_effects_spatializer.xml"
AOSP_CFG="/system/etc /vendor/etc /vendor/etc/audio/sku_ audio_effects.xml audio_effects_conf"
CLO_CFG="/odm/etc /system/etc /vendor/etc /vendor/etc/audio /vendor/etc/audio/sku_ _qssi audio_effects.xml audio_effects_conf"
ASUS_CFG="/system/etc /vendor/etc /vendor/etc/audio/sku_ audio_effects.xml audio_effects_AI2203.xml audio_effects_conf brand"
HONOR_CFG="/odm/etc/audio /system/etc /vendor/etc /vendor/etc/audio/sku_ audio_effects.xml audio_effects_conf"
XIAOMI_CFG="/odm/etc/audio/sku_ /system/etc /vendor/etc /vendor/etc/audio /vendor/etc/audio/sku_ _qssi audio_effects.xml audio_effects_conf"
AOSP_EFF="/system/etc/audio_effects.conf /vendor/etc/audio_effects.conf"

# loader <root> <libdir> <config strings> [effects strings]
loader() {
  # shellcheck disable=SC2086
  fakebin "$1$2/libeffectsconfig.so" $3
  # shellcheck disable=SC2086
  fakebin "$1$2/libeffects.so" ${4:-$AOSP_EFF}
  fakebin "$1$2/hw/android.hardware.audio.effect@7.0-impl.so" "EffectsFactory"
}

# hal <root> <libdir> [hidl version]: the audio HAL as /proc shows it, with
# the loader mapped from <libdir>. audioserver and an app (pids that are
# looked at first) map the system copy of the config parser, the HIDL client
# library and, by chance, a vendor library: neither is the HAL.
hal() {
  p="$1/proc/1234"; mkdir -p "$p"
  printf '/vendor/bin/hw/android.hardware.audio.service\000' > "$p/cmdline"
  ln -sfn /vendor/bin/hw/android.hardware.audio.service "$p/exe"
  {
    echo "5a00000000-5a00010000 r-xp 00000000 fd:05 11    /vendor/bin/hw/android.hardware.audio.service"
    echo "7a00000000-7a00010000 r-xp 00000000 fd:05 12    $2/libeffects.so"
    echo "7a10000000-7a10010000 r-xp 00000000 fd:05 13    $2/libeffectsconfig.so"
    echo "7a10010000-7a10011000 r--p 00010000 fd:05 13    $2/libeffectsconfig.so"
    [ -n "${3:-}" ] && echo "7a20000000-7a20010000 r-xp 00000000 fd:05 14    $2/hw/android.hardware.audio.effect@$3-impl.so"
    echo "7b00000000-7b00001000 rw-p 00000000 00:00 0 "
  } > "$p/maps"
  mkdir -p "$1/proc/100" "$1/proc/1000"
  printf '/system/bin/audioserver\000' > "$1/proc/100/cmdline"
  printf '%s\n' "7c00000000-7c00010000 r-xp 00000000 fd:03 21    /system/lib64/libeffectsconfig.so" \
    "7c10000000-7c10010000 r-xp 00000000 fd:03 22    /system/lib64/android.hardware.audio.effect@7.0.so" > "$1/proc/100/maps"
  printf 'com.example.player\000' > "$1/proc/1000/cmdline"
  echo "7d00000000-7d00010000 r-xp 00000000 fd:05 12    /vendor/lib64/libeffects.so" > "$1/proc/1000/maps"
}

props() {  # props <file> [extra lines]
  printf 'ro.build.version.sdk=36\nro.build.version.release=16\nro.product.model=SM-A176U1\n%s\n' "${2:-}" > "$1"
}

# A Galaxy A16/A17 5G: Exynos, HIDL effect HAL 7.0, Samsung's loader asking
# only for audio_effects_sec.xml. audio_effects.xml next to it is the AOSP
# list in CRLF, which nothing reads.
samsung() {  # samsung <root> [sec line ending]
  rm -rf "$1"
  write "$1/vendor/etc/audio_effects.xml" "$AOSP_XML" crlf
  write "$1/vendor/etc/audio_effects_sec.xml" "$SEC_XML" "${2:-}"
  write "$1/vendor/etc/audio_effects_spatializer.xml" "$SPATIAL_XML"
  write "$1/system/etc/audio_effects.xml" "$AOSP_XML"
  loader "$1" /vendor/lib64 "$SAMSUNG_CFG" "$SAMSUNG_EFF"
  loader "$1" /vendor/lib "$SAMSUNG_CFG" "$SAMSUNG_EFF"
  hal "$1" /vendor/lib64 7.0
}
props "$T/samsung.props" "ro.product.vendor.manufacturer=samsung"
props "$T/plain.props" "ro.product.vendor.manufacturer=Generic"

INSTUBS="$T/instubs.sh"
cat > "$INSTUBS" <<'EOF'
ui_print() { echo "$*"; }
abort() { echo "$*"; exit 1; }
set_perm() { echo "set_perm $*" >> "$MODPATH/.perms"; }
set_perm_recursive() { :; }
EOF

# install_case <name> <root> <props>: runs customize.sh as a root manager
# would, with the environment it sets. Leaves the module in $MP and the
# printed log in $LOGF.
install_case() {
  MP="$T/mod-$1"; LOGF="$T/install-$1.log"
  rm -rf "$MP"; mkdir -p "$MP/libs/arm64-v8a" "$MP/libs/armeabi-v7a"
  cp "$MOD/customize.sh" "$MOD/post-fs-data.sh" "$MOD/service.sh" "$MOD/module.prop" "$MP/"
  cp -r "$MOD/common" "$MP/"
  echo "engine arm64" > "$MP/libs/arm64-v8a/libjamesdsp.so"
  echo "engine arm" > "$MP/libs/armeabi-v7a/libjamesdsp.so"
  magisk_default=""
  [ -z "${KSU:-}${APATCH:-}" ] && magisk_default=1
  ( export RV4A_ROOT="$2" PROPS="$3" MODPATH="$MP" ARCH="${ARCH:-arm64}" AIDL="${AIDL:-}" \
      KSU="${KSU:-}" KSU_VER="${KSU_VER:-}" KSU_VER_CODE="${KSU_VER_CODE:-}" \
      KSU_LATE_LOAD="${KSU_LATE_LOAD:-}" KSU_RUNTIME_MODE="${KSU_RUNTIME_MODE:-}" KSUD_VER="${KSUD_VER:-}" \
      APATCH="${APATCH:-}" APATCH_VER="${APATCH_VER:-}" APATCH_VER_CODE="${APATCH_VER_CODE:-}" \
      MAGISK_VER="${MAGISK_VER:-${magisk_default:+28.1}}" MAGISK_VER_CODE="${MAGISK_VER_CODE:-${magisk_default:+28100}}"
    $SH -c ". '$STUB'; . '$INSTUBS'; . '$MP/customize.sh'" ) > "$LOGF" 2>&1
}
show() { sed 's/^/        | /' "$1"; }
installs() {  # installs <label> <case> <root> <props>
  install_case "$2" "$3" "$4"; rc=$?
  ok "$1" $rc
  [ $rc = 0 ] || show "$LOGF"
}
refused() {  # refused <label> <case> <root> <props> <message fragment>
  install_case "$2" "$3" "$4"; rc=$?
  grep -q "$5" "$LOGF"; m=$?
  [ $rc != 0 ] && [ $m = 0 ] && [ ! -s "$MP/.source_config" ] && [ -z "$(staged)" ]; ok "$1" $?
  [ $rc != 0 ] && [ $m = 0 ] || show "$LOGF"
}
# Every staged config, by device path.
staged() { ( cd "$MP/system" 2>/dev/null && find . -name 'audio_effects*' -type f | sed 's|^\.||; s|^/etc/|/system/etc/|' | sort | tr '\n' ' ' ); }
# What strip_ours makes of a file, through the shell under test.
strip() { $SH -c ". '$MOD/common/patch_audio_config.sh'; strip_ours '$1'"; }
lines_added() { echo $(( $(wc -l < "$2") - $(awk 'END { print NR }' "$1") )); }

echo "== shell under test: $SH =="

# ---------------------------------------------------------------- Samsung
echo; echo "[1] Samsung: the loader reads audio_effects_sec.xml only"
R="$T/sam"; samsung "$R"
installs "A17-like phone installs" sam "$R" "$T/samsung.props"
expect "only the _sec list is patched" "$(staged)" "/vendor/etc/audio_effects_sec.xml "
O="$MP/system/vendor/etc/audio_effects_sec.xml"
grep -q "Loader: libeffectsconfig.so asks for audio_effects_sec.xml" "$LOGF"; ok "the log names the loader library and the name it asks for" $?
grep -q "^- Effect HAL: HIDL 7.0" "$LOGF"; ok "the log says HIDL 7.0" $?
grep -q "^- Registered in /vendor/etc/audio_effects_sec.xml" "$LOGF"; ok "the log says where it registered" $?
grep -q "^- Root: Magisk 28.1 (28100)" "$LOGF" && grep -q "^- Phone: SM-A176U1, Android 16 (API 36)" "$LOGF"; ok "root manager, version and phone in the log" $?
cmp -s <(grep '^[-!]' "$LOGF") <(grep '^[-!]' "$MP/install.log"); ok "install.log keeps the same lines" $?
expect "two lines added" "$(lines_added "$R/vendor/etc/audio_effects_sec.xml" "$O")" 2
strip "$O" > "$T/a"; strip "$R/vendor/etc/audio_effects_sec.xml" > "$T/b"
cmp -s "$T/a" "$T/b"; ok "taking our lines out gives the stock file back byte for byte" $?
l1="$(grep -n "<library name=\"jdsp\" path=\"libjamesdsp.so\"/>" "$O" | cut -d: -f1)"
l2="$(grep -n "</libraries>" "$O" | cut -d: -f1)"
e1="$(grep -n "<effect name=\"jamesdsp\" library=\"jdsp\" uuid=\"$UUID\"/>" "$O" | cut -d: -f1)"
e2="$(grep -n "</effects>" "$O" | cut -d: -f1)"
[ "$((l1 + 1))" = "$l2" ] && [ "$((e1 + 1))" = "$e2" ]; ok "our lines sit just before </libraries> and </effects>" $?
expect "indented like their neighbours" "$(grep -c '^        <library name="jdsp"\|^        <effect name="jamesdsp"' "$O")" 2
sig="$($SH -c ". '$MOD/common/patch_audio_config.sh'; config_sig '$R/vendor/etc/audio_effects_sec.xml'")"
expect ".source_config: the _sec list, measured without our lines" "$(cat "$MP/.source_config")" "/vendor/etc/audio_effects_sec.xml $sig"
expect "stock size measured the same way as wc" "$sig" "$(wc -c < "$R/vendor/etc/audio_effects_sec.xml" | tr -dc 0-9) $(wc -l < "$R/vendor/etc/audio_effects_sec.xml" | tr -dc 0-9)"
grep -q "audio_effects_sec.xml 0 0 0644 u:object_r:vendor_configs_file:s0" "$MP/.perms"; ok "the copy gets the vendor config label" $?
[ -f "$MP/system/vendor/lib64/soundfx/libjamesdsp.so" ] && [ -f "$MP/system/lib64/soundfx/libjamesdsp.so" ] && [ ! -d "$MP/libs" ]
ok "engine staged in vendor and system lib64/soundfx, package libs removed" $?

R="$T/sam2"; samsung "$R" crlf; write "$R/vendor/etc/audio_effects.xml" "$EMPTY_XML"
installs "_sec list in CRLF, empty plain list next to it" sam2 "$R" "$T/samsung.props"
O="$MP/system/vendor/etc/audio_effects_sec.xml"
expect "still only the _sec list" "$(staged)" "/vendor/etc/audio_effects_sec.xml "
expect "every line of the copy ends in CRLF, ours too" "$(grep -c "$CR\$" "$O")" "$(wc -l < "$O" | tr -dc 0-9)"
grep -q "(CRLF line endings kept)" "$LOGF"; ok "the log says the line endings were kept" $?
strip "$O" > "$T/a"; strip "$R/vendor/etc/audio_effects_sec.xml" > "$T/b"
cmp -s "$T/a" "$T/b"; ok "CRLF: taking our lines out gives the stock file back" $?

R="$T/sam3"; samsung "$R"
sed -i "s|</effects>|    <effect name=\"jamesdsp\" library=\"jdsp\" uuid=\"$UUID\"/>\n    </effects>|" "$R/vendor/etc/audio_effects_spatializer.xml"
installs "another JamesDSP mod left our effect in the spatializer list" sam3 "$R" "$T/samsung.props"
grep -q "audio_effects_spatializer.xml already lists this effect and is loaded first" "$LOGF"; ok "  it says that entry wins" $?

R="$T/sam4"; samsung "$R"; rm -rf "$R/proc" "$R/vendor/lib64" "$R/vendor/lib"
installs "Samsung loader unreadable: installs on a guess" sam4 "$R" "$T/samsung.props"
expect "  both the _sec and the plain list are patched" "$(staged)" "/vendor/etc/audio_effects.xml /vendor/etc/audio_effects_sec.xml "
grep -q "Could not read the effect loader; trying audio_effects_sec.xml audio_effects.xml" "$LOGF" &&
  grep -q "cannot be told, so each is patched" "$LOGF"; ok "  and says it is guessing" $?
expect "  .source_config tracks both" "$(cut -d' ' -f1 "$MP/.source_config" | tr '\n' ' ')" "/vendor/etc/audio_effects_sec.xml /vendor/etc/audio_effects.xml "
R="$T/sam5"; samsung "$R"; rm -rf "$R/proc" "$R/vendor/lib64" "$R/vendor/lib"
installs "unreadable loader on another brand: audio_effects.xml only" sam5 "$R" "$T/plain.props"
expect "  the plain list" "$(staged)" "/vendor/etc/audio_effects.xml "

R="$T/sam6"; samsung "$R"; rm -rf "$R/proc"
installs "no HAL process found: the loader in /vendor/lib64 is read" sam6 "$R" "$T/samsung.props"
expect "  still only the _sec list" "$(staged)" "/vendor/etc/audio_effects_sec.xml "
grep -q "^- Effect HAL: no effect HAL process found" "$LOGF"; ok "  and the log says no HAL process was found" $?

R="$T/sam7"; samsung "$R"; hal "$R" /vendor/lib 7.0
installs "32-bit audio HAL" sam7 "$R" "$T/samsung.props"
grep -q "audio HAL is 32-bit" "$LOGF"; ok "  the log says so" $?

# ---------------------------------------------------------------- AOSP and vendor lists
echo; echo "[2] which folder wins"
aosp() {  # aosp <root> <loader strings>
  rm -rf "$1"
  write "$1/vendor/etc/audio_effects.xml" "$AOSP_XML"
  write "$1/system/etc/audio_effects.xml" "$AOSP_XML"
  loader "$1" /vendor/lib64 "$2"; hal "$1" /vendor/lib64 7.0
}
R="$T/aosp"; aosp "$R" "$AOSP_CFG"
installs "stock AOSP phone" aosp "$R" "$T/plain.props"
expect "  /vendor/etc, not /system/etc" "$(staged)" "/vendor/etc/audio_effects.xml "
grep -q "Loader: libeffectsconfig.so asks for audio_effects.xml" "$LOGF"; ok "  the log names audio_effects.xml" $?

write "$R/vendor/etc/audio/audio_effects.xml" "$AOSP_XML"
installs "AOSP loader ignores /vendor/etc/audio" aosp2 "$R" "$T/plain.props"
expect "  still /vendor/etc" "$(staged)" "/vendor/etc/audio_effects.xml "
R="$T/clo"; aosp "$R" "$CLO_CFG"; write "$R/vendor/etc/audio/audio_effects.xml" "$AOSP_XML"
installs "Qualcomm loader reads /vendor/etc/audio first" clo "$R" "$T/plain.props"
expect "  /vendor/etc/audio" "$(staged)" "/vendor/etc/audio/audio_effects.xml "

write "$R/vendor/etc/audio/sku_kalama/audio_effects.xml" "$AOSP_XML"
props "$T/sku.props" "ro.boot.product.vendor.sku=kalama"
installs "sku folder" sku "$R" "$T/sku.props"
expect "  /vendor/etc/audio/sku_kalama wins" "$(staged)" "/vendor/etc/audio/sku_kalama/audio_effects.xml "
installs "sku folder without the sku property" sku2 "$R" "$T/plain.props"
expect "  is not read" "$(staged)" "/vendor/etc/audio/audio_effects.xml "
write "$R/vendor/etc/audio/sku_kalama_qssi/audio_effects.xml" "$AOSP_XML"
props "$T/qssi.props" "ro.boot.product.vendor.sku=kalama
ro.vendor.qti.va_aosp.support=1"
installs "Qualcomm qssi sku folder" qssi "$R" "$T/qssi.props"
expect "  sku_kalama_qssi wins" "$(staged)" "/vendor/etc/audio/sku_kalama_qssi/audio_effects.xml "
R="$T/aosp-qssi"; aosp "$R" "$AOSP_CFG"
write "$R/vendor/etc/audio/sku_kalama/audio_effects.xml" "$AOSP_XML"; write "$R/vendor/etc/audio/sku_kalama_qssi/audio_effects.xml" "$AOSP_XML"
installs "a loader without _qssi" aosp-qssi "$R" "$T/qssi.props"
expect "  skips the qssi folder" "$(staged)" "/vendor/etc/audio/sku_kalama/audio_effects.xml "
R="$T/xiaomi"; aosp "$R" "$XIAOMI_CFG"; write "$R/odm/etc/audio/sku_nuwa/audio_effects.xml" "$AOSP_XML"
write "$R/vendor/etc/audio/sku_nuwa/audio_effects.xml" "$AOSP_XML"
props "$T/nuwa.props" "ro.boot.product.vendor.sku=nuwa"
installs "a loader naming /odm/etc/audio/sku_" xiaomi "$R" "$T/nuwa.props"
expect "  reads the /odm sku folder" "$(staged)" "/odm/etc/audio/sku_nuwa/audio_effects.xml "
R="$T/honor"; aosp "$R" "$HONOR_CFG"; write "$R/odm/etc/audio/audio_effects.xml" "$AOSP_XML"
installs "a loader naming /odm/etc/audio" honor "$R" "$T/plain.props"
expect "  reads it before /vendor/etc" "$(staged)" "/odm/etc/audio/audio_effects.xml "

R="$T/odm"; aosp "$R" "$AOSP_CFG"; write "$R/odm/etc/audio_effects.xml" "$AOSP_XML"
installs "/odm/etc" odm "$R" "$T/plain.props"
expect "  wins over /vendor/etc" "$(staged)" "/odm/etc/audio_effects.xml "
grep -q "audio_effects.xml 0 0 0644 u:object_r:vendor_configs_file:s0" "$MP/.perms"; ok "  with the vendor config label" $?
R="$T/odmlink"; aosp "$R" "$AOSP_CFG"; mkdir -p "$R/vendor/odm/etc"; ln -s vendor/odm "$R/odm"
write "$R/vendor/odm/etc/audio_effects.xml" "$AOSP_XML"
installs "/odm linked into /vendor" odmlink "$R" "$T/plain.props"
expect "  is named and staged by its real path" "$(staged)" "/vendor/odm/etc/audio_effects.xml "
R="$T/odmbad"; aosp "$R" "$AOSP_CFG"; write "$R/odm/etc/audio_effects.xml" "$(printf '%s\n' "$AOSP_XML" | sed '$d')"
installs "a broken /odm list (no closing root)" odmbad "$R" "$T/plain.props"
expect "  is passed over, as the loader passes it over" "$(staged)" "/vendor/etc/audio_effects.xml "

R="$T/asus"; aosp "$R" "$ASUS_CFG"; write "$R/vendor/etc/audio_effects_AI2203.xml" "$AOSP_XML"
installs "a loader naming two files (Asus)" asus "$R" "$T/plain.props"
expect "  both are patched" "$(staged)" "/vendor/etc/audio_effects.xml /vendor/etc/audio_effects_AI2203.xml "
grep -q "Loader: libeffectsconfig.so asks for audio_effects.xml audio_effects_AI2203.xml" "$LOGF" &&
  grep -q "cannot be told" "$LOGF"; ok "  and the log says why" $?
expect "  .source_config has a line for each" "$(wc -l < "$MP/.source_config" | tr -dc 0-9)" 2

# ---------------------------------------------------------------- .conf
echo; echo "[3] older phones: .conf only"
R="$T/conf"; rm -rf "$R"; write "$R/vendor/etc/audio_effects.conf" "$AOSP_CONF"; write "$R/system/etc/audio_effects.conf" "$AOSP_CONF"
write "$R/odm/etc/audio_effects.conf" "$AOSP_CONF"; loader "$R" /vendor/lib64 "$AOSP_CFG"; hal "$R" /vendor/lib64 2.0
installs ".conf-only phone" conf "$R" "$T/plain.props"
expect "  /vendor/etc/audio_effects.conf, never /odm's" "$(staged)" "/vendor/etc/audio_effects.conf "
O="$MP/system/vendor/etc/audio_effects.conf"
grep -q "path /system/lib/soundfx/libjamesdsp.so" "$O" && grep -q "uuid $UUID" "$O"; ok "  library and effect blocks added" $?
$SH -c ". '$MOD/common/patch_audio_config.sh'; validate_conf '$O'"; ok "  the copy passes validation" $?
rm "$R/vendor/etc/audio_effects.conf"
installs ".conf in /system/etc only" conf2 "$R" "$T/plain.props"
expect "  /system/etc/audio_effects.conf" "$(staged)" "/system/etc/audio_effects.conf "
[ -f "$MP/system/etc/audio_effects.conf" ]; ok "  staged under system/etc" $?
R="$T/confxml"; aosp "$R" "$AOSP_CFG"; write "$R/vendor/etc/audio_effects.conf" "$AOSP_CONF"
installs "XML and .conf both present" confxml "$R" "$T/plain.props"
expect "  the XML wins" "$(staged)" "/vendor/etc/audio_effects.xml "

# ---------------------------------------------------------------- refusals
echo; echo "[4] what the installer refuses"
R="$T/none"; rm -rf "$R"; mkdir -p "$R/vendor/etc"; loader "$R" /vendor/lib64 "$AOSP_CFG"
refused "no config at all" none "$R" "$T/plain.props" "There is nowhere to register"
AIDL=1 refused "AIDL HAL and no legacy config" aidl "$R" "$T/plain.props" "runs the AIDL audio effects HAL"
R="$T/aosp"; AIDL=1 installs "AIDL HAL with a legacy config installs" aidl2 "$R" "$T/plain.props"
grep -q "^- Effect HAL: AIDL" "$LOGF" && grep -q "BOTH the AIDL HAL" "$LOGF"; ok "  with a note" $?
R="$T/mini"; rm -rf "$R"; loader "$R" /vendor/lib64 "$AOSP_CFG"; mkdir -p "$R/vendor/etc"
printf '<audio_effects_conf><libraries><library name="a" path="a.so"/></libraries><effects></effects></audio_effects_conf>\n' > "$R/vendor/etc/audio_effects.xml"
refused "a one-line list is not patched" mini "$R" "$T/plain.props" "an unusual layout"
R="$T/proxy"; aosp "$R" "$AOSP_CFG"
sed -i "s|</effects>|        <effectProxy name=\"x\" library=\"proxy\" uuid=\"aaaa\">\n            <libsw library=\"other\" uuid=\"$UUID\"/>\n        </effectProxy>\n    </effects>|" "$R/vendor/etc/audio_effects.xml"
refused "our uuid already used inside an effect proxy" proxy "$R" "$T/plain.props" "already lists this effect for another library"

R="$T/comment"; aosp "$R" "$AOSP_CFG"
sed -i 's|^    <effects>|    <!-- old list, kept for reference\n    </effects>\n    -->\n    <effects>|' "$R/vendor/etc/audio_effects.xml"
installs "a closing tag inside a comment" comment "$R" "$T/plain.props"
O="$MP/system/vendor/etc/audio_effects.xml"
e1="$(grep -n "uuid=\"$UUID\"" "$O" | cut -d: -f1)"; e0="$(grep -n '^    <effects>' "$O" | cut -d: -f1)"
[ -n "$e1" ] && [ "$e1" -gt "$e0" ]; ok "  is not used: our effect goes in the real list" $?

# The checks a generated copy must pass, fed copies that are wrong on purpose.
val() { $SH -c ". '$MOD/common/patch_audio_config.sh'; validate_xml '$1' '$2'"; }
S="$T/aosp/vendor/etc/audio_effects.xml"; G="$T/mod-aosp/system/vendor/etc/audio_effects.xml"
val "$G" "$S"; ok "a good copy passes" $?
sed "s|^\(        <effect name=\"jamesdsp\".*\)$|    <!--\1-->|" "$G" > "$T/v1.xml"
val "$T/v1.xml" "$S"; [ $? != 0 ]; ok "our effect inside a comment fails" $?
grep -v 'name="jamesdsp"' "$G" > "$T/v2.xml"; echo "<effect name=\"jamesdsp\" library=\"jdsp\" uuid=\"$UUID\"/>" >> "$T/v2.xml"
val "$T/v2.xml" "$S"; [ $? != 0 ]; ok "our effect after the root element fails" $?
sed 's|ce772f20|ce772f21|' "$G" > "$T/v3.xml"
val "$T/v3.xml" "$S"; [ $? != 0 ]; ok "a changed stock line fails" $?
sed 's|\(<library name="jdsp".*\)|\1\n\1|' "$G" > "$T/v4.xml"
val "$T/v4.xml" "$S"; [ $? != 0 ]; ok "our library twice fails" $?
sed 's|\(<effect name="jamesdsp".*\)|\1\n\1|' "$G" > "$T/v5.xml"
val "$T/v5.xml" "$S"; [ $? != 0 ]; ok "our effect twice fails" $?

# ---------------------------------------------------------------- AML, other mods, updates
echo; echo "[5] AML, other modules, and updating over an earlier install"
R="$T/sam"; mkdir -p "$R/data/adb/modules/aml"
installs "with AML" aml "$R" "$T/samsung.props"
[ -f "$MP/aml.sh" ] && grep -q "patch_cfgs jamesdsp $UUID jdsp /system/lib/soundfx/libjamesdsp.so" "$MP/aml.sh" &&
  [ ! -f "$MP/.source_config" ] && [ -z "$(staged)" ]; ok "  aml.sh written, no config of our own" $?
grep -q "Effects list in use: /vendor/etc/audio_effects_sec.xml" "$LOGF"; ok "  the log still says which list the loader reads" $?
rm -rf "$R/data/adb/modules"
mkdir -p "$R/data/adb/modules/dolby/system/vendor/etc"; cp "$R/vendor/etc/audio_effects_sec.xml" "$R/data/adb/modules/dolby/system/vendor/etc/"
installs "another module ships the same list" other "$R" "$T/samsung.props"
grep -q "Also replaced by: dolby" "$LOGF"; ok "  it is named" $?
touch "$R/data/adb/modules/dolby/disable"
install_case other2 "$R" "$T/samsung.props"
grep -q "Also replaced by" "$LOGF"; [ $? != 0 ]; ok "  a disabled one is not" $?
rm -rf "$R/data/adb/modules"

samsung "$R"; install_case fresh "$R" "$T/samsung.props"; FRESH="$MP"
# 4.4.0 put its lines straight after the opening tags, in the file the
# loader does not read; an update to it sees that copy mounted.
R="$T/upd"; samsung "$R"
sed -i "s|<libraries>|<libraries>\n        <library name=\"jdsp\" path=\"libjamesdsp.so\"/>|; s|<effects>|<effects>\n        <effect name=\"jamesdsp\" library=\"jdsp\" uuid=\"$UUID\"/>|" \
  "$R/vendor/etc/audio_effects_sec.xml"
installs "update over an older version's copy mounted on the list" upd "$R" "$T/samsung.props"
cmp -s "$MP/system/vendor/etc/audio_effects_sec.xml" "$FRESH/system/vendor/etc/audio_effects_sec.xml"; ok "  same copy as a fresh install" $?
cmp -s "$MP/.source_config" "$FRESH/.source_config"; ok "  same .source_config as a fresh install" $?
cp "$FRESH/system/vendor/etc/audio_effects_sec.xml" "$R/vendor/etc/audio_effects_sec.xml"
installs "reinstall over this version's own copy" upd2 "$R" "$T/samsung.props"
cmp -s "$MP/system/vendor/etc/audio_effects_sec.xml" "$FRESH/system/vendor/etc/audio_effects_sec.xml" &&
  cmp -s "$MP/.source_config" "$FRESH/.source_config"; ok "  same copy and record again" $?
R="$T/sam"; mkdir -p "$R/data/adb/modules/rv4a_engine"; touch "$R/data/adb/modules/rv4a_engine/disable"
echo "rejected /x (failed validation)" > "$R/data/adb/modules/rv4a_engine/boot.log"
installs "update over a module that switched itself off" redisable "$R" "$T/samsung.props"
[ ! -f "$R/data/adb/modules/rv4a_engine/disable" ] && grep -q "Cleared a previous self-disable" "$LOGF"; ok "  the flag is cleared" $?
rm -rf "$R/data/adb"

# ---------------------------------------------------------------- root managers
echo; echo "[6] root managers"
R="$T/sam"
KSU=true KSU_VER=3.0.0 KSU_VER_CODE=30123 refused "KernelSU 3 without a metamodule" ksu3 "$R" "$T/samsung.props" "none is active (none installed)"
grep -q "meta-overlayfs" "$LOGF"; ok "  says what to install" $?
mkdir -p "$R/data/adb/modules/meta-overlayfs"; touch "$R/data/adb/modules/meta-overlayfs/metamount.sh"
ln -sfn modules/meta-overlayfs "$R/data/adb/metamodule"
KSU=true KSU_VER=3.0.0 KSU_VER_CODE=30123 installs "KernelSU 3 with meta-overlayfs" ksu3meta "$R" "$T/samsung.props"
grep -q "^- Root: KernelSU 3.0.0 (30123)" "$LOGF" && grep -q "^- Metamodule: meta-overlayfs$" "$LOGF"; ok "  version and metamodule in the log" $?
touch "$R/data/adb/modules/meta-overlayfs/disable"
KSU=true KSU_VER=3.0.0 KSU_VER_CODE=30123 refused "  a disabled metamodule does not count" ksu3dis "$R" "$T/samsung.props" "meta-overlayfs is disabled"
rm "$R/data/adb/modules/meta-overlayfs/disable" "$R/data/adb/modules/meta-overlayfs/metamount.sh"
KSU=true KSU_VER=3.0.0 KSU_VER_CODE=30123 refused "  nor one without metamount.sh" ksu3nomount "$R" "$T/samsung.props" "has no metamount.sh"
touch "$R/data/adb/modules/meta-overlayfs/metamount.sh"; rm "$R/data/adb/metamodule"
printf 'id=meta-overlayfs\nmetamodule=1\n' > "$R/data/adb/modules/meta-overlayfs/module.prop"
KSU=true KSU_VER=3.0.0 KSU_VER_CODE=30123 installs "  found through metamodule=1 when the link is missing" ksu3prop "$R" "$T/samsung.props"
rm -rf "$R/data/adb/modules"
mkdir -p "$R/data/adb"; fakebin "$R/data/adb/ksud" "post-fs-data.sh" "metamount.sh" "service.sh"
KSU=true refused "ksud that runs metamount.sh, no version given" ksubin "$R" "$T/samsung.props" "none is active"
fakebin "$R/data/adb/ksud" "post-fs-data.sh" "service.sh"
KSU=true KSU_VER_CODE=30500 installs "ksud without it wins over a fork's version code" ksubin2 "$R" "$T/samsung.props"
grep -q "not needed by this version" "$LOGF"; ok "  no metamodule needed" $?
rm -f "$R/data/adb/ksud"
KSU=true installs "KernelSU version unknown: installs" ksuunk "$R" "$T/samsung.props"
grep -q "Could not tell which KernelSU" "$LOGF" && grep -q "^- Root: KernelSU version unknown" "$LOGF"; ok "  with a warning" $?
KSU=true KSUD_VER=3.1.0 refused "  but a ksud -V of 3.x is enough to know" ksud3 "$R" "$T/samsung.props" "none is active"
KSU=true KSU_VER=1.0.5 KSU_VER_CODE=11872 installs "KernelSU 1.0.5 mounts by itself" ksu1 "$R" "$T/samsung.props"
KSU=true KSU_VER=3.2.0 KSU_VER_CODE=30400 KSU_LATE_LOAD=1 refused "late-load mode" late "$R" "$T/samsung.props" "late-load mode"
KSU=true KSU_VER=3.2.0 KSU_VER_CODE=30400 KSU_RUNTIME_MODE=late-load refused "late-load mode (runtime mode)" late2 "$R" "$T/samsung.props" "late-load mode"
APATCH=true APATCH_VER=11219 APATCH_VER_CODE=11219 refused "APatch 11219 without a metamodule" ap "$R" "$T/samsung.props" "This APatch only mounts"
APATCH=true APATCH_VER_CODE=11142 installs "older APatch mounts by itself" ap2 "$R" "$T/samsung.props"
touch "$R/data/adb/.litemode_enable"
APATCH=true APATCH_VER_CODE=11142 refused "  unless it is in lite mode" aplite "$R" "$T/samsung.props" "lite mode"
rm -f "$R/data/adb/.litemode_enable"
mkdir -p "$R/data/adb/ap"
KSU=true KSU_VER=1.0.5 KSU_VER_CODE=11872 installs "an APatch folder left behind does not hide KernelSU" leftover "$R" "$T/samsung.props"
grep -q "^- Root: KernelSU" "$LOGF"; ok "  KernelSU it is" $?
mkdir -p "$R/data/adb/ksu"
installs "nor do KernelSU folders hide Magisk" leftover2 "$R" "$T/samsung.props"
grep -q "^- Root: Magisk" "$LOGF" && ! grep -q "Metamodule" "$LOGF"; ok "  Magisk, no metamodule talk" $?
rm -rf "$R/data/adb"

# ---------------------------------------------------------------- post-fs-data
echo; echo "[7] post-fs-data: watchdog, drift and our copies"
fresh() { R="$T/sam"; samsung "$R"; install_case boot "$R" "$T/samsung.props" >/dev/null; B="$MP"; C="$B/system/vendor/etc/audio_effects_sec.xml"; }
pfd() { RV4A_ROOT="$R" $SH "$B/post-fs-data.sh"; }
fresh; pfd
grep -q "source unchanged: /vendor/etc/audio_effects_sec.xml" "$B/boot.log" && grep -q "^ok .*audio_effects_sec.xml" "$B/boot.log"
ok "a clean boot checks the source and our copy" $?
[ -f "$B/.boot_incomplete" ] && [ ! -f "$B/skip_mount" ]; ok "boot marker set, nothing stopped" $?
grep -q "engine in the module: .*lib64/soundfx" "$B/boot.log"; ok "it says the engine is in the module (not that it is visible)" $?
echo "/vendor/etc/audio_effects_sec.xml $(wc -c < "$R/vendor/etc/audio_effects_sec.xml") $(wc -l < "$R/vendor/etc/audio_effects_sec.xml")" > "$B/.source_config"
rm -f "$B/.boot_incomplete"; pfd
grep -q "source unchanged" "$B/boot.log" && [ -f "$C" ]; ok "the one-line format of earlier installs still reads" $?
# An earlier install measured the file as it read it, which was a copy with
# our lines when one was mounted; on a manager that mounts before this runs
# that same copy is what is read now.
cp "$C" "$R/vendor/etc/audio_effects_sec.xml"
echo "/vendor/etc/audio_effects_sec.xml $(wc -c < "$C") $(wc -l < "$C")" > "$B/.source_config"
rm -f "$B/.boot_incomplete"; pfd
grep -q "source unchanged" "$B/boot.log" && [ -f "$C" ]; ok "  also when it was measured on a mounted copy" $?
fresh; cp "$C" "$R/vendor/etc/audio_effects_sec.xml"; pfd
grep -q "source unchanged" "$B/boot.log" && [ -f "$C" ]; ok "our own copy already mounted over the source is not a change" $?
fresh; sed -i 's|</effects>|        <effect name="new" library="bundle" uuid="11111111-2222-3333-4444-555555555555"/>\n    </effects>|' "$R/vendor/etc/audio_effects_sec.xml"; pfd
[ -f "$C.rejected" ] && [ ! -f "$C" ] && [ -f "$B/skip_mount" ] && grep -q "SOURCE CONFIG CHANGED" "$B/boot.log"
ok "a system update changed the list: our copy set aside, mounting stopped" $?
rm -f "$B/.boot_incomplete"; pfd; [ ! -f "$C" ]; ok "  and it stays aside on the next boot" $?
fresh; rm "$R/vendor/etc/audio_effects_sec.xml"; pfd
[ -f "$C.rejected" ] && grep -q "SOURCE CONFIG GONE" "$B/boot.log"; ok "the list is gone: our copy set aside" $?
R="$T/asus"; install_case boot2 "$R" "$T/plain.props" >/dev/null; B="$MP"
sed -i 's|bassboost"|bassboost2"|' "$R/vendor/etc/audio_effects_AI2203.xml"; pfd
expect "one of two tracked lists changed: both copies set aside" "$(find "$B/system" -name '*.rejected' | wc -l | tr -dc 0-9)" 2
fresh; printf 'junk\n' > "$C"; pfd
[ -f "$C.rejected" ] && [ -f "$B/skip_mount" ] && grep -q "failed validation" "$B/boot.log"; ok "a damaged copy is rejected and mounting stopped" $?
R="$T/conf"; write "$R/vendor/etc/audio_effects.conf" "$AOSP_CONF"; install_case boot3 "$R" "$T/plain.props" >/dev/null; B="$MP"; pfd
grep -q "^ok .*audio_effects.conf" "$B/boot.log" && grep -q "source unchanged: /vendor/etc/audio_effects.conf" "$B/boot.log"
ok "a .conf copy passes the boot check" $?
fresh; mv "$B/system/vendor" "$B/vendor"; ln -s ../vendor "$B/system/vendor"; pfd
grep -q "^ok .*audio_effects_sec.xml" "$B/boot.log"; ok "KernelSU layout (system/vendor is a link): our copy is still checked" $?
fresh; pfd >/dev/null; pfd; grep -q "strike 1" "$B/boot.log" && [ -f "$C" ]; ok "one unfinished boot is a strike, nothing more" $?
pfd; [ -f "$B/disable" ] && [ -f "$C.disabled" ] && [ ! -f "$C" ]; ok "two unfinished boots: disabled, our copy set aside" $?
rm -f "$B/disable"; pfd; [ -f "$C" ] && grep -q "restored" "$B/boot.log"; ok "re-enabled: the copy comes back on the next boot" $?
a="$(sed -n '/^strip_ours() {/,/^}/p' "$MOD/common/patch_audio_config.sh" | grep -v '^ *#' | sed "s/EFFECT_UUID/UUID/g")"
b="$(sed -n '/^strip_ours() {/,/^}/p' "$MOD/post-fs-data.sh" | grep -v '^ *#')"
[ -n "$a" ] && [ "$a" = "$b" ]; ok "post-fs-data's strip_ours matches the installer's" $?

# ---------------------------------------------------------------- service
echo; echo "[8] service.sh: what the audio HAL can see"
svc() {  # svc <audioserver pids, one per call>
  printf '%s\n' $1 > "$T/as"
  cp "$T/samsung.props" "$T/svc.props"; echo "sys.boot_completed=1" >> "$T/svc.props"
  ( export RV4A_ROOT="$R" PROPS="$T/svc.props" AS_SEQ="$T/as" LOGCAT="$T/logcat"
    $SH -c ". '$STUB'; . \"\$0\"" "$B/service.sh" ) >/dev/null 2>&1
}
printf '%s\n' "01-01 00:00:00.000 1234 1234 E EffectsFactoryConfigLoader: Could not find library in effect path" > "$T/logcat"
fresh; pfd; svc "900"
grep -q "engine NOT visible at /vendor/lib64/soundfx" "$B/boot.log" &&
  grep -q "/vendor/etc/audio_effects_sec.xml does NOT list the engine" "$B/boot.log" &&
  grep -q "engine not loaded by the audio HAL" "$B/boot.log"
ok "nothing mounted: all three said plainly" $?
grep -q "logcat: .*EffectsFactoryConfigLoader" "$B/boot.log"; ok "the loader's own lines are kept" $?
[ ! -f "$B/.boot_incomplete" ]; ok "audioserver steady: marker cleared" $?
mkdir -p "$R/vendor/lib64/soundfx"; cp "$B/system/vendor/lib64/soundfx/libjamesdsp.so" "$R/vendor/lib64/soundfx/"
cp "$C" "$R/vendor/etc/audio_effects_sec.xml"
echo "7e00000000-7e00010000 r-xp 00000000 fd:05 99    /vendor/lib64/soundfx/libjamesdsp.so" >> "$R/proc/1234/maps"
svc "900"
grep -q "engine visible at /vendor/lib64/soundfx" "$B/boot.log" &&
  grep -q "/vendor/etc/audio_effects_sec.xml lists the engine" "$B/boot.log" &&
  grep -q "engine loaded by pid 1234 (/vendor/bin/hw/android.hardware.audio.service)" "$B/boot.log"
ok "mounted and loaded: all three said" $?
# One pid for the wait, one for each of the two samples.
fresh; pfd; svc "900 900 901"
[ -f "$B/.boot_incomplete" ] && grep -q "audioserver restarted" "$B/boot.log" && grep -q "engine NOT visible" "$B/boot.log"
ok "audioserver restarted: marker kept, report still written" $?

# ---------------------------------------------------------------- scripts
echo; echo "[9] scripts"
for s in "$MOD"/customize.sh "$MOD"/post-fs-data.sh "$MOD"/service.sh "$MOD"/common/*.sh; do
  $SH -n "$s"; ok "$(basename "$s") parses" $?
  grep -q "$CR" "$s"; [ $? != 0 ]; ok "$(basename "$s") has unix line endings" $?
done

echo; echo "$pass passed, $fail failed"
[ $fail = 0 ]
