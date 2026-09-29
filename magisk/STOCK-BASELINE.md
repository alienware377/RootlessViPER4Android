# What this module is, and is not, based on

Short version: this module was **written for this fork**, not derived from the
stock JamesDSP module. It is a drop-in *replacement* for it, not a *descendant*
of it. This file records the baseline so the question is answerable next time
instead of being re-researched.

## What we share with stock, deliberately

| | value |
|---|---|
| Effect implementation UUID | `f27317f4-c984-4de6-9a90-545759495bf2` |
| Effect type UUID | `f98765f4-c321-5de6-9a45-123459495ab2` |
| Installed filename | `libjamesdsp.so` |
| Library name in the config | `jdsp` |
| Effect name in the config | `jamesdsp` |

Those UUIDs come from james34602's engine, which we vendor, not from the stock
module. Matching them is what lets the app find our engine with no app-side
change, and what lets stock's and AML's duplicate checks see us.

Because both modules install to the same path, **installing both means the
mount order decides which binary sits behind the shared UUID**. Nothing in the
config can tell them apart. Install one or the other.

## Where stock actually lives

This took real digging, so it is written down.

- The last **officially released** stock module is
  `Zackptg5/JamesDSPManager` at `master`, commit `67466d13b78d`,
  `version=v6.1 (11-27-2023/11-24-2023)`, `versionCode=100`. It has no AIDL
  support, no `aml.sh`, and no `post-fs-data.sh`.
- The line people **actually install** on Android 13+ is
  `ShadoV90/JamesDSPManager`, branch **`tests`**, HEAD
  `dc2b25104e869d37d5529cb3fc18573d22de376a`, tag `6.5-RC3-test4`, published
  2026-06-11 and marked prerelease. Its release note reads "This is last
  version before maintain suspension due to lack of time."

**The trap:** `ShadoV90/JamesDSPManager` at `master` is byte-identical to
Zackptg5 at `master`. Reading the default branch shows v6.1 and leads straight
to the wrong conclusion that there is no AIDL work. All of it is on `tests`.

## What we took from stock

- **`aml.sh` as a sourced patch script** calling `patch_cfgs`, rather than the
  file of shell variables we used to ship. No version of AML has ever read
  `AML_LIB_NAME` and friends; ours was a no-op that also suppressed AML's own
  working builtin for this exact UUID.
- **The library name `jdsp`.** Ours said `jamesdsp`, which defeated the
  duplicate protection in both stock and AML.
- **The 32-bit `/lib/soundfx/` path in `.conf` files.** Not a bug. AOSP's
  `checkLibraryPath()` requires that literal substring and rebuilds the real
  path itself, already `lib64` under `__LP64__`. Stock writes the same. Do not
  "fix" this to `lib64`; it breaks the load on every device.
- **The runtime AIDL probe** (`service list | grep android.hardware.audio.effect.IFactory`)
  in place of guessing from file paths.

## What we deliberately did NOT take

- **`uninstall.sh` moving a handler into `/data/adb/service.d`.** Stock's
  survives module removal and runs `pm uninstall` on the app on a later boot.
  Everything we install stays inside `$MODPATH`, which is most of the reason
  this module cannot brick a device.
- **`update.json`.** Stock's on `tests` points `zipUrl` at a directory rather
  than a zip, and declares `versionCode 105` while `module.prop` says `100`.
- **Boot-time `patchelf`.** Stock rewrites `DT_NEEDED` entries of the effect
  library on every boot with a bundled `patchelf`. Real functionality on
  Pixels, a large new failure surface everywhere else.
- **Their prebuilt AIDL `libjamesdsp.so`.** It comes from an unmerged upstream
  PR and implements none of this fork's parameter ids, so it would gain us
  nothing. There is also a provenance question: this project is GPLv3.
- **The MMT-Extended installer framework** (`common/functions.sh`,
  `META-INF`, the volume-key selector). We use Magisk's own `customize.sh`
  path instead.

## Checking whether this is still current

```
curl -s https://api.github.com/repos/ShadoV90/JamesDSPManager/branches |
  python -c "import sys,json; [print(b['name'], b['commit']['sha'][:12]) for b in json.load(sys.stdin)]"
```

If `tests` is no longer `dc2b25104e86`, read
`https://raw.githubusercontent.com/ShadoV90/JamesDSPManager/tests/changelog.md`
and decide whether anything new belongs here.
