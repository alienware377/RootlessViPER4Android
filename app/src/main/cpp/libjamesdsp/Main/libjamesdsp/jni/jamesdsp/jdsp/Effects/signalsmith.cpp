// The Signalsmith methods of the pitch shifter: a thin C face on Signalsmith
// Stretch, which is C++ and header-only (third_party/signalsmith-stretch and
// its FFT, third_party/signalsmith-linear, both MIT; see THIRD_PARTY_NOTICES.md).
//
// The engine is C, so this file is the only place that sees a template or an
// exception. Everything that can allocate (construction and configure) is
// wrapped so a failure comes back as a null pointer, which the pitch shifter
// treats like any other mode that would not start: it walks down to the next
// method instead of going quiet. Nothing is allocated after Create; process()
// only reuses buffers sized in configure(), so the calls marked realtime safe
// below really are.
#include <cmath>
#include <new>
#include "signalsmith-stretch/signalsmith-stretch.h"

extern "C" {
#include "../jdsp_header.h"
}

namespace {

typedef signalsmith::stretch::SignalsmithStretch<float> Stretch;

struct Shifter
{
	// A fixed seed: the default constructor asks std::random_device for one,
	// and the randomness is only ever used for time stretches past 2x, which
	// a pitch shifter never asks for.
	Shifter() : st(0x52563441L) {}
	Stretch st;
	float ratio = 1.0f;
	int formant = 0;
};

void applyFormant(Shifter *s)
{
	if (s->formant)
	{
		// Keep the spectral envelope where it was while the partials move,
		// which is what stops a voice turning into a chipmunk or a giant.
		// Base 0 asks it to find the fundamental itself.
		s->st.setFormantFactor(1.0f, true);
		s->st.setFormantBase(0.0f);
	}
	else
		s->st.setFormantFactor(1.0f, false);
}

// Lets the library read straight out of the pitch shifter's history ring, so
// priming and catching up need no contiguous copy. It only ever indexes
// inputs[channel][sample], so this is all it takes.
struct RingChannel
{
	const float *buf;
	int mask;
	int64_t start;
	float operator[](int i) const { return buf[(start + i) & mask]; }
};
struct RingIO
{
	const float *const *bufs;
	int mask;
	int64_t start;
	RingChannel operator[](int c) const { return RingChannel{bufs[c], mask, start}; }
};

} // namespace

extern "C" {

void *SignalsmithCreateCustom(float fs, int formant, float blockSec, float intervalSec, int split)
{
	if (!(fs >= 8000.0f) || !(fs <= 768000.0f)) return nullptr;
	try
	{
		Shifter *s = new Shifter();
		const int block = (int)(fs * blockSec);
		const int interval = (int)(fs * intervalSec);
		if (block < 64 || interval < 16 || interval > block)
		{
			delete s;
			return nullptr;
		}
		s->st.configure(2, block, interval, split != 0);
		s->formant = formant ? 1 : 0;
		s->st.setTransposeFactor(1.0f);
		applyFormant(s);
		return s;
	}
	catch (...)
	{
		return nullptr;
	}
}

void *SignalsmithCreate(float fs, int formant)
{
	return SignalsmithCreateCustom(fs, formant, SIGNALSMITH_BLOCK_SEC,
	                               SIGNALSMITH_INTERVAL_SEC, SIGNALSMITH_SPLIT);
}

void SignalsmithDestroy(void *p)
{
	delete static_cast<Shifter *>(p);
}

void SignalsmithSetRatio(void *p, float ratio)
{
	Shifter *s = static_cast<Shifter *>(p);
	// A ratio that is not a positive number has no meaning, and in formant
	// mode a NaN one becomes a NaN band index inside the library, which reads
	// far outside its table and crashes the process (on x86 the index turns
	// into the most negative int). Play it unshifted instead.
	if (!(ratio > 0.0f) || !std::isfinite(ratio)) ratio = 1.0f;
	if (ratio == s->ratio) return;
	s->ratio = ratio;
	s->st.setTransposeFactor(ratio);
}

void SignalsmithSetFormant(void *p, int formant)
{
	Shifter *s = static_cast<Shifter *>(p);
	formant = formant ? 1 : 0;
	if (formant == s->formant) return;
	s->formant = formant;
	applyFormant(s);
}

int SignalsmithLatency(const void *p)
{
	const Shifter *s = static_cast<const Shifter *>(p);
	return s->st.inputLatency() + s->st.outputLatency();
}

int SignalsmithSeekLength(const void *p)
{
	return static_cast<const Shifter *>(p)->st.seekLength();
}

void SignalsmithSeekRing(void *p, const float *const *ring, int mask, int64_t start, int n)
{
	if (n <= 0) return;
	static_cast<Shifter *>(p)->st.seek(RingIO{ring, mask, start}, n, 1.0);
}

void SignalsmithProcessRing(void *p, const float *const *ring, int mask, int64_t start,
                            float *const *out, int n)
{
	if (n <= 0) return;
	static_cast<Shifter *>(p)->st.process(RingIO{ring, mask, start}, n, out, n);
}

} // extern "C"
