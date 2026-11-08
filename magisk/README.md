# RootfulViPER4Android engine (Magisk module)

Installs this fork's DSP engine as a **system-wide audio effect**.

## Why this exists

In root mode the app doesn't use the engine bundled in the APK. It drives a
system effect through Android's `AudioEffect` API, which is a *different*
native library: the one shipped by whichever JamesDSP module is installed.

That means with the stock module, everything this fork added silently does
nothing in root mode: the extra reverb rooms, the ViPER classic limiter, the
rebuilt delay, the retuned speaker optimisation. `JamesDspRemoteEngine` returns
`true` for those calls, so they fail quietly rather than crashing.

This module ships the fork's own engine instead, exposing the same effect
descriptor the app looks up, so no app-side change is needed.

## Building

Run `magisk/build-module.sh` from the repository root: it builds every ABI and
writes `build/rv4a-engine-module.zip`.

If you package by hand, zip with forward-slash separators. Windows'
`Compress-Archive` writes backslashes, and a module zipped that way extracts as
files with literal backslashes in their names rather than directories, so it
installs without error and does nothing.

### Manual

```
./gradlew :app:externalNativeBuildRootFdroidRelease   # or build the target directly
cmake --build <build-dir> --target jamesdsp_hal
```

The target emits `libjamesdsp_hal.so` (it cannot be called `libjamesdsp.so` in
the build tree without colliding with the APK's own library of that name).
Copy it to `magisk/libs/<abi>/libjamesdsp.so` (renaming as you go, since that
is the filename the audio server expects), then zip the `magisk/` directory.

## Installing

Flash in Magisk, then reboot. The installer copies the library into
`/system/lib*/soundfx/` and registers it in the audio effects config the
device actually reads, overlaying the file rather than editing the real one.

Which file that is depends on the phone. The audio HAL's effect loader reads
exactly one list: the first readable, well-formed file in its folders, or the
older `.conf` when there is no XML at all. Vendors change the name and the
folders and write both into the loader itself: Samsung's reads only
`audio_effects_sec.xml`, and the `audio_effects.xml` beside it is never used.
So the installer reads the names and folders out of the loader library the
audio HAL has loaded, and falls back to the AOSP ones. The install log names
the library, the file it asks for, and the file that was patched.

## Which devices this works on

The engine here is a **legacy audio effect library**: the audio server finds it
through `audio_effects.xml` (or the older `.conf`). That is the interface
Android used up to and including 14.

**Android 15 moved audio effects to an AIDL HAL.** Devices that ship only the
AIDL path never read those files, so the engine cannot be found there no matter
where it is installed: the app reports no driver. This is the same wall every
legacy audio mod hit, and it is why ViPER4Android now ships separate non-AIDL
and AIDL modules. There is **no AIDL build of this module yet**; supporting it
means implementing the AIDL effect HAL, not relocating a file.

The installer checks for a legacy config and refuses to install rather than
leaving something inert on the device.

Not affected either way: **rootless mode**, which processes audio inside the app
and needs none of this.

## Root implementations

Built and verified against **Magisk**. The module format is understood by
**KernelSU**, **KernelSU Next** and **APatch** too, and the installer adapts its
recovery advice to whichever it finds.

One caveat worth knowing before you try: on KernelSU and APatch, mounting files
into `/vendor` and `/system` can require a **metamodule**. If the module
installs cleanly but the effect never appears, check that first. It is the
usual cause, and it is reported for other audio modules as well.

## If the device won't boot

An audio module can leave the audio server crashing on every boot, which looks
like a bootloop. Recovery, easiest first:

1. **ADB**: with USB debugging on, and su over adb already granted in your
   root manager, run
   `adb wait-for-device shell su -c 'touch /data/adb/modules/rv4a_engine/disable'`
   (KernelSU: `su -c 'ksud module disable rv4a_engine'`, APatch:
   `su -c 'apd module disable rv4a_engine'`). This turns off only this module.
2. **Safe Mode**: hold the key combo your device uses at boot. Magisk detects
   Safe Mode and disables all modules; the state persists after a normal
   reboot, so you can then remove this one from the Magisk app.
3. **Recovery**: delete `/data/adb/modules/rv4a_engine` from a file manager in
   TWRP, or `rm -rf /data/adb/modules/rv4a_engine` from its shell.

The module also defends itself. It disables itself after two boots that do not
finish, and `post-fs-data.sh` runs before the audio server
starts and moves aside any audio config of ours that fails validation, or that
was made from a file a system update has since changed, so the device boots
with the stock configuration instead.

Two logs in the module folder (`/data/adb/modules/rv4a_engine/`) say what
happened. `install.log` is what the installer found: root manager and
version, the effect HAL, the loader and the file it reads. `boot.log` is what
each boot checked and, once the phone is up, whether the engine and our
config are visible where the audio HAL looks and whether the HAL loaded the
engine. Please attach both to an issue.

## Coexisting with other audio mods

Only one copy of `audio_effects.xml` can be mounted at a time, so audio mods
overwrite each other by nature. **Audio Modification Library** exists to solve
this: it collects the audio configs from every installed mod and merges them
into a single set.

This module checks for AML at install time. If AML is present it writes an
`aml.sh` describing its library and effect and leaves the merging alone, so
ViPER4Android, Dolby and others keep working alongside it. Without AML it
overlays the config itself.

**If you run more than one audio mod, install AML.** Without it, whichever mod
was installed last wins and the others fall silent.

## Status

The library loads, initialises the engine, processes audio and applies the
effect settings the app sends. Three cards are not wired up in root mode yet and
do nothing there: Dynamic system, Differential surround and the two-band Bass
exciter. Please open an issue with your device and Android version if any other
effect does not respond.

**Do not install alongside the stock JamesDSP module**: two libraries claiming
the same effect UUID will conflict.
