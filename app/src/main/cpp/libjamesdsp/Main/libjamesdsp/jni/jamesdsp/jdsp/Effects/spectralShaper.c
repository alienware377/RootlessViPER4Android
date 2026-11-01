/*
 * Spectral shaper: turns down frequencies that stick out of their surroundings,
 * only while they do.
 *
 * Method, all of it from published work:
 *
 *   - Short-time Fourier analysis, modification and weighted overlap-add
 *     (Allen and Rabiner 1977; Griffin and Lim 1984; J. O. Smith, "Spectral
 *     Audio Signal Processing"). Hann analysis and Hann synthesis at a hop of a
 *     quarter frame: the shifted squared windows sum to exactly 1.5, so the
 *     output is scaled by 2/3 and an untouched spectrum comes back as the input,
 *     delayed.
 *
 *   - A reference level for every frequency that narrow peaks cannot pull up.
 *     It is the mirror image of the "true envelope" of Robel and Rodet (DAFx-05):
 *     average over an octave, clip the spectrum down to that average, average
 *     again, three times over. A peak gets clipped each pass, so it cannot raise
 *     its own reference; a broad slope passes straight through.
 *
 *   - A cut-only gain computer with a soft knee and separate attack and release
 *     in the log domain (Giannoulis, Massberg and Reiss, JAES 2012), per
 *     frequency, at frame rate.
 *
 *   - Smoothing in time and across frequency, which is what keeps per-bin gains
 *     from chattering on noise (Boll 1979; Ephraim and Malah 1984; Cappe 1994),
 *     and keeps every notch at least three bins wide so its impulse response
 *     stays far shorter than the window taper.
 *
 *   - A tone gate built on the "unpredictability" measure of the MPEG-1
 *     psychoacoustic model 2 (ISO/IEC 11172-3, Annex D). A steady sinusoid is
 *     predictable from the two frames before it; noise is not. Predictable bins
 *     are kept out of the cut, so a clean tone passes untouched. The cost is
 *     that a resonance excited by a steady note is protected too, which is why
 *     the card has a switch to catch steady tones as well.
 *
 * The only parts reused from the engine are its real FHT codelets (DFT1024,
 * DFT2048, DFT4096). The two small tables they need are built by stftCommon.h
 * with the same maths as LLbitReversalTbl and fhtsinHalfTblFloat, so the native
 * harness can link this file without the filter design library.
 *
 * Buffering is sample by sample: every input sample is pushed into a ring of N,
 * a frame runs every R pushes, and one output sample is popped per input
 * sample. So the delay is exactly N - 1 for ANY block size, including blocks
 * that never line up with the hop, which the engine's blocks do not. The
 * compander's buffer only writes what complete frames have produced, which on
 * unaligned blocks leaves dry samples next to late wet ones.
 */
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "../jdsp_header.h"
#include "eel2/numericSys/codelet.h"
#include "stftCommon.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

/* Tone gate thresholds on the smoothed unpredictability. Below LOW a bin is
   fully tonal, above HIGH fully noise-like. Calibrated in the harness against
   noise and off-bin sines. */
#define SHAPER_TONAL_LOW 0.08f
#define SHAPER_TONAL_HIGH 0.18f
/* A bump has to stand out for this many frames in a row before it is cut.
   Without it a new note was cut by the full Amount for its first 100 ms or so:
   it sticks out from its first frame, but the tone gate needs three frames to
   see that it is steady. The same idea as the persistence test in howling
   detection (van Waterschoot and Moonen, Proc. IEEE 2011). Measured on a
   note starting over quiet noise, the dip in its first 150 ms was 5.9 dB with
   no persistence, 2.3 dB with four frames, 0.4 dB with five and none with
   six. Five frames are 27 ms at 48 kHz; real resonances are cut no less. */
#ifndef SHAPER_PERSIST_FRAMES
#define SHAPER_PERSIST_FRAMES 5
#endif
/* Soft knee width in dB. */
#define SHAPER_KNEE_DB 3.0f
/* Switch on and off take this long. */
#define SHAPER_FADE_MS 10.0f
/* Detection power smoothing. */
#ifndef SHAPER_POWER_MS
#define SHAPER_POWER_MS 10.0f
#endif
/* Input samples are clamped to this. Nothing real gets near it (it is about
   +120 dBFS), and anything bigger would overflow the power sums in float. */
#define SHAPER_INPUT_LIMIT 1.0e6f
/* Reference passes: average, then clip-and-average this many more times. Each
   pass clips the peak down to the reference before averaging again, so more
   passes read a peak's true height better. Three stopped a broad +12 dB peak
   at 4.1 dB with Amount 6. Eight (with the 1 / k weighting in shBox) reach 5.1
   to 5.7 dB on narrow and broad peaks alike while moving pink noise by under
   0.05 dB per third of an octave; twelve start to carve the narrow focus. */
#ifndef SHAPER_REF_PASSES
#define SHAPER_REF_PASSES 8
#endif

static float shClamp(float v, float lo, float hi, float fallback)
{
	if (!(v == v)) return fallback; /* NaN */
	if (v < lo) return lo;
	if (v > hi) return hi;
	return v;
}

static int shFrameSize(float fs)
{
	if (fs < 64000.0f) return 1024;
	if (fs < 128000.0f) return 2048;
	return 4096;
}

static float shFrameCoef(float ms, float fs, int hop)
{
	if (ms <= 0.0f) return 0.0f;
	return expf(-(float)hop / ((ms * 0.001f) * fs));
}

/* Floats needed for one frame size. Everything lives in one block, so a size
   change is one allocation and one free. */
static size_t shBytes(int N)
{
	const size_t K = (size_t)(N / 2 + 1);
	size_t floats = 0;
	floats += 4 * (size_t)N;   /* in[2], acc[2] */
	floats += 4 * (size_t)N;   /* win, sinTab, bufA, bufB */
	floats += 18 * K;          /* per-bin arrays */
	size_t bytes = floats * sizeof(float);
	bytes += (size_t)N * sizeof(unsigned);      /* bitRev */
	bytes += 2 * (K + 1) * sizeof(double);      /* prefix sums, weights */
	return bytes + 64;
}

static void shCarve(SpectralShaper *s, int N)
{
	const int K = N / 2 + 1;
	unsigned char *p = (unsigned char *)s->mem;
	/* Doubles first so they are aligned. */
	s->prefix = (double *)p; p += (size_t)(K + 1) * sizeof(double);
	s->prefixW = (double *)p; p += (size_t)(K + 1) * sizeof(double);
	float *f = (float *)p;
	s->in[0] = f; f += N;
	s->in[1] = f; f += N;
	s->acc[0] = f; f += N;
	s->acc[1] = f; f += N;
	s->win = f; f += N;
	s->sinTab = f; f += N;
	s->bufA = f; f += N;
	s->bufB = f; f += N;
	s->pow = f; f += K;
	s->ld = f; f += K;
	s->ref = f; f += K;
	s->tmp = f; f += K;
	s->tgt = f; f += K;
	s->gain = f; f += K;
	s->band = f; f += K;
	s->mag1 = f; f += K;
	s->mag2 = f; f += K;
	s->u1re = f; f += K;
	s->u1im = f; f += K;
	s->u2re = f; f += K;
	s->u2im = f; f += K;
	s->cSm = f; f += K;
	s->tonal = f; f += K;
	s->gainLin = f; f += K;
	s->age = f; f += K;
	s->cPrev = f; f += K;
	s->bitRev = (unsigned *)f;
}

/* Everything that depends on the audio, not on the settings. */
static void shClearState(SpectralShaper *s)
{
	const int N = s->N, K = N / 2 + 1;
	memset(s->in[0], 0, sizeof(float) * N);
	memset(s->in[1], 0, sizeof(float) * N);
	memset(s->acc[0], 0, sizeof(float) * N);
	memset(s->acc[1], 0, sizeof(float) * N);
	for (int k = 0; k < K; k++)
	{
		s->pow[k] = 0.0f;
		s->gain[k] = 0.0f;
		s->mag1[k] = s->mag2[k] = 0.0f;
		s->u1re[k] = s->u2re[k] = 1.0f;
		s->u1im[k] = s->u2im[k] = 0.0f;
		s->cSm[k] = 1.0f;
		s->tonal[k] = 0.0f;
		s->age[k] = 0.0f;
		s->cPrev[k] = 1.0f;
	}
	s->pos = 0;
	s->hop = 0;
}

/* Settings that follow the rate or the controls. Never touches audio state, so
   moving a control mid-song cannot click: targets move through the attack and
   release smoother, and overlap-add crossfades every frame change. */
static void shDerive(SpectralShaper *s)
{
	const float fs = s->fs;
	const int N = s->N, R = s->R, K = N / 2 + 1;
	if (!N) return;

	s->threshDb = 10.0f - 0.1f * s->sensitivityPct;

	/* Speed 0 to 100 %, log-mapped: attack 20 to 1 ms, release 300 to 40 ms. */
	const float sp = s->speedPct * 0.01f;
	const float attMs = 20.0f * powf(1.0f / 20.0f, sp);
	const float relMs = 300.0f * powf(40.0f / 300.0f, sp);
	s->attC = shFrameCoef(attMs, fs, R);
	s->relC = shFrameCoef(relMs, fs, R);
	s->powC = shFrameCoef(SHAPER_POWER_MS, fs, R);

	switch (s->focus)
	{
	case SHAPER_FOCUS_NARROW: s->refOct = 2.0f / 3.0f; s->detailOct = 1.0f / 12.0f; break;
	case SHAPER_FOCUS_BROAD:  s->refOct = 2.0f;        s->detailOct = 1.0f / 3.0f;  break;
	default:                  s->refOct = 1.0f;        s->detailOct = 1.0f / 6.0f;  break;
	}

	/* The range, kept inside 0.45 fs and at least half an octave wide. */
	float lo = s->loHz, hi = s->hiHz;
	if (hi > 0.45f * fs) hi = 0.45f * fs;
	if (hi < 1.5f * lo) lo = hi / 1.5f;
	const float binHz = fs / (float)N;
	const float edge = powf(2.0f, 1.0f / 6.0f);
	for (int k = 0; k < K; k++)
	{
		const float f = (float)k * binHz;
		float m;
		if (f >= lo && f <= hi) m = 1.0f;
		else if (f < lo && f > lo / edge)
			m = 0.5f - 0.5f * cosf((float)M_PI * log2f(f * edge / lo) * 6.0f);
		else if (f > hi && f < hi * edge)
			m = 0.5f - 0.5f * cosf((float)M_PI * log2f(hi * edge / f) * 6.0f);
		else m = 0.0f;
		s->band[k] = m;
	}
	/* Bins that can be cut (the taper included, plus one for the cross-bin
	   smoothing), and the wider context the reference is worked out over so
	   the edges of the range still have surroundings to compare with. */
	int kLo = (int)floorf(lo / edge / binHz) - 1;
	int kHi = (int)ceilf(hi * edge / binHz) + 1;
	if (kLo < 1) kLo = 1;
	if (kHi > K - 2) kHi = K - 2;
	const float ctx = powf(2.0f, 0.5f * s->refOct);
	int cLo = (int)floorf(lo / edge / ctx / binHz) - 3;
	float cHiHz = hi * edge * ctx;
	if (cHiHz > 0.45f * fs) cHiHz = 0.45f * fs;
	int cHi = (int)ceilf(cHiHz / binHz) + 3;
	if (cLo < 1) cLo = 1;
	if (cHi > K - 2) cHi = K - 2;
	if (cHi < kHi) cHi = kHi;
	if (cLo > kLo) cLo = kLo;
	s->kLo = kLo; s->kHi = kHi;
	s->kCtxLo = cLo; s->kCtxHi = cHi;

	/* A floor for the level, about 100 dB under a full-scale sine's bin. */
	s->floorPow = 1.0e-11f * (float)N * (float)N;

	s->fadeLen = (int)(SHAPER_FADE_MS * 0.001f * fs);
	if (s->fadeLen < 1) s->fadeLen = 1;
}

/* Makes sure the buffers match the frame size for the current rate. Returns 0
   if memory could not be had. Called from Enable and Refresh only, never from
   Process. */
static int shPrepare(SpectralShaper *s, float fs)
{
	const int N = shFrameSize(fs);
	s->fs = fs;
	if (s->mem && s->N == N)
	{
		shDerive(s);
		return 1;
	}
	void *mem = calloc(1, shBytes(N));
	if (!mem) return 0;
	void *old = s->mem;
	s->mem = mem;
	s->N = N;
	s->R = N / 4;
	s->latency = N - 1;
	shCarve(s, N);
	if (N == 1024) s->fft = DFT1024;
	else if (N == 2048) s->fft = DFT2048;
	else s->fft = DFT4096;
	stftBitRevTable(s->bitRev, N);
	/* Periodic Hann, which is what makes the overlap sum exactly 1.5. */
	stftHannPeriodic(s->win, N);
	stftSinTable(s->sinTab, N);
	/* Running sum of the 1 / k weights; bin 0 is never averaged. */
	s->prefixW[0] = 0.0;
	s->prefixW[1] = 0.0;
	for (int k = 1; k <= N / 2; k++) s->prefixW[k + 1] = s->prefixW[k] + 1.0 / (double)k;
	shClearState(s);
	/* Fresh buffers hold nothing worth hearing, so the wet signal waits for
	   them to fill and then fades in, exactly as on switch-on. */
	s->filled = 0;
	s->wetGain = 0.0f;
	shDerive(s);
	if (old) free(old);
	return 1;
}

/* Fractional-octave average of x for each bin in [a, b]: from k / q to k * q,
   at least one bin either side, clipped to [a, b].

   Each bin is weighted by 1 / k. Bins are evenly spaced in Hz, so a plain
   average over a box that is symmetric in octaves gives its upper half more
   say than its lower half, and on a falling spectrum (pink noise, most music)
   reads low. Over two octaves that bias is about 0.7 dB, which noise then
   reads as excess. Weighting by 1 / k makes every equal slice of an octave
   count the same. */
static void shBox(SpectralShaper *s, const float *x, float *out, int a, int b, float q)
{
	double *ps = s->prefix;
	const double *pw = s->prefixW;
	ps[a] = 0.0;
	for (int k = a; k <= b; k++) ps[k + 1] = ps[k] + (double)x[k] / (double)k;
	const float iq = 1.0f / q;
	for (int k = a; k <= b; k++)
	{
		int lo = (int)((float)k * iq + 0.5f);
		int hi = (int)((float)k * q + 0.5f);
		if (lo > k - 1) lo = k - 1;
		if (hi < k + 1) hi = k + 1;
		if (lo < a) lo = a;
		if (hi > b) hi = b;
		out[k] = (float)((ps[hi + 1] - ps[lo]) / (pw[hi + 1] - pw[lo]));
	}
}

static float shKnee(float d)
{
	const float w = SHAPER_KNEE_DB;
	if (d <= -0.5f * w) return 0.0f;
	if (d >= 0.5f * w) return d;
	const float t = d + 0.5f * w;
	return t * t / (2.0f * w);
}

/* One frame: analysis, detection, gains, synthesis, overlap-add. */
static void shFrame(SpectralShaper *s)
{
	const int N = s->N, K = N / 2 + 1, mask = N - 1;
	const int start = (s->pos + 1) & mask;
	const int ms = s->msMode;
	float *A = s->bufA, *B = s->bufB;
	const unsigned *br = s->bitRev;

	for (int j = 0; j < N; j++)
	{
		const int idx = (start + j) & mask;
		const float l = s->in[0][idx], r = s->in[1][idx];
		const float w = s->win[j];
		if (ms == MS_MODE_STEREO)
		{
			A[br[j]] = l * w;
			B[br[j]] = r * w;
		}
		else
		{
			A[br[j]] = (l + r) * 0.5f * w;  /* centre */
			B[br[j]] = (l - r) * 0.5f * w;  /* edges */
		}
	}
	s->fft(A, s->sinTab);
	s->fft(B, s->sinTab);

	/* Detection power and the spectrum the tone gate looks at. With a Hartley
	   transform, |X|^2 = (H[k]^2 + H[N-k]^2) / 2, and H[k] +/- H[N-k] give the
	   real and (negated) imaginary parts, doubled. */
	float total = 0.0f;
	const int gLo = s->kLo - 3 > 1 ? s->kLo - 3 : 1;
	const int gHi = s->kHi + 3 < K - 2 ? s->kHi + 3 : K - 2;
	for (int k = 0; k < K; k++)
	{
		const int kk = (N - k) & mask;
		const float pa = (A[k] * A[k] + A[kk] * A[kk]) * 0.5f;
		const float pb = (B[k] * B[k] + B[kk] * B[kk]) * 0.5f;
		float p;
		if (ms == MS_MODE_STEREO) p = 0.5f * (pa + pb);
		else if (ms == MS_MODE_MID) p = pa;
		else p = pb;
		total += p;
#ifdef BROKEN_NOSMOOTH
		float sm = p;
#else
		float sm = p + s->powC * (s->pow[k] - p);
#endif
		if (sm < 1.0e-30f) sm = 0.0f;
		s->pow[k] = sm;
	}
#ifndef BROKEN_NOSCRUB
	if (!isfinite(total))
	{
		/* Something non-finite got in. Start again from silence rather than let
		   it latch every smoother for good, which would leave the card silent
		   for the rest of the session. The output dips for a frame or so and
		   comes back on its own as the ring refills, faded by the windows. */
		shClearState(s);
		return;
	}
#endif

	/* Tone gate, on the bins the cut could reach. */
	if (!s->catchTones)
	{
		for (int k = gLo; k <= gHi; k++)
		{
			const int kk = (N - k) & mask;
			float re, im;
			if (ms == MS_MODE_STEREO)
			{
				/* The mid signal, so a tone in both channels is judged once. */
				re = 0.5f * ((A[k] + A[kk]) + (B[k] + B[kk]));
				im = 0.5f * ((A[k] - A[kk]) + (B[k] - B[kk]));
			}
			else if (ms == MS_MODE_MID) { re = A[k] + A[kk]; im = A[k] - A[kk]; }
			else { re = B[k] + B[kk]; im = B[k] - B[kk]; }
			const float mag = sqrtf(re * re + im * im);
			/* Predicted from the two frames before: magnitude carried on in a
			   straight line, phase advanced by the same step as last time. */
			float pm = 2.0f * s->mag1[k] - s->mag2[k];
			if (pm < 0.0f) pm = 0.0f;
			const float c1 = s->u1re[k], s1 = s->u1im[k];
			const float c2 = s->u2re[k], s2 = s->u2im[k];
			const float dr = c1 * c1 - s1 * s1, di = 2.0f * c1 * s1;
			const float pr = pm * (dr * c2 + di * s2);
			const float pi = pm * (di * c2 - dr * s2);
			const float er = re - pr, ei = im - pi;
			const float den = mag + pm;
			const float c = den > 1.0e-20f ? sqrtf(er * er + ei * ei) / den : 1.0f;
			/* Quick to call a bin steady, slow to call it noise. Steady means
			   predictable on two frames running: a tone always is, while noise
			   dips under the threshold on about one frame in twenty and almost
			   never twice in a row. Taking a single low reading made the gate
			   flicker on noise, which wobbled the cut on a steady resonance. */
			const float both = c > s->cPrev[k] ? c : s->cPrev[k];
			s->cPrev[k] = c;
			s->cSm[k] = both < s->cSm[k] ? both : 0.5f * (s->cSm[k] + c);
			s->mag2[k] = s->mag1[k];
			s->u2re[k] = c1; s->u2im[k] = s1;
			s->mag1[k] = mag;
			if (mag > 1.0e-20f) { s->u1re[k] = re / mag; s->u1im[k] = im / mag; }
			else { s->u1re[k] = 1.0f; s->u1im[k] = 0.0f; }
			float t = (SHAPER_TONAL_HIGH - s->cSm[k]) / (SHAPER_TONAL_HIGH - SHAPER_TONAL_LOW);
			if (t < 0.0f) t = 0.0f;
			if (t > 1.0f) t = 1.0f;
			s->tmp[k] = t;
		}
		/* Spread over the Hann main lobe, two bins either side. */
		for (int k = s->kLo; k <= s->kHi; k++)
		{
			float m = 0.0f;
			for (int d = -2; d <= 2; d++)
			{
				const int q = k + d;
				if (q >= gLo && q <= gHi && s->tmp[q] > m) m = s->tmp[q];
			}
			s->tonal[k] = m;
		}
	}

	/* Detail level and the reference it is compared with, in dB, over the
	   context range only. */
	const int a = s->kCtxLo, b = s->kCtxHi;
#ifdef BROKEN_NOSMOOTH
	for (int k = a; k <= b; k++) s->tmp[k] = s->pow[k];
#else
	shBox(s, s->pow, s->tmp, a, b, powf(2.0f, 0.5f * s->detailOct));
#endif
	for (int k = a; k <= b; k++)
		s->ld[k] = 10.0f * log10f(s->tmp[k] + s->floorPow);
	const float qRef = powf(2.0f, 0.5f * s->refOct);
	shBox(s, s->ld, s->ref, a, b, qRef);
#ifndef BROKEN_NOCLIP
	for (int pass = 0; pass < SHAPER_REF_PASSES; pass++)
	{
		for (int k = a; k <= b; k++)
			s->tmp[k] = s->ld[k] < s->ref[k] ? s->ld[k] : s->ref[k];
		shBox(s, s->tmp, s->ref, a, b, qRef);
	}
#endif

	/* Cut targets inside the range, before smoothing across frequency. */
	const float amount = s->amountDb;
	const float thr = s->threshDb;
	for (int k = 0; k < K; k++) s->tgt[k] = 0.0f;
	for (int k = s->kLo; k <= s->kHi; k++)
	{
		float over = shKnee(s->ld[k] - s->ref[k] - thr);
		if (over > amount) over = amount;
#ifdef BROKEN_SIGN
		float t = over;
#else
		float t = -over;
#endif
		if (!s->catchTones)
#ifndef BROKEN_NOTONAL
			t *= 1.0f - s->tonal[k];
#else
			;
#endif
		t *= s->band[k];
#ifndef BROKEN_NOPERSIST
		/* Not cut deeper until it has stood out for a few frames in a row. */
		if (t < -0.5f)
		{
			if (s->age[k] < (float)SHAPER_PERSIST_FRAMES) s->age[k] += 1.0f;
		}
		else s->age[k] = 0.0f;
		if (s->age[k] < (float)SHAPER_PERSIST_FRAMES && t < s->gain[k]) t = s->gain[k];
#endif
		s->tgt[k] = t;
	}
#ifdef BROKEN_BROADBAND
	{
		/* What a plain compressor would do: one gain for the whole range,
		   driven by the total level. Harness only. */
		static float avg = -1000.0f;
		const float lt = 10.0f * log10f(total + s->floorPow);
		if (avg < -900.0f) avg = lt;
		avg = lt + 0.97f * (avg - lt);
		float over = lt - avg;
		if (over < 0.0f) over = 0.0f;
		if (over > amount) over = amount;
		for (int k = s->kLo; k <= s->kHi; k++) s->tgt[k] = -over * s->band[k];
	}
#endif

	/* [1 2 1] / 4 across frequency, then attack and release per bin. */
	const float att = s->attC, rel = s->relC;
	float prev = 0.0f;
	for (int k = 0; k < K; k++)
	{
		const float next = k + 1 < K ? s->tgt[k + 1] : 0.0f;
		const float cur = s->tgt[k];
		const float t = 0.25f * (prev + 2.0f * cur + next);
		prev = cur;
		float g = s->gain[k];
		/* Attack while the cut deepens, release while it eases. */
		const float c = t < g ? att : rel;
		g = t + c * (g - t);
		if (g > -1.0e-6f && g < 1.0e-6f) g = 0.0f;
		s->gain[k] = g;
		float lin = expf(g * 0.11512925464970229f);
		if (s->listen) lin = 1.0f - lin;
		s->gainLin[k] = lin;
	}

	/* Apply. The gain is real and the same at k and N - k, so it multiplies
	   the Hartley coefficients directly. The half not being worked on in mid
	   or edges mode passes through, or is silent when listening to what is
	   removed, since nothing is removed from it. */
	const float pass = s->listen ? 0.0f : 1.0f;
	for (int k = 0; k < K; k++)
	{
#ifdef BROKEN_MSSWAP
		/* The cut on the wrong half: harness only. */
		const float gA = (ms == MS_MODE_MID) ? pass : s->gainLin[k];
		const float gB = (ms == MS_MODE_SIDE) ? pass : s->gainLin[k];
#else
		const float gA = (ms == MS_MODE_SIDE) ? pass : s->gainLin[k];
		const float gB = (ms == MS_MODE_MID) ? pass : s->gainLin[k];
#endif
		const int kk = (N - k) & mask;
		A[k] *= gA;
		B[k] *= gB;
		if (kk != k)
		{
			A[kk] *= gA;
			B[kk] *= gB;
		}
	}
	/* The inverse wants bit-reversed input. */
	stftBitRevPermute(A, br, N);
	stftBitRevPermute(B, br, N);
	s->fft(A, s->sinTab);
	s->fft(B, s->sinTab);

	/* Synthesis window, overlap-add. Hann squared at a quarter-frame hop sums to
	   1.5, and the transform pair leaves a factor of N. */
#ifdef BROKEN_NOSCALE
	const float scale = 1.0f / (float)N;
#else
	const float scale = 2.0f / (3.0f * (float)N);
#endif
	for (int j = 0; j < N; j++)
	{
		const int idx = (start + j) & mask;
		const float w = s->win[j] * scale;
		const float ya = A[j] * w, yb = B[j] * w;
		if (ms == MS_MODE_STEREO)
		{
			s->acc[0][idx] += ya;
			s->acc[1][idx] += yb;
		}
		else
		{
			s->acc[0][idx] += ya + yb;
#ifdef BROKEN_MSMIX
			/* Right rebuilt as left: harness only. */
			s->acc[1][idx] += ya + yb;
#else
			s->acc[1][idx] += ya - yb;
#endif
		}
	}
}

void SpectralShaperProcess(JamesDSPLib *jdsp, size_t n)
{
	SpectralShaper *s = &jdsp->shaper;
	if (!s->mem)
	{
		/* Never prepared (no memory). Nothing to fade, so finish any switch-off. */
		if (s->fadingOut) { s->fadingOut = 0; jdsp->shaperEnabled = 0; }
		return;
	}
	float *left = jdsp->tmpBuffer[0];
	float *right = jdsp->tmpBuffer[1];
	const int N = s->N, mask = N - 1, R = s->R, lat = s->latency;
	const float step = 1.0f / (float)s->fadeLen;
#ifdef BROKEN_FIFO
	/* Hop counted per block, as a hop-aligned buffer would: harness only. */
	s->hop = 0;
#endif

	for (size_t i = 0; i < n; i++)
	{
		float xL = left[i], xR = right[i];
#ifndef BROKEN_NOSCRUB
		/* Non-finite in, silence in: what the output stage would make of it
		   anyway, but before it can reach a smoother. */
		if (!isfinite(xL)) xL = 0.0f;
		if (!isfinite(xR)) xR = 0.0f;
		if (xL > SHAPER_INPUT_LIMIT) xL = SHAPER_INPUT_LIMIT;
		if (xL < -SHAPER_INPUT_LIMIT) xL = -SHAPER_INPUT_LIMIT;
		if (xR > SHAPER_INPUT_LIMIT) xR = SHAPER_INPUT_LIMIT;
		if (xR < -SHAPER_INPUT_LIMIT) xR = -SHAPER_INPUT_LIMIT;
#endif
		s->in[0][s->pos] = xL;
		s->in[1][s->pos] = xR;
		if (++s->hop >= R)
		{
			s->hop = 0;
			shFrame(s);
		}
		/* The slot just after the write head holds the sample from N - 1 ago,
		   and the frame that just ran was the last one to add to it. */
		const int rd = (s->pos + 1) & mask;
		const float wL = s->acc[0][rd], wR = s->acc[1][rd];
		s->acc[0][rd] = 0.0f;
		s->acc[1][rd] = 0.0f;
		s->pos = rd;

		/* Exactly dry until the ring has filled, then in over a few ms; the
		   same walk takes it back out on switch-off. */
		float a;
		if (s->filled < lat)
		{
			s->filled++;
			s->wetGain = 0.0f;
			a = 0.0f;
		}
		else
		{
#ifdef BROKEN_NOFADE
			s->wetGain = s->fadingOut ? 0.0f : 1.0f;
#else
			const float target = s->fadingOut ? 0.0f : 1.0f;
			if (s->wetGain < target)
			{
				s->wetGain += step;
				if (s->wetGain > target) s->wetGain = target;
			}
			else if (s->wetGain > target)
			{
				s->wetGain -= step;
				if (s->wetGain < target) s->wetGain = target;
			}
#endif
			a = s->wetGain;
		}
		if (a >= 1.0f)
		{
			left[i] = wL;
			right[i] = wR;
		}
		else if (a > 0.0f)
		{
			left[i] = xL + (wL - xL) * a;
			right[i] = xR + (wR - xR) * a;
		}
		else
		{
			left[i] = xL;
			right[i] = xR;
		}
	}

	/* The switch-off has run its course, so the chain can stop calling us. */
	if (s->fadingOut && (s->wetGain <= 0.0f || s->filled < lat))
	{
		s->fadingOut = 0;
		s->wetGain = 0.0f;
		jdsp->shaperEnabled = 0;
	}
}

void SpectralShaperSetParam(JamesDSPLib *jdsp, float amountDb, float sensitivityPct,
	float loHz, float hiHz, float speedPct, int focus, int catchTones,
	int listen, int msMode)
{
	/* Under the lock: Process reads every one of these on the audio thread. */
	jdsp_lock(jdsp);
	SpectralShaper *s = &jdsp->shaper;
	s->amountDb = shClamp(amountDb, 0.0f, 12.0f, 6.0f);
	s->sensitivityPct = shClamp(sensitivityPct, 0.0f, 100.0f, 60.0f);
	s->loHz = shClamp(loHz, 500.0f, 8000.0f, 2000.0f);
	s->hiHz = shClamp(hiHz, 2000.0f, 16000.0f, 10000.0f);
	if (s->hiHz < 1.5f * s->loHz) s->hiHz = 1.5f * s->loHz;
	s->speedPct = shClamp(speedPct, 0.0f, 100.0f, 50.0f);
	if (focus < SHAPER_FOCUS_NARROW || focus > SHAPER_FOCUS_BROAD) focus = SHAPER_FOCUS_BALANCED;
	s->focus = focus;
	s->catchTones = catchTones ? 1 : 0;
	s->listen = listen ? 1 : 0;
	if (msMode < 0 || msMode >= MS_MODE_COUNT) msMode = MS_MODE_STEREO;
	s->msMode = msMode;
#ifdef BROKEN_RESET
	if (s->mem) { shClearState(s); s->filled = 0; s->wetGain = 0.0f; }
#endif
	if (s->mem) shDerive(s);
	jdsp_unlock(jdsp);
}

void SpectralShaperEnable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	SpectralShaper *s = &jdsp->shaper;
	if (jdsp->shaperEnabled)
	{
		/* Already on, or fading out: the buffers are full and current, so turn
		   round rather than refill. */
		s->fadingOut = 0;
		jdsp_unlock(jdsp);
		return;
	}
	const float fs = jdsp->fs > 0.0f ? jdsp->fs : 48000.0f;
	if (!shPrepare(s, fs))
	{
		jdsp_unlock(jdsp);
		return;
	}
	shClearState(s);
	s->filled = 0;
	s->wetGain = 0.0f;
	s->fadingOut = 0;
	jdsp->shaperEnabled = 1;
	jdsp_unlock(jdsp);
}

void SpectralShaperDisable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	SpectralShaper *s = &jdsp->shaper;
	if (jdsp->shaperEnabled)
	{
		/* Not cleared here: that would stop the chain calling Process and the
		   output would jump from the delayed signal to the live one between two
		   samples. Process fades out and clears the flag itself. */
		s->fadingOut = 1;
	}
	else if (s->mem)
	{
		/* Fully off (the fade has finished). The app sends the switch state
		   with every settings change, so this is reached soon after. */
		free(s->mem);
		s->mem = NULL;
		s->N = 0;
	}
	jdsp_unlock(jdsp);
}

/* Unlocked on purpose: JamesDSPSetSampleRate holds the lock. A new rate that
   needs a different frame size reallocates; otherwise only the coefficients
   and the range move, and the audio state is kept. */
void SpectralShaperRefresh(JamesDSPLib *jdsp)
{
	SpectralShaper *s = &jdsp->shaper;
	const float fs = jdsp->fs > 0.0f ? jdsp->fs : 48000.0f;
	if (!s->mem)
	{
		s->fs = fs;
		return;
	}
#ifdef BROKEN_NOREFRESH
	s->fs = fs;
	shDerive(s);
#else
	if (!shPrepare(s, fs))
	{
		/* Kept the old buffers. Running them at the wrong size is still safe,
		   just tuned for the old rate. */
		s->fs = fs;
		shDerive(s);
	}
#endif
}

void SpectralShaperFree(JamesDSPLib *jdsp)
{
	SpectralShaper *s = &jdsp->shaper;
	if (s->mem) free(s->mem);
	s->mem = NULL;
	s->N = 0;
	jdsp->shaperEnabled = 0;
}

int SpectralShaperLatency(JamesDSPLib *jdsp)
{
	int lat = 0;
	jdsp_lock(jdsp);
	if (jdsp->shaperEnabled && jdsp->shaper.mem)
	{
#ifdef BROKEN_LATENCY
		lat = jdsp->shaper.N - jdsp->shaper.R;
#else
		lat = jdsp->shaper.latency;
#endif
	}
	jdsp_unlock(jdsp);
	return lat;
}
