# Device commands as the module's scripts see them, for test_module.sh.
# Sourced after common/aidl_common.sh, so these replace the real ones.
#
# Time is a counter in $CLOCK: sleep advances it, date +%s reads it, so the
# crash loop window is measured the way it is on a phone, without waiting.

sleep() { c="$(cat "$CLOCK" 2>/dev/null)"; echo $(( ${c:-0} + ${1:-1} )) > "$CLOCK"; }
date() { if [ "$1" = +%s ]; then cat "$CLOCK" 2>/dev/null || echo 0; else echo "t=$(cat "$CLOCK" 2>/dev/null)"; fi; }
getprop() { rv4a_getprop "$1"; }
# The real `service check` prints "Service <name>: not found" for a missing
# service, which also contains the word "found".
service() {
  case "$1" in
    check) if [ -n "$NO_FACTORY" ]; then echo "Service $2: not found"; else echo "Service $2: found"; fi ;;
    call) [ -n "$BINDER_V" ] && printf "Result: Parcel(00000000 %08x   '........')\n" "$BINDER_V" ;;
    list) [ -n "$NO_FACTORY" ] || printf '12\t%s: [android.hardware.audio.effect.IFactory]\n' "$RV4A_FACTORY" ;;
  esac
}
lshal() { [ -n "$HIDL" ] && echo "android.hardware.audio.effect@7.0::IEffectsFactory/default"; }
dumpsys() { :; }
ksud() { echo "ksud ${KSUD_VER:-1.0.5}"; }
chcon() { :; }
logcat() { echo "01-01 00:00:00.000 100 100 I RV4A-AIDL: createEffect: test line"; }
# Pops the next pid from a sequence file; the last one repeats.
pop() {
  l="$(head -n 1 "$1")"
  [ "$(wc -l < "$1")" -gt 1 ] && sed -i 1d "$1"
  echo "$l"
}
if [ -n "$SEQ_HOST" ]; then rv4a_factory_pid() { pop "$SEQ_HOST"; }; fi
pidof() { case "$1" in audioserver) pop "$SEQ_AS" ;; *) pop "$SEQ_HOST" ;; esac; }
cmd() { echo "notification: $*" >> "$NOTIFY"; }
mount() { echo "mount $*" >> "$MOUNTS"; }
umount() { echo "umount $*" >> "$UMOUNT"; }
