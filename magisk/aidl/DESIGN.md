# Root mode on AIDL effect HAL phones

EXPERIMENTAL. Nothing here is part of the normal `rv4a_engine` module or of a
release. The tester guide for the first device tests lives outside the repo.

## What it is

Newer phones (Android 14 and later on most vendors) run audio effects behind
the **AIDL effect HAL**. The legacy module cannot help there: the audio server
no longer loads legacy effect libraries itself.

The vendor's effect factory (`android.hardware.audio.effect.IFactory/default`)
still works from a config file, though. It reads `audio_effects_config.xml`,
`dlopen`s every library listed in it and looks up three functions:

```
binder_exception_t createEffect(const AudioUuid*, std::shared_ptr<IEffect>*);
binder_exception_t queryEffect(const AudioUuid*, Descriptor*);
binder_exception_t destroyEffect(const std::shared_ptr<IEffect>&);
```

So the engine ships as such a plugin, `librv4a_aidl_v<N>.so`, and the module
adds it to that config. Nothing replaces, proxies or restarts the factory. If
the library fails to load, our effect is missing and every other effect works.

## The plugin

```
src/Rv4aAidlEffect.cpp   createEffect, queryEffect; the effect class
src/LegacyBridge.cpp     calls into the legacy effect's own entry points
aosp/<tag>/              AOSP's EffectImpl, EffectContext, EffectThread
interfaces/              frozen AIDL snapshots (see interfaces/README.md)
fmq/, compat/            libfmq and the platform headers the NDK lacks
```

- **AOSP's `EffectImpl`** does the IEffect protocol: open, close, reopen,
  commands, the state machine, the audio queues and the event flag the
  framework wakes the effect with. It is taken unmodified from the Android
  release that froze each version.
- **The effect is the legacy one.** `Rv4aAidlEffect.cpp` creates a legacy
  effect through `EffectCreate` from `app/src/main/cpp/hal/JamesDspHalEffect.cpp`
  and forwards to its `command` and `process`. Every root mode fix (buffered
  payloads for Liveprog, impulse responses, GraphicEQ and DDC, payload hashes,
  the refusal to enable empty subsystems, GET_PARAM for the engine's pid and
  rate) applies to both paths from one source. The shape follows anonymix007's
  JamesDSP AIDL wrapper.
- **The app needs no AIDL code.** The framework already hands a legacy
  `setParameter` or `getParameter` to an AIDL effect as a `VendorExtension`
  whose `DefaultExtension` holds the whole `effect_param_t`
  (`AidlConversionEffect.cpp`); the plugin passes those bytes to the legacy
  effect unchanged, and its reply back.
- **One build per effect HAL version.** The factory and the plugin exchange
  C++ objects in one process, so their layouts must match. `IFactory` is the
  same from V1 to V4, but `Flags` (inside `Descriptor`) changed in V2 and V4
  and `IEffect` gained `reopen()` in V2. V2 is Android 15, V3 is 16, V4 is 17.
  A phone runs its vendor's version, which can be older than its Android.
- **Linking.** Only `createEffect`, `queryEffect` and `destroyEffect` are
  exported; libc++, the interface code and the legacy effect are private.
  Runtime dependencies: libbinder_ndk, liblog, libc, libm, libdl. 16 KB
  aligned. Strict linking. `check-elf.sh` enforces all of this.
- **Never abort.** Configurations the engine cannot take (anything but
  32-bit float stereo) are refused with `EX_ILLEGAL_ARGUMENT` before they reach
  AOSP code that would abort, and the logging shim does not abort on fatal.

Known limit: the plugin carries its own copy of the IEffect binder class. When
audioserver hands an effect back to the factory's `destroyEffect`, the factory
sees a proxy rather than the object it created, cannot find it in its map, and
keeps the closed shell of that one effect (a few hundred bytes; the engine
itself is freed by `close()`). The same is true of any plugin that embeds its
interface code, which every NDK-built plugin has to.

## The module (`module/`)

`customize.sh` installs only when everything matches; `common/aidl_common.sh`
holds the logic, shared with the boot scripts and the host tests.

1. An AIDL factory must be registered.
2. The version comes from the VINTF manifests and must agree with the
   interface library the factory has loaded. Only V2, V3, V4 are installed.
3. Stock JamesDSP, LightningDSP, the legacy module and PIXAML must be gone
   (same effect uuid, or a replaced factory). AML is not supported yet.
4. The config is the one the factory reads: its own APEX first (reported, not
   patched: that is the Pixel layout), then `/odm/etc`,
   `/vendor/etc/audio/sku_<sku>`, `/vendor/etc`, `/system/etc`.
5. Two lines are added, one in `<libraries>` and one in `<effects>`, with the
   file's own line endings; every other byte is kept, and the result is
   checked before use.
6. Labels: `vendor_file` for the library, `vendor_configs_file` for the config.

Boot safety (`post-fs-data.sh`, `service.sh`):

- Two boots that do not complete switch the module off (as the legacy one).
- Before mounting: if the vendor build, the effect HAL version or the stock
  config changed since install, or our copy or library fail a check, our
  config is set aside for good. The device is stock whenever that file is
  missing, because the factory then never hears of our library.
- After boot: the factory must register within 60 s, have our library loaded
  with the matching interface version, and keep its pid (and audioserver its
  pid) for 15 s. For the rest of the boot, three restarts within three minutes
  count as a crash loop. On any of these: our config is set aside, our mount
  is taken away so the restarts stop at once, and a notification asks for a
  reboot.
- The manager's module description shows the last decision.

## Building and testing

```
magisk/aidl/build.sh                     # all versions, arm64-v8a and x86_64, ELF-checked
magisk/aidl/package.sh magisk/aidl/out/libs out.zip
tools/run_aidl_harness.sh emulator-5554  # x86_64 harness on an emulator
bash magisk/aidl/tests/test_module.sh    # installer and boot scripts, on Linux
```

`tools/aidl_plugin_harness.cpp` plays the factory (dlopen, queryEffect,
createEffect in process) and audioserver (every call over a binder proxy, the
audio queues and event flag) around the real library, and compares the output
bit for bit with the legacy effect.

## What has not been verified

Everything above runs without an AIDL rooted phone. Not yet known: that a real
vendor factory reads the config we patch and accepts our library in its linker
namespace and SELinux domain, that the labels survive KernelSU and Magisk
mounts, audio and parameters through the real framework, offload and
Bluetooth paths, CPU use in the HAL process, and the watchdog on a real broken
install. Pixel (config only inside the APEX) is a separate later step; see
PIXEL-ROUTE.md.

## History

An earlier attempt here built a standalone service meant to take over
`IFactory/default` and proxy the stock factory. AOSP's factory hardcodes that
instance name, so the stock one could not run beside it, and its installer was
made to refuse to install. It has been removed. Two claims from that time were
wrong and are corrected above: `IFactory` did not change between V1 and V3,
and the app has always been able to reach an AIDL effect through the
framework's vendor extension conversion.
