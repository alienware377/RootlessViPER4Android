#!/usr/bin/env bash
# Builds the effect library for every ABI and packages the Magisk module.
#
# Run from the repository root. Needs ANDROID_NDK_HOME (or ANDROID_SDK_ROOT
# with an ndk/ directory) and a cmake with ninja.
set -euo pipefail

# CI exposes the NDK under several names; take whichever is set.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/ndk}}}"
[ -d "$NDK/build/cmake" ] || NDK="$(ls -d "$NDK"/* | sort -V | tail -1)"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[ -f "$TOOLCHAIN" ] || { echo "No NDK toolchain at $TOOLCHAIN"; exit 1; }

OUT="$(pwd)/build/magisk"
rm -rf "$OUT"; mkdir -p "$OUT"

for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
  BUILD="$(pwd)/build/hal-$ABI"
  rm -rf "$BUILD"
  cmake -S app/src/main/cpp -B "$BUILD" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-26
  cmake --build "$BUILD" --target jamesdsp_hal
  mkdir -p "$OUT/libs/$ABI"
  # Renamed here: the target can't be called libjamesdsp.so in the build tree
  # without colliding with the APK's own library of that name.
  # Built into hal/ under its real installed name, so its soname matches what
  # it is loaded as. The target still cannot be NAMED jamesdsp, because the
  # APK's own library already is.
  cp "$BUILD/hal/libjamesdsp.so" "$OUT/libs/$ABI/libjamesdsp.so"
  # Unstripped these are about 5 MB each, so four of them put 21 MB of debug
  # symbols into a module whose whole job is one library.
  STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip* 2>/dev/null | head -1)"
  [ -x "$STRIP" ] && "$STRIP" --strip-all "$OUT/libs/$ABI/libjamesdsp.so"
  echo "built $ABI ($(wc -c < "$OUT/libs/$ABI/libjamesdsp.so") bytes)"
done

# post-fs-data.sh is the boot safety net - it quarantines a bad audio config
# before the audio server starts. Leaving it out of the package would ship the
# module without its only defence against an unrecoverable bootloop.
cp magisk/module.prop magisk/customize.sh magisk/service.sh \
   magisk/post-fs-data.sh magisk/README.md magisk/STOCK-BASELINE.md "$OUT/"
mkdir -p "$OUT/common"; cp -r magisk/common/. "$OUT/common/"

# A single carriage return in any of these is not cosmetic. post-fs-data.sh
# would create a file named "disable" with a CR on the end, which Magisk does
# not recognise, so the boot watchdog would be dead while the module stayed
# active: the most dangerous state this package has. One in module.prop lands
# inside the module id itself. Windows checkouts produce exactly this, so the
# check runs over what is about to be packaged rather than over the repo.
for f in "$OUT"/*.sh "$OUT"/common/*.sh "$OUT/module.prop"; do
  [ -f "$f" ] || continue
  if grep -q "$(printf '\r')" "$f"; then
    echo "REFUSING TO PACKAGE: carriage returns in $(basename "$f")" >&2
    exit 1
  fi
  case "$f" in
    *.sh) sh -n "$f" || { echo "REFUSING TO PACKAGE: $(basename "$f") is not valid shell" >&2; exit 1; } ;;
  esac
done
echo "scripts verified: unix line endings, valid shell"

# zip -r keeps forward slashes. Windows' Compress-Archive does not, and a module
# zipped that way installs as files with literal backslashes in their names
# instead of directories, so it silently does nothing.
( cd "$OUT" && zip -qr ../rv4a-engine-module.zip . )
echo "packaged: build/rv4a-engine-module.zip"
