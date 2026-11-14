/*
 * LUFS normalizer: keeps the loudness steady from one song to the next.
 *
 * Loudness is measured the way ITU-R BS.1770 measures it: K-weighted, in
 * 400 ms blocks taken every 100 ms, with a relative gate 10 LU under the mean
 * of the blocks. Here that runs over the last few seconds rather than a whole
 * programme, and a gain moves slowly towards whatever brings the result to
 * the target. It is feed-forward: what it measures is its own input, ahead of
 * its gain, so nothing after it (the maximiser, the output limiter) can reach
 * back into the measurement, and the two can never chase each other.
 *
 * Three rules keep it from doing harm:
 *
 *   Quiet     It never raises the gain while the last 100 ms are under
 *             -50 LUFS: a pause, the tail of a fade, hiss or room tone between
 *             tracks. Real programme, even quiet classical, sits well above
 *             that. Two seconds of it and the history is dropped, so the next
 *             song is measured on its own; until then a boost eases back to
 *             unity and a cut is held, so a loud song after a quiet one never
 *             arrives boosted.
 *   Limits    Boost and cut each have their own ceiling, and the gain only
 *             ever approaches its target from one side, so it cannot
 *             overshoot either.
 *   Peaks     With the peak guard on, a boost never lifts the loudest sample
 *             of the measured history above where the output limiter starts,
 *             or -1 dBFS if that is lower, so the limiter is not left doing
 *             the normalizer's job.
 *
 * Behaviour after the LUFS normalizer in evoeram's Loundess-PEQ-RootlessJamesDSP
 * (GPLv3), whose idea follows LUFSTargeting in ViPERDSP, built on the reverse
 * engineering of ViPER4Android by Martmists, Iscle and likelikeslike; the
 * original ViPER4Android is the work of Zhuhang and ViPER520. Rewritten for
 * this engine: an exact sliding window in place of a decaying accumulator, a
 * history whose length follows the speed, separate boost and cut limits, the
 * quiet floor and the peak guard.
 *
 * K-weighting: both BS.1770 stages are designed per sample rate from the
 * parameters libebur128 uses (MIT, Copyright (c) 2011 Jan Kokemüller), which
 * reproduce the standard's published 48 kHz coefficients and give the same
 * response at 44.1 kHz. See THIRD_PARTY_NOTICES.md.
 */
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include "../jdsp_header.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

/* Pre-filter (high shelf) and RLB (high pass) parameters, as in libebur128. */
#define KW_SHELF_F0 1681.974450955533
#define KW_SHELF_G 3.999843853973347
#define KW_SHELF_Q 0.7071752369554196
#define KW_HP_F0 38.13547087602444
#define KW_HP_Q 0.5003270373238773

/* LUFS = -0.691 + 10 log10(sum of the channels' mean squares). */
#define LUFS_OFFSET (-0.691)
/* A block this far under the mean of the history does not count (BS.1770). */
#define LUFS_REL_GATE 0.1
/* Blocks quieter than this are a pause, not programme. BS.1770's own absolute
   gate is -70 LUFS, meant for a whole programme; for a running measurement
   that would let hiss between tracks set the gain. */
#define LUFS_QUIET_FLOOR (-50.0)
/* Blocks of quiet, at one per 100 ms, after which the history is dropped. */
#define LUFS_QUIET_RESET 20
/* Gain control rate: the gain is recomputed every this many samples and
   ramped in between. */
#define LUFS_CTL 16

/* Per speed: seconds of history, then the time constants (seconds) for the
   gain rising and falling. Rising is always the slower: a boost that comes
   late is a song starting a little quiet, one that comes early is a blast. */
static const float lufsHistorySec[3] = { 20.0f, 10.0f, 4.0f };
static const float lufsUpSec[3] = { 4.0f, 2.0f, 0.8f };
static const float lufsDownSec[3] = { 1.5f, 0.7f, 0.25f };

static double lufsFs(JamesDSPLib *jdsp)
{
	return jdsp->fs > 0.0f ? (double)jdsp->fs : 48000.0;
}

/* Both K-weighting stages for one rate. The shelf is normalised by a0; the
   high pass keeps the (1, -2, 1) numerator the standard publishes. */
static void lufsDesignKWeighting(LufsNormalizer *l, double fs)
{
	double K = tan(M_PI * KW_SHELF_F0 / fs);
	const double Vh = pow(10.0, KW_SHELF_G / 20.0);
	const double Vb = pow(Vh, 0.4996667741545416);
	double a0 = 1.0 + K / KW_SHELF_Q + K * K;
	l->kb[0][0] = (Vh + Vb * K / KW_SHELF_Q + K * K) / a0;
	l->kb[0][1] = 2.0 * (K * K - Vh) / a0;
	l->kb[0][2] = (Vh - Vb * K / KW_SHELF_Q + K * K) / a0;
	l->ka[0][0] = 2.0 * (K * K - 1.0) / a0;
	l->ka[0][1] = (1.0 - K / KW_SHELF_Q + K * K) / a0;

	K = tan(M_PI * KW_HP_F0 / fs);
	a0 = 1.0 + K / KW_HP_Q + K * K;
	l->kb[1][0] = 1.0;
	l->kb[1][1] = -2.0;
	l->kb[1][2] = 1.0;
	l->ka[1][0] = 2.0 * (K * K - 1.0) / a0;
	l->ka[1][1] = (1.0 - K / KW_HP_Q + K * K) / a0;
}

/* Everything worked out from the rate and the speed. */
static void lufsDesign(LufsNormalizer *l, double fs)
{
	lufsDesignKWeighting(l, fs);
	l->fs = (float)fs;
	l->subLen = (int)(fs * 0.1 + 0.5);
	l->histLen = (int)(lufsHistorySec[l->speed] * 10.0f + 0.5f);
	if (l->histLen > LUFS_HISTORY_MAX)
		l->histLen = LUFS_HISTORY_MAX;
	l->upCoef = 1.0 - exp(-(double)LUFS_CTL / (lufsUpSec[l->speed] * fs));
	l->downCoef = 1.0 - exp(-(double)LUFS_CTL / (lufsDownSec[l->speed] * fs));
}

/* The filters and the block being gathered. The history is kept: loudness
   already measured stays true whatever the rate is now. */
static void lufsClearRunning(LufsNormalizer *l)
{
	memset(l->kz, 0, sizeof(l->kz));
	l->subPos = 0;
	l->subSum = 0.0;
	l->subPeak = 0.0f;
	l->subIdx = 0;
	l->subFilled = 0;
	l->pendingValid = 0;
}

static void lufsClearHistory(LufsNormalizer *l)
{
	l->histIdx = 0;
	l->histCount = 0;
	l->valid = 0;
	l->measured = -70.0;
	l->peakDb = -200.0;
}

/* Anything that is not a number, or is past +60 dBFS, is measured as
   silence: one such sample would otherwise sit in the filters for good. */
static inline double lufsClean(float x)
{
	return (x == x && fabsf(x) < 1000.0f) ? (double)x : 0.0;
}

void LufsNormalizerSetParam(JamesDSPLib *jdsp, float targetLufs, float maxBoostDb,
	float maxCutDb, int speed, int peakGuard)
{
	/* Each written so NaN fails the first test and lands on the safe end:
	   the quietest target, no boost, no cut. */
	if (!(targetLufs >= -30.0f)) targetLufs = -30.0f;
	if (targetLufs > -5.0f) targetLufs = -5.0f;
	if (!(maxBoostDb >= 0.0f)) maxBoostDb = 0.0f;
	if (maxBoostDb > 12.0f) maxBoostDb = 12.0f;
	if (!(maxCutDb >= 0.0f)) maxCutDb = 0.0f;
	if (maxCutDb > 24.0f) maxCutDb = 24.0f;
	if (speed < 0 || speed > 2) speed = 1;
	jdsp_lock(jdsp);
	LufsNormalizer *l = &jdsp->lufsNorm;
	l->target = targetLufs;
	l->maxBoost = maxBoostDb;
	l->maxCut = maxCutDb;
	l->speed = speed;
	l->peakGuard = peakGuard ? 1 : 0;
	lufsDesign(l, lufsFs(jdsp));
	jdsp_unlock(jdsp);
}

/* For a sample rate change; the caller holds the lock. */
void LufsNormalizerRefresh(JamesDSPLib *jdsp)
{
	LufsNormalizer *l = &jdsp->lufsNorm;
	lufsDesign(l, lufsFs(jdsp));
	lufsClearRunning(l);
}

/* Where a boost has to stop so the loudest recent sample stays clear of the
   output limiter, in dB of gain. */
static double lufsPeakRoom(JamesDSPLib *jdsp, const LufsNormalizer *l)
{
	double ceiling = 0.8912509381337456; /* -1 dBFS */
	const float th = jdsp->limiter.threshold;
	if (jdsp->limiter.mode != 2 && th > 0.0f && th < ceiling)
		ceiling = th;
	/* The post gain is applied between here and the limiter. */
	if (jdsp->postGain > 0.0f && isfinite(jdsp->postGain))
		ceiling /= jdsp->postGain;
	return 20.0 * log10(ceiling) - l->peakDb;
}

/* The integrated loudness of the history, gated as BS.1770 gates it, and the
   highest peak in it. */
static void lufsMeasure(LufsNormalizer *l)
{
	int m = l->histCount < l->histLen ? l->histCount : l->histLen;
	if (m <= 0)
	{
		l->valid = 0;
		return;
	}
	double sum = 0.0;
	float peak = 0.0f;
	for (int i = 0, j = l->histIdx; i < m; i++)
	{
		j = j > 0 ? j - 1 : LUFS_HISTORY_MAX - 1;
		sum += l->histPow[j];
		if (l->histPeak[j] > peak)
			peak = l->histPeak[j];
	}
	const double gate = sum / (double)m * LUFS_REL_GATE;
	double gated = 0.0;
	int n = 0;
	for (int i = 0, j = l->histIdx; i < m; i++)
	{
		j = j > 0 ? j - 1 : LUFS_HISTORY_MAX - 1;
		if (l->histPow[j] > gate)
		{
			gated += l->histPow[j];
			n++;
		}
	}
	/* Never empty: the largest block is always above a tenth of the mean. */
	l->measured = LUFS_OFFSET + 10.0 * log10(gated / (double)n);
	l->peakDb = peak > 1e-10f ? 20.0 * log10((double)peak) : -200.0;
	l->valid = 1;
}

static int lufsIsQuiet(double meanSquare)
{
	return !(meanSquare > 0.0) || LUFS_OFFSET + 10.0 * log10(meanSquare) < LUFS_QUIET_FLOOR;
}

/* Every 100 ms: close the sub-block, take the 400 ms block it completes, and
   decide where the gain is heading.

   Quiet is judged on the newest 100 ms alone, so the gain stops rising within
   100 ms of the music stopping. Each block also waits one step before it
   joins the history and is dropped if quiet follows it: the blocks that
   straddle the end of a song are part silence, and let in they read low and
   would nudge the gain up just as the music stops. */
static void lufsSubBlockDone(JamesDSPLib *jdsp, LufsNormalizer *l)
{
	const double sub = l->subSum / (double)l->subLen;
	l->subPow[l->subIdx] = sub;
	l->subPk[l->subIdx] = l->subPeak;
	l->subIdx = (l->subIdx + 1) & 3;
	if (l->subFilled < 4)
		l->subFilled++;
	l->subSum = 0.0;
	l->subPeak = 0.0f;
	l->subPos = 0;
	if (l->subFilled < 4)
		return;
	const int quiet = lufsIsQuiet(sub);

	if (!quiet)
	{
		l->quietBlocks = 0;
		if (l->pendingValid && !lufsIsQuiet(l->pendingPow))
		{
			l->histPow[l->histIdx] = l->pendingPow;
			l->histPeak[l->histIdx] = l->pendingPeak;
			l->histIdx = (l->histIdx + 1) % LUFS_HISTORY_MAX;
			if (l->histCount < LUFS_HISTORY_MAX)
				l->histCount++;
			lufsMeasure(l);
		}
	}
	else if (++l->quietBlocks >= LUFS_QUIET_RESET && l->histCount > 0)
		lufsClearHistory(l);

	l->pendingPow = 0.25 * (l->subPow[0] + l->subPow[1] + l->subPow[2] + l->subPow[3]);
	l->pendingPeak = l->subPk[0];
	for (int i = 1; i < 4; i++)
		if (l->subPk[i] > l->pendingPeak) l->pendingPeak = l->subPk[i];
	l->pendingValid = 1;

	double want;
	if (!l->valid)
	{
		/* Nothing measured: let a boost go back to unity, hold a cut. */
		want = l->gainDb < 0.0 ? l->gainDb : 0.0;
	}
	else
	{
		want = (double)l->target - l->measured;
		if (l->peakGuard && want > 0.0)
		{
			double room = lufsPeakRoom(jdsp, l);
			if (room < 0.0) room = 0.0;
			if (want > room) want = room;
		}
		if (quiet && want > l->gainDb)
			want = l->gainDb;
	}
	if (want > (double)l->maxBoost) want = (double)l->maxBoost;
	if (want < -(double)l->maxCut) want = -(double)l->maxCut;
	l->wantDb = want;
}

void LufsNormalizerProcess(JamesDSPLib *jdsp, size_t n)
{
	LufsNormalizer *l = &jdsp->lufsNorm;
	if (!l->histPow || !l->histPeak)
		return;
	float *left = jdsp->tmpBuffer[0];
	float *right = jdsp->tmpBuffer[1];
	const double b00 = l->kb[0][0], b01 = l->kb[0][1], b02 = l->kb[0][2];
	const double a01 = l->ka[0][0], a02 = l->ka[0][1];
	const double a11 = l->ka[1][0], a12 = l->ka[1][1];

	for (size_t i = 0; i < n; i++)
	{
		const double xl = lufsClean(left[i]);
		const double xr = lufsClean(right[i]);
		/* Transposed direct form II, shelf then high pass, per channel. The
		   high pass numerator is (1, -2, 1). */
		double y = b00 * xl + l->kz[0][0][0];
		l->kz[0][0][0] = b01 * xl - a01 * y + l->kz[0][0][1];
		l->kz[0][0][1] = b02 * xl - a02 * y;
		double kl = y + l->kz[0][1][0];
		l->kz[0][1][0] = -2.0 * y - a11 * kl + l->kz[0][1][1];
		l->kz[0][1][1] = y - a12 * kl;
		y = b00 * xr + l->kz[1][0][0];
		l->kz[1][0][0] = b01 * xr - a01 * y + l->kz[1][0][1];
		l->kz[1][0][1] = b02 * xr - a02 * y;
		double kr = y + l->kz[1][1][0];
		l->kz[1][1][0] = -2.0 * y - a11 * kr + l->kz[1][1][1];
		l->kz[1][1][1] = y - a12 * kr;
		l->subSum += kl * kl + kr * kr;
		const float pk = (float)(fabs(xl) > fabs(xr) ? fabs(xl) : fabs(xr));
		if (pk > l->subPeak)
			l->subPeak = pk;
		if (++l->subPos >= l->subLen)
			lufsSubBlockDone(jdsp, l);

		if (++l->ctlPos >= LUFS_CTL)
		{
			l->ctlPos = 0;
			const double c = l->wantDb > l->gainDb ? l->upCoef : l->downCoef;
			l->gainDb += c * (l->wantDb - l->gainDb);
			l->gainLin = l->gainNext;
			l->gainNext = (float)pow(10.0, l->gainDb / 20.0);
			l->gainStep = (l->gainNext - l->gainLin) / (float)LUFS_CTL;
		}
		l->gainLin += l->gainStep;
		left[i] *= l->gainLin;
		right[i] *= l->gainLin;
	}
	/* Denormals in the filter state cost time on some cores once the input
	   has gone quiet, and a state that went bad starts again from zero. */
	for (int c = 0; c < 2; c++)
		for (int s = 0; s < 2; s++)
			for (int k = 0; k < 2; k++)
			{
				const double v = l->kz[c][s][k];
				if (!isfinite(v) || fabs(v) < 1e-30)
					l->kz[c][s][k] = 0.0;
			}
}

void LufsNormalizerEnable(JamesDSPLib *jdsp)
{
	/* Under the lock: Process may be running on the audio thread, and it must
	   not see a half-built history. */
	jdsp_lock(jdsp);
	LufsNormalizer *l = &jdsp->lufsNorm;
	if (!jdsp->lufsNormEnabled)
	{
		if (!l->histPow) l->histPow = (double *)calloc(LUFS_HISTORY_MAX, sizeof(double));
		if (!l->histPeak) l->histPeak = (float *)calloc(LUFS_HISTORY_MAX, sizeof(float));
		if (!l->histPow || !l->histPeak)
		{
			/* Refuse to run half allocated. Inline rather than Disable, which
			   takes the lock this is already holding. */
			free(l->histPow); l->histPow = 0;
			free(l->histPeak); l->histPeak = 0;
			jdsp_unlock(jdsp);
			return;
		}
		lufsDesign(l, lufsFs(jdsp));
		lufsClearRunning(l);
		lufsClearHistory(l);
		l->quietBlocks = 0;
		/* Starts at unity: nothing is known about the music yet. */
		l->gainDb = 0.0;
		l->wantDb = 0.0;
		l->gainLin = l->gainNext = 1.0f;
		l->gainStep = 0.0f;
		l->ctlPos = 0;
		jdsp->lufsNormEnabled = 1;
	}
	jdsp_unlock(jdsp);
}

void LufsNormalizerDisable(JamesDSPLib *jdsp)
{
	LufsNormalizer *l = &jdsp->lufsNorm;
	/* Clear the flag first so no further block enters, then take the lock,
	   which waits for a block already inside Process to leave. */
	jdsp->lufsNormEnabled = 0;
	jdsp_lock(jdsp);
	free(l->histPow); l->histPow = 0;
	free(l->histPeak); l->histPeak = 0;
	jdsp_unlock(jdsp);
}
