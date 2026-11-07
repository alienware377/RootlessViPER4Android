# AIDL effect HAL service (not ready to install)

A standalone effect service for devices that use the AIDL audio effect HAL
(Android 14 and later). It is built in CI so the toolchain keeps working, but
its installer refuses to install: the stock effect service cannot run under a
second name, so taking over its name would leave the device with no working
effect HAL. Rooted devices with an AIDL effect HAL should use rootless mode
for now.

## Building

The interface headers come from AOSP, not the NDK, so the NDK backend is
generated from the vendored `.aidl` files first. Use build-tools 37 or newer:
build-tools 34 rejects a suppression annotation in `AudioChannelLayout.aidl`
and emits only part of the package.

```sh
bash magisk/aidl/generate.sh
for abi in arm64-v8a x86_64; do
  cmake -S magisk/aidl -B "build/$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-33
  cmake --build "build/$abi"
  bash magisk/aidl/check-undefined.sh "build/$abi/rv4a-effect-service" \
    "$ANDROID_NDK_HOME" "$abi" 33
done
```

The link allows unresolved symbols, so `check-undefined.sh` is what catches a
source file missing from the build.
