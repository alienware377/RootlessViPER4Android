#!/system/bin/sh
# Read-only diagnostics for RootfulViPER4Android's root mode, on AIDL and on
# older (HIDL) effect HAL phones alike. Changes nothing on the phone.
#
# From a root shell (adb shell su, or a terminal app after su):
#   sh /data/adb/modules/rv4a_engine_aidl/rv4a-aidl-diag.sh     installed
#   sh /sdcard/Download/rv4a-aidl-diag.sh                       before installing
#
# Writes one text file (path printed at the end, normally
# /sdcard/Download/rv4a-aidl-diag.txt) with: the phone model and Android
# version, which effect HAL it runs and which version, the effect factory's
# process, the effect config files and our lines in them, this module's
# status and logs, whether the library was loaded, SELinux denials and
# matching log lines, and whether the engine shows up in audio_flinger.
# Nothing personal: no accounts, serial numbers, contacts, app lists or full
# logs, only the lines that match the audio effect system.

MODID=rv4a_engine_aidl
MOD=/data/adb/modules/$MODID
UUID=f27317f4-c984-4de6-9a90-545759495bf2
FACTORY=android.hardware.audio.effect.IFactory/default
PATTERN='RV4A|Root(less|ful)ViPER|rv4a_aidl|jamesdsp|EffectHalAidl|EffectsFactoryHalAidl|EffectConfig|EffectFactory|EffectsFactory|AHAL_|TimeCheck|memfd|ashmem|Cannot do a user transaction|soundfx|audio_effects'

OUT="$1"
if [ -z "$OUT" ]; then
  for d in /sdcard/Download /storage/emulated/0/Download /data/local/tmp; do
    if [ -d "$d" ] && touch "$d/.rv4a-diag-test" 2>/dev/null; then
      rm -f "$d/.rv4a-diag-test"; OUT="$d/rv4a-aidl-diag.txt"; break
    fi
  done
fi
[ -n "$OUT" ] || OUT=/data/local/tmp/rv4a-aidl-diag.txt
BODY="$OUT.body.$$"
SUM="$OUT.sum.$$"
: > "$BODY"; : > "$SUM"

say() { echo "$*" >> "$BODY"; }
sec() { { echo; echo "=== $* ==="; } >> "$BODY"; }
run() { { echo "\$ $*"; sh -c "$*" 2>&1 | head -n 400; } >> "$BODY"; }
sum() { echo "$*" >> "$SUM"; }
prop() { getprop "$1"; }

if [ "$(id -u 2>/dev/null)" != 0 ]; then
  echo "Run this as root (su first). Most of what it reads needs root."
fi

# ---------------------------------------------------------------- device
sec "device"
for p in ro.product.manufacturer ro.product.model ro.product.device ro.product.name \
         ro.board.platform ro.hardware ro.build.version.release ro.build.version.sdk \
         ro.build.id ro.build.version.security_patch ro.build.fingerprint \
         ro.vendor.build.fingerprint ro.vendor.build.version.sdk ro.vendor.api_level \
         ro.board.api_level ro.board.first_api_level ro.product.first_api_level \
         ro.boot.product.vendor.sku ro.product.cpu.abi ro.treble.enabled \
         vendor.audio.hal.stubmode ro.audio.ignore_effects sys.use_memfd \
         ro.vendor.audio.sdk.fluencetype persist.vendor.audio.fluence.speaker; do
  v="$(prop $p)"; [ -n "$v" ] && say "$p=$v"
done
say "kernel=$(uname -r 2>/dev/null)"
say "selinux=$(getenforce 2>/dev/null)"
sum "Phone: $(prop ro.product.manufacturer) $(prop ro.product.model) ($(prop ro.product.device)), Android $(prop ro.build.version.release) (API $(prop ro.build.version.sdk)), vendor API $(prop ro.vendor.api_level)$(prop ro.board.first_api_level | sed 's/^/, first vendor API /')"

# ---------------------------------------------------------------- root
sec "root manager"
ROOT=""
if [ -d /data/adb/ksu ] || command -v ksud >/dev/null 2>&1; then
  ROOT="KernelSU $(ksud -V 2>/dev/null)"
  run "ksud -V"
  run "ls -lZd /data/adb/metamodule"
  [ -f /data/adb/metamodule/module.prop ] && run "cat /data/adb/metamodule/module.prop"
  [ -e /data/adb/metamodule ] || say "no metamodule (KernelSU 3 and later mount nothing without one)"
fi
if [ -d /data/adb/ap ] || command -v apd >/dev/null 2>&1; then
  ROOT="$ROOT APatch $(apd -V 2>/dev/null)"
fi
if command -v magisk >/dev/null 2>&1; then
  ROOT="$ROOT Magisk $(magisk -v 2>/dev/null) ($(magisk -V 2>/dev/null))"
fi
say "root: ${ROOT:-unknown}"
say "late load: KSU_LATE_LOAD=${KSU_LATE_LOAD:-unset} KSU_RUNTIME_MODE=${KSU_RUNTIME_MODE:-unset}"
say "uptime: $(awk '{ print int($1) }' /proc/uptime 2>/dev/null)s"
sum "Root: ${ROOT:-unknown}$( [ -e /data/adb/metamodule ] && echo ', metamodule present')"

sec "modules"
for m in /data/adb/modules/*/; do
  m="${m%/}"; [ -d "$m" ] || continue
  st=""
  [ -f "$m/disable" ] && st="$st disabled"
  [ -f "$m/remove" ] && st="$st to-be-removed"
  v="$(sed -n 's/^version=//p' "$m/module.prop" 2>/dev/null)"
  say "${m##*/} $v$st"
  audio="$(find "$m" -maxdepth 6 \( -name 'audio_effects*' -o -path '*soundfx*' -o -name '*audio*hal*' \) 2>/dev/null | sed "s|^$m||" | head -n 10 | tr '\n' ' ')"
  [ -n "$audio" ] && say "    ships: $audio"
done

# ---------------------------------------------------------------- HAL
sec "effect HAL"
run "service list | grep -iE 'audio|effect'"
run "lshal 2>/dev/null | grep -iE 'audio|effect'"
KIND=none
service check $FACTORY 2>/dev/null | grep -q ": found" && KIND=aidl
[ $KIND = none ] && lshal 2>/dev/null | grep -q IEffectsFactory && KIND=hidl
say "kind: $KIND"
BV=""
if [ $KIND = aidl ]; then
  run "service call $FACTORY 16777215"
  hv="$(service call $FACTORY 16777215 2>/dev/null | head -n 1 | sed -n 's/.*Parcel(00000000 \([0-9a-fA-F]\{8\}\).*/\1/p')"
  [ -n "$hv" ] && BV="$(printf '%d' "0x$hv" 2>/dev/null)"
  say "version reported by the factory: ${BV:-no answer}"
fi

sec "VINTF declarations of the effect HAL"
VV=""
for f in /vendor/etc/vintf/manifest.xml /vendor/etc/vintf/manifest_*.xml /vendor/etc/vintf/manifest/*.xml \
         /odm/etc/vintf/manifest.xml /odm/etc/vintf/manifest_*.xml /odm/etc/vintf/manifest/*.xml \
         /vendor/manifest.xml /odm/manifest.xml /apex/*/etc/vintf/*.xml; do
  [ -f "$f" ] || continue
  grep -q "android.hardware.audio.effect" "$f" || continue
  say "--- $f"
  grep -n -B3 -A4 "android.hardware.audio.effect" "$f" >> "$BODY"
  v="$(tr '\n' ' ' < "$f" | sed 's/<!--[^>]*-->//g' | grep -o '<hal format="aidl"[^<]*<name>android.hardware.audio.effect</name>[^/]*<version>[0-9]*' | sed 's/.*<version>//' | head -n 1)"
  [ -n "$v" ] && VV="$VV $v"
done
say "declared AIDL versions:${VV:- none found}"

# ---------------------------------------------------------------- factory
sec "effect factory process"
FPID="$(dumpsys --pid $FACTORY 2>/dev/null | tr -dc '0-9')"
if [ -z "$FPID" ] || [ ! -d "/proc/$FPID" ]; then
  FPID=""
  for p in $(pidof audiohalservice.qti android.hardware.audio.service android.hardware.audio.service-aidl.aoc \
             android.hardware.audio.service-aidl.mediatek android.hardware.audio.service-aidl.example \
             android.hardware.audio.effect.service-aidl.example 2>/dev/null); do
    FPID=$p; break
  done
fi
FEXE=""; NDK=""
if [ -n "$FPID" ]; then
  FEXE="$(readlink /proc/$FPID/exe)"
  say "pid=$FPID"
  say "exe=$FEXE"
  say "cmdline=$(tr '\0' ' ' < /proc/$FPID/cmdline 2>/dev/null)"
  say "context=$(cat /proc/$FPID/attr/current 2>/dev/null)"
  run "ps -o pid,stime,etime,args -p $FPID"
  NDK="$(grep -o 'android\.hardware\.audio\.effect-V[0-9]*-ndk' /proc/$FPID/maps 2>/dev/null | sed 's/.*-V\([0-9]*\)-ndk/\1/' | sort -u | tr '\n' ' ')"
  say "interface libraries loaded: V${NDK:-none (linked in)}"
  say "effect libraries loaded:"
  awk '{ print $NF }' /proc/$FPID/maps 2>/dev/null | grep -E 'soundfx|effect|rv4a|jamesdsp' | sort -u >> "$BODY"
  RC="$(grep -l "$FEXE" /vendor/etc/init/*.rc /odm/etc/init/*.rc /system/etc/init/*.rc /apex/*/etc/*.rc 2>/dev/null | head -n 3)"
  for r in $RC; do
    say "--- init service in $r"
    awk -v exe="$FEXE" '
      /^service / { show = index($0, exe) > 0 }
      /^on / { show = 0 }
      show { print }' "$r" >> "$BODY"
  done
  say "config file names written in the factory and its libraries:"
  { echo "$FEXE"; awk '{ print $NF }' /proc/$FPID/maps 2>/dev/null | grep '\.so$' | grep -E '^/(vendor|odm|apex)/' | grep -iE 'audio|effect' | sort -u; } |
    while read -r b; do
      [ -f "$b" ] || continue
      s="$(tr -c 'A-Za-z0-9_./-' '\n' < "$b" 2>/dev/null | grep -E 'audio_effects[A-Za-z0-9_]*\.(xml|conf)$|extensioneffect|createIdentityWithConfig' | sort -u | tr '\n' ' ')"
      [ -n "$s" ] && say "  $b: $s"
    done
else
  say "no factory process found"
fi
AS="$(pidof audioserver 2>/dev/null)"
say "audioserver pid=$AS"
[ -n "$AS" ] && run "ps -o pid,stime,etime,args -p $AS"
sum "Effect HAL: $KIND, factory reports V${BV:-?}, loads interface V${NDK:-none}, manifests say V${VV:-?}"
sum "Factory: ${FEXE:-not found} (pid ${FPID:-?})"

# ---------------------------------------------------------------- HIDL
if [ $KIND = hidl ]; then
  sec "older (HIDL) effect HAL"
  for p in $(pidof android.hardware.audio.service android.hardware.audio@7.0-service \
             android.hardware.audio@6.0-service vendor.audio-hal 2>/dev/null); do
    say "pid $p: $(readlink /proc/$p/exe)"
    say "  maps a JamesDSP-style engine: $(grep -ciE 'jamesdsp|jdsp' /proc/$p/maps 2>/dev/null) lines"
  done
  say "config names in the legacy effects loader:"
  for b in /vendor/lib64/libeffectsconfig.so /vendor/lib64/libeffects.so /system/lib64/libeffectsconfig.so \
           /system/lib64/libeffects.so; do
    [ -f "$b" ] || continue
    say "  $b: $(tr -c 'A-Za-z0-9_./-' '\n' < "$b" | grep -E 'audio_effects[A-Za-z0-9_]*\.(xml|conf)$|/etc' | sort -u | tr '\n' ' ')"
  done
  run "grep -n -iE 'jdsp|jamesdsp|$UUID' /vendor/etc/audio_effects*.xml /vendor/etc/audio/audio_effects*.xml /vendor/etc/audio/sku_*/audio_effects*.xml /odm/etc/audio_effects*.xml /system/etc/audio_effects*.xml"
  [ -d /data/adb/modules/rv4a_engine ] && run "cat /data/adb/modules/rv4a_engine/module.prop; ls -lZR /data/adb/modules/rv4a_engine/system 2>/dev/null | head -n 60"
fi

# ---------------------------------------------------------------- configs
sec "audio effect configs"
run "ls -dZ /vendor/etc /vendor/etc/audio /vendor/lib64/soundfx /odm/etc /odm/lib64/soundfx /system/etc"
for f in $(ls /odm/etc/audio_effects* /vendor/etc/audio_effects* /vendor/etc/audio/audio_effects* \
             /vendor/etc/audio/sku_*/audio_effects* /system/etc/audio_effects* /product/etc/audio_effects* \
             /system_ext/etc/audio_effects* /apex/*/etc/audio_effects* 2>/dev/null | sort -u); do
  [ -f "$f" ] || continue
  say "--- $f"
  ls -lZ "$f" >> "$BODY" 2>&1
  say "    $(wc -l < "$f") lines, $(grep -c "$(printf '\r')" "$f") with CR, our uuid: $(grep -c "$UUID" "$f")"
  m="$(awk -v p="$f" '$5 == p { print $4 " (" $9 ")" }' /proc/1/mountinfo 2>/dev/null | tr '\n' ' ')"
  [ -n "$m" ] && say "    mounted over by: $m"
  grep -n -iE '<library |<effect |<effectProxy|<libsw|<libhw|rv4a|jdsp|jamesdsp' "$f" | head -n 80 >> "$BODY"
done
run "ls -lZ /vendor/lib64/soundfx /odm/lib64/soundfx"

# ---------------------------------------------------------------- module
sec "this module"
if [ -d "$MOD" ]; then
  say "status: $(cat $MOD/status 2>/dev/null)"
  [ -f "$MOD/disable" ] && say "flag: disabled"
  [ -f "$MOD/remove" ] && say "flag: to be removed"
  run "sed -n 's/^version=//p' $MOD/module.prop"
  run "cat $MOD/install.prop"
  run "cat $MOD/boot.log.1"
  run "cat $MOD/boot.log"
  run "ls -R $MOD | head -n 80"
  run "ls -lZ $MOD/system/vendor/lib64/soundfx"
  for l in "$MOD"/logcat.*.txt; do
    [ -f "$l" ] && { say "--- saved $(basename "$l") (last 200 lines)"; tail -n 200 "$l" >> "$BODY"; }
  done
  sum "Module: $(cat $MOD/status 2>/dev/null)"
else
  say "not installed"
  sum "Module: not installed"
fi
LOADED=no
if [ -n "$FPID" ] && grep -qE 'rv4a_aidl|jamesdsp' /proc/$FPID/maps 2>/dev/null; then LOADED=yes; fi
sum "Engine library loaded by the factory: $LOADED"

# ---------------------------------------------------------------- logs
sec "log lines from the factory process (last 300)"
[ -n "$FPID" ] && logcat -b all -d --pid="$FPID" 2>/dev/null | tail -n 300 >> "$BODY"
sec "matching log lines (last 600)"
logcat -b all -d 2>/dev/null | grep -E "$PATTERN" | tail -n 600 >> "$BODY"
sec "SELinux denials mentioning audio (last 150)"
{ logcat -b all -d 2>/dev/null; dmesg 2>/dev/null; } | grep 'avc: *denied' |
  grep -iE 'audio|rv4a|soundfx|effect|memfd|ashmem|vendor_configs' | tail -n 150 >> "$BODY"
DEN="$({ logcat -b all -d 2>/dev/null; dmesg 2>/dev/null; } | grep 'avc: *denied' | grep -ciE 'rv4a|soundfx|audio_effects')"
sum "SELinux denials naming our files: ${DEN:-0}"

# ---------------------------------------------------------------- framework
sec "audio_flinger: our effect"
AF="$(dumpsys media.audio_flinger 2>/dev/null)"
echo "$AF" | grep -n -iE -B3 -A10 "$UUID|Root(less|ful)ViPER" | head -n 160 >> "$BODY"
INAF=no
echo "$AF" | grep -qiE "$UUID|Root(less|ful)ViPER" && INAF=yes
say "effect lines in the dump: $(echo "$AF" | grep -ciE 'effect')"
sec "effect list as the framework sees it"
dumpsys media.audio_policy 2>/dev/null | grep -n -iE -A2 "$UUID|Root(less|ful)ViPER" | head -n 40 >> "$BODY"
if [ $KIND = aidl ]; then
  sec "factory dump"
  dumpsys $FACTORY 2>/dev/null | head -n 200 >> "$BODY"
fi
sum "Engine seen in audio_flinger (only while the app has it active): $INAF"

# ---------------------------------------------------------------- crashes
sec "recent tombstones"
ls -lt /data/tombstones 2>/dev/null | head -n 8 >> "$BODY"
for t in $(ls -t /data/tombstones/tombstone_[0-9][0-9] 2>/dev/null | head -n 4); do
  if grep -qiE 'audio|effect|rv4a' "$t" 2>/dev/null; then
    say "--- $t (header and first frames)"
    grep -m 1 -E '^Timestamp' "$t" >> "$BODY"
    grep -m 1 -E '^pid:' "$t" >> "$BODY"
    grep -m 1 -E '^signal|^Abort message' "$t" >> "$BODY"
    grep -m 25 -E '^ +#[0-9]+ pc' "$t" >> "$BODY"
  fi
done

sec "mounts seen by init"
grep -E ' /(vendor|odm|system_ext|product)(/| )|/apex/[^ ]*audio' /proc/1/mountinfo 2>/dev/null | head -n 60 >> "$BODY"

{
  echo "RootfulViPER4Android AIDL diagnostics, $(date 2>/dev/null)"
  echo
  cat "$SUM"
  cat "$BODY"
  echo
  echo "end"
} > "$OUT"
rm -f "$BODY" "$SUM"
chmod 0644 "$OUT" 2>/dev/null
echo "Written to $OUT"
echo "Please attach that file to the GitHub issue."
