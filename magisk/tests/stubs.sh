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
