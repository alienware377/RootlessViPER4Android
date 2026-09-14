# Rubber Band Library — vendored source mirror

This is an unmodified copy of the Rubber Band Library, kept here for two
reasons.

**The licence requires it.** Rubber Band is GPL, and this project hands out
compiled `librubberband.so` binaries as an optional download. Anyone given a
binary is entitled to the matching source, so it lives here, in the same
repository, pinned to the exact revision the binaries are built from.

**So the download keeps working.** If upstream ever moves or disappears, the
optional download does not quietly break — everything needed to rebuild it is
already here.

| | |
|---|---|
| Upstream | https://github.com/breakfastquay/rubberband |
| Version | 4.0.0 |
| Commit | `e4296ac80b1170018a110bc326fd0d45a0eb27d6` |
| Licence | GNU GPL v2 **or, at your option, any later version** — see `COPYING` |

The "or any later version" clause is what makes this compatible with this
project's GPLv3. Rubber Band is also available under a separate commercial
licence from its authors; that is not the licence used here.

Nothing in this directory has been edited. The Android build lives outside it,
in `android/`, so this tree stays a clean mirror that can be diffed against
upstream.

## Rebuilding

```
bash third_party/rubberband/android/build.sh
```

Produces a stripped `librubberband.so` for all four ABIs. The library exposes
Rubber Band's own C API (`rubberband_live_*`), so the app loads it with `dlopen`
and looks the functions up by name — no wrapper of ours sits in between.
