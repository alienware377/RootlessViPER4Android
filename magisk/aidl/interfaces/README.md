# Vendored AIDL interfaces

Frozen `aidl_api` snapshots from AOSP (taken at tag `android-17.0.0_r1`;
frozen versions never change after they ship). Each folder is one package at
one version, with its `.hash` exactly as AOSP has it, so the generated
`getInterfaceHash()` matches the device's.

| Effect HAL | Android | Packages used (`generate.sh <N>`) |
|---|---|---|
| V2 | 15 | `android.hardware.audio.effect-V2`, `android.hardware.audio.common-V3`, `android.media.audio.common.types-V3` |
| V3 | 16 | `android.hardware.audio.effect-V3`, `android.hardware.audio.common-V4`, `android.media.audio.common.types-V4`, `android.media.audio.eraser.types-V1` |
| V4 | 17 | `android.hardware.audio.effect-V4`, `android.hardware.audio.common-V5`, `android.media.audio.common.types-V5`, `android.media.audio.eraser.types-V2` |

All three also use `android.hardware.common-V2` and
`android.hardware.common.fmq-V1`. The sets come from `versions_with_info` in
`hardware/interfaces/audio/aidl/Android.bp`.

**Why every version is built separately.** `IFactory` is the same in V1 to V4,
but the plugin is loaded inside the device's own effect factory and hands it a
`Descriptor` and an `IEffect` as C++ objects. `Flags`, inside the descriptor,
gained fields in V2 and again in V4, and `IEffect` gained `reopen()` in V2.
A plugin built for the wrong version would write a differently laid out struct
into the factory's memory, so the installer only installs the build that
matches the device's factory.

Generated with the SDK's own `aidl` (build-tools 37; 34 drops part of
`AudioChannelLayout` without a clear error):

```
magisk/aidl/generate.sh 4        # writes magisk/aidl/generated/v4
```

These files are Apache-2.0, copyright The Android Open Source Project.
