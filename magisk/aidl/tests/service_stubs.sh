# Device commands as service.sh sees them, for test_module.sh. Sourced after
# common/aidl_common.sh, so these replace the real ones.
sleep() { :; }
getprop() { rv4a_getprop "$1"; }
service() { [ -n "$NO_FACTORY" ] || echo "Service android.hardware.audio.effect.IFactory/default: found"; }
# Pops the next pid from a sequence file; the last one repeats.
pop() {
  l="$(head -n 1 "$1")"
  [ "$(wc -l < "$1")" -gt 1 ] && sed -i 1d "$1"
  echo "$l"
}
rv4a_factory_pid() { pop "$SEQ_HOST"; }
pidof() { case "$1" in audioserver) pop "$SEQ_AS" ;; *) pop "$SEQ_HOST" ;; esac; }
cmd() { echo "notification: $*" >> "$NOTIFY"; }
umount() { echo "umount $*" >> "$UMOUNT"; }
