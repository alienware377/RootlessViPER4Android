/*
 * Third-party notice for the Smooth mode phase vocoder in this file.
 *
 * The phase vocoder further down (pvFrame: the analysis, phase unwrapping,
 * bin shifting and resynthesis steps) is derived from smbPitchShift by
 * Stephan M. Bernsee. Only that part of the file derives from it.
 *
 * Upstream: https://blogs.zynaptiq.com/bernsee/pitch-shifting-using-the-ft/
 * Source:   https://blogs.zynaptiq.com/bernsee/repo/smbPitchShift.cpp
 * Licence:  The Wide Open License (WOL). It requires the copyright notice
 *           and licence below to appear in all source copies, so they are
 *           reproduced here unchanged.
 *
 * COPYRIGHT 1999-2015 Stephan M. Bernsee <s.bernsee [AT] zynaptiq [DOT] com>
 *
 *                       The Wide Open License (WOL)
 *
 * Permission to use, copy, modify, distribute and sell this software and its
 * documentation for any purpose is hereby granted without fee, provided that
 * the above copyright notice and this license appear in all source copies.
 * THIS SOFTWARE IS PROVIDED "AS IS" WITHOUT EXPRESS OR IMPLIED WARRANTY OF
 * ANY KIND. See http://www.dspguru.com/wol.htm for more information.
 */
// Pitch shifting, four ways.
//
// Granular: dual-tap ring buffer with equal-power sine crossfade (classic
// harmonizer topology). Cheap and instant, but the two read heads drift in and
// out of phase with each other on a held note - the warble people describe as
// the chipmunk or flanged quality. No amount of tuning removes it; it is what
// the method is.
//
// Smooth: a phase vocoder, further down. Stays coherent on held notes, at the
// cost of latency and CPU. Neither is simply better, so both are offered.
//
// Rubber Band: the optional download, in rubberband.c.
//
// Signalsmith: Signalsmith Stretch, built in, through signalsmith.cpp. The
// cleanest of the built-in methods and the slowest to answer: what comes out
// is what went in 150ms earlier.
//
// What ties them together is a history of the dry input that is kept whatever
// the method. Granular reads its taps from it. Signalsmith reads its input
// from it, and blends against the dry signal delayed by exactly its own delay,
// so a partial mix is a mix rather than a slapback echo. And a method chosen
// while the music plays is started from it, so it begins with what just played
// instead of with silence, and is crossfaded in from the method before it once
// it has something to give.
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include "../jdsp_header.h"

// The longest granular window, in samples.
#define PS_BUFLEN 8192
// Audio goes through in pieces no longer than this, which is what bounds the
// scratch buffers and the work done between two checks of the crossfade.
#define PS_CHUNK 256
// How long a switch from one method to another takes once the new one is ready.
#define PS_XF_SEC 0.04f

// What is producing the sound. Zero is "no crossfade" for xfFrom, so that a
// zeroed struct means nothing is fading.
enum
{
	PS_XF_NONE = 0,
	PS_ENGINE_DRY,
	PS_ENGINE_GRANULAR,
	PS_ENGINE_SMOOTH,
	PS_ENGINE_RUBBERBAND,
	PS_ENGINE_SIGNALSMITH
};

static int pvAllocBuffers(PitchShift *p);
static void pvFreeBuffers(PitchShift *p);
static void pvProcess(PitchShift *p, const float *const *in, float *const *out, int n);

static float psRate(const JamesDSPLib *jdsp)
{
	const float fs = (float)jdsp->fs;
	return fs < 8000.0f ? 48000.0f : fs;
}

/* ---------------------------------------------------------------------------
 * The dry history.
 * ------------------------------------------------------------------------- */

// Long enough to prime Signalsmith (one seek length to seek over and one to
// run through, so its output is already full the moment it is heard), with
// room for the block being written. Never shorter than the longest granular
// window either.
static int psHistLen(float fs)
{
	const int seek = (int)(fs * SIGNALSMITH_BLOCK_SEC) + (int)(fs * SIGNALSMITH_INTERVAL_SEC) + 2;
	const int need = 2 * seek + 2 * PS_CHUNK;
	int len = 16384;
	while (len < need) len <<= 1;
	return len;
}

static void psHistFree(PitchShift *p)
{
	for (int c = 0; c < 2; c++)
		if (p->hist[c]) { free(p->hist[c]); p->hist[c] = 0; }
	for (int k = 0; k < 4; k++)
		if (p->scratch[k]) { free(p->scratch[k]); p->scratch[k] = 0; }
	p->histMask = 0;
	p->histPos = 0;
	p->histEpoch++;
}

// Empties the history, making it the right length for fs. Allocates, so it is
// only ever called from Enable and Refresh, never from the audio thread.
static int psHistReset(PitchShift *p, float fs)
{
	const int len = psHistLen(fs);
	if (p->hist[0] && p->histMask + 1 != len)
		psHistFree(p);
	for (int c = 0; c < 2; c++)
	{
		if (!p->hist[c]) p->hist[c] = (float *)calloc((size_t)len, sizeof(float));
		else memset(p->hist[c], 0, (size_t)len * sizeof(float));
	}
	for (int k = 0; k < 4; k++)
		if (!p->scratch[k]) p->scratch[k] = (float *)calloc(PS_CHUNK, sizeof(float));
	if (!p->hist[0] || !p->hist[1] || !p->scratch[0] || !p->scratch[1] ||
	    !p->scratch[2] || !p->scratch[3])
	{
		psHistFree(p);
		return 0;
	}
	p->histMask = len - 1;
	p->histPos = 0;
	p->histEpoch++;
	return 1;
}

/* ---------------------------------------------------------------------------
 * Signalsmith set-up.
 *
 * Building an instance allocates a few hundred kilobytes and priming it runs
 * ten or so of its blocks, and the audio thread waits on jdsp_lock for every
 * block of this card. So both happen here, outside the lock, on whichever
 * thread changed the setting: the history is copied out under the lock (a
 * memcpy), the copy is fed to the new instance with the lock released, and
 * only swapping the finished instance in takes the lock again. The few
 * milliseconds of audio that went past meanwhile are fed to it on the audio
 * thread before its first block, from the live history.
 * ------------------------------------------------------------------------- */

typedef struct
{
	void *inst;
	float fs;
	int epoch, primed, failed;
	int64_t fedTo;
} PsFresh;

// Which modes end up on Signalsmith: its own two, and the Rubber Band pair
// when the download is not there to run them.
static int psWantsSignalsmith(int mode)
{
	return PITCH_MODE_IS_SIGNALSMITH(mode) ||
	       (PITCH_MODE_IS_RUBBERBAND(mode) && !RubberBandAvailable());
}

static int psFormantMode(int mode)
{
	return mode == PITCH_MODE_SIGNALSMITH_FORMANT || mode == PITCH_MODE_RUBBERBAND_FORMANT;
}

// An instance that is not being fed every block has fallen behind the music,
// so only one that is still being run counts as ready to reuse.
static int psSignalsmithLive(const PitchShift *p, float fs)
{
	return p->ss && p->ssFs == fs &&
	       (p->engine == PS_ENGINE_SIGNALSMITH || p->xfFrom == PS_ENGINE_SIGNALSMITH);
}

// Called WITHOUT the lock. mode < 0 means "whatever is set now", for Enable.
static void psPrepareSignalsmith(JamesDSPLib *jdsp, int mode, float rate, int forEnable, PsFresh *f)
{
	PitchShift *p = &jdsp->pitchShift;
	memset(f, 0, sizeof(*f));
	if (mode >= 0 && !psWantsSignalsmith(mode))
		return;
	jdsp_lock(jdsp);
	if (mode < 0) { mode = p->mode; rate = p->rate; }
	const float fs = psRate(jdsp);
	const int enabled = jdsp->pitchShiftEnabled;
	const int have = psSignalsmithLive(p, fs);
	const int len = p->histMask + 1;
	const int running = enabled && p->hist[0] != 0;
	jdsp_unlock(jdsp);
	// A card that is off builds nothing until it is switched on.
	if (have || !psWantsSignalsmith(mode) || (!enabled && !forEnable))
		return;
	f->inst = SignalsmithCreate(fs, psFormantMode(mode));
	if (!f->inst)
	{
		f->failed = 1;
		return;
	}
	f->fs = fs;
	SignalsmithSetRatio(f->inst, rate);
	if (!running)
		return;
	float *copy = (float *)malloc((size_t)len * 2 * sizeof(float));
	if (!copy)
		return;
	int ok = 0;
	int64_t pos = 0;
	jdsp_lock(jdsp);
	if (jdsp->pitchShiftEnabled && p->hist[0] && p->histMask + 1 == len)
	{
		memcpy(copy, p->hist[0], (size_t)len * sizeof(float));
		memcpy(copy + len, p->hist[1], (size_t)len * sizeof(float));
		pos = p->histPos;
		f->epoch = p->histEpoch;
		ok = 1;
	}
	jdsp_unlock(jdsp);
	if (ok)
	{
		// Seek over one seek length, then run the next one through with the
		// output thrown away: after a seek alone the overlap-add has nothing
		// in it yet, and the first part of what comes out would be a fade in.
		// Positions before the card was switched on read as silence, since
		// the history starts out zeroed and is longer than both together.
		const float *ring[2] = { copy, copy + len };
		const int S = SignalsmithSeekLength(f->inst);
		float tmpL[PS_CHUNK], tmpR[PS_CHUNK];
		float *out[2] = { tmpL, tmpR };
		SignalsmithSeekRing(f->inst, ring, len - 1, pos - 2 * (int64_t)S, S);
		for (int64_t q = pos - S; q < pos;)
		{
			const int k = (pos - q) > PS_CHUNK ? PS_CHUNK : (int)(pos - q);
			SignalsmithProcessRing(f->inst, ring, len - 1, q, out, k);
			q += k;
		}
		f->fedTo = pos;
		f->primed = 1;
	}
	free(copy);
}

// Anything taken out of service under the lock is handed back to be destroyed
// after it is released. There is only ever one per call; a second is
// destroyed on the spot rather than leaked.
static void psTrash(void *inst, void **trash)
{
	if (!inst) return;
	if (*trash) SignalsmithDestroy(*trash);
	*trash = inst;
}

// Under the lock. Returns how many samples the instance has to run before it
// is worth hearing, or -1 if it could not be started.
static int psSignalsmithReady(PitchShift *p, float fs, int formant, int live, PsFresh *f, void **trash)
{
	int warm = 0;
	if (!(live && p->ss && p->ssFs == fs))
	{
		void *inst = 0;
		int primed = 0;
		int64_t fedTo = p->histPos;
		psTrash(p->ss, trash);
		p->ss = 0;
		if (f && f->inst && f->fs == fs)
		{
			inst = f->inst;
			f->inst = 0;
			if (f->primed && f->epoch == p->histEpoch)
			{
				primed = 1;
				fedTo = f->fedTo;
			}
		}
		else if (!(f && f->failed))
			// Only when there was no chance to build one beforehand: a sample
			// rate change, or Rubber Band refusing to start a moment after it
			// said it was there. Rare enough to take the stall.
			inst = SignalsmithCreate(fs, formant);
		if (!inst)
			return -1;
		p->ss = inst;
		p->ssFs = fs;
		p->ssLatency = SignalsmithLatency(inst);
		p->ssSeekLen = SignalsmithSeekLength(inst);
		p->ssCatchUpMax = 0;
		if (!primed)
		{
			// Seeking only copies, so it is cheap enough to do here. Its
			// output still has to fill, so it is not heard until it has.
			const float *ring[2] = { p->hist[0], p->hist[1] };
			SignalsmithSeekRing(inst, ring, p->histMask, p->histPos - p->ssSeekLen, p->ssSeekLen);
			fedTo = p->histPos;
			warm = p->ssLatency;
		}
		p->ssFedTo = fedTo;
	}
	SignalsmithSetFormant(p->ss, formant);
	SignalsmithSetRatio(p->ss, p->rate);
	// What it plays is the music from ssLatency ago, which is silence until
	// the card has been on that long.
	if (p->histPos < p->ssLatency && warm < p->ssLatency - (int)p->histPos)
		warm = p->ssLatency - (int)p->histPos;
	return warm;
}

/* ---------------------------------------------------------------------------
 * Choosing what plays.
 * ------------------------------------------------------------------------- */

// Gives the chosen method what it needs, walking one step down the ladder
// whenever that fails rather than leaving the card silent: Rubber Band that
// is not installed, or will not start, becomes Signalsmith, keeping the
// formant choice; Signalsmith that cannot start becomes Smooth; Smooth that
// cannot allocate becomes Granular, which needs nothing beyond the history
// Enable already made. Worse sounding, still running.
//
// Also says how long the method needs to run before it has anything real to
// give, which is how long a switch to it waits before fading.
static int psResolve(PitchShift *p, float fs, int ssLive, PsFresh *f, void **trash, int *warm)
{
	int mode = p->mode;
	*warm = 0;
	if (PITCH_MODE_IS_RUBBERBAND(mode))
	{
		if (RubberBandPrepare(p, fs, mode == PITCH_MODE_RUBBERBAND_FORMANT))
		{
			*warm = 2 * p->rbBlock;
			return PS_ENGINE_RUBBERBAND;
		}
		mode = mode == PITCH_MODE_RUBBERBAND_FORMANT ? PITCH_MODE_SIGNALSMITH_FORMANT
		                                              : PITCH_MODE_SIGNALSMITH;
	}
	if (PITCH_MODE_IS_SIGNALSMITH(mode))
	{
		const int w = psSignalsmithReady(p, fs, mode == PITCH_MODE_SIGNALSMITH_FORMANT,
		                                 ssLive, f, trash);
		if (w >= 0)
		{
			p->mode = mode;
			*warm = w;
			return PS_ENGINE_SIGNALSMITH;
		}
		mode = PITCH_MODE_SMOOTH;
	}
	if (mode == PITCH_MODE_SMOOTH)
	{
		if (pvAllocBuffers(p))
		{
			p->mode = mode;
			*warm = PV_SIZE;
			return PS_ENGINE_SMOOTH;
		}
	}
	p->mode = PITCH_MODE_GRANULAR;
	return PS_ENGINE_GRANULAR;
}

static int psTarget(const PitchShift *p)
{
	return p->bypass ? PS_ENGINE_DRY : (p->engine ? p->engine : PS_ENGINE_DRY);
}

// What the listener mostly hears right now: the outgoing method until the
// fade is half way, the incoming one after.
static int psHeard(const PitchShift *p)
{
	if (p->xfFrom != PS_XF_NONE && (p->xfWait > 0 || p->xfPos * 2 < p->xfLen))
		return p->xfFrom;
	return psTarget(p);
}

// Hands back the memory of every method that is neither playing nor fading
// out, so choosing Granular really does give the vocoder's buffers back.
static void psReleaseUnused(PitchShift *p, void **trash)
{
	const int a = p->engine, b = p->xfFrom;
	if (a != PS_ENGINE_SMOOTH && b != PS_ENGINE_SMOOTH)
		pvFreeBuffers(p);
	if (a != PS_ENGINE_RUBBERBAND && b != PS_ENGINE_RUBBERBAND)
		RubberBandRelease(p);
	if (a != PS_ENGINE_SIGNALSMITH && b != PS_ENGINE_SIGNALSMITH && p->ss)
	{
		psTrash(p->ss, trash);
		p->ss = 0;
	}
}

// The caller holds the lock - jdsp_lock is not recursive, so this must not
// take it. startingUp is set when the card was off until now (or the stream
// just restarted at a new rate), so there is nothing to fade from but the dry
// signal itself.
static void psApply(PitchShift *p, float fs, PsFresh *f, void **trash, int startingUp)
{
	const int targetBefore = startingUp ? PS_ENGINE_DRY : psTarget(p);
	const int heardBefore = startingUp ? PS_ENGINE_DRY : psHeard(p);
	const int ssLive = psSignalsmithLive(p, fs);
	int warm;
	p->engine = psResolve(p, fs, ssLive, f, trash, &warm);
	// No shift asked for and nothing to blend against: skip the stage rather
	// than run it. It is not a null - at 44.1kHz the half-window makes the
	// granular read interpolate between neighbouring samples, which is a
	// two-tap lowpass six decibels down at 15kHz, so leaving it running costs
	// treble and 25ms of latency to achieve nothing. The same argument applies
	// to the vocoder, which would otherwise cost its own latency for no change.
	//
	// Except Signalsmith. Its delay is 150ms, and skipping it at exactly zero
	// would take that delay away and put it back every time the slider went
	// through the middle, which is a skip and a repeat each way. It keeps
	// running, unshifted.
	p->bypass = (p->engine != PS_ENGINE_SIGNALSMITH && fabsf(p->rate - 1.0f) < 1e-6f &&
	             p->mix >= 0.999f) ? 1 : 0;
	const int target = psTarget(p);
	if (startingUp)
	{
		p->xfFrom = PS_XF_NONE;
		// Smooth and Rubber Band ramp their own wet path in from the dry
		// signal. Signalsmith's delay is too long for that: the dry signal
		// plays, undelayed, until the delayed one has real music in it, and
		// then gives way to it.
		if (target == PS_ENGINE_SIGNALSMITH)
		{
			p->xfFrom = PS_ENGINE_DRY;
			p->xfWait = warm;
			p->xfPos = 0;
			p->xfLen = (int)(PS_XF_SEC * fs);
		}
	}
	else if (target != targetBefore)
	{
		if (target == heardBefore)
			// Switched back before the fade got half way: what is heard is
			// already this one, so just stop fading.
			p->xfFrom = PS_XF_NONE;
		else
		{
			p->xfFrom = heardBefore;
			p->xfWait = target == PS_ENGINE_DRY ? 0 : warm;
			p->xfPos = 0;
			p->xfLen = (int)(PS_XF_SEC * fs);
			// The crossfade covers the new method's arrival, so its own
			// ramp from dry would only blend in the unshifted signal.
			if (target == PS_ENGINE_SMOOTH || target == PS_ENGINE_RUBBERBAND)
				p->wetGain = 1.0f;
		}
	}
	psReleaseUnused(p, trash);
}

void PitchShiftSetParam(JamesDSPLib *jdsp, float semitones, float mixPct, int mode)
{
	PitchShift *p = &jdsp->pitchShift;
	PsFresh fresh;
	void *trash = 0;
	if (semitones > 12.0f) semitones = 12.0f;
	if (semitones < -12.0f) semitones = -12.0f;
	if (mode < 0 || mode >= PITCH_MODE_COUNT) mode = PITCH_MODE_GRANULAR;
	const float rate = powf(2.0f, semitones / 12.0f);
	psPrepareSignalsmith(jdsp, mode, rate, 0, &fresh);
	/* Held for the whole update. Changing mode swaps which code path Process
	   takes AND whether the buffers behind it exist, so an unlocked write
	   here could send the audio thread down a path a moment before its
	   buffers were allocated. */
	jdsp_lock(jdsp);
	const float fs = psRate(jdsp);
	p->rate = rate;
	p->mix = mixPct * 0.01f;
	if (p->mix < 0.0f) p->mix = 0.0f;
	if (p->mix > 1.0f) p->mix = 1.0f;
	p->mode = mode;
	p->pvFs = fs;
	int win = (int)(0.05f * fs);
	if (win > PS_BUFLEN - 200) win = PS_BUFLEN - 200;
	if (win < 256) win = 256;
	p->win = win;
	// A card that is off sets nothing up: Enable does that when it comes on.
	if (jdsp->pitchShiftEnabled && p->hist[0])
		psApply(p, fs, &fresh, &trash, 0);
	jdsp_unlock(jdsp);
	SignalsmithDestroy(trash);
	SignalsmithDestroy(fresh.inst);
}

static inline float psRead(const float *buf, float pos, int mask)
{
	int i0 = (int)pos;
	float fr = pos - (float)i0;
	int i1 = (i0 + 1) & mask;
	return buf[i0 & mask] * (1.0f - fr) + buf[i1] * fr;
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
   The engine's own transform is reachable directly (the DFTn codelets in
   eel2/numericSys/codelet.c, used by dynamic.c and spectralShaper.c), but it
   is a real Hartley transform; this code wants complex bins, so it keeps its
   own. */
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
	// Nothing comes out of the overlap-add until a whole frame has gone in, and
	// pvOut starts at zero. At a full wet mix that is the output disappearing
	// for twenty-odd milliseconds every time this mode is chosen. Holding the
	// dry signal and fading the wet in over it turns the same gap into the
	// effect arriving.
	p->wetGain = 0.0f;
	p->fadeLen = (float)PV_SIZE + (p->pvFs > 0.0f ? p->pvFs : 48000.0f) * 0.02f;
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
		{
			/* Wrapped to +-pi every hop. Left to grow, held material (where no
			   transient re-anchors it) took it to 1.8e7 rad in two minutes,
			   where one float step is a whole radian: each hop's advance then
			   rounded the same wrong way, the bins drifted off pitch on their
			   own, and after about 15 minutes the high partials were noise. */
			const float s = p->pvSumPhase[c][k] + d;
			p->pvSumPhase[c][k] = s - 6.283185307f * rintf(s * 0.159154943f);
		}
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

static void pvProcess(PitchShift *p, const float *const *in, float *const *out, int n)
{
	const float fs = p->pvFs > 0.0f ? p->pvFs : 48000.0f;
	const float step = p->fadeLen > 0.0f ? 1.0f / p->fadeLen : 1.0f;
	float g = p->wetGain;
	for (int i = 0; i < n; i++)
	{
		const float dry[2] = { in[0][i], in[1][i] };
		for (int c = 0; c < 2; c++)
		{
			p->pvIn[c][PV_SIZE - PV_HOP + p->pvRover] = dry[c];
			const float wet = p->pvOut[c][p->pvRover];
			out[c][i] = dry[c] + p->mix * g * (wet - dry[c]);
		}
		if (g < 1.0f) { g += step; if (g > 1.0f) g = 1.0f; }
		p->pvRover++;
		if (p->pvRover >= PV_HOP)
		{
			p->pvRover = 0;
			for (int c = 0; c < 2; c++) pvFrame(p, c, p->rate, fs);
		}
	}
	p->wetGain = g;
}

/* ---------------------------------------------------------------------------
 * Running each method over one chunk. The chunk is already in the history at
 * histPos, which is where Granular and Signalsmith read it from.
 * ------------------------------------------------------------------------- */

static void psGranular(PitchShift *p, float *const *out, int m)
{
	const int mask = p->histMask;
	const float len = (float)(mask + 1);
	const float W = (float)p->win;
	const float halfW = W * 0.5f;
	const float piW = 3.14159265358979f / W;
	for (int i = 0; i < m; i++)
	{
		const int w = (int)((p->histPos + i) & mask);
		const float l = p->hist[0][w];
		const float r = p->hist[1][w];

		float d1 = p->phasor;
		float d2 = d1 + halfW;
		if (d2 >= W) d2 -= W;
		float g1 = sinf(piW * d1);
		float g2 = sinf(piW * d2);

		float p1 = (float)w - d1;
		float p2 = (float)w - d2;
		if (p1 < 0.0f) p1 += len;
		if (p2 < 0.0f) p2 += len;

		float wetL = g1 * psRead(p->hist[0], p1, mask) + g2 * psRead(p->hist[0], p2, mask);
		float wetR = g1 * psRead(p->hist[1], p1, mask) + g2 * psRead(p->hist[1], p2, mask);

		out[0][i] = l + p->mix * (wetL - l);
		out[1][i] = r + p->mix * (wetR - r);

		p->phasor += (1.0f - p->rate);
		while (p->phasor >= W) p->phasor -= W;
		while (p->phasor < 0.0f) p->phasor += W;
	}
}

// Feeds Signalsmith whatever history it has not seen yet, output discarded.
// Normally nothing, or the few milliseconds that went by while a new instance
// was being primed. If it fell so far behind that the history has moved on,
// it is pointed at the latest audio instead; that costs a short fade but no
// burst of work.
static void psSignalsmithCatchUp(PitchShift *p, int m)
{
	const int64_t gap = p->histPos - p->ssFedTo;
	if (gap <= 0)
		return;
	const float *ring[2] = { p->hist[0], p->hist[1] };
	const int len = p->histMask + 1;
	if (gap > len - m - PS_CHUNK)
	{
		SignalsmithSeekRing(p->ss, ring, p->histMask, p->histPos - p->ssSeekLen, p->ssSeekLen);
		p->ssFedTo = p->histPos;
		return;
	}
	if (gap > p->ssCatchUpMax) p->ssCatchUpMax = (int)gap;
	float *tmp[2] = { p->scratch[0], p->scratch[1] };
	while (p->ssFedTo < p->histPos)
	{
		const int k = (p->histPos - p->ssFedTo) > PS_CHUNK ? PS_CHUNK : (int)(p->histPos - p->ssFedTo);
		SignalsmithProcessRing(p->ss, ring, p->histMask, p->ssFedTo, tmp, k);
		p->ssFedTo += k;
	}
}

static void psSignalsmith(PitchShift *p, float *const *out, int m)
{
	const float *ring[2] = { p->hist[0], p->hist[1] };
	float *wet[2] = { p->scratch[0], p->scratch[1] };
	const int mask = p->histMask;
	SignalsmithProcessRing(p->ss, ring, mask, p->histPos, wet, m);
	p->ssFedTo = p->histPos + m;
	// Blended against the dry signal from exactly as long ago as the wet one,
	// so a partial mix stays in time with itself.
	const int64_t base = p->histPos - p->ssLatency;
	const float mix = p->mix;
	for (int c = 0; c < 2; c++)
	{
		const float *h = p->hist[c];
		const float *w = wet[c];
		float *o = out[c];
		for (int i = 0; i < m; i++)
		{
			const float d = h[(base + i) & mask];
			o[i] = d + mix * (w[i] - d);
		}
	}
}

static void psRun(PitchShift *p, int engine, float *const *in, float *const *out, int m)
{
	switch (engine)
	{
	case PS_ENGINE_SIGNALSMITH:
		if (p->ss) { psSignalsmith(p, out, m); return; }
		break;
	case PS_ENGINE_RUBBERBAND:
		/* rbReady is what counts, not the mode: if the download went missing
		   between one block and the next, nothing is behind it. */
		if (p->rbReady) { RubberBandProcess(p, (const float *const *)in, out, m); return; }
		break;
	case PS_ENGINE_SMOOTH:
		/* Passes the dry signal rather than reading a null if the allocation
		   failed. */
		if (p->pvReady) { pvProcess(p, (const float *const *)in, out, m); return; }
		break;
	case PS_ENGINE_GRANULAR:
		psGranular(p, out, m);
		return;
	}
	if (out != in)
		for (int c = 0; c < 2; c++)
			memcpy(out[c], in[c], (size_t)m * sizeof(float));
}

static void psChunk(PitchShift *p, float *l, float *r, int m)
{
	const int mask = p->histMask;
	const int64_t pos = p->histPos;
	for (int i = 0; i < m; i++)
	{
		const int k = (int)((pos + i) & mask);
		p->hist[0][k] = l[i];
		p->hist[1][k] = r[i];
	}
	if (p->ss && (p->engine == PS_ENGINE_SIGNALSMITH || p->xfFrom == PS_ENGINE_SIGNALSMITH))
		psSignalsmithCatchUp(p, m);
	float *io[2] = { l, r };
	const int target = psTarget(p);
	if (p->xfFrom == PS_XF_NONE)
		psRun(p, target, io, io, m);
	else
	{
		// Both run: the old one so it can fade out, the new one so it is
		// already filled with audio by the time it is faded in. Equal power,
		// since the two are generally at different delays and so unrelated.
		float *old[2] = { p->scratch[2], p->scratch[3] };
		psRun(p, p->xfFrom, io, old, m);
		psRun(p, target, io, io, m);
		const float k = 1.5707963268f / (float)(p->xfLen > 0 ? p->xfLen : 1);
		for (int i = 0; i < m; i++)
		{
			float a, b;
			if (p->xfWait > 0)
			{
				p->xfWait--;
				a = 1.0f; b = 0.0f;
			}
			else if (p->xfPos < p->xfLen)
			{
				const float x = ((float)p->xfPos + 0.5f) * k;
				a = cosf(x); b = sinf(x);
				p->xfPos++;
			}
			else
			{
				a = 0.0f; b = 1.0f;
			}
			l[i] = a * old[0][i] + b * l[i];
			r[i] = a * old[1][i] + b * r[i];
		}
		// Done. What faded out is freed by the next change of setting, off
		// the audio thread; it is simply no longer run.
		if (p->xfWait <= 0 && p->xfPos >= p->xfLen)
			p->xfFrom = PS_XF_NONE;
	}
	p->histPos = pos + m;
}

void PitchShiftProcess(JamesDSPLib *jdsp, size_t n)
{
	PitchShift *p = &jdsp->pitchShift;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1] || !p->hist[0])
		return;
	for (size_t off = 0; off < n;)
	{
		const int m = (n - off) > PS_CHUNK ? PS_CHUNK : (int)(n - off);
		psChunk(p, jdsp->tmpBuffer[0] + off, jdsp->tmpBuffer[1] + off, m);
		off += (size_t)m;
	}
}

void PitchShiftEnable(JamesDSPLib *jdsp)
{
	PitchShift *ps = &jdsp->pitchShift;
	PsFresh fresh;
	void *trash = 0;
	psPrepareSignalsmith(jdsp, -1, 1.0f, 1, &fresh);
	// Under the lock: Process may be running on the audio thread, and it must
	// not observe a half-built set of buffers.
	jdsp_lock(jdsp);
	const float fs = psRate(jdsp);
	const int startingUp = !jdsp->pitchShiftEnabled;
	if (startingUp)
	{
		if (!psHistReset(ps, fs))
		{
			jdsp->pitchShiftEnabled = 0;
			jdsp_unlock(jdsp);
			SignalsmithDestroy(fresh.inst);
			return;
		}
		ps->phasor = 0.0f;
		ps->engine = PS_ENGINE_DRY;
		ps->xfFrom = PS_XF_NONE;
	}
	// SetParam owns everything the chosen mode needs, but it may have run
	// before this card was ever switched on - and, for Rubber Band, before the
	// download existed at all. Ask again here.
	ps->pvFs = fs;
	psApply(ps, fs, &fresh, &trash, startingUp);
	jdsp->pitchShiftEnabled = 1;
	jdsp_unlock(jdsp);
	SignalsmithDestroy(trash);
	SignalsmithDestroy(fresh.inst);
}

void PitchShiftDisable(JamesDSPLib *jdsp)
{
	PitchShift *ps = &jdsp->pitchShift;
	jdsp->pitchShiftEnabled = 0;
	// Clear the flag first so no further block enters, then take the lock,
	// which waits for any block already inside Process to leave. Freeing
	// without that wait is a use-after-free on the audio thread.
	jdsp_lock(jdsp);
	psHistFree(ps);
	pvFreeBuffers(ps);
	RubberBandRelease(ps);
	void *ss = ps->ss;
	ps->ss = 0;
	ps->engine = PS_ENGINE_DRY;
	ps->xfFrom = PS_XF_NONE;
	jdsp_unlock(jdsp);
	SignalsmithDestroy(ss);
}

// A sample rate change used to leave pvFs at whatever it was built for, which
// quietly put every partial in the wrong place, and would leave Rubber Band
// running at the wrong rate entirely. Called with the lock already held, from
// JamesDSPSetSampleRate and nowhere else.
void PitchShiftRefresh(JamesDSPLib *jdsp)
{
	PitchShift *p = &jdsp->pitchShift;
	const float fs = psRate(jdsp);
	void *trash = 0;
	if (!jdsp->pitchShiftEnabled)
		return;
	p->pvFs = fs;
	int win = (int)(0.05f * fs);
	if (win > PS_BUFLEN - 200) win = PS_BUFLEN - 200;
	if (win < 256) win = 256;
	p->win = win;
	// The vocoder's history describes the old rate, so start it again rather
	// than letting the first frames chase a discontinuity. Everything else
	// starts again too: the stream has restarted, and the history (sized for
	// the old rate) and a Signalsmith instance built for it are both wrong.
	// Rebuilding Signalsmith here does stall one block, since the caller holds
	// the lock, but so does the rate change itself.
	pvFreeBuffers(p);
	if (p->ss)
	{
		SignalsmithDestroy(p->ss);
		p->ss = 0;
	}
	if (!psHistReset(p, fs))
	{
		// Process passes the audio through untouched without a history.
		p->engine = PS_ENGINE_DRY;
		p->xfFrom = PS_XF_NONE;
		return;
	}
	p->engine = PS_ENGINE_DRY;
	p->xfFrom = PS_XF_NONE;
	psApply(p, fs, 0, &trash, 1);
	SignalsmithDestroy(trash);
}

// How far behind the input the output is, in samples, for whatever is playing
// now. Granular's two taps move about, so it is their average.
int PitchShiftLatency(JamesDSPLib *jdsp)
{
	PitchShift *p = &jdsp->pitchShift;
	int lat = 0;
	jdsp_lock(jdsp);
	if (jdsp->pitchShiftEnabled)
	{
		switch (psTarget(p))
		{
		case PS_ENGINE_SIGNALSMITH: lat = p->ssLatency; break;
		case PS_ENGINE_SMOOTH: lat = PV_SIZE; break;
		case PS_ENGINE_RUBBERBAND: lat = p->rbBlock; break;
		case PS_ENGINE_GRANULAR: lat = p->win / 2; break;
		default: lat = 0; break;
		}
	}
	jdsp_unlock(jdsp);
	return lat;
}
