// Pitch shifting, two ways.
//
// Granular: dual-tap ring buffer with equal-power sine crossfade (classic
// harmonizer topology). Cheap and instant, but the two read heads drift in and
// out of phase with each other on a held note - the warble people describe as
// the chipmunk or flanged quality. No amount of tuning removes it; it is what
// the method is.
//
// Smooth: a phase vocoder, further down. Stays coherent on held notes, at the
// cost of latency and CPU. Neither is simply better, so both are offered.
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include "../jdsp_header.h"

#define PS_BUFLEN 8192

static int pvAllocBuffers(PitchShift *p);
static void pvFreeBuffers(PitchShift *p);

void PitchShiftSetParam(JamesDSPLib *jdsp, float semitones, float mixPct, int mode)
{
	/* Held for the whole update. Changing mode swaps which code path Process
	   takes AND whether the vocoder buffers exist, so an unlocked write here
	   could send the audio thread down the smooth path a moment before its
	   buffers were allocated. */
	jdsp_lock(jdsp);
	PitchShift *p = &jdsp->pitchShift;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	if (semitones > 12.0f) semitones = 12.0f;
	if (semitones < -12.0f) semitones = -12.0f;
	p->rate = powf(2.0f, semitones / 12.0f);
	p->mix = mixPct * 0.01f;
	if (p->mix < 0.0f) p->mix = 0.0f;
	if (p->mix > 1.0f) p->mix = 1.0f;
	if (mode < 0 || mode >= PITCH_MODE_COUNT) mode = PITCH_MODE_GRANULAR;
	p->mode = mode;
	p->pvFs = fs;
	int win = (int)(0.05f * fs);
	if (win > PS_BUFLEN - 200) win = PS_BUFLEN - 200;
	if (win < 256) win = 256;
	p->win = win;
	// No shift asked for and nothing to blend against: skip the stage rather
	// than run it. It is not a null - at 44.1kHz the half-window makes the
	// read interpolate between neighbouring samples, which is a two-tap
	// lowpass six decibels down at 15kHz, so leaving it running costs treble
	// and 25ms of latency to achieve nothing. The same argument applies to the
	// vocoder, which would otherwise cost its own latency for no change.
	p->bypass = (fabsf(semitones) < 1e-6f && p->mix >= 0.999f) ? 1 : 0;

	/* Buffers follow the mode, so switching to Granular gives the memory back
	   rather than holding it for a path no longer in use. If the allocation
	   fails, pvReady stays clear and Process quietly uses the granular path -
	   worse sounding, but running. */
	if (p->mode == PITCH_MODE_SMOOTH)
	{
		if (!pvAllocBuffers(p)) p->mode = PITCH_MODE_GRANULAR;
	}
	else
		pvFreeBuffers(p);
	jdsp_unlock(jdsp);
}

static inline float psRead(const float *buf, float pos)
{
	int i0 = (int)pos;
	float fr = pos - (float)i0;
	int i1 = (i0 + 1) & (PS_BUFLEN - 1);
	return buf[i0 & (PS_BUFLEN - 1)] * (1.0f - fr) + buf[i1] * fr;
}

/* ---------------------------------------------------------------------------
 * Smooth mode: a phase vocoder.
 *
 * Each frame is transformed, each bin's TRUE frequency is recovered from how
 * far its phase moved since the last frame, the bins are moved to their new
 * positions, and the phase is advanced to match where the shifted partial
 * should now be. Because the phase is tracked rather than spliced, a held note
 * stays coherent - which is the whole reason to pay for it.
 *
 * The transient reset is what keeps it from sounding smeared. A vocoder
 * assumes every partial continues smoothly, which is exactly wrong at a drum
 * hit: the phases it has carefully maintained now describe a sound that has
 * ended, and the attack arrives blurred across the frame. So the frame-to-frame
 * rise in magnitude is watched, and when it jumps the synthesis phases are
 * reset to the analysis phases, letting the transient through sharp.
 * ------------------------------------------------------------------------- */

/* In-place radix-2 FFT. sign -1 forward, +1 inverse (inverse scales by 1/n).
   Written out rather than borrowed from the engine, whose FFT is wrapped
   inside a convolver that only exposes a whole convolution. */
static void pvFft(float *re, float *im, int n, int sign)
{
	int i, j = 0, k, len;
	for (i = 1; i < n; i++)
	{
		int bit = n >> 1;
		for (; j & bit; bit >>= 1) j ^= bit;
		j ^= bit;
		if (i < j)
		{
			float t = re[i]; re[i] = re[j]; re[j] = t;
			t = im[i]; im[i] = im[j]; im[j] = t;
		}
	}
	for (len = 2; len <= n; len <<= 1)
	{
		const double ang = 2.0 * M_PI / (double)len * (double)sign;
		const float wr = (float)cos(ang), wi = (float)sin(ang);
		for (i = 0; i < n; i += len)
		{
			float cr = 1.0f, ci = 0.0f;
			for (k = 0; k < len / 2; k++)
			{
				const int a = i + k, b = a + len / 2;
				const float xr = re[b] * cr - im[b] * ci;
				const float xi = re[b] * ci + im[b] * cr;
				re[b] = re[a] - xr; im[b] = im[a] - xi;
				re[a] += xr;        im[a] += xi;
				const float nr = cr * wr - ci * wi;
				ci = cr * wi + ci * wr; cr = nr;
			}
		}
	}
	if (sign > 0)
	{
		const float s = 1.0f / (float)n;
		for (i = 0; i < n; i++) { re[i] *= s; im[i] *= s; }
	}
}

static void pvFreeBuffers(PitchShift *p)
{
	for (int c = 0; c < 2; c++)
	{
		if (p->pvIn[c]) { free(p->pvIn[c]); p->pvIn[c] = 0; }
		if (p->pvOut[c]) { free(p->pvOut[c]); p->pvOut[c] = 0; }
		if (p->pvAccum[c]) { free(p->pvAccum[c]); p->pvAccum[c] = 0; }
		if (p->pvLastPhase[c]) { free(p->pvLastPhase[c]); p->pvLastPhase[c] = 0; }
		if (p->pvSumPhase[c]) { free(p->pvSumPhase[c]); p->pvSumPhase[c] = 0; }
		if (p->pvPrevMag[c]) { free(p->pvPrevMag[c]); p->pvPrevMag[c] = 0; }
	}
	if (p->pvWindow) { free(p->pvWindow); p->pvWindow = 0; }
	if (p->pvRe) { free(p->pvRe); p->pvRe = 0; }
	if (p->pvIm) { free(p->pvIm); p->pvIm = 0; }
	if (p->pvAnaMag) { free(p->pvAnaMag); p->pvAnaMag = 0; }
	if (p->pvAnaFreq) { free(p->pvAnaFreq); p->pvAnaFreq = 0; }
	if (p->pvSynMag) { free(p->pvSynMag); p->pvSynMag = 0; }
	if (p->pvSynFreq) { free(p->pvSynFreq); p->pvSynFreq = 0; }
	if (p->pvSrcBin) { free(p->pvSrcBin); p->pvSrcBin = 0; }
	p->pvReady = 0;
}

/* Returns 0 if anything could not be allocated, leaving nothing half-built:
   Process checks pvReady and falls back rather than reading a null. */
static int pvAllocBuffers(PitchShift *p)
{
	if (p->pvReady) return 1;
	int ok = 1;
	for (int c = 0; c < 2 && ok; c++)
	{
		p->pvIn[c] = (float*)calloc(PV_SIZE, sizeof(float));
		p->pvOut[c] = (float*)calloc(PV_SIZE, sizeof(float));
		p->pvAccum[c] = (float*)calloc(PV_SIZE * 2, sizeof(float));
		p->pvLastPhase[c] = (float*)calloc(PV_BINS, sizeof(float));
		p->pvSumPhase[c] = (float*)calloc(PV_BINS, sizeof(float));
		p->pvPrevMag[c] = (float*)calloc(PV_BINS, sizeof(float));
		ok = p->pvIn[c] && p->pvOut[c] && p->pvAccum[c] &&
		     p->pvLastPhase[c] && p->pvSumPhase[c] && p->pvPrevMag[c];
	}
	if (ok)
	{
		p->pvWindow = (float*)calloc(PV_SIZE, sizeof(float));
		p->pvRe = (float*)calloc(PV_SIZE, sizeof(float));
		p->pvIm = (float*)calloc(PV_SIZE, sizeof(float));
		p->pvAnaMag = (float*)calloc(PV_BINS, sizeof(float));
		p->pvAnaFreq = (float*)calloc(PV_BINS, sizeof(float));
		p->pvSynMag = (float*)calloc(PV_BINS, sizeof(float));
		p->pvSynFreq = (float*)calloc(PV_BINS, sizeof(float));
		p->pvSrcBin = (int*)calloc(PV_BINS, sizeof(int));
		ok = p->pvWindow && p->pvRe && p->pvIm && p->pvAnaMag &&
		     p->pvAnaFreq && p->pvSynMag && p->pvSynFreq && p->pvSrcBin;
	}
	if (!ok) { pvFreeBuffers(p); return 0; }
	for (int i = 0; i < PV_SIZE; i++)
		p->pvWindow[i] = 0.5f - 0.5f * cosf(2.0f * (float)M_PI * (float)i / (float)PV_SIZE);
	p->pvRover = 0;
	p->pvFlux[0] = p->pvFlux[1] = 0.0f;
	p->pvTransients = 0;
	p->pvReady = 1;
	return 1;
}

/* One analysis/synthesis frame for one channel. */
static void pvFrame(PitchShift *p, int c, float ratio, float fs)
{
	const float freqPerBin = fs / (float)PV_SIZE;
	const float expected = 2.0f * (float)M_PI * (float)PV_HOP / (float)PV_SIZE;
	const float osamp = (float)PV_SIZE / (float)PV_HOP;
	float *re = p->pvRe, *im = p->pvIm;
	int k;

	for (k = 0; k < PV_SIZE; k++)
	{
		re[k] = p->pvIn[c][k] * p->pvWindow[k];
		im[k] = 0.0f;
	}
	pvFft(re, im, PV_SIZE, -1);

	float flux = 0.0f, totalMag = 0.0f;
	for (k = 0; k < PV_BINS; k++)
	{
		const float mag = sqrtf(re[k] * re[k] + im[k] * im[k]);
		const float phase = atan2f(im[k], re[k]);
		float d = phase - p->pvLastPhase[c][k];
		p->pvLastPhase[c][k] = phase;
		d -= (float)k * expected;
		/* Wrap the leftover into +/-pi. Phase is only ever known modulo a
		   turn, and without this every bin reads as far higher than it is. */
		int qpd = (int)(d / (float)M_PI);
		if (qpd >= 0) qpd += qpd & 1; else qpd -= qpd & 1;
		d -= (float)M_PI * (float)qpd;
		d = osamp * d / (2.0f * (float)M_PI);
		p->pvAnaMag[k] = mag;
		p->pvAnaFreq[k] = ((float)k + d) * freqPerBin;
		const float rise = mag - p->pvPrevMag[c][k];
		if (rise > 0.0f) flux += rise;
		totalMag += mag;
		p->pvPrevMag[c][k] = mag;
	}

	/* Two tests, and it needs both.
	   Against the recent average, so it fires on a quiet passage as readily as
	   a loud one. And against the frame's own total, because the ratio test
	   alone is meaningless when the signal is steady: flux is then almost zero,
	   and almost-zero divided by almost-zero crosses any threshold you like on
	   rounding noise. That is not hypothetical - it fired continuously on a
	   held sine and cost about 20dB of the shifted partial. A real onset moves
	   a serious fraction of the whole frame. */
	const int transient = (p->pvFlux[c] > 1e-9f) && (flux > p->pvFlux[c] * 2.5f) &&
	                      (flux > totalMag * 0.15f);
	if (transient) p->pvTransients++;
	p->pvFlux[c] = p->pvFlux[c] * 0.7f + flux * 0.3f;

	for (k = 0; k < PV_BINS; k++)
	{
		p->pvSynMag[k] = 0.0f; p->pvSynFreq[k] = 0.0f; p->pvSrcBin[k] = k;
	}
	for (k = 0; k < PV_BINS; k++)
	{
		const int idx = (int)((float)k * ratio);
		if (idx >= 0 && idx < PV_BINS)
		{
			p->pvSynMag[idx] += p->pvAnaMag[k];
			p->pvSynFreq[idx] = p->pvAnaFreq[k] * ratio;
			p->pvSrcBin[idx] = k;
		}
	}

	for (k = 0; k < PV_BINS; k++)
	{
		float d = p->pvSynFreq[k] / freqPerBin - (float)k;
		d = 2.0f * (float)M_PI * d / osamp;
		d += (float)k * expected;
		if (transient)
			/* The phase of the bin this content actually came FROM. Using bin
			   k's own phase looks equivalent and is not: after a shift, bin k
			   holds a partial that was measured somewhere else entirely, so
			   restarting it on k's phase makes successive frames fight rather
			   than reinforce - which cost about 35dB and was invisible at
			   ratio 1.0, where the two happen to coincide. */
			p->pvSumPhase[c][k] = p->pvLastPhase[c][p->pvSrcBin[k]];
		else
			p->pvSumPhase[c][k] += d;
		const float ph = p->pvSumPhase[c][k];
		re[k] = p->pvSynMag[k] * cosf(ph);
		im[k] = p->pvSynMag[k] * sinf(ph);
	}
	/* Mirrored as a conjugate so the inverse gives back a real signal. */
	for (k = PV_BINS; k < PV_SIZE; k++)
	{
		re[k] = re[PV_SIZE - k];
		im[k] = -im[PV_SIZE - k];
	}
	pvFft(re, im, PV_SIZE, 1);

	/* Hann applied at both ends sums to 1.5 at this hop, so divide it out. */
	for (k = 0; k < PV_SIZE; k++)
		p->pvAccum[c][k] += p->pvWindow[k] * re[k] * (1.0f / 1.5f);

	memcpy(p->pvOut[c], p->pvAccum[c], PV_HOP * sizeof(float));
	memmove(p->pvAccum[c], p->pvAccum[c] + PV_HOP,
	        (PV_SIZE * 2 - PV_HOP) * sizeof(float));
	memset(p->pvAccum[c] + PV_SIZE * 2 - PV_HOP, 0, PV_HOP * sizeof(float));
	memmove(p->pvIn[c], p->pvIn[c] + PV_HOP, (PV_SIZE - PV_HOP) * sizeof(float));
}

static void pvProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n)
{
	const float fs = p->pvFs > 0.0f ? p->pvFs : 48000.0f;
	for (size_t i = 0; i < n; i++)
	{
		const float dry[2] = { jdsp->tmpBuffer[0][i], jdsp->tmpBuffer[1][i] };
		for (int c = 0; c < 2; c++)
		{
			p->pvIn[c][PV_SIZE - PV_HOP + p->pvRover] = dry[c];
			const float wet = p->pvOut[c][p->pvRover];
			jdsp->tmpBuffer[c][i] = dry[c] + p->mix * (wet - dry[c]);
		}
		p->pvRover++;
		if (p->pvRover >= PV_HOP)
		{
			p->pvRover = 0;
			for (int c = 0; c < 2; c++) pvFrame(p, c, p->rate, fs);
		}
	}
}

void PitchShiftProcess(JamesDSPLib *jdsp, size_t n)
{
	PitchShift *p = &jdsp->pitchShift;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	if (p->bypass)
		return;
	if (p->mode == PITCH_MODE_SMOOTH)
	{
		/* Falls back rather than reading a null if the allocation failed. */
		if (p->pvReady)
		{
			pvProcess(jdsp, p, n);
			return;
		}
	}
	if (!p->buf[0] || !p->buf[1])
		return;
	float W = (float)p->win;
	float halfW = W * 0.5f;
	float piW = 3.14159265358979f / W;
	for (i = 0; i < n; i++)
	{
		int w = p->w;
		float l = jdsp->tmpBuffer[0][i];
		float r = jdsp->tmpBuffer[1][i];
		p->buf[0][w] = l;
		p->buf[1][w] = r;

		float d1 = p->phasor;
		float d2 = d1 + halfW;
		if (d2 >= W) d2 -= W;
		float g1 = sinf(piW * d1);
		float g2 = sinf(piW * d2);

		float p1 = (float)w - d1;
		float p2 = (float)w - d2;
		if (p1 < 0.0f) p1 += PS_BUFLEN;
		if (p2 < 0.0f) p2 += PS_BUFLEN;

		float wetL = g1 * psRead(p->buf[0], p1) + g2 * psRead(p->buf[0], p2);
		float wetR = g1 * psRead(p->buf[1], p1) + g2 * psRead(p->buf[1], p2);

		jdsp->tmpBuffer[0][i] = l + p->mix * (wetL - l);
		jdsp->tmpBuffer[1][i] = r + p->mix * (wetR - r);

		p->phasor += (1.0f - p->rate);
		while (p->phasor >= W) p->phasor -= W;
		while (p->phasor < 0.0f) p->phasor += W;
		p->w = (w + 1) & (PS_BUFLEN - 1);
	}
}

void PitchShiftEnable(JamesDSPLib *jdsp)
{
	// Under the lock: Process may be running on the audio thread, and it must
	// not observe a half-built set of buffers.
	jdsp_lock(jdsp);
	if (!jdsp->pitchShiftEnabled)
	{
		PitchShift *ps = &jdsp->pitchShift;
		for (int c = 0; c < 2; c++)
			if (!ps->buf[c])
				ps->buf[c] = (float*)calloc(PS_BUFLEN, sizeof(float));
		if (!ps->buf[0] || !ps->buf[1])
		{
			jdsp->pitchShiftEnabled = 0;
			jdsp_unlock(jdsp);
			return;
		}
		for (int c = 0; c < 2; c++)
			memset(ps->buf[c], 0, PS_BUFLEN * sizeof(float));
		ps->w = 0;
		ps->phasor = 0.0f;
	}
	// SetParam owns the vocoder buffers, but it may have run before this card
	// was ever switched on, so make sure they exist for the chosen mode.
	{
		PitchShift *ps = &jdsp->pitchShift;
		if (ps->mode == PITCH_MODE_SMOOTH && !pvAllocBuffers(ps))
			ps->mode = PITCH_MODE_GRANULAR;
	}
	jdsp->pitchShiftEnabled = 1;
	jdsp_unlock(jdsp);
}

void PitchShiftDisable(JamesDSPLib *jdsp)
{
	PitchShift *ps = &jdsp->pitchShift;
	jdsp->pitchShiftEnabled = 0;
	// Clear the flag first so no further block enters, then take the lock,
	// which waits for any block already inside Process to leave. Freeing
	// without that wait is a use-after-free on the audio thread.
	jdsp_lock(jdsp);
	for (int c = 0; c < 2; c++)
		if (ps->buf[c]) { free(ps->buf[c]); ps->buf[c] = 0; }
	pvFreeBuffers(ps);
	jdsp_unlock(jdsp);
}
