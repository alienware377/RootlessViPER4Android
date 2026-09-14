/*
 * Android amalgamation of Rubber Band.
 *
 * Mirrors upstream's own single/RubberBandSingle.cpp with two differences:
 * HAVE_VDSP is dropped, since that is Apple's accelerate framework and means
 * nothing here, and the library's built-in FFT is selected in its place. The
 * result needs nothing outside the NDK - no FFTW, no libsamplerate, no speex -
 * which is what keeps the optional download a single self-contained file.
 *
 * Kept beside the mirror rather than inside it so that tree stays byte-identical
 * to upstream and can be diffed against it.
 *
 * NO_THREADING is upstream's own option for a single-threaded build. The app
 * drives this from one audio thread, so the extra threads would only add
 * scheduling the audio path does not want.
 */
#define USE_BQRESAMPLER 1
#define NO_TIMING 1
#define NO_THREADING 1
#define NO_THREAD_CHECKS 1
#define USE_BUILTIN_FFT 1

#include "../src/faster/AudioCurveCalculator.cpp"
#include "../src/faster/CompoundAudioCurve.cpp"
#include "../src/faster/HighFrequencyAudioCurve.cpp"
#include "../src/faster/SilentAudioCurve.cpp"
#include "../src/faster/PercussiveAudioCurve.cpp"
#include "../src/common/Log.cpp"
#include "../src/common/Profiler.cpp"
#include "../src/common/FFT.cpp"
#include "../src/common/Resampler.cpp"
#include "../src/common/BQResampler.cpp"
#include "../src/common/Allocators.cpp"
#include "../src/common/StretchCalculator.cpp"
#include "../src/common/sysutils.cpp"
#include "../src/common/mathmisc.cpp"
#include "../src/common/Thread.cpp"
#include "../src/faster/StretcherChannelData.cpp"
#include "../src/faster/R2Stretcher.cpp"
#include "../src/faster/StretcherProcess.cpp"
#include "../src/finer/R3Stretcher.cpp"
#include "../src/finer/R3LiveShifter.cpp"
#include "../src/RubberBandStretcher.cpp"
#include "../src/RubberBandLiveShifter.cpp"
#include "../src/rubberband-c.cpp"
