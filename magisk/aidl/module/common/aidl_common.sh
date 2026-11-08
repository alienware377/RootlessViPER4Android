#!/system/bin/sh
# Shared by customize.sh, the boot scripts and action.sh of the experimental
# AIDL module, and by the host-side tests in magisk/aidl/tests.
#
# Everything here reads the device through $RV4A_ROOT (empty on a device, a
# fixture tree in the tests), $RV4A_PROC and rv4a_getprop, so the same
# functions that run on a phone run against sample files on a PC.
#
# Written for POSIX sh with the toybox/busybox tools Magisk, KernelSU and
# APatch provide: sh, awk, sed, grep, tr, cmp, sha1sum or md5sum.

RV4A_ROOT="${RV4A_ROOT:-}"
RV4A_PROC="${RV4A_PROC:-/proc}"
RV4A_MODID="rv4a_engine_aidl"
RV4A_UUID="f27317f4-c984-4de6-9a90-545759495bf2"
RV4A_TYPE="f98765f4-c321-5de6-9a45-123459495ab2"
RV4A_LIBNAME="rv4a_aidl"
RV4A_SUPPORTED_VERSIONS="2 3 4"
RV4A_FACTORY="android.hardware.audio.effect.IFactory/default"
# Flag file that allows creating a config on phones that ship none (Pixel).
RV4A_CREATE_FLAG="/data/local/tmp/rv4a-aidl-pixel"
# What the boot scripts keep from logcat: our plugin, the factory and the
# framework side of the effect HAL, the linker and SELinux.
RV4A_LOG_PATTERN='RV4A|RootlessViPER|rv4a_aidl|EffectHalAidl|EffectsFactoryHalAidl|EffectConfig|EffectFactory|AHAL_|TimeCheck|memfd|ashmem|Cannot do a user transaction|linker|dlopen|avc: +denied.*(audio|rv4a|soundfx)'

rv4a_getprop() {
  if [ -n "$RV4A_PROPS" ]; then
    sed -n "s/^$1=//p" "$RV4A_PROPS" | head -n 1
  else
    getprop "$1"
  fi
}

# ---------------------------------------------------------------------------
# What the effect HAL looks like from the outside
# ---------------------------------------------------------------------------

# "found" only for a registered service. `service check` prints
# "Service <name>: not found" otherwise, so the word alone is not enough.
rv4a_service_registered() {
  service check "$1" 2>/dev/null | grep -q ": found"
}

# aidl, hidl or none. The framework only uses the AIDL effect HAL when the
# factory is registered; otherwise it falls back to HIDL.
rv4a_hal_kind() {
  if rv4a_service_registered "$RV4A_FACTORY"; then echo aidl; return; fi
  if lshal 2>/dev/null | grep -q "IEffectsFactory"; then echo hidl; return; fi
  echo none
}

# The version the running factory reports for itself over binder: the
# interface version its code was generated from, which is what decides the
# layout of what it exchanges with a plugin. getInterfaceVersion is
# transaction 16777215 in every AIDL interface.
rv4a_binder_version() {
  out="$(service call "$RV4A_FACTORY" 16777215 2>/dev/null | head -n 1)"
  st="$(echo "$out" | sed -n 's/.*Parcel(\([0-9a-fA-F]\{8\}\) \([0-9a-fA-F]\{8\}\).*/\1/p')"
  hv="$(echo "$out" | sed -n 's/.*Parcel(\([0-9a-fA-F]\{8\}\) \([0-9a-fA-F]\{8\}\).*/\2/p')"
  [ "$st" = 00000000 ] && [ -n "$hv" ] || return 0
  v="$(printf '%d' "0x$hv" 2>/dev/null)"
  case "$v" in ''|*[!0-9]*) return 0 ;; esac
  [ "$v" -ge 1 ] && [ "$v" -le 99 ] && echo "$v"
}

# ---------------------------------------------------------------------------
# VINTF: what the vendor declares. An AIDL entry without <version> means 1.
# ---------------------------------------------------------------------------
rv4a_vintf_files() {
  for f in "$RV4A_ROOT"/vendor/etc/vintf/manifest.xml \
           "$RV4A_ROOT"/vendor/etc/vintf/manifest_*.xml \
           "$RV4A_ROOT"/vendor/etc/vintf/manifest/*.xml \
           "$RV4A_ROOT"/odm/etc/vintf/manifest.xml \
           "$RV4A_ROOT"/odm/etc/vintf/manifest_*.xml \
           "$RV4A_ROOT"/odm/etc/vintf/manifest/*.xml \
           "$RV4A_ROOT"/vendor/manifest.xml \
           "$RV4A_ROOT"/odm/manifest.xml \
           "$RV4A_ROOT"/apex/*/etc/vintf/*.xml; do
    [ -f "$f" ] && echo "$f"
  done
}

# Prints "<version> <file>" for every AIDL declaration of the effect HAL.
rv4a_vintf_effect_entries() {
  rv4a_vintf_files | while read -r f; do
    awk -v file="$f" '
      { buf = buf " " $0 }
      END {
        # Comments first: a commented-out <hal> block is not a declaration.
        while ((i = index(buf, "<!--")) > 0) {
          rest = substr(buf, i + 4)
          j = index(rest, "-->")
          if (j == 0) { buf = substr(buf, 1, i - 1); break }
          buf = substr(buf, 1, i - 1) substr(rest, j + 3)
        }
        s = buf
        while ((i = index(s, "<hal")) > 0) {
          s = substr(s, i + 4)
          j = index(s, "</hal>")
          if (j == 0) break
          h = substr(s, 1, j - 1)
          s = substr(s, j + 6)
          if (h !~ /format="aidl"/) continue
          if (h !~ /<name>[ \t]*android\.hardware\.audio\.effect[ \t]*<\/name>/) continue
          v = 1
          if (match(h, /<version>[ \t]*[0-9]+/)) {
            t = substr(h, RSTART, RLENGTH)
            gsub(/[^0-9]/, "", t)
            v = t + 0
          }
          print v " " file
        }
      }' "$f"
  done
}

# Prints the version when every declaration agrees, nothing otherwise.
rv4a_vintf_effect_version() {
  rv4a_vintf_effect_entries | awk '{ print $1 }' | sort -u | awk '
    { n++; v = $1 } END { if (n == 1) print v }'
}

# ---------------------------------------------------------------------------
# The factory's process. It registers IFactory/default, and on Qualcomm,
# MediaTek and Pixel it is the same process as the core audio HAL, so if our
# library takes it down every sound on the device stops with it.
# ---------------------------------------------------------------------------
rv4a_factory_pid() {
  # Tests set this; "none" stands for a factory that could not be found.
  if [ -n "$RV4A_FACTORY_PID" ]; then
    [ "$RV4A_FACTORY_PID" = none ] || echo "$RV4A_FACTORY_PID"
    return
  fi
  p="$(dumpsys --pid "$RV4A_FACTORY" 2>/dev/null | tr -dc '0-9')"
  if [ -n "$p" ] && [ -d "$RV4A_PROC/$p" ]; then echo "$p"; return; fi
  # Older dumpsys without --pid: a process that maps an effect HAL interface
  # library. audioserver maps one too (it is the client), so it is skipped.
  for m in "$RV4A_PROC"/[0-9]*/maps; do
    d="${m%/maps}"
    case "$(readlink "$d/exe" 2>/dev/null)" in */audioserver) continue ;; esac
    if grep -q "android\.hardware\.audio\.effect-V[0-9]*-ndk" "$m" 2>/dev/null; then
      echo "${d##*/}"; return
    fi
  done
}

# Interface library versions mapped by a process, e.g. "3". Empty when the
# factory links its interface code statically.
rv4a_factory_ndk_versions() {
  grep -o "android\.hardware\.audio\.effect-V[0-9]*-ndk" "$RV4A_PROC/$1/maps" 2>/dev/null |
    sed 's/.*-V\([0-9]*\)-ndk/\1/' | sort -u | tr '\n' ' ' | sed 's/ $//'
}

rv4a_factory_exe() { readlink "$RV4A_PROC/$1/exe" 2>/dev/null; }

# Name of the APEX the factory runs from, if any.
rv4a_factory_apex() {
  exe="$(rv4a_factory_exe "$1")"
  case "$exe" in
    /apex/*) n="${exe#/apex/}"; n="${n%%/*}"; echo "${n%%@*}" ;;
  esac
}

# The factory's executable and the vendor libraries it has loaded that could
# hold its effect code: those are where the config file name is written.
rv4a_factory_binaries() {
  exe="$(rv4a_factory_exe "$1")"
  [ -n "$exe" ] && echo "$exe"
  awk '{ print $NF }' "$RV4A_PROC/$1/maps" 2>/dev/null | grep '\.so$' |
    grep -E '^/(vendor|odm|apex)/' | grep -iE 'audio|effect' | sort -u | head -n 40
  [ -f "$RV4A_ROOT/vendor/lib64/libeffectsconfig.so" ] && echo /vendor/lib64/libeffectsconfig.so
}

# Every audio effects config name or path written in those binaries, one per
# line, e.g. "audio_effects_config.xml" or "/vendor/etc/audio_effects_config.xml".
rv4a_factory_config_strings() {
  rv4a_factory_binaries "$1" | while read -r b; do
    [ -f "$RV4A_ROOT$b" ] || continue
    tr -c 'A-Za-z0-9_./-' '\n' < "$RV4A_ROOT$b" 2>/dev/null | grep -E 'audio_effects[A-Za-z0-9_]*\.xml$'
  done | sort -u
}

# ---------------------------------------------------------------------------
# Choosing the effect HAL version to build for.
#
# rv4a_choose_version <binder> <mapped versions> <manifest version>
# Prints "<version> <source>", "conflict <reason>" or "none". The factory's
# own answer over binder wins, then the interface library it has loaded, then
# the manifest: on Qualcomm the manifest has been seen to disagree with what
# the factory was built against, and the layout follows the build.
# ---------------------------------------------------------------------------
rv4a_choose_version() {
  b="$1"; m="$2"; v="$3"
  mc=0; for x in $m; do mc=$((mc + 1)); done
  if [ -n "$b" ]; then
    if [ "$mc" -ge 1 ]; then
      case " $m " in
        *" $b "*) ;;
        *) echo "conflict the factory answers V$b but has the V$m interface library loaded"; return ;;
      esac
    fi
    echo "$b binder"; return
  fi
  if [ "$mc" = 1 ]; then echo "$m mapped"; return; fi
  if [ "$mc" -gt 1 ]; then echo "conflict the factory has several interface versions loaded ($m)"; return; fi
  if [ -n "$v" ]; then echo "$v manifest"; return; fi
  echo none
}

# ---------------------------------------------------------------------------
# The config files.
#
# AOSP's factory reads the first of: its own APEX's etc/, /odm/etc,
# /vendor/etc/audio/sku_<ro.boot.product.vendor.sku>, /vendor/etc,
# /system/etc. Vendors move this around (Samsung adds _sec files, Qualcomm a
# _stub file in stub mode, Pixel builds the path into its own factory), so the
# module adds its lines to every copy the factory could plausibly read: a
# file the factory ignores is harmless, a missed one means no effect.
# ---------------------------------------------------------------------------

# Config file names this factory may read.
rv4a_config_names() {
  {
    echo audio_effects_config.xml
    rv4a_factory_config_strings "$1" | sed 's|.*/||' | grep -E '^audio_effects_config[A-Za-z0-9_]*\.xml$'
  } | sort -u | while read -r n; do
    case "$n" in
      *stub*) [ "$(rv4a_getprop vendor.audio.hal.stubmode)" = true ] && echo "$n" ;;
      *) echo "$n" ;;
    esac
  done
}

rv4a_config_dirs() {
  apex="$1"
  [ -n "$apex" ] && echo "/apex/$apex/etc"
  echo /odm/etc
  sku="$(rv4a_getprop ro.boot.product.vendor.sku)"
  [ -n "$sku" ] && echo "/vendor/etc/audio/sku_$sku"
  for d in "$RV4A_ROOT"/vendor/etc/audio/sku_*; do
    [ -d "$d" ] && echo "${d#$RV4A_ROOT}"
  done
  echo /vendor/etc/audio
  echo /vendor/etc
  echo /system/etc
}

# The one file AOSP's order says the factory reads. Empty when there is none.
rv4a_primary_config() {
  apex="$1"
  if [ -n "$apex" ] && [ -f "$RV4A_ROOT/apex/$apex/etc/audio_effects_config.xml" ]; then
    echo "/apex/$apex/etc/audio_effects_config.xml"; return
  fi
  sku="$(rv4a_getprop ro.boot.product.vendor.sku)"
  for d in /odm/etc ${sku:+/vendor/etc/audio/sku_$sku} /vendor/etc /system/etc; do
    if [ -r "$RV4A_ROOT$d/audio_effects_config.xml" ]; then
      echo "$d/audio_effects_config.xml"; return
    fi
  done
}

# Every existing config the factory may read, one path per line, each file
# once even when it is reachable through a symlink (/odm -> /vendor/odm).
rv4a_candidate_configs() {
  apex="$1"; names="$2"
  rv4a_config_dirs "$apex" | while read -r d; do
    for n in $names; do
      f="$d/$n"
      [ -f "$RV4A_ROOT$f" ] || continue
      r="$(readlink -f "$RV4A_ROOT$f" 2>/dev/null)"; r="${r#$RV4A_ROOT}"
      echo "${r:-$f}"
    done
  done | awk '!seen[$0]++'
}

# Where the factory looks when the phone ships no config at all: the path
# written into the factory itself (Pixel), as long as it does not exist.
rv4a_builtin_config_path() {
  rv4a_factory_config_strings "$1" | grep -E '^/vendor/etc/audio_effects_config\.xml$' | head -n 1
}

# ---------------------------------------------------------------------------
# Patching. Two whole lines are added, one before </libraries> and one before
# </effects>, each with the line ending its neighbour uses. Every other byte
# is kept. A file whose closing tags do not sit on lines of their own is
# refused rather than rewritten.
# ---------------------------------------------------------------------------
rv4a_lib_line() { echo "<library name=\"$RV4A_LIBNAME\" path=\"$1\"/>"; }
rv4a_effect_line() {
  echo "<effect name=\"$RV4A_LIBNAME\" library=\"$RV4A_LIBNAME\" uuid=\"$RV4A_UUID\" type=\"$RV4A_TYPE\"/>"
}

# The file with our lines removed. Our lines are identified by our library
# name only, so nothing that belongs to the device can ever match.
rv4a_strip() {
  awk '
    index($0, "<library name=\"rv4a_aidl\"") { next }
    index($0, "library=\"rv4a_aidl\"") { next }
    { print }' "$1"
}

# A fingerprint of a config with our lines removed, so the same device file
# gives the same answer whether or not our copy is mounted over it.
rv4a_config_sig() {
  if command -v sha1sum >/dev/null 2>&1; then
    rv4a_strip "$1" | sha1sum | awk '{ print $1 }'
  else
    rv4a_strip "$1" | md5sum | awk '{ print $1 }'
  fi
}

# rv4a_patch <original> <output> <library file name>
# Returns 0 on success, 3 when the file's layout is not one we patch.
rv4a_patch() {
  rv4a_strip "$1" | awk -v lib="$(rv4a_lib_line "$3")" -v eff="$(rv4a_effect_line)" '
    {
      line = $0
      cr = ""
      core = line
      if (substr(line, length(line), 1) == "\r") { cr = "\r"; core = substr(line, 1, length(line) - 1) }
      if (!incomment && !donelib && core ~ /^[ \t]*<\/libraries>[ \t]*$/) {
        ind = core; sub(/<.*/, "", ind)
        print ind "    " lib cr
        donelib = 1
      }
      if (!incomment && !doneeff && core ~ /^[ \t]*<\/effects>[ \t]*$/) {
        ind = core; sub(/<.*/, "", ind)
        print ind "    " eff cr
        doneeff = 1
      }
      print line
      # Track comments so a closing tag inside one is never used.
      s = core
      while (1) {
        if (incomment) { k = index(s, "-->"); if (!k) break; incomment = 0; s = substr(s, k + 3) }
        else { k = index(s, "<!--"); if (!k) break; incomment = 1; s = substr(s, k + 4) }
      }
    }
    END { if (!donelib || !doneeff) exit 3 }' > "$2"
}

# A whole config for a phone that ships none: only our library and effect.
rv4a_minimal_config() {
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<audio_effects_conf version="2.0" xmlns="http://schemas.android.com/audio/audio_effects_conf/v2_0">
    <libraries>
        $(rv4a_lib_line "$1")
    </libraries>
    <effects>
        $(rv4a_effect_line)
    </effects>
</audio_effects_conf>
EOF
}

# rv4a_validate <original> <patched> <library file name>
# Prints the first problem found and returns 1, or returns 0.
rv4a_validate() {
  orig="$1"; out="$2"; lib="$3"
  [ -s "$out" ] || { echo "patched config is empty"; return 1; }
  # Everything that was there is still there, unchanged and in order.
  t1="$out.orig.$$"; t2="$out.strip.$$"
  rv4a_strip "$orig" > "$t1"; rv4a_strip "$out" > "$t2"
  if ! cmp -s "$t1" "$t2"; then rm -f "$t1" "$t2"; echo "the original content was altered"; return 1; fi
  rm -f "$t1" "$t2"
  n="$(grep -c "<library name=\"$RV4A_LIBNAME\" path=\"$lib\"/>" "$out")"
  [ "$n" = 1 ] || { echo "library line present $n times"; return 1; }
  n="$(grep -c "uuid=\"$RV4A_UUID\"" "$out")"
  [ "$n" = 1 ] || { echo "our uuid present $n times"; return 1; }
  # Our lines sit inside the sections they belong to. Text inside comments
  # is ignored, as the factory's XML parser ignores it.
  awk -v lib="<library name=\"$RV4A_LIBNAME\"" -v eff="library=\"$RV4A_LIBNAME\"" '
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
      if (index(v, "</audio_effects_conf>")) root = 1
    }
    END { exit !(lo && lo < ll && ll < lc && eo && eo < el && el < ec && root) }' "$out" ||
    { echo "our lines are not inside <libraries> and <effects>"; return 1; }
  return 0
}

# ---------------------------------------------------------------------------
# Other modules
# ---------------------------------------------------------------------------

# Modules that answer to the same effect uuid, or replace the factory.
rv4a_conflicting_modules() {
  for m in ainur_jamesdsp jamesdsp ainur_lightningdsp lightningdsp rv4a_engine \
           sv_apixaml pixaml PIXAML ViPER4AndroidFX viperfx_re ViPERFX_RE; do
    d="$RV4A_ROOT/data/adb/modules/$m"
    [ -d "$d" ] || continue
    [ -f "$d/remove" ] && continue
    if [ -f "$d/disable" ]; then echo "$m(disabled)"; else echo "$m"; fi
  done
}

rv4a_aml_present() {
  for m in aml AML ainur_aml; do
    d="$RV4A_ROOT/data/adb/modules/$m"
    [ -d "$d" ] && [ ! -f "$d/remove" ] && [ ! -f "$d/disable" ] && return 0
  done
  return 1
}

# Active modules that ship their own copy of a path (any layout a manager
# mounts: system/vendor/..., or vendor/... and odm/... at the top).
rv4a_modules_with_file() {
  for d in "$RV4A_ROOT"/data/adb/modules/*/; do
    d="${d%/}"; id="${d##*/}"
    [ "$id" = "$RV4A_MODID" ] && continue
    [ -f "$d/disable" ] || [ -f "$d/remove" ] && continue
    for c in "$d/system$1" "$d$1"; do
      if [ -e "$c" ]; then echo "$id"; break; fi
    done
  done
}

# Active modules that add files next to a path. On Magisk that puts a fresh
# directory over its folder after our own mounts, which would hide our copy.
rv4a_modules_in_dir() {
  dir="${1%/*}"
  for d in "$RV4A_ROOT"/data/adb/modules/*/; do
    d="${d%/}"; id="${d##*/}"
    [ "$id" = "$RV4A_MODID" ] && continue
    [ -f "$d/disable" ] || [ -f "$d/remove" ] && continue
    for c in "$d/system$dir" "$d$dir"; do
      if [ -d "$c" ] && [ -n "$(ls -A "$c" 2>/dev/null)" ]; then echo "$id"; break; fi
    done
  done
}

# Something other than this module mounted over a path, seen from init.
rv4a_foreign_mount() {
  awk -v p="$1" -v me="$RV4A_MODID" '$5 == p && !index($4, me) { print $4; exit }' \
    "$RV4A_PROC/1/mountinfo" 2>/dev/null
}

# ---------------------------------------------------------------------------
# The root manager
# ---------------------------------------------------------------------------
rv4a_root_impl() {
  if [ -n "$KSU" ] || [ -d "$RV4A_ROOT/data/adb/ksu" ]; then echo KernelSU
  elif [ -n "$APATCH" ] || [ -d "$RV4A_ROOT/data/adb/ap" ]; then echo APatch
  else echo Magisk; fi
}

# The major version of KernelSU's userspace, 0 when unknown.
rv4a_ksu_major() {
  v="$KSU_VER"
  [ -n "$v" ] || v="$(ksud -V 2>/dev/null)"
  v="$(echo "$v" | sed -n 's/[^0-9]*\([0-9][0-9]*\)\..*/\1/p' | head -n 1)"
  echo "${v:-0}"
}

# Root that is loaded after boot (KernelSU late-load, temporary root
# exploits): no post-fs-data stage, so the audio HAL has read its config
# before this module could show it anything.
rv4a_late_load() {
  [ "$KSU_LATE_LOAD" = 1 ] || [ "$KSU_RUNTIME_MODE" = late-load ]
}

rv4a_metamodule() {
  [ -e "$RV4A_ROOT/data/adb/metamodule" ] || [ -L "$RV4A_ROOT/data/adb/metamodule" ]
}

# install.prop: what the install was based on, read back on every boot.
rv4a_prop() { sed -n "s/^$1=//p" "$2" 2>/dev/null | head -n 1; }

# The configs this install patched, one path per line.
rv4a_installed_configs() {
  n="$(rv4a_prop configs "$1")"
  case "$n" in ''|*[!0-9]*) return 0 ;; esac
  i=1
  while [ $i -le "$n" ]; do rv4a_prop "config_$i" "$1"; i=$((i + 1)); done
}

# ---------------------------------------------------------------------------
# Mounts. Our config copies are bind mounted over the device's files by this
# module itself rather than by the root manager, so they can be taken away at
# any time, on every manager: KernelSU's overlay of a whole partition cannot
# be undone without a reboot, a single bind mount can. They are made in
# init's mount namespace, which the audio HAL runs in.
# ---------------------------------------------------------------------------
rv4a_in_init() {
  # The tests (RV4A_PROPS set) stub mount and umount and need no namespace.
  if [ -n "$RV4A_PROPS" ] || [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ]; then
    "$@"
  elif command -v nsenter >/dev/null 2>&1; then
    nsenter -t 1 -m "$@"
  else
    "$@"
  fi
}

# Is our copy mounted at this path, seen from init?
rv4a_our_mount() {
  awk -v p="$1" -v me="$RV4A_MODID" '$5 == p && index($4, me) { f = 1 } END { exit !f }' \
    "$RV4A_PROC/1/mountinfo" 2>/dev/null
}

# The SELinux label a copy should carry: the original's, so the factory may
# read it exactly as it reads the original.
rv4a_label_of() {
  l="$(ls -Zd "$RV4A_ROOT$1" 2>/dev/null | awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^u:object_r:/) { print $i; exit } }')"
  echo "${l:-u:object_r:vendor_configs_file:s0}"
}

# rv4a_mount_configs <moddir>: mounts every config copy this install has.
# Prints what it did. Returns 1 when one could not be mounted.
rv4a_mount_configs() {
  rc=0
  for c in $(rv4a_installed_configs "$1/install.prop"); do
    src="$1/configs$c"
    [ -f "$src" ] || continue
    if rv4a_our_mount "$c"; then echo "already mounted: $c"; continue; fi
    lbl="$(rv4a_prop "label_$(rv4a_config_index "$1" "$c")" "$1/install.prop")"
    chcon "${lbl:-u:object_r:vendor_configs_file:s0}" "$src" 2>/dev/null
    chmod 0644 "$src" 2>/dev/null
    if rv4a_in_init mount -o bind "$src" "$RV4A_ROOT$c" 2>/dev/null; then
      echo "mounted our copy over $c"
    else
      echo "could not mount our copy over $c"; rc=1
    fi
  done
  return $rc
}

rv4a_config_index() {
  n="$(rv4a_prop configs "$1/install.prop")"
  case "$n" in ''|*[!0-9]*) return 0 ;; esac
  i=1
  while [ $i -le "$n" ]; do
    [ "$(rv4a_prop "config_$i" "$1/install.prop")" = "$2" ] && { echo $i; return; }
    i=$((i + 1))
  done
}

# Takes away every mount of ours, so the next time the audio HAL starts it
# reads the stock files.
rv4a_unmount_configs() {
  for c in $(rv4a_installed_configs "$1/install.prop"); do
    if rv4a_our_mount "$c"; then
      rv4a_in_init umount -l "$RV4A_ROOT$c" 2>/dev/null && echo "unmounted our copy of $c"
    fi
  done
}

# Is our library where the factory looks for it? Only the root manager can
# put a new file into /vendor/lib64/soundfx.
rv4a_lib_visible() {
  lib="$(rv4a_prop lib "$1/install.prop")"
  [ -n "$lib" ] && cmp -s "$RV4A_ROOT/vendor/lib64/soundfx/$lib" "$1/system/vendor/lib64/soundfx/$lib"
}

# ---------------------------------------------------------------------------
# Status, quarantine and notices
# ---------------------------------------------------------------------------
RV4A_DESC="Experimental: the RootfulViPER4Android engine for phones with the AIDL audio effect HAL. Test build."

# Shown in the root manager's module list, so whoever installed it can see
# what the module last decided without reading any log.
rv4a_set_status() {
  echo "$2" > "$1/status"
  if [ -f "$1/module.prop" ]; then
    sed -i "s|^description=.*|description=$RV4A_DESC Status: $2|" "$1/module.prop" 2>/dev/null
  fi
}

# Is there a config copy that would be used on this boot?
rv4a_active() {
  [ -d "$1/configs" ] || [ -f "$1/system/vendor/etc/audio_effects_config.xml" ]
}

# Sets our config copies aside for good. Without them the factory reads only
# the device's own files and never loads our library. Set-aside copies are
# never put back automatically; reinstalling makes fresh ones.
rv4a_quarantine() {
  if [ -d "$1/configs" ]; then
    rm -rf "$1/configs.rejected"
    mv -f "$1/configs" "$1/configs.rejected" && echo "set our config copies aside: $2"
  fi
  if [ -f "$1/system/vendor/etc/audio_effects_config.xml" ]; then
    mv -f "$1/system/vendor/etc/audio_effects_config.xml" "$1/created_config.rejected" &&
      echo "set the created config aside: $2"
  fi
  rv4a_set_status "$1" "switched off ($2). Reinstall to try again."
}

rv4a_notify() {
  title="RV4A AIDL engine"
  cmd notification post -S bigtext -t "$title" rv4a_aidl "$1" >/dev/null 2>&1 ||
    su 2000 -c "cmd notification post -S bigtext -t '$title' rv4a_aidl '$1'" >/dev/null 2>&1 ||
    echo "could not post a notification"
}

# A filtered copy of logcat in the module folder. The factory's start-up
# lines rotate out of the log buffer quickly, so the boot script keeps them.
rv4a_save_logcat() {
  {
    echo "--- $2 $(date 2>/dev/null)"
    logcat -b all -d 2>/dev/null | grep -E "$RV4A_LOG_PATTERN" | tail -n 1500
  } > "$1/logcat.$3.txt" 2>/dev/null
}

# ---------------------------------------------------------------------------
# post-fs-data: watchdog and drift checks. Prints its log to stdout. Runs
# before the root manager mounts anything and before the audio HAL starts.
# ---------------------------------------------------------------------------
rv4a_boot_check_body() {
  MODDIR="$1"
  P="$MODDIR/install.prop"
  MARK="$MODDIR/.boot_incomplete"
  STRIKES="$MODDIR/.boot_strikes"
  echo "--- boot check $(date 2>/dev/null) ---"
  if [ ! -f "$P" ]; then
    echo "no install.prop; nothing to check"
    return 0
  fi
  LIB="$(rv4a_prop lib "$P")"
  VER="$(rv4a_prop hal_version "$P")"

  # 1. Copies the watchdog set aside come back once a boot is attempted
  #    again, so re-enabling the module works on this boot.
  if [ -d "$MODDIR/configs.disabled" ] && [ ! -d "$MODDIR/configs" ]; then
    mv "$MODDIR/configs.disabled" "$MODDIR/configs" && echo "restored our configs after a watchdog stop"
  fi
  if [ -f "$MODDIR/created_config.disabled" ]; then
    mkdir -p "$MODDIR/system/vendor/etc"
    mv "$MODDIR/created_config.disabled" "$MODDIR/system/vendor/etc/audio_effects_config.xml" &&
      echo "restored the created config after a watchdog stop"
  fi

  # 2. Two strikes, as in the legacy module: one unfinished boot can be a
  #    power cut or a long first boot after an update.
  if [ -f "$MARK" ]; then
    n="$(cat "$STRIKES" 2>/dev/null)"
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    n=$((n + 1))
    echo "$n" > "$STRIKES"
    echo "previous boot did not complete (strike $n)"
    if [ "$n" -ge 2 ]; then
      touch "$MODDIR/disable" || echo "WARNING: could not create the disable flag"
      [ -d "$MODDIR/configs" ] && mv "$MODDIR/configs" "$MODDIR/configs.disabled" && echo "set our configs aside (watchdog)"
      [ -f "$MODDIR/system/vendor/etc/audio_effects_config.xml" ] &&
        mv "$MODDIR/system/vendor/etc/audio_effects_config.xml" "$MODDIR/created_config.disabled" &&
        echo "set the created config aside (watchdog)"
      rm -f "$MARK" "$STRIKES"
      rv4a_set_status "$MODDIR" "switched off after $n boots that did not complete. Re-enable it to try again"
      echo "module disabled"
      return 0
    fi
  else
    rm -f "$STRIKES"
  fi
  touch "$MARK" || echo "WARNING: could not write the boot marker; watchdog is off"

  if ! rv4a_active "$MODDIR"; then
    echo "no config copy is active ($(cat "$MODDIR/status" 2>/dev/null)); the device runs stock"
    return 0
  fi

  # 3. Is this still the device we were installed on?
  fp="$(rv4a_getprop ro.vendor.build.fingerprint)"
  ofp="$(rv4a_prop vendor_fingerprint "$P")"
  if [ -n "$ofp" ] && [ -n "$fp" ] && [ "$fp" != "$ofp" ]; then
    echo "vendor build changed: $ofp -> $fp"
    rv4a_quarantine "$MODDIR" "the system was updated"
    return 0
  fi
  now="$(rv4a_vintf_effect_version)"
  was="$(rv4a_prop vintf_version "$P")"
  if [ -n "$now" ] && [ -n "$was" ] && [ "$now" != "$was" ]; then
    echo "declared effect HAL version changed: V$was -> V$now"
    rv4a_quarantine "$MODDIR" "the effect HAL is now V$now, this install is V$VER"
    return 0
  fi
  i=1
  for c in $(rv4a_installed_configs "$P"); do
    if [ "$(rv4a_prop create "$P")" = 1 ]; then
      # Some managers mount module files before this runs, so a file that
      # carries our uuid is our own.
      if [ -e "$RV4A_ROOT$c" ] && ! grep -q "$RV4A_UUID" "$RV4A_ROOT$c" 2>/dev/null; then
        echo "$c exists now, this install created one"
        rv4a_quarantine "$MODDIR" "the phone now has its own $c"
        return 0
      fi
      continue
    fi
    if [ ! -r "$RV4A_ROOT$c" ]; then
      echo "$c is gone"
      rv4a_quarantine "$MODDIR" "the stock config $c is gone"
      return 0
    fi
    if [ "$(rv4a_config_sig "$RV4A_ROOT$c")" != "$(rv4a_prop "sig_$i" "$P")" ]; then
      echo "$c differs from the one this install was made from"
      rv4a_quarantine "$MODDIR" "the stock config changed"
      return 0
    fi
    i=$((i + 1))
  done

  # 4. Our copies: the stock file plus our two lines, nothing else, and the
  #    library they name is the one for this version.
  if [ "$LIB" != "librv4a_aidl_v$VER.so" ] ||
     [ ! -f "$MODDIR/system/vendor/lib64/soundfx/$LIB" ]; then
    echo "library $LIB missing or not for V$VER"
    rv4a_quarantine "$MODDIR" "the library is missing"
    return 0
  fi
  chcon u:object_r:vendor_file:s0 "$MODDIR/system/vendor/lib64/soundfx/$LIB" 2>/dev/null
  for c in $(rv4a_installed_configs "$P"); do
    [ "$(rv4a_prop create "$P")" = 1 ] && continue
    if ! why="$(rv4a_validate "$RV4A_ROOT$c" "$MODDIR/configs$c" "$LIB")"; then
      echo "our copy of $c failed validation: $why"
      rv4a_quarantine "$MODDIR" "a config copy failed a check"
      return 0
    fi
  done
  echo "ok: V$VER, $LIB, configs: $(rv4a_installed_configs "$P" | tr '\n' ' ')"
  rv4a_set_status "$MODDIR" "installed for effect HAL V$VER, checking this boot"
  return 0
}

rv4a_boot_check() {
  [ -f "$1/boot.log" ] && mv -f "$1/boot.log" "$1/boot.log.1"
  rv4a_boot_check_body "$1" > "$1/boot.log" 2>&1
}

# Mounting, after the boot check. $2 says which stage is calling: on
# KernelSU and APatch the manager mounts the module's own files between
# post-fs-data and post-mount, and a mount made before that would end up
# underneath its overlay, so ours are made in post-mount there. Magisk has
# no post-mount stage and does not touch the files we mount.
rv4a_mount_stage() {
  MODDIR="$1"
  P="$MODDIR/install.prop"
  [ -d "$MODDIR/configs" ] && [ -f "$P" ] || return 0
  case "$(rv4a_root_impl):$2" in
    Magisk:post-fs-data|KernelSU:post-mount|APatch:post-mount) ;;
    *) return 0 ;;
  esac
  {
    if [ "$2" = post-mount ] && ! rv4a_lib_visible "$MODDIR"; then
      echo "$(rv4a_prop lib "$P") is not in /vendor/lib64/soundfx: the root manager did not mount this module's files. Our configs stay unmounted, the phone runs stock."
      rv4a_set_status "$MODDIR" "inactive: the root manager did not mount the library (KernelSU 3 and later need a metamodule such as meta-overlayfs)"
      return 0
    fi
    # Another module's copy mounted since post-fs-data would be what we read
    # here; our copy was made from the stock file, so do not cover theirs.
    i=1
    for c in $(rv4a_installed_configs "$P"); do
      if ! rv4a_our_mount "$c" && [ "$(rv4a_config_sig "$RV4A_ROOT$c")" != "$(rv4a_prop "sig_$i" "$P")" ]; then
        echo "$c is no longer the stock file at mount time (another module?); not mounting"
        rv4a_set_status "$MODDIR" "inactive: $c was replaced by something else at boot"
        return 0
      fi
      i=$((i + 1))
    done
    rv4a_mount_configs "$MODDIR"
  } >> "$MODDIR/boot.log" 2>&1
}

# ---------------------------------------------------------------------------
# service.sh: watching the audio HAL. Starts as soon as the late_start stage
# runs, which is before boot completes and usually after the HAL started, so
# a crash loop that keeps Android from finishing its boot is caught too.
# ---------------------------------------------------------------------------
rv4a_log() { echo "$(date 2>/dev/null): $*" >> "$MODDIR/boot.log" 2>/dev/null; }

# Our library has made the audio HAL unusable: set our configs aside, take
# our mounts away so the HAL's next start reads the stock files, and post a
# notification.
rv4a_give_up() {
  rv4a_log "$1"
  rv4a_quarantine "$MODDIR" "$2" >> "$MODDIR/boot.log" 2>&1
  rv4a_unmount_configs "$MODDIR" >> "$MODDIR/boot.log" 2>&1
  rv4a_save_logcat "$MODDIR" "after giving up: $1" giveup
  rv4a_notify "The AIDL engine test build switched itself off: $2. Sound should come back by itself; reboot if it does not. Then run rv4a-aidl-diag.sh from the module folder and send the file it writes." >> "$MODDIR/boot.log" 2>&1
}

rv4a_pid_of_exe() { [ -n "$1" ] && pidof "$(basename "$1")" 2>/dev/null | awk '{ print $1 }'; }

# rv4a_restart_window <now> <timestamps>: how many are within 180 s.
rv4a_restart_window() {
  n=0
  for t in $2; do [ $(($1 - t)) -le 180 ] && n=$((n + 1)); done
  echo $n
}

rv4a_service_check() {
  MODDIR="$1"
  P="$MODDIR/install.prop"
  LIB="$(rv4a_prop lib "$P")"
  VER="$(rv4a_prop hal_version "$P")"
  STEP="${RV4A_STEP:-3}"

  if ! rv4a_active "$MODDIR"; then
    i=0
    while [ "$(rv4a_getprop sys.boot_completed)" != "1" ] && [ $i -lt 600 ]; do sleep 1; i=$((i + 1)); done
    [ "$(rv4a_getprop sys.boot_completed)" = "1" ] && rm -f "$MODDIR/.boot_incomplete" "$MODDIR/.boot_strikes"
    rv4a_log "our configs are inactive; nothing to watch"
    return 0
  fi

  # 1. The factory registers once it has loaded every library its config
  #    names. If it never does, the likeliest reason on this boot is us.
  j=0
  while [ $j -lt 120 ]; do
    rv4a_service_registered "$RV4A_FACTORY" && break
    sleep 1; j=$((j + 1))
  done
  if [ $j -ge 120 ]; then
    rv4a_give_up "$RV4A_FACTORY did not register within 120s of late_start" "the effect factory did not start"
    return 0
  fi

  host="$(rv4a_factory_pid)"
  exe="$(rv4a_factory_exe "$host")"
  as="$(pidof audioserver 2>/dev/null | awk '{ print $1 }')"
  rv4a_log "factory registered after ${j}s: pid $host ($exe), audioserver pid $as"

  # 2. Watch from now on. Three restarts of the factory's process or of
  #    audioserver within three minutes is a crash loop, before or after boot
  #    completes. One on its own is not ours to judge.
  restarts=""
  booted=no; checked=no; t=0; quiet=0; snaps=0
  while rv4a_active "$MODDIR"; do
    if [ -n "$RV4A_MONITOR_LIMIT" ] && [ $t -ge "$RV4A_MONITOR_LIMIT" ]; then break; fi
    sleep "$STEP"; t=$((t + 1)); quiet=$((quiet + STEP))
    h="$(rv4a_pid_of_exe "$exe")"; [ -n "$h" ] || h="$(rv4a_factory_pid)"
    a="$(pidof audioserver 2>/dev/null | awk '{ print $1 }')"
    # A process that was not up yet at the first look is not a restart.
    [ -z "$host" ] && [ -n "$h" ] && host="$h"
    [ -z "$as" ] && [ -n "$a" ] && as="$a"
    if [ -n "$h" ] && [ -n "$a" ] && { [ "$h" != "$host" ] || [ "$a" != "$as" ]; }; then
      now="$(date +%s)"
      rv4a_log "restart: factory $host -> $h, audioserver $as -> $a"
      host="$h"; as="$a"; quiet=0
      restarts="$restarts $now"
      if [ $snaps -lt 5 ]; then snaps=$((snaps + 1)); rv4a_save_logcat "$MODDIR" "after a restart" "restart$snaps"; fi
      n="$(rv4a_restart_window "$now" "$restarts")"
      if [ "$n" -ge 3 ]; then
        rv4a_give_up "$n restarts within 180s" "the audio HAL kept restarting"
        return 0
      fi
    fi

    if [ "$booted" = no ]; then
      if [ "$(rv4a_getprop sys.boot_completed)" = 1 ]; then
        booted=yes
      elif [ $((t * STEP)) -ge 600 ]; then
        rv4a_log "boot did not complete within 600s; leaving the marker for post-fs-data"
        booted=never
      fi
    fi

    # 3. Once booted and quiet for 15 s: is our library in the factory, the
    #    version right and our config what the factory sees?
    if [ "$booted" = yes ] && [ "$checked" = no ] && [ $quiet -ge 15 ]; then
      checked=yes
      rv4a_health "$host" || return 0
      rm -f "$MODDIR/.boot_incomplete" "$MODDIR/.boot_strikes"
      rv4a_save_logcat "$MODDIR" "after boot" boot
    fi
  done
}

# The one-off check after boot. Returns 1 when it gave up.
rv4a_health() {
  loaded=no
  grep -q "$LIB" "$RV4A_PROC/$1/maps" 2>/dev/null && loaded=yes
  ndk="$(rv4a_factory_ndk_versions "$1")"
  bv="$(rv4a_binder_version)"
  rv4a_log "factory pid $1: $LIB loaded: $loaded, interface library V${ndk:-none}, binder V${bv:-?}"
  bad=""
  if [ -n "$bv" ]; then
    [ "$bv" = "$VER" ] || bad="V$bv over binder"
  elif [ -n "$ndk" ]; then
    case " $ndk " in *" $VER "*) ;; *) bad="the V$ndk interface library" ;; esac
  fi
  if [ -n "$bad" ]; then
    rv4a_give_up "the factory has $bad, this install is V$VER" "the effect HAL version does not match"
    return 1
  fi
  seen=""; missing=""
  for c in $(rv4a_installed_configs "$P"); do
    if grep -q "$RV4A_UUID" "$RV4A_ROOT$c" 2>/dev/null; then seen="$seen $c"; else missing="$missing $c"; fi
  done
  [ -n "$missing" ] && rv4a_log "our lines are not visible in:$missing"
  if [ "$loaded" = yes ]; then
    rv4a_set_status "$MODDIR" "running, effect HAL V$VER, loaded by the effect factory"
    rv4a_log "steady with $LIB loaded; watchdog cleared"
  elif [ -z "$seen" ]; then
    rv4a_set_status "$MODDIR" "inactive: none of our config copies is visible (see boot.log)"
    rv4a_log "none of our configs is visible, so the factory cannot know the library"
  else
    rv4a_set_status "$MODDIR" "installed, but the effect factory did not load the library (see boot.log)"
    rv4a_log "steady, but $LIB is not in the factory's memory: it reads another config, or could not load the library"
  fi
  return 0
}
