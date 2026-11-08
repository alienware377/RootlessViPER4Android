# Third-party notices

This app is licensed under the GNU GPL version 3 (see [LICENSE](LICENSE)). It
also contains code taken from the projects listed below, each under its own
licence. Permissive licences such as MIT, BSD and the Boost Software License
are only satisfied when the copyright notice and licence text travel with the
code, so each component is listed here with its full notice, and the source
files that hold the ported or copied code carry the upstream notice in a
comment at the top.

Unless a path starts with `app/`, `magisk/` or `third_party/`, it is relative
to `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/`.

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

### Android Open Source Project: AIDL audio effect HAL

- **Author:** The Android Open Source Project
- **Licence:** Apache License 2.0, see [the Apache licence](#apache-license-20-aosp-and-anonymix007)
- **Upstream:** https://android.googlesource.com/, at the release tags named
  below
- **Files:** only in the experimental AIDL root mode module, under
  `magisk/aidl/`, never in the app:
  - `interfaces/`: frozen AIDL snapshots of `android.hardware.audio.effect`
    V2 to V4, `android.hardware.audio.common`, `android.media.audio.common.types`,
    `android.media.audio.eraser.types`, `android.hardware.common` and
    `android.hardware.common.fmq` (platform/hardware/interfaces and
    platform/system/hardware/interfaces, tag android-17.0.0_r1), and the code
    the SDK's `aidl` tool generates from them at build time.
  - `aosp/android-15.0.0_r1/`, `aosp/android-16.0.0_r1/`,
    `aosp/android-17.0.0_r1/`: `EffectImpl.cpp`, `EffectContext.cpp`,
    `EffectThread.cpp` and `include/effect-impl/`, unmodified, from
    platform/hardware/interfaces `audio/aidl/default` at each tag.
  - `fmq/`: libfmq headers and `EventFlag.cpp` (platform/system/libfmq), and
    `android-base/macros.h` and `unique_fd.h` (platform/system/libbase).
  - `compat/android/`: the libbinder_ndk headers (platform/frameworks/native),
    `compat/cutils/native_handle.h` (platform/system/core) and
    `compat/android-base/thread_annotations.h` (platform/system/libbase,
    android-17.0.0_r1).
- **Used by:** `librv4a_aidl_v2.so`, `librv4a_aidl_v3.so` and
  `librv4a_aidl_v4.so`, the rooted engine as an AIDL effect plugin. Apache 2.0
  code may be combined with GPLv3 code; the combined libraries are distributed
  under GPLv3.

### JamesDSP AIDL wrapper (anonymix007)

- **Author:** anonymix007, on top of The Android Open Source Project's effect
  example code
- **Licence:** Apache License 2.0, see [the Apache licence](#apache-license-20-aosp-and-anonymix007)
- **Upstream:** https://github.com/james34602/JamesDSPManager/pull/68, files
  `jamesdsp_aidl.cpp` and `jamesdsp_aidl.h`
- **Files:** `magisk/aidl/src/Rv4aAidlEffect.cpp` follows the shape of that
  wrapper (AOSP's `EffectImpl` around the legacy effect's own command and
  process entry points, parameters passed through as `DefaultExtension`
  bytes) and keeps its notice.
- **Used by:** the AIDL effect plugin libraries above, not the app.

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

### Apache License 2.0 (AOSP and anonymix007)

Applies to the files listed under the two Apache-licensed components above.
Each of those files carries its own copyright notice.

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
