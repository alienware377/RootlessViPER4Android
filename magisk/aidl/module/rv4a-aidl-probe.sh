#!/system/bin/sh
# Read-only probe for the AIDL engine test. Changes nothing on the phone.
#
#   adb push rv4a-aidl-probe.sh /data/local/tmp/
#   adb shell su -c 'sh /data/local/tmp/rv4a-aidl-probe.sh' > rv4a-probe.txt
#
# Collects what decides whether, and which build of, the AIDL engine can be
# installed: the effect HAL version, the factory's process and libraries, the
# config file it reads and the labels on it, and the modules already present.

DIR=${0%/*}
COMMON=""
for c in "$DIR/common/aidl_common.sh" "$DIR/aidl_common.sh"; do [ -f "$c" ] && COMMON="$c"; done

section() { echo; echo "=== $* ==="; }

section "device"
for p in ro.product.model ro.product.device ro.build.fingerprint ro.vendor.build.fingerprint \
         ro.build.version.sdk ro.vendor.build.version.sdk ro.board.platform \
         ro.boot.product.vendor.sku ro.product.cpu.abi; do
  echo "$p=$(getprop $p)"
done
echo "selinux=$(getenforce 2>/dev/null)"
echo "uid=$(id 2>/dev/null)"

section "audio services"
service list 2>/dev/null | grep -i "audio"

section "VINTF declarations of the effect HAL"
for f in /vendor/etc/vintf/manifest.xml /vendor/etc/vintf/manifest_*.xml /vendor/etc/vintf/manifest/*.xml \
         /odm/etc/vintf/manifest.xml /odm/etc/vintf/manifest_*.xml /odm/etc/vintf/manifest/*.xml \
         /vendor/manifest.xml /odm/manifest.xml /apex/*/etc/vintf/*.xml; do
  [ -f "$f" ] || continue
  grep -q "android.hardware.audio.effect" "$f" || continue
  echo "--- $f"
  grep -n -B3 -A4 "android.hardware.audio.effect" "$f"
done
if [ -n "$COMMON" ]; then
  . "$COMMON"
  echo "parsed: $(rv4a_vintf_effect_entries | tr '\n' ';')"
  echo "version: $(rv4a_vintf_effect_version)"
fi

section "effect factory process"
pid="$(dumpsys --pid android.hardware.audio.effect.IFactory/default 2>/dev/null | tr -dc '0-9')"
echo "pid=$pid"
if [ -n "$pid" ]; then
  echo "exe=$(readlink /proc/$pid/exe)"
  echo "cmdline=$(tr '\0' ' ' < /proc/$pid/cmdline)"
  echo "context=$(cat /proc/$pid/attr/current 2>/dev/null)"
  echo "mapped effect libraries:"
  grep -oE "/[^ ]*(soundfx|audio\.effect|effect)[^ ]*\.so" /proc/$pid/maps | sort -u
fi
echo "audioserver=$(pidof audioserver)"

section "candidate configs"
for f in /odm/etc/audio_effects* /vendor/etc/audio/sku_*/audio_effects* /vendor/etc/audio_effects* \
         /vendor/etc/audio/audio_effects* /system/etc/audio_effects* /apex/*/etc/audio_effects*; do
  [ -e "$f" ] && ls -lZ "$f"
done
if [ -n "$COMMON" ] && [ -n "$pid" ]; then
  cfg="$(rv4a_find_config "$(rv4a_factory_apex "$pid")")"
  echo "the factory would read: $cfg"
  case "$cfg" in
    apex:*) f="${cfg#apex:}" ;;
    *) f="$cfg" ;;
  esac
  if [ -n "$f" ] && [ -f "$f" ]; then
    echo "--- head of $f ($(wc -l < "$f") lines, $(wc -c < "$f") bytes)"
    head -n 60 "$f"
    echo "--- libraries and effects sections"
    grep -n "<libraries>\|</libraries>\|<effects>\|</effects>\|f27317f4" "$f"
    grep -c "$(printf '\r')" "$f" | sed 's/^/lines with CR: /'
  fi
fi

section "soundfx"
ls -lZ /vendor/lib64/soundfx /odm/lib64/soundfx /system/lib64/soundfx 2>/dev/null

section "modules"
ls /data/adb/modules 2>/dev/null
for m in /data/adb/modules/*; do
  [ -f "$m/disable" ] && echo "disabled: ${m##*/}"
  [ -f "$m/remove" ] && echo "to be removed: ${m##*/}"
done
[ -d /data/adb/ksu ] && echo "KernelSU: $(ksud -V 2>/dev/null)"
command -v magisk >/dev/null 2>&1 && echo "Magisk: $(magisk -v 2>/dev/null)"
grep -E " /vendor | /vendor/etc| /system " /proc/self/mountinfo | head -20

section "audio_flinger"
dumpsys media.audio_flinger 2>/dev/null | head -n 120

echo
echo "probe done"
