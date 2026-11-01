# libmysofa, vendored in part

The SOFA reader of libmysofa, used only to read head related impulse
response sets (`.sofa` files) for the Binaural upmix card. It is built into
its own small library, `librv4asofa.so`, which is loaded only by the helper
process that reads a set, never by the audio engine and never at startup.

| | |
|---|---|
| Upstream | https://github.com/hoene/libmysofa |
| Version | 1.3.5 (tag `v1.3.5`, commit `6cc5b15a73e9bd97810d03767082edda7f315881`) |
| Licence | BSD 3-Clause, `LICENSE` |
| Files here | `src/hdf/*` and, from `src/hrtf/`, `reader.c`, `check.c`, `spherical.c`, `tools.c`, `tools.h`, `mysofa.h`, `portable_endian.h` |
| Written here | `src/config.h` and `src/hrtf/mysofa_export.h`, which upstream's CMake build generates |

The copied files are unmodified. Left out: the lookup, neighbour,
interpolation, resampling, loudness and minimum phase parts of `src/hrtf/`
(the app does its own processing), the bundled Speex resampler and kd-tree
they need, upstream's tests, and its `share/` folder of example sets, some of
which carry licences that do not allow use here.

`portable_endian.h` is in the public domain (its own header says so). The
library needs zlib, which comes from the Android NDK's system library.

A `.sofa` file is untrusted input, and libmysofa has had memory safety bugs
found by fuzzing (all fixed upstream). So it is only ever run in an isolated
process (`android:isolatedProcess`) that has no permissions and no access to
the app's files, and is handed the file as an open descriptor. A file that
crashes the reader only ends that process.
