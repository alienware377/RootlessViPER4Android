# Signalsmith Stretch and Signalsmith Linear, vendored

Unmodified copies of the files the pitch shifter's Signalsmith methods compile
from. Both libraries are header-only C++, so these headers are the whole of
what goes into the engine; `Effects/signalsmith.cpp` in the engine is this
project's own C interface to them.

| | Signalsmith Stretch | Signalsmith Linear |
|---|---|---|
| Upstream | https://github.com/Signalsmith-Audio/signalsmith-stretch | https://github.com/Signalsmith-Audio/linear |
| Version | 1.4.0 | 0.6.6 |
| Commit | `88061fd4eda56a2353c5d65c74aa3f4ccf76f9da` | `7bb1c8f36794d6980080ce817c91f8da8fe56a66` |
| Files here | `signalsmith-stretch/signalsmith-stretch.h` | `signalsmith-linear/stft.h`, `signalsmith-linear/fft.h` |
| Licence | MIT, `signalsmith-stretch/LICENSE.txt` | MIT, `signalsmith-linear/LICENSE.txt` |

The Stretch commit is release tag 1.4.0 (`a670068`) plus one upstream commit
that only changes its build file to pull Linear 0.6.6, which fixes a macro in
`stft.h`. The `version` constant inside `signalsmith-stretch.h` still says
1.3.2 at that commit, so the commit above is what identifies this copy.

Stretch includes `signalsmith-linear/stft.h`, so the build puts this
`third_party/` folder on the include path. Linear's other headers are not
needed: `stft.h` and `fft.h` include nothing else unless one of the optional
`SIGNALSMITH_USE_*` FFT back-ends is defined, and none is.

To check a copy against upstream, compare blob hashes, for example
`git hash-object signalsmith-stretch/signalsmith-stretch.h` against
`git rev-parse 88061fd:signalsmith-stretch.h` in a clone of the upstream
repository.
