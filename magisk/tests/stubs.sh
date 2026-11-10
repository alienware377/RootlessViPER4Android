# Device commands as the module's scripts see them, for test_module.sh.
# Sourced before the script under test, so these replace the real ones.

# Properties come from the file in $PROPS, one name=value per line.
getprop() { sed -n "s/^$1=//p" "$PROPS" 2>/dev/null | head -n 1; }

# $AIDL set: the AIDL effect factory is registered.
service() {
  case "$1" in
    list) [ -n "${AIDL:-}" ] &&
            printf '12\tandroid.hardware.audio.effect.IFactory/default: [android.hardware.audio.effect.IFactory]\n'
          return 0 ;;
  esac
}

ksud() { [ -n "${KSUD_VER:-}" ] && echo "ksud $KSUD_VER"; }

# No waiting in tests.
sleep() { :; }

# audioserver's pid on each call, from the lines of $AS_SEQ; the last one
# repeats.
pidof() {
  [ "$1" = audioserver ] && [ -f "${AS_SEQ:-}" ] || return 1
  l="$(head -n 1 "$AS_SEQ")"
  [ "$(wc -l < "$AS_SEQ")" -gt 1 ] && sed -i 1d "$AS_SEQ"
  [ -n "$l" ] && echo "$l"
}

logcat() { [ -f "${LOGCAT:-}" ] && cat "$LOGCAT"; return 0; }
dmesg() { :; }

# Each call is recorded in $KILLED. With $HAL_RESTART set, the process init
# would bring back appears under that pid, with the engine loaded this time.
kill() {
  [ -n "${KILLED:-}" ] && echo "$*" >> "$KILLED"
  for a in "$@"; do pid="$a"; done
  if [ -n "${HAL_RESTART:-}" ] && [ -d "$RV4A_ROOT/proc/$pid" ]; then
    mv "$RV4A_ROOT/proc/$pid" "$RV4A_ROOT/proc/$HAL_RESTART"
    echo "7e00000000-7e00010000 r-xp 00000000 fd:05 99    /vendor/lib64/soundfx/libjamesdsp.so" >> "$RV4A_ROOT/proc/$HAL_RESTART/maps"
  fi
  return 0
}
