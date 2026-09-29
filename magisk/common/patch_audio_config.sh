#!/system/bin/sh
# Registers the effect with the device's audio configuration.
#
# A malformed audio_effects file can crash the audio server on every boot, so
# each generated file is checked before it is left in place; anything that
# fails validation is discarded rather than shipped.

EFFECT_UUID="f27317f4-c984-4de6-9a90-545759495bf2"
EFFECT_TYPE="f98765f4-c321-5de6-9a45-123459495ab2"
# The library name the rest of the ecosystem uses. It was "jamesdsp" here,
# which reads fine but defeats the duplicate protection in both the stock
# JamesDSP module and AML: they skip a config that already greps "jdsp", so
# under the old name the same uuid could be registered twice under two
# different library names. The effect name stays "jamesdsp", as theirs does.
LIB_NAME="jdsp"
EFFECT_NAME="jamesdsp"

# Where the legacy effects loader actually looks, in its own order. The old
# list had six entries and missed /odm and the Qualcomm sku paths, which is
# most Snapdragon devices.
find_configs() {
  for f in \
    /odm/etc/audio_effects.xml \
    /odm/etc/audio_effects.conf \
    /vendor/etc/audio/audio_effects.xml \
    /vendor/etc/audio/audio_effects.conf \
    /vendor/etc/audio_effects.xml \
    /vendor/etc/audio_effects.conf \
    /system/vendor/etc/audio_effects.xml \
    /system/vendor/etc/audio_effects.conf \
    /system_ext/etc/audio_effects.xml \
    /product/etc/audio_effects.xml \
    /system/etc/audio_effects.xml \
    /system/etc/audio_effects.conf
  do
    [ -f "$f" ] && echo "$f"
  done
}

# Magisk mirrors module files from $MODPATH/system, so /vendor/etc/x has to be
# written to $MODPATH/system/vendor/etc/x. Writing to $MODPATH/vendor/etc
# silently produces a module that mounts nothing.
module_path_for() {
  case "$1" in
    /system/*) echo "$MODPATH$1" ;;
    *)         echo "$MODPATH/system$1" ;;
  esac
}

# A config may already carry our lines, because the file we are reading is the
# previous version of this module still mounted over the real one. Reading it
# as-is and then skipping because "it is already present" is what made every
# upgrade ship an empty module. Strip our own lines and patch the result, so
# re-running is idempotent either way.
strip_ours() {
  case "$1" in
    *.conf)
      # A .conf entry is a block, not a line. Dropping the lines that mention
      # us left the block's body and its closing brace behind, which closed
      # the surrounding section early and lost every effect after it: bass
      # boost, the equaliser, the lot. Skip whole blocks instead, and include
      # the old "jamesdsp" library name so a config written by an earlier
      # version is cleaned up rather than accumulated on top of.
      awk -v lib="$LIB_NAME" -v eff="$EFFECT_NAME" '
        skip { if ($0 ~ /^  \}/) skip = 0; next }
        $0 ~ "^  (" lib "|jamesdsp|" eff ") \\{" { skip = 1; next }
        { print }
      ' "$1"
      ;;
    *)
      # XML entries really are one line each, so this is safe here. Anchored
      # to the tags we write so a stream attachment that happens to name the
      # same uuid elsewhere in the file is left alone.
      grep -v "<effect .*uuid=\"$EFFECT_UUID\"" "$1" |
        grep -v "<library name=\"$LIB_NAME\"" |
        grep -v "<library name=\"jamesdsp\"" |
        grep -v "<effect name=\"$EFFECT_NAME\""
      ;;
  esac
}

validate_xml() {
  grep -q "$EFFECT_UUID" "$1" &&
  grep -q "name=\"$LIB_NAME\"" "$1" &&
  grep -q "</audio_effects_conf>" "$1" &&
  grep -q "</libraries>" "$1" &&
  grep -q "</effects>" "$1" &&
  [ "$(wc -c < "$1")" -gt 200 ]
}

validate_conf() {
  grep -q "$EFFECT_UUID" "$1" &&
  grep -q "$LIB_NAME {" "$1" &&
  grep -q "soundfx" "$1" &&
  # Braces must balance, and must never close below zero on the way. Both
  # validators used to check only that our entries were present, so a file
  # with a stray brace that silently truncated the effects list passed.
  awk '{ n += gsub(/{/,"{") - gsub(/}/,"}"); if (n < 0) exit 1 } END { exit (n != 0) }' "$1" &&
  [ "$(wc -c < "$1")" -gt 100 ]
}

# awk sets a flag for each insertion and prints them at the end, so the caller
# can tell the difference between "patched" and "found only one of the two
# sections". The old version inserted each line independently and validated by
# grepping the uuid, which lives on the effect line only - so a file that got
# an effect and no library passed and was shipped.
patch_xml() {
  strip_ours "$1" | awk -v lib="        <library name=\"$LIB_NAME\" path=\"libjamesdsp.so\"/>" \
      -v eff="        <effect name=\"$EFFECT_NAME\" library=\"$LIB_NAME\" uuid=\"$EFFECT_UUID\"/>" '
    { print }
    /<libraries>/ && !l { print lib; l=1 }
    /<effects>/   && !e { print eff; e=1 }
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

patch_audio_config() {
  found=0
  seen=""
  for cfg in $(find_configs); do
    # /vendor is a symlink into /system/vendor on pre-Treble and on Samsung, so
    # two entries in the list above are one file. Patching it twice gives two
    # copies of our entries in one config.
    real="$(readlink -f "$cfg" 2>/dev/null || echo "$cfg")"
    case " $seen " in *" $real "*) continue ;; esac
    seen="$seen $real"

    target="$(module_path_for "$cfg")"
    mkdir -p "$(dirname "$target")"

    # Generated beside the real name and moved into place only once it has
    # passed, so an interrupted install can never leave a half-written config
    # where the audio server will read it.
    tmp="$target.tmp"
    ok=
    case "$cfg" in
      *.xml)  patch_xml  "$cfg" "$tmp" && validate_xml  "$tmp" && ok=1 ;;
      *.conf) patch_conf "$cfg" "$tmp" && validate_conf "$tmp" && ok=1 ;;
    esac

    if [ "$ok" = "1" ]; then
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
      ui_print "- Registered in $cfg"
      # Remember which file we patched and what it looked like underneath. A
      # system update replaces the real config but leaves this module in
      # place, and all three root implementations keep modules across an OTA,
      # so from then on the device would be running a patched copy of the
      # PREVIOUS release's config: effects the update added would vanish and
      # ones it removed would come back pointing at libraries that no longer
      # exist. Nothing else here would notice, because our own entries are
      # still perfectly well formed.
      echo "$cfg $(wc -c < "$cfg") $(wc -l < "$cfg")" > "$MODPATH/.source_config"
      found=1
      # One config is what the loader reads. Patching every candidate we can
      # find multiplies the chance of a duplicate registration for no gain.
      break
    else
      # Better to ship no override than one that stops the audio server booting
      rm -f "$tmp"
      ui_print "! Generated config for $cfg failed validation, skipped"
    fi
  done
  if [ "$found" != "1" ]; then
    ui_print "! No audio config patched; the effect will not load"
    return 1
  fi
  return 0
}

# For AML and PIXAML.
#
# This used to write a file setting AML_LIB_NAME, AML_LIB_PATH, AML_EFFECT_NAME
# and AML_EFFECT_UUID. No version of AML has ever read any of those. Worse,
# AML's loop is "if the module has an aml.sh, source it, otherwise fall back to
# a built-in scan", and its built-in list already covers libjamesdsp.so under
# this exact uuid - so the file we shipped did nothing AND suppressed the one
# thing that would have worked. On any device with AML installed, this module
# registered the effect nowhere and reported success.
#
# AML sources this file in a subshell with patch_cfgs available, after it has
# gathered the device's configs. Its built-in scan only globs the 32-bit
# soundfx directories, which is why we cannot simply delete this file and let
# the scan find us on arm64.
write_effect_fragment() {
  cat > "$MODPATH/aml.sh" << AMLEOF
#!/system/bin/sh
# Sourced by Audio Modification Library while it merges audio mods.
patch_cfgs $EFFECT_NAME $EFFECT_UUID $LIB_NAME /system/lib/soundfx/libjamesdsp.so
AMLEOF
  set_perm "$MODPATH/aml.sh" 0 0 0755
  ui_print "- Wrote aml.sh for the library to merge"
}
