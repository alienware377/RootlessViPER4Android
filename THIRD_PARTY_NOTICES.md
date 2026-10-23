# Third-party notices

This app is licensed under the GNU GPL version 3 (see [LICENSE](LICENSE)). It
also contains code taken from the projects listed below, each under its own
licence. Permissive licences such as MIT, BSD and the Boost Software License
are only satisfied when the copyright notice and licence text travel with the
code, so each component is listed here with its full notice, and the source
files that hold the ported or copied code carry the upstream notice in a
comment at the top.

Unless a path starts with `app/` or `third_party/`, it is relative to
`app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/`.

## Components

### libbs2b

- **Author:** Boris Mikhaylov
- **Licence:** MIT, see [the libbs2b notice](#mit-licence-libbs2b)
- **Upstream:** https://bs2b.sourceforge.net/
  (source: https://sourceforge.net/p/bs2b/code/HEAD/tree/trunk/libbs2b/)
- **Files:** `Effects/bs2b.c`, derived from libbs2b `src/bs2b.c`. The matching
  bs2b constants and declarations are in `jdsp_header.h`.
- **Used by:** the BS2B presets of the Crossfeed card.
- **Also in the tree, not built into the app:**
  `app/src/main/cpp/libjamesdsp/VeryOldOpen_source_edition/Audio_Engine/eclipse_libjamesdsp_free_bp/jni/bs2b.c`
  and `bs2b.h` in the same folder.

### FFTConvolver

- **Author:** HiFi-LoFi
- **Licence:** MIT, see [the FFTConvolver notice](#mit-licence-fftconvolver)
- **Upstream:** https://github.com/HiFi-LoFi/FFTConvolver
- **Files:** `Effects/eel2/numericSys/FFTConvolver.c` and `FFTConvolver.h`, a
  C port of `FFTConvolver.cpp` and `FFTConvolver.h`.
  `generalDSP/TwoStageFFTConvolver.c` and `TwoStageFFTConvolver.h`, a C port of
  `TwoStageFFTConvolver.cpp` and `TwoStageFFTConvolver.h`.
- **Used by:** the Convolver, the equalizers, the HRTF crossfeed modes and Live
  programmable DSP.

### Boost.Math interpolators (cubic Hermite, PCHIP, makima)

- **Author:** Nick Thompson
- **Licence:** Boost Software License 1.0, see
  [the Boost notice](#boost-software-license-10-boostmath-interpolators)
- **Upstream:** https://github.com/boostorg/math, files
  `include/boost/math/interpolators/cubic_hermite.hpp`,
  `include/boost/math/interpolators/detail/cubic_hermite_detail.hpp`,
  `include/boost/math/interpolators/pchip.hpp` and
  `include/boost/math/interpolators/makima.hpp`
- **Files:** `generalDSP/interpolation.c`, a C port of those headers, and its
  declarations in `generalDSP/interpolation.h`.
- **Used by:** the Multimodal equalizer, the Dynamic range compander and the
  impulse response toolbox in `app/src/main/cpp/libjdspimptoolbox/`.
- **Also in the tree, not built into the app:**
  `app/src/main/cpp/libjamesdsp/Main/DSPManager/jni/main/interpolation.c`.

### smbPitchShift

- **Author:** Stephan M. Bernsee
- **Licence:** The Wide Open License (WOL), see
  [the WOL notice](#the-wide-open-license-wol-smbpitchshift)
- **Upstream:** https://blogs.zynaptiq.com/bernsee/pitch-shifting-using-the-ft/
  (source: https://blogs.zynaptiq.com/bernsee/repo/smbPitchShift.cpp,
  version 1.2)
- **Files:** `Effects/pitchshift.c`. Only the phase vocoder used by the Smooth
  method of the Pitch shifter derives from smbPitchShift. WOL requires the
  notice in all source copies, and this repository distributes source.

### sndfilter

- **Author:** Sean Connelly
- **Licence:** MIT, as published when this copy was taken, see
  [the sndfilter notice](#mit-licence-sndfilter). Upstream moved to the BSD
  Zero Clause License in June 2024; the copy here keeps the MIT terms it was
  taken under.
- **Upstream:** https://github.com/velipso/sndfilter (formerly
  github.com/voidqk/sndfilter)
- **Files:** `Effects/reverb.c`, with the `sf_reverb` types and declarations in
  `jdsp_header.h`.
- **Used by:** the Virtual room effect card, the preset reverb. It is not used
  by the Reverberation card, which is separate code in
  `Effects/viperextras2.c`.
- **Design note:** sndfilter's own `reverb.h` states that its reverb algorithm
  is based on the Progenitor2 reverb from Freeverb3,
  http://www.nongnu.org/freeverb3/.

### dr_wav, dr_flac, dr_mp3

- **Author:** David Reid. dr_mp3 is based on minimp3 by lieff
  (https://github.com/lieff/minimp3), dedicated to the public domain under CC0.
- **Licence:** a choice of public domain (Unlicense) or MIT No Attribution.
  Neither asks for the notice to be reproduced; the full statements remain
  intact at the end of each file.
- **Upstream:** https://github.com/mackron/dr_libs
- **Files:** `Effects/eel2/dr_wav.h`, `Effects/eel2/dr_flac.h`,
  `Effects/eel2/dr_mp3.h`, and the copies in
  `app/src/main/cpp/libjdspimptoolbox/`.

### libsamplerate

- **Author:** Erik de Castro Lopo. `src_sinc.c` in the engine copy also carries
  a 2023 copyright line for James Fung.
- **Licence:** BSD 2-Clause, see
  [the libsamplerate notice](#bsd-2-clause-licence-libsamplerate)
- **Upstream:** https://github.com/libsndfile/libsamplerate
- **Files:** `Effects/eel2/numericSys/libsamplerate/` and
  `app/src/main/cpp/libjdspimptoolbox/libsamplerate/`. Each file keeps its
  upstream header, which points to the upstream `COPYING` file. That file is
  not in this tree, so its text is reproduced below. The per-file headers give
  the exact copyright years for each file.

### Also in this repository

- **Rubber Band Library**, `third_party/rubberband/`: Chris Cannam, Particular
  Programs Ltd, GNU GPL version 2 or later. It is vendored unmodified with its
  own `COPYING`, and its bundled KISS FFT and Speex resampler keep their own
  `COPYING` files under `third_party/rubberband/src/ext/`.

## Licence texts

### MIT licence (libbs2b)

From libbs2b `COPYING`:

```text
Copyright (c) 2005 Boris Mikhaylov

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### MIT licence (FFTConvolver)

From FFTConvolver `COPYING.txt`:

```text
Copyright (c) 2017 HiFi-LoFi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished
to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### MIT licence (sndfilter)

From sndfilter `LICENSE` as of December 2016 (commit `ad26e05`), the terms this
copy was taken under:

```text
The MIT License (MIT)

Copyright (c) 2016 Sean Connelly (@voidqk, web: syntheti.cc)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Boost Software License 1.0 (Boost.Math interpolators)

Copyright line as it appears in the Boost.Math headers listed above:

```text
Copyright Nick Thompson, 2020
Use, modification and distribution are subject to the
Boost Software License, Version 1.0.
(See accompanying file LICENSE_1_0.txt
or copy at http://www.boost.org/LICENSE_1_0.txt)
```

From https://www.boost.org/LICENSE_1_0.txt:

```text
Boost Software License - Version 1.0 - August 17th, 2003

Permission is hereby granted, free of charge, to any person or organization
obtaining a copy of the software and accompanying documentation covered by
this license (the "Software") to use, reproduce, display, distribute,
execute, and transmit the Software, and to prepare derivative works of the
Software, and to permit third-parties to whom the Software is furnished to
do so, all subject to the following:

The copyright notices in the Software and this entire statement, including
the above license grant, this restriction and the following disclaimer,
must be included in all copies of the Software, in whole or in part, and
all derivative works of the Software, unless such copies or derivative
works are solely in the form of machine-executable object code generated by
a source language processor.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO EVENT
SHALL THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE LIABLE
FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### The Wide Open License (WOL) (smbPitchShift)

From the header of `smbPitchShift.cpp`, version 1.2:

```text
COPYRIGHT 1999-2015 Stephan M. Bernsee <s.bernsee [AT] zynaptiq [DOT] com>

                      The Wide Open License (WOL)

Permission to use, copy, modify, distribute and sell this software and its
documentation for any purpose is hereby granted without fee, provided that
the above copyright notice and this license appear in all source copies.
THIS SOFTWARE IS PROVIDED "AS IS" WITHOUT EXPRESS OR IMPLIED WARRANTY OF
ANY KIND. See http://www.dspguru.com/wol.htm for more information.
```

### BSD 2-Clause licence (libsamplerate)

From libsamplerate `COPYING`:

```text
Copyright (c) 2012-2016, Erik de Castro Lopo <erikd@mega-nerd.com>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
