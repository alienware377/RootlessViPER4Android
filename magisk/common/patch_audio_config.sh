#!/system/bin/sh
# Registers the effect with the device's audio configuration.
#
# A malformed audio_effects file can crash the audio server on every boot, so
# each generated file is checked before it is left in place; anything that
# fails validation is discarded rather than shipped.
#
# Device paths are read through $RV4A_ROOT, which is empty on a phone and a
# sample device tree in magisk/tests, so the tests run this same code.

RV4A_ROOT="${RV4A_ROOT:-}"

EFFECT_UUID="f27317f4-c984-4de6-9a90-545759495bf2"
EFFECT_TYPE="f98765f4-c321-5de6-9a45-123459495ab2"
# The library name the rest of the ecosystem uses. AML and the stock JamesDSP
# module skip a config that already names "jdsp", so using the same name
# prevents a duplicate registration. The effect name stays "jamesdsp", as
# theirs does.
LIB_NAME="jdsp"
EFFECT_NAME="jamesdsp"

# install.log in the module folder keeps what the installer printed, so a
# tester can send it after the manager's screen is gone.
say() {
  ui_print "$*"
  [ -n "$MODPATH" ] && echo "$*" >> "$MODPATH/install.log" 2>/dev/null
  return 0
}

# ---------------------------------------------------------------------------
# Which config the effect loader reads
#
# The legacy loader parses exactly one XML file: the first readable,
# well-formed one in its list of folders. A file that does not parse sends it
# on to the next folder; one that does ends the search, even with no effects
# in it. Only when no XML parses at all does it read audio_effects.conf, from
# /vendor/etc and then /system/etc. A patched copy of any other file is never
# read, and the install would still say it registered the engine.
#
# Vendors change the file name and the folders, and both are written into
# the loader itself. Samsung's asks only for audio_effects_sec.xml, Qualcomm
# based ones add /vendor/etc/audio and a _qssi sku folder, Asus adds a
# per-model name. So they are read out of the loader the effect HAL has
# loaded. When it cannot be read, the file AOSP's folders lead to and the one
# the Qualcomm folders lead to are both patched.
# ---------------------------------------------------------------------------

# The effect HAL's process. Only processes with "audio" in their command line
# are looked at: reading every process's memory map takes seconds.
effect_hal_pid() {
  for c in $(grep -l audio "$RV4A_ROOT"/proc/[0-9]*/cmdline 2>/dev/null); do
    p="${c%/cmdline}"
    if grep -q -E 'android\.hardware\.audio\.effect@[0-9.]+-impl\.so|/lib(64)?/libeffects\.so$' \
         "$p/maps" 2>/dev/null; then
      echo "${p##*/}"; return 0
    fi
  done
  return 1
}

# Names and paths written in a binary, one per line.
bin_strings() {
  tr -c 'A-Za-z0-9_./-' '\n' 2>/dev/null < "$RV4A_ROOT$1" | grep -E '^/|audio_effects|_qssi'
}

# Config names a binary asks for. audio_effects_config.xml belongs to the
# AIDL effect HAL, not to this loader.
names_in() {
  bin_strings "$1" | grep -E '^audio_effects[A-Za-z0-9_]*\.xml$' | grep -v config | sort -u
}

has_string() { printf '%s\n' "$LOADER_STRINGS" | grep -qxF "$1"; }

# Sets LOADER_PID, LOADER_BINS (the loader's libraries, as the HAL maps them
# when it can be found, which also catches a 32-bit HAL), LOADER_STRINGS,
# LOADER_NAMES, LOADER_DIRS and LOADER_EXTRA (files loaded on top of the main
# one, which we never patch). LOADER_GUESS is 1 when no loader could be read
# and the names and folders are the usual ones rather than this phone's;
# LOADER_AOSP_DIRS is then AOSP's own list, to try beside LOADER_DIRS.
detect_loader() {
  LOADER_PID="$(effect_hal_pid)"
  LOADER_BINS=""
  if [ -n "$LOADER_PID" ]; then
    LOADER_BINS="$(awk '{ print $NF }' "$RV4A_ROOT/proc/$LOADER_PID/maps" 2>/dev/null |
      grep -E '/libeffectsconfig\.so$|/libeffects\.so$|/android\.hardware\.audio\.effect@[0-9.]+-impl\.so$' |
      sort -u | tr '\n' ' ')"
  fi
  if [ -z "$LOADER_BINS" ]; then
    for d in /vendor/lib64 /vendor/lib /system/lib64 /system/lib; do
      [ -f "$RV4A_ROOT$d/libeffectsconfig.so" ] || continue
      for b in "$RV4A_ROOT$d/libeffectsconfig.so" "$RV4A_ROOT$d/libeffects.so" \
               "$RV4A_ROOT$d"/hw/android.hardware.audio.effect@*-impl.so; do
        [ -f "$b" ] && LOADER_BINS="$LOADER_BINS ${b#"$RV4A_ROOT"}"
      done
      break
    done
  fi
  readable=""
  for b in $LOADER_BINS; do
    [ -r "$RV4A_ROOT$b" ] && readable="$readable $b"
  done
  LOADER_BINS="${readable# }"
  LOADER_STRINGS=""
  for b in $LOADER_BINS; do
    LOADER_STRINGS="$LOADER_STRINGS
$(bin_strings "$b")"
  done
  LOADER_STRINGS="$(printf '%s\n' "$LOADER_STRINGS" | grep -v '^$' | sort -u)"

  LOADER_GUESS=0
  if [ -z "$LOADER_STRINGS" ]; then
    LOADER_GUESS=1
    if [ "$(getprop ro.product.vendor.manufacturer)" = samsung ] ||
       [ "$(getprop ro.product.manufacturer)" = samsung ]; then
      LOADER_NAMES="audio_effects_sec.xml audio_effects.xml"
    else
      LOADER_NAMES="audio_effects.xml"
    fi
  else
    LOADER_NAMES="$(printf '%s\n' "$LOADER_STRINGS" |
      grep -E '^audio_effects[A-Za-z0-9_]*\.xml$' | grep -v config | tr '\n' ' ')"
    LOADER_NAMES="${LOADER_NAMES% }"
    [ -n "$LOADER_NAMES" ] || LOADER_NAMES="audio_effects.xml"
  fi
  LOADER_DIRS="$(loader_dirs | tr '\n' ' ')"
  LOADER_DIRS="${LOADER_DIRS% }"
  LOADER_AOSP_DIRS=""
  if [ "$LOADER_GUESS" = 1 ]; then
    LOADER_AOSP_DIRS="$(loader_dirs aosp | tr '\n' ' ')"
    LOADER_AOSP_DIRS="${LOADER_AOSP_DIRS% }"
  fi
  LOADER_EXTRA="$(printf '%s\n' "$LOADER_STRINGS" |
    grep -E '^/.*/audio_effects[A-Za-z0-9_]*\.xml$' | grep -v config | tr '\n' ' ')"
  LOADER_EXTRA="${LOADER_EXTRA% }"
}

# The folders the loader searches, in its order: AOSP's, plus the ones this
# loader names. When it could not be read (LOADER_GUESS), the two Qualcomm
# adds are included, and "loader_dirs aosp" gives AOSP's alone for the other
# possibility. Where Xiaomi's /odm sku folder and Honor's /odm/etc/audio sit
# in their vendors' lists is not known exactly; both are kept ahead of the
# folders they shadow.
loader_dirs() {
  sku="$(getprop ro.boot.product.vendor.sku)"
  echo /odm/etc
  if [ "$1" = aosp ]; then
    [ -n "$sku" ] && echo "/vendor/etc/audio/sku_$sku"
    echo /vendor/etc
    echo /system/etc
    return 0
  fi
  if [ -n "$sku" ]; then
    if [ "$LOADER_GUESS" != 1 ] && has_string /odm/etc/audio/sku_; then
      echo "/odm/etc/audio/sku_$sku"
    fi
    if [ "$(getprop ro.vendor.qti.va_aosp.support)" = 1 ] &&
       { [ "$LOADER_GUESS" = 1 ] || printf '%s\n' "$LOADER_STRINGS" | grep -q _qssi; }; then
      echo "/vendor/etc/audio/sku_${sku}_qssi"
    fi
    echo "/vendor/etc/audio/sku_$sku"
  fi
  if [ "$LOADER_GUESS" != 1 ] && has_string /odm/etc/audio; then echo /odm/etc/audio; fi
  if [ "$LOADER_GUESS" = 1 ] || has_string /vendor/etc/audio; then echo /vendor/etc/audio; fi
  echo /vendor/etc
  echo /system/etc
}

# A file the loader would take: readable, with its root element opened and
# closed. One that fails to parse sends the loader on to the next folder, so
# it must not end the search here either.
xml_usable() {
  [ -r "$1" ] && grep -q '<audio_effects_conf' "$1" && grep -q '</audio_effects_conf>' "$1"
}

# A device path with its links resolved (/odm/etc/x is /vendor/odm/etc/x where
# /odm links into /vendor), so one file has one name, and its copy goes where
# the manager mounts it.
real_path() {
  rp="$(readlink -f "$RV4A_ROOT$1" 2>/dev/null)"
  rp="${rp#"$RV4A_ROOT"}"
  echo "${rp:-$1}"
}

# The configs to patch, one per line: for each name the loader asks for, the
# first usable file in its folders. Normally that is one file. Only a loader
# naming several (Asus) or one that could not be read gives more: for the
# latter, the first file in AOSP's order and the first in Qualcomm's, which
# differ when both /vendor/etc/audio and /vendor/etc have one. Which of them
# the loader takes cannot be told from outside, and patching a file it
# ignores costs nothing. With no XML at all, the .conf it falls back to.
loader_configs() {
  found=""
  for n in $LOADER_NAMES; do
    for dirs in "$LOADER_DIRS" "$LOADER_AOSP_DIRS"; do
      for d in $dirs; do
        xml_usable "$RV4A_ROOT$d/$n" || continue
        f="$(real_path "$d/$n")"
        case " $found " in *" $f "*) ;; *) found="$found $f"; echo "$f" ;; esac
        break
      done
    done
  done
  [ -n "$found" ] && return 0
  for f in /vendor/etc/audio_effects.conf /system/etc/audio_effects.conf; do
    if [ -r "$RV4A_ROOT$f" ]; then real_path "$f"; return 0; fi
  done
  return 1
}

# "HIDL 7.0" when the effect HAL process has an HIDL implementation loaded.
hidl_version() {
  [ -n "$LOADER_PID" ] || return 0
  v="$(grep -o -E 'android\.hardware\.audio\.effect@[0-9.]+-impl\.so' \
       "$RV4A_ROOT/proc/$LOADER_PID/maps" 2>/dev/null | head -n 1)"
  v="${v#*@}"; v="${v%-impl.so}"
  [ -n "$v" ] && echo "HIDL $v"
  return 0
}

# Active modules other than this one that ship their own copy of a path.
# Only one copy can be mounted, so the two would hide each other.
modules_with_file() {
  for d in "$RV4A_ROOT"/data/adb/modules/*/; do
    d="${d%/}"; id="${d##*/}"
    [ "$id" = rv4a_engine ] && continue
    if [ -f "$d/disable" ] || [ -f "$d/remove" ]; then continue; fi
    for c in "$d/system$1" "$d$1"; do
      if [ -e "$c" ]; then echo "$id"; break; fi
    done
  done
}

# ---------------------------------------------------------------------------
# Patching
# ---------------------------------------------------------------------------

# Magisk mirrors module files from $MODPATH/system, so /vendor/etc/x has to be
# written to $MODPATH/system/vendor/etc/x. Writing to $MODPATH/vendor/etc
# silently produces a module that mounts nothing.
module_path_for() {
  case "$1" in
    /system/*) echo "$MODPATH$1" ;;
    *)         echo "$MODPATH/system$1" ;;
  esac
}

# The entries this module writes, and the ones earlier versions wrote (which
# named the library "jamesdsp"), without their indentation or line ending:
# one line each in XML, a block in a .conf with its lines joined by \n.
# post-fs-data.sh has a copy of these; the two must stay the same.
XML_LIB="<library name=\"$LIB_NAME\" path=\"libjamesdsp.so\"/>"
XML_EFF="<effect name=\"$EFFECT_NAME\" library=\"$LIB_NAME\" uuid=\"$EFFECT_UUID\"/>"
XML_OLD="<library name=\"jamesdsp\" path=\"libjamesdsp.so\"/>|<effect name=\"$EFFECT_NAME\" library=\"jamesdsp\" uuid=\"$EFFECT_UUID\"/>"
CONF_LIB="$LIB_NAME {\npath /system/lib/soundfx/libjamesdsp.so\n}"
CONF_EFF="$EFFECT_NAME {\nlibrary $LIB_NAME\nuuid $EFFECT_UUID\n}"
CONF_OLD="jamesdsp {\npath /system/lib/soundfx/libjamesdsp.so\n}|$EFFECT_NAME {\nlibrary jamesdsp\nuuid $EFFECT_UUID\n}"

# Prints a config without the entries in $2 (separated by |). An entry only
# matches whole, line for line, with indentation and line endings ignored;
# every other line is printed exactly as it was. A line that merely mentions
# our names, or carries anything else besides our entry, is kept, so nothing
# of somebody else's goes with it. With a third argument, fails unless each
# entry was found exactly once.
drop_entries() {
  awk -v entries="$2" -v once="${3:-}" '
    function bare(s) { sub(/^[ \t]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
    { raw[NR] = $0; t[NR] = bare($0) }
    END {
      n = split(entries, e, "|")
      for (x = 1; x <= n; x++) {
        len[x] = split(e[x], part, "\n")
        for (y = 1; y <= len[x]; y++) want[x, y] = part[y]
      }
      i = 1
      while (i <= NR) {
        hit = 0
        for (x = 1; x <= n && !hit; x++) {
          hit = x
          for (y = 1; y <= len[x]; y++) if (t[i + y - 1] != want[x, y]) { hit = 0; break }
        }
        if (hit) { seen[hit]++; i += len[hit] } else { print raw[i]; i++ }
      }
      if (once) for (x = 1; x <= n; x++) if (seen[x] != 1) exit 1
    }
  ' "$1"
}

# A config may already carry our entries, because the file we are reading is
# the previous version of this module still mounted over the real one. Take
# them out and patch the result, so re-running is idempotent either way.
# A .conf entry is a whole block: leaving its closing brace behind would end
# the surrounding section early and lose every effect after it.
# post-fs-data.sh has a copy of this; the two must stay the same.
strip_ours() {
  case "$1" in
    *.conf) drop_entries "$1" "$CONF_LIB|$CONF_EFF|$CONF_OLD" ;;
    *)      drop_entries "$1" "$XML_LIB|$XML_EFF|$XML_OLD" ;;
  esac
}

# Checks a generated XML config. With the original as a second argument it
# also checks that the copy is exactly the original, less entries an earlier
# version of this module left in it, plus our two lines once each, and that
# those sit inside <libraries> and <effects> rather than in a comment or
# after the root.
validate_xml() {
  grep -q "$EFFECT_UUID" "$1" &&
  grep -q "name=\"$LIB_NAME\"" "$1" &&
  grep -q "</audio_effects_conf>" "$1" &&
  grep -q "</libraries>" "$1" &&
  grep -q "</effects>" "$1" &&
  [ "$(wc -c < "$1")" -gt 200 ] || return 1
  [ -n "$2" ] || return 0
  strip_ours "$2" > "$1.orig"
  drop_entries "$1" "$XML_LIB|$XML_EFF" once > "$1.ours"; found=$?
  cmp -s "$1.orig" "$1.ours"; same=$?
  rm -f "$1.orig" "$1.ours"
  [ "$found" = 0 ] && [ "$same" = 0 ] || return 1
  [ "$(grep -c "<library name=\"$LIB_NAME\"" "$1")" = 1 ] || return 1
  [ "$(grep -c "uuid=\"$EFFECT_UUID\"" "$1")" = 1 ] || return 1
  awk -v lib="<library name=\"$LIB_NAME\"" -v eff="uuid=\"$EFFECT_UUID\"" '
    function visible(line,   out, k) {
      out = ""
      while (1) {
        if (incomment) { k = index(line, "-->"); if (!k) return out; incomment = 0; line = substr(line, k + 3) }
        else { k = index(line, "<!--"); if (!k) return out line; out = out substr(line, 1, k - 1); incomment = 1; line = substr(line, k + 4) }
      }
    }
    {
      v = visible($0)
      if (index(v, "<libraries>") && !lo) lo = NR
      if (index(v, "</libraries>") && !lc) lc = NR
      if (index(v, "<effects>") && !eo) eo = NR
      if (index(v, "</effects>") && !ec) ec = NR
      if (index(v, lib)) ll = NR
      if (index(v, eff)) el = NR
      if (index(v, "</audio_effects_conf>")) root = NR
    }
    END { exit !(lo && lo < ll && ll < lc && eo && eo < el && el < ec && ec < root) }
  ' "$1"
}

# The same for a .conf: with the original, the copy must be it plus our two
# blocks, each placed first in its own section.
validate_conf() {
  grep -q "$EFFECT_UUID" "$1" &&
  grep -q "$LIB_NAME {" "$1" &&
  grep -q "soundfx" "$1" &&
  # Braces must balance, and must never close below zero on the way: a stray
  # brace would silently truncate the effects list. Bracketed, because a bare
  # { is not a valid pattern to busybox awk, which is the awk every root
  # manager runs this with: it fails every file instead.
  awk '{ n += gsub(/[{]/, "{") - gsub(/[}]/, "}"); if (n < 0) exit 1 } END { exit (n != 0) }' "$1" &&
  [ "$(wc -c < "$1")" -gt 100 ] || return 1
  [ -n "$2" ] || return 0
  strip_ours "$2" > "$1.orig"
  drop_entries "$1" "$CONF_LIB|$CONF_EFF" once > "$1.ours"; found=$?
  cmp -s "$1.orig" "$1.ours"; same=$?
  rm -f "$1.orig" "$1.ours"
  [ "$found" = 0 ] && [ "$same" = 0 ] || return 1
  awk -v lib="$LIB_NAME {" -v eff="$EFFECT_NAME {" '
    { t = $0; sub(/^[ \t]+/, "", t); sub(/[ \t\r]+$/, "", t) }
    t == lib && prev ~ /^libraries[ \t]*[{]/ { l++ }
    t == eff && prev ~ /^effects[ \t]*[{]/ { e++ }
    { prev = $0 }
    END { exit !(l == 1 && e == 1) }
  ' "$1"
}

# Two whole lines are added, one before </libraries> and one before
# </effects>, indented like their neighbours and ending the way those end:
# some vendor configs use CRLF, some LF, and a few mix both. Every other byte
# is kept. A closing tag inside a comment, or sharing its line with anything
# else, is not used, so a config laid out some other way is refused (exit 3)
# rather than given our lines in the wrong place.
patch_xml() {
  strip_ours "$1" | awk -v lib="$XML_LIB" -v eff="$XML_EFF" '
    {
      line = $0; cr = ""; core = line
      if (substr(line, length(line), 1) == "\r") { cr = "\r"; core = substr(line, 1, length(line) - 1) }
      if (!incomment && !l && core ~ /^[ \t]*<\/libraries>[ \t]*$/) {
        ind = core; sub(/<.*/, "", ind); print ind "    " lib cr; l = 1
      }
      if (!incomment && !e && core ~ /^[ \t]*<\/effects>[ \t]*$/) {
        ind = core; sub(/<.*/, "", ind); print ind "    " eff cr; e = 1
      }
      print line
      s = core
      while (1) {
        if (incomment) { k = index(s, "-->"); if (!k) break; incomment = 0; s = substr(s, k + 3) }
        else { k = index(s, "<!--"); if (!k) break; incomment = 1; s = substr(s, k + 4) }
      }
    }
    END { if (!l || !e) exit 3 }
  ' > "$2"
}

# The path here MUST stay "/lib/soundfx/" and must never be changed to lib64.
# AOSP checkLibraryPath() requires that literal substring, rejects the entry
# without it, and then rebuilds the path itself from a root that is already
# lib64 under __LP64__. Writing lib64 breaks the load on every device, 32-bit
# and 64-bit alike. The stock JamesDSP module writes the same 32-bit form.
patch_conf() {
  strip_ours "$1" | awk -v lib="  $LIB_NAME {\n    path /system/lib/soundfx/libjamesdsp.so\n  }" \
      -v eff="  $EFFECT_NAME {\n    library $LIB_NAME\n    uuid $EFFECT_UUID\n  }" '
    { print }
    /^libraries[[:space:]]*\{/ && !l { printf "%s\n", lib; l=1 }
    /^effects[[:space:]]*\{/   && !e { printf "%s\n", eff; e=1 }
    END { if (!l || !e) exit 3 }
  ' > "$2"
}

# Size and line count of a config with our own lines left out, so the same
# device file gives the same answer whether or not our copy is mounted over
# it. post-fs-data.sh compares these on every boot.
config_sig() {
  echo "$(strip_ours "$1" | wc -c | tr -dc '0-9') $(strip_ours "$1" | wc -l | tr -dc '0-9')"
}

# The vendor and system builds, read from their build.prop files rather than
# with getprop, because modules that hide root often change the fingerprint
# properties. A system update changes at least one of them. Empty when
# neither can be read. post-fs-data.sh has a copy of this; the two must stay
# the same.
build_id() {
  v="$(sed -n 's/^ro\.vendor\.build\.fingerprint=//p' "$RV4A_ROOT/vendor/build.prop" 2>/dev/null | head -n 1)"
  s="$(sed -n 's/^ro\.system\.build\.fingerprint=//p; s/^ro\.build\.fingerprint=//p' \
       "$RV4A_ROOT/system/build.prop" 2>/dev/null | head -n 1)"
  [ -n "$v$s" ] && echo "${v:-unknown} ${s:-unknown}"
  return 0
}

# Patches every file in $CONFIGS (one device path per line, from
# loader_configs). All of them or none: a file left unpatched may be the one
# the loader reads.
patch_audio_config() {
  if [ -z "$CONFIGS" ]; then
    say "! No audio config to patch; the effect will not load"
    return 1
  fi
  old_mod="$RV4A_ROOT/data/adb/modules/rv4a_engine"
  old_build="$(sed -n 's/^#build //p' "$old_mod/.source_config" 2>/dev/null | head -n 1)"
  now_build="$(build_id)"
  : > "$MODPATH/.source_config"
  for cfg in $CONFIGS; do
    src="$RV4A_ROOT$cfg"
    # Our own entries are taken out first, so anything left that names the
    # effect or its library is somebody else's, or ours edited by hand or
    # commented out. Either way it is not ours to remove.
    if strip_ours "$src" | grep -q -e "$EFFECT_UUID" -e 'libjamesdsp\.so'; then
      say "! $cfg already lists this engine, in an entry this"
      say "  module did not write. Another JamesDSP-based mod, or"
      say "  the ROM itself, provides it, and only one of them can."
      return 1
    fi
    # What is read here may be the installed module's copy, mounted over the
    # real file. Made before a system update, it is the old release's list,
    # and a copy made from it would carry that on. The boot check records
    # the build once it has seen the real file unchanged, so a build that
    # still differs means it never could: APatch with a metamodule mounts
    # before the check runs. That boot's check stops the mount for the next.
    case "$cfg" in /system/*) oc="$old_mod$cfg" ;; *) oc="$old_mod/system$cfg" ;; esac
    if [ -n "$old_build" ] && [ -n "$now_build" ] && [ "$old_build" != "$now_build" ] &&
       cmp -s "$src" "$oc"; then
      say "! $cfg is still the copy the installed"
      say "  module made before a system update, so a new one cannot"
      say "  be made from it. Reboot once, which takes the old copy"
      say "  out of use, then install this again."
      return 1
    fi
    target="$(module_path_for "$cfg")"
    mkdir -p "$(dirname "$target")"

    # Generated beside the real name and moved into place only once it has
    # passed, so an interrupted install can never leave a half-written config
    # where the audio server will read it.
    tmp="$target.tmp"
    ok=
    case "$cfg" in
      *.xml)  patch_xml  "$src" "$tmp" && validate_xml  "$tmp" "$src" && ok=1 ;;
      *.conf) patch_conf "$src" "$tmp" && validate_conf "$tmp" "$src" && ok=1 ;;
    esac

    if [ "$ok" != "1" ]; then
      # Better to ship no override than one that stops the audio server booting
      rm -f "$tmp"
      say "! Could not patch $cfg safely (an unusual layout); nothing was changed"
      return 1
    fi
    mv -f "$tmp" "$target"
    # Config files under vendor carry a different label from system ones, and
    # an unreadable config is indistinguishable from a corrupt one to the
    # audio server. Only set it where the type is known to exist.
    case "$target" in
      */system/vendor/*|*/system/odm/*)
        [ "${API:-0}" -ge 29 ] &&
          set_perm "$target" 0 0 0644 u:object_r:vendor_configs_file:s0 ||
          set_perm "$target" 0 0 0644
        ;;
      *) set_perm "$target" 0 0 0644 ;;
    esac
    crs="$(grep -c "$(printf '\r')" "$target")"
    if [ "$crs" -gt 0 ]; then
      say "- Registered in $cfg (CRLF line endings kept)"
    else
      say "- Registered in $cfg"
    fi
    # Remember which files we patched and what they looked like underneath. A
    # system update replaces the real config but leaves this module in
    # place, and all three root implementations keep modules across an OTA,
    # so from then on the device would be running a patched copy of the
    # PREVIOUS release's config: effects the update added would vanish and
    # ones it removed would come back pointing at libraries that no longer
    # exist. Nothing else here would notice, because our own entries are
    # still perfectly well formed. One line per file: path, size, lines.
    echo "$cfg $(config_sig "$src")" >> "$MODPATH/.source_config"
  done
  # And the build, for when the boot check cannot read the real file.
  [ -n "$now_build" ] && echo "#build $now_build" >> "$MODPATH/.source_config"
  return 0
}

# For AML and PIXAML.
#
# AML sources a module's aml.sh instead of running its built-in scan, so this
# file must do the registration itself. It is sourced in a subshell with
# patch_cfgs available, after AML has gathered the device's configs. The
# built-in scan only globs the 32-bit soundfx directories, so on arm64 it would
# not find us anyway.
write_effect_fragment() {
  cat > "$MODPATH/aml.sh" << AMLEOF
#!/system/bin/sh
# Sourced by Audio Modification Library while it merges audio mods.
patch_cfgs $EFFECT_NAME $EFFECT_UUID $LIB_NAME /system/lib/soundfx/libjamesdsp.so
AMLEOF
  set_perm "$MODPATH/aml.sh" 0 0 0755
  say "- Wrote aml.sh for the library to merge"
}
