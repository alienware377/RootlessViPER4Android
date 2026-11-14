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
  declarations in `generalDSP/interpolation.h`; also
  `app/src/main/java/me/timschneeberger/rootlessjamesdsp/globalfx/HermiteCurve.kt`,
  a Kotlin port of the PCHIP and makima parts of that C file, which carries
  the same notice.
- **Used by:** the Multimodal equalizer, the Dynamic range compander and the
  impulse response toolbox in `app/src/main/cpp/libjdspimptoolbox/`; the
  Kotlin port draws the Multimodal equalizer's curve for global mode.
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

### Signalsmith Stretch

- **Author:** Geraint Luff / Signalsmith Audio Ltd.
- **Licence:** MIT, see
  [the Signalsmith Stretch notice](#mit-licence-signalsmith-stretch)
- **Upstream:** https://github.com/Signalsmith-Audio/signalsmith-stretch,
  release 1.4.0 as of commit `88061fd4eda56a2353c5d65c74aa3f4ccf76f9da` (the
  release tag `a670068` plus a build-file change that moves it to Signalsmith
  Linear 0.6.6). The header's own version constant still reads 1.3.2 at that
  commit; the commit is what identifies the copy.
- **Files:** `third_party/signalsmith-stretch/signalsmith-stretch.h`, unmodified,
  with its `LICENSE.txt`. Reached through `Effects/signalsmith.cpp`, which is
  this project's own C interface to it.
- **Used by:** the two Signalsmith methods of the Pitch shifter, and through
  them the fallback for the Rubber Band methods when the add-on is missing.

### Signalsmith Linear

- **Author:** Signalsmith Audio
- **Licence:** MIT, see
  [the Signalsmith Linear notice](#mit-licence-signalsmith-linear)
- **Upstream:** https://github.com/Signalsmith-Audio/linear, release 0.6.6
  (commit `7bb1c8f36794d6980080ce817c91f8da8fe56a66`)
- **Files:** `third_party/signalsmith-linear/stft.h` and `fft.h`, unmodified,
  with its `LICENSE.txt`. These are the only two files Signalsmith Stretch uses
  when none of the optional `SIGNALSMITH_USE_*` back-ends is switched on, and
  none is.
- **Used by:** Signalsmith Stretch, for its FFT and short-time transform.

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

### AutoEq

- **Author:** Jaakko Pasanen
- **Licence:** MIT, see [the AutoEq notice](#mit-licence-autoeq)
- **Upstream:** https://github.com/jaakkopasanen/AutoEq, as of commit
  `7ae0f56d53074872b028649617a22bbb4232feb7`
- **Files:** `app/src/main/java/me/timschneeberger/rootlessjamesdsp/matcheq/`.
  A Kotlin port, not a copy: the frequency grids, interpolation, centring,
  target building, smoothing, slope limiting and GraphicEQ output of
  `autoeq/frequency_response.py` and `autoeq/utils.py`, the defaults of
  `autoeq/constants.py`, and the table of which target suits which measuring
  rig from `dbtools/create_webapp_data.py` (file names and bass shelf amounts
  only).
- **Used by:** Match EQ, on the Graphic EQ screen.
- **Data:** none is included. AutoEq's measurements and target curves are
  downloaded at runtime from the AutoEq repository, one file at a time, only
  when the user picks them, and stay in the app's own storage.

### Loudness / PEQ fork of RootlessJamesDSP

- **Author:** evoeram
- **Licence:** GNU GPL version 3, the same as this app, so there is no
  separate licence text below
- **Upstream:** https://github.com/evoeram/Loundess-PEQ-RootlessJamesDSP, as of
  commit `489d9331ef7b67dac304a20d3be41bd0bd82e3f1`
- **Files:** `app/src/main/java/me/timschneeberger/rootlessjamesdsp/utils/GraphicEqToPeq.kt`,
  ported from `app/src/main/java/me/timschneeberger/rootlessjamesdsp/utils/GraphEqToPeqConverter.kt`.
  A port, not a copy. From upstream: the log-frequency interpolation of the
  nodes, the greedy placement of peaking filters at the largest error, the
  Nelder-Mead refinement of all filters after each one, the quadratic penalty
  on Q above 4, the preamp against clipping, and the 8/12/16/20 filter choice
  and quality grades of the result dialog. Changed here: the responses come
  from this app's `BiquadUtils`, so they are the curve the engine applies; the
  curve's overall level goes into the preamp instead of the filters; each new
  filter is fitted on its own before the joint pass; fitting stops once the
  error is under 0.5 dB; and filters that end up doing nothing are dropped and
  their spot skipped.
- **Used by:** Convert to PEQ, on the Graphic EQ screen.

### libmysofa

- **Author:** Christian Hoene, Symonics GmbH
- **Licence:** BSD 3-Clause, see [the libmysofa notice](#bsd-3-clause-licence-libmysofa)
- **Upstream:** https://github.com/hoene/libmysofa, release 1.3.5 (tag `v1.3.5`,
  commit `6cc5b15a73e9bd97810d03767082edda7f315881`)
- **Files:** `third_party/libmysofa/`: the SOFA reader only (`src/hdf/` and part
  of `src/hrtf/`), unmodified. `src/config.h` and `src/hrtf/mysofa_export.h` are
  written for this project in place of files upstream's build generates. See
  `third_party/libmysofa/README-RV4A.md` for what was left out.
  `src/hrtf/portable_endian.h` in it is by Mathias Panzenböck and in the public
  domain, as its own header says.
- **Used by:** the Binaural upmix, to read `.sofa` head measurement files. It is
  built into its own library, `librv4asofa.so`, loaded only by an isolated helper
  process. It needs zlib, which is the Android system library and not shipped
  with the app.

### MIT KEMAR head measurements (data)

- **Authors:** Bill Gardner and Keith Martin, MIT Media Lab, Machine Listening
  Group. SOFA conversion by Piotr Majdak and Michael Mihocic, Acoustics Research
  Institute, Austrian Academy of Sciences.
- **Licence:** see [the MIT KEMAR notice](#mit-kemar-data-licence)
- **Source:** https://sofacoustics.org/data/database/mit/mit_kemar_normal_pinna.sofa
  (1,171,764 bytes, SHA-256
  `e7035994f5fd754058424c061380ee92b1d5ed58fccef2887a4266916616acdf`), the
  official SOFA database copy of http://sound.media.mit.edu/resources/KEMAR.html
- **Files:** `app/src/main/assets/hrtf/mit_kemar_normal_pinna.sofa`, unmodified.
- **Used by:** the Binaural upmix, as its built-in head.
- **Citation:** Gardner, W. G., and Martin, K. D. (1995). "HRTF measurements of a
  KEMAR". J. Acoust. Soc. Am. 97(6), 3907 to 3908. Shown on the card and in the
  About screen.
- **Not a GPL work:** this is measurement data under its own terms, shipped next
  to the app as a separate file. Debian ships the same data in its main archive
  under the same text.

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

### MIT licence (Signalsmith Stretch)

From Signalsmith Stretch `LICENSE.txt`:

```text
MIT License

Copyright (c) 2022 Geraint Luff / Signalsmith Audio Ltd.

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

### MIT licence (Signalsmith Linear)

From Signalsmith Linear `LICENSE.txt`:

```text
MIT License

Copyright (c) 2025 Signalsmith Audio

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

### BSD 3-Clause licence (libmysofa)

From libmysofa `LICENSE`:

```text
Copyright (c) 2016-2017, Symonics GmbH, Christian Hoene
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    (1) Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

    (2) Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

    (3)The name of the author may not be used to
    endorse or promote products derived from this software without
    specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

### MIT KEMAR data licence

From the MIT Media Lab's KEMAR page and the archive's `README`:

```text
This data is Copyright 1994 by the MIT Media Laboratory. It is provided free
with no restrictions on use, provided the authors are cited when the data is
used in any research or commercial application.
```

The `License` attribute inside the SOFA file:

```text
This HRTF data is provided without any usage restrictions. It is requested
that you cite Gardner and Martin (1995) when using this data for research or
commercial applications.
```

### MIT licence (AutoEq)

From AutoEq `LICENSE`:

```text
MIT License

Copyright (c) 2018-2022 Jaakko Pasanen

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
