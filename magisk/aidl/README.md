# Root mode on AIDL effect HAL phones

EXPERIMENTAL. Nothing here is part of the normal `rv4a_engine` module or of a
release.

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
the library cannot be loaded at all, our effect is missing and every other
effect works. If it loads but its `queryEffect` fails, AOSP's factory stops
answering its effect list at that point, and the effects it would have listed
after ours go missing too. Our `queryEffect` only copies a constant
descriptor, so it has nothing that can fail.

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
- **The same name.** The descriptor's name is the legacy effect's own
  (`RootlessViPER4Android engine v5`) with ` AIDL` after it, read from the
  legacy descriptor at run time, so the app's engine version check treats
  both paths alike and they cannot drift apart.
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

1. **Root at boot.** Late-load root (KernelSU's late-load mode, temporary
   root) is refused: the audio HAL reads its config before such root exists.
   KernelSU 3 and later mount module files only through a metamodule, so
   without one it is refused (or, when the version is unknown, installed with
   a note; the boot script then reports that the library was not mounted).
2. **Which HAL.** AIDL when `IFactory/default` is registered. An HIDL phone is
   sent to the normal module.
3. **Which version.** The factory's own answer over binder
   (`getInterfaceVersion`, transaction 16777215), then the interface library
   its process has loaded, then the VINTF manifests. The manifest can
   disagree with what the factory was built against (seen on Qualcomm), and
   the layout follows the build, so a disagreement is only reported. A
   binder answer that disagrees with the loaded library is refused. V2, V3
   and V4 are installed; V1 (Android 14 vendors) is refused, because that
   factory only accepts effect names it already knows and has no `type=`.
4. **Conflicts.** Stock JamesDSP, LightningDSP, the legacy module, PIXAML and
   ViPERFX_RE must be gone (same effect uuid, or a replaced factory). AML is
   not supported yet. Another module shipping, or anything mounted over, one
   of the configs below is refused by name.
5. **Which configs.** Every copy the factory could read: the names
   `audio_effects_config.xml` plus any `audio_effects_config*.xml` written in
   the factory's own binary or libraries (Samsung's `_sec`, Qualcomm's
   `_stub` in stub mode), in its APEX's `etc/`, `/odm/etc`, every
   `/vendor/etc/audio/sku_*`, `/vendor/etc/audio`, `/vendor/etc` and
   `/system/etc`. A copy the factory ignores is harmless; a missed one means
   no effect. The one AOSP's order picks is reported as the primary.
6. **Pixel** ships no config: its factory has `/vendor/etc/audio_effects_config.xml`
   built in. Creating that file may hide the phone's own effects while the
   module is on, so it is opt-in (`touch /data/local/tmp/rv4a-aidl-pixel`),
   and the created file is mounted by the root manager.
7. **Patching.** Two lines are added to each copy, one in `<libraries>` and one
   in `<effects>`, with the line ending of the lines around them (mixed CRLF
   files exist); every other byte is kept, and each result is checked before
   use. The copies go to `configs/`, never to `system/`.
8. **Labels.** `vendor_file` for the library; each config copy gets its
   original's label.

Mounting: the root manager mounts only the library (a new file in
`/vendor/lib64/soundfx` needs its overlay). The config copies are bind
mounted over the device's files by the module itself, in init's mount
namespace: in `post-fs-data.sh` on Magisk, in `post-mount.sh` on KernelSU and
APatch (after their overlay, which would otherwise cover them), and only when
the library is really visible there. So a copy can be taken away at any time
on every manager, which an overlay of the whole partition does not allow.

Boot safety (`post-fs-data.sh`, `service.sh`, `action.sh`):

- Two boots that do not complete switch the module off (as the legacy one).
- Before mounting: if the vendor build, the declared effect HAL version or a
  stock config changed since install, or a copy or the library fail a check,
  the copies are set aside for good. The device is stock whenever they are
  not mounted.
- From the late_start stage on, before boot completes: the factory must
  register within 120 s, and three restarts of its process or of
  audioserver within three minutes count as a crash loop, for the whole
  boot. Either way the copies are set aside, our mounts are taken away (so
  the HAL's next start reads the stock files and sound comes back without a
  reboot) and a notification is posted.
- Once boot completed and things are quiet for 15 s: our library must be in
  the factory's memory with the matching version (binder answer or loaded
  library); otherwise the status says what is missing.
- A filtered logcat is kept in the module folder after boot, after each
  restart and when the module gives up, since the factory's start-up lines
  rotate out quickly.
- `action.sh` (the manager's Action button) switches it off by hand.
- The manager's module description shows the last decision.

`rv4a-aidl-diag.sh` is read-only and writes one text file with everything a
tester needs to send: device and versions, root manager and metamodule, HAL
kind and version from all three sources, the factory's process, init service
and config names, every effect config with our lines and mounts, the module's
logs, matching log lines, SELinux denials, audio_flinger and tombstones. It
also covers HIDL phones, for the legacy module.

## Building and testing

```
magisk/aidl/build.sh                     # all versions, arm64-v8a and x86_64, ELF-checked
magisk/aidl/package.sh magisk/aidl/out/libs out.zip [abis] [versions]
tools/run_aidl_harness.sh emulator-5554  # x86_64 harness on an emulator
bash magisk/aidl/tests/test_module.sh    # installer and boot scripts, on Linux or WSL
```

`tools/aidl_plugin_harness.cpp` plays the factory (dlopen, queryEffect,
createEffect in process) and audioserver (every call over a binder proxy, the
audio queues and event flag) around the real library, and compares the output
bit for bit with the legacy effect.

## What has not been verified

Everything above runs without an AIDL rooted phone. Not yet known: that a real
vendor factory reads the configs we patch and accepts our library in its
linker namespace and SELinux domain; that audioserver may map the queue
memory the plugin creates (`/dev/ashmem` or memfd, chosen as libcutils
chooses); that `service call` reaches the factory from a root shell; whether
post-mount runs before the audio HAL on every KernelSU version; whether
Pixel's factory adds a created config to its built-in effects or replaces
them; audio and parameters through the real framework, including large
payloads (convolver, DDC, Liveprog) inside audioserver's time limit; offload
and Bluetooth paths; CPU use in the HAL process; and the watchdog on a real
broken install.

## History

An earlier version built a standalone service meant to replace
`IFactory/default`; AOSP's factory hardcodes that instance name, so it could
not work and was removed.
