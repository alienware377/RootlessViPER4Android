// ViPER FET compressor, Cure+ crossfeed, ViPER bass, Reverberation (freeverb
// style) and Speaker optimization — inspired ports for the V4A edition fork.
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include "../jdsp_header.h"

static void vx2Biquad(float *c, float fs, float f0, float q, float gainDb, int type)
{
	// type: 0 lowshelf, 1 peaking, 2 highpass
	float A = powf(10.0f, gainDb / 40.0f);
	float w0 = 2.0f * 3.14159265358979f * f0 / fs;
	float cw = cosf(w0), sw = sinf(w0);
	float alpha = sw / (2.0f * q);
	float a0, b0, b1, b2, a1, a2;
	if (type == 0)
	{
		float sq = 2.0f * sqrtf(A) * alpha;
		a0 = (A + 1.0f) + (A - 1.0f) * cw + sq;
		b0 = A * ((A + 1.0f) - (A - 1.0f) * cw + sq);
		b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cw);
		b2 = A * ((A + 1.0f) - (A - 1.0f) * cw - sq);
		a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cw);
		a2 = (A + 1.0f) + (A - 1.0f) * cw - sq;
	}
	else if (type == 1)
	{
		a0 = 1.0f + alpha / A;
		b0 = 1.0f + alpha * A;
		b1 = -2.0f * cw;
		b2 = 1.0f - alpha * A;
		a1 = -2.0f * cw;
		a2 = 1.0f - alpha / A;
	}
	else
	{
		a0 = 1.0f + alpha;
		b0 = (1.0f + cw) * 0.5f;
		b1 = -(1.0f + cw);
		b2 = b0;
		a1 = -2.0f * cw;
		a2 = 1.0f - alpha;
	}
	c[0] = b0 / a0; c[1] = b1 / a0; c[2] = b2 / a0;
	c[3] = a1 / a0; c[4] = a2 / a0;
}

static float vx2Bq(const float *c, float *z, float x)
{
	float y = c[0] * x + c[1] * z[0] + c[2] * z[1] - c[3] * z[2] - c[4] * z[3];
	z[1] = z[0]; z[0] = x;
	z[3] = z[2]; z[2] = y;
	return y;
}

// ---------------- FET compressor ----------------
void FetCompSetParam(JamesDSPLib *jdsp, float thresholdDb, float ratio, float attackMs, float releaseMs, float makeupDb)
{
	FetComp *f = &jdsp->fetComp;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	if (ratio < 1.0f) ratio = 1.0f;
	if (attackMs < 0.05f) attackMs = 0.05f;
	if (releaseMs < 5.0f) releaseMs = 5.0f;
	f->thrLin = powf(10.0f, thresholdDb / 20.0f);
	f->slope = 1.0f - 1.0f / ratio;
	f->attC = 1.0f - expf(-1.0f / (attackMs * 0.001f * fs));
	f->relC = 1.0f - expf(-1.0f / (releaseMs * 0.001f * fs));
	f->makeup = powf(10.0f, makeupDb / 20.0f);
}

void FetCompProcess(JamesDSPLib *jdsp, size_t n)
{
	FetComp *f = &jdsp->fetComp;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		float l = jdsp->tmpBuffer[0][i];
		float r = jdsp->tmpBuffer[1][i];
		float mag = fabsf(l);
		float mr = fabsf(r);
		if (mr > mag) mag = mr;
		if (mag > f->env)
			f->env += f->attC * (mag - f->env);
		else
			f->env += f->relC * (mag - f->env);
		float gain = 1.0f;
		if (f->env > f->thrLin && f->env > 1e-8f)
		{
			float overDb = 20.0f * log10f(f->env / f->thrLin);
			float grDb = overDb * f->slope;
			gain = powf(10.0f, -grDb / 20.0f);
		}
		gain *= f->makeup;
		jdsp->tmpBuffer[0][i] = l * gain;
		jdsp->tmpBuffer[1][i] = r * gain;
	}
}

void FetCompEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->fetCompEnabled)
		jdsp->fetComp.env = 0.0f;
	jdsp->fetCompEnabled = 1;
}
void FetCompDisable(JamesDSPLib *jdsp) { jdsp->fetCompEnabled = 0; }

// ---------------- Cure+ (auditory protection crossfeed) ----------------
void CureSetParam(JamesDSPLib *jdsp, int level)
{
	Cure *c = &jdsp->cure;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	float cutoff = 700.0f, g = 0.30f;
	if (level == 1) { cutoff = 700.0f; g = 0.40f; }
	else if (level == 2) { cutoff = 650.0f; g = 0.55f; }
	c->lpCoef = 1.0f - expf(-2.0f * 3.14159265f * cutoff / fs);
	c->feed = g;
	c->norm = 1.0f / (1.0f + g);
}

void CureProcess(JamesDSPLib *jdsp, size_t n)
{
	Cure *c = &jdsp->cure;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		float l = jdsp->tmpBuffer[0][i];
		float r = jdsp->tmpBuffer[1][i];
		c->lpzL += c->lpCoef * (r - c->lpzL);
		c->lpzR += c->lpCoef * (l - c->lpzR);
		jdsp->tmpBuffer[0][i] = (l + c->feed * c->lpzL) * c->norm;
		jdsp->tmpBuffer[1][i] = (r + c->feed * c->lpzR) * c->norm;
	}
}

void CureEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->cureEnabled)
		jdsp->cure.lpzL = jdsp->cure.lpzR = 0.0f;
	jdsp->cureEnabled = 1;
}
void CureDisable(JamesDSPLib *jdsp) { jdsp->cureEnabled = 0; }

// ---------------- ViPER bass ----------------
void ViperBassSetParam(JamesDSPLib *jdsp, int mode, float freq, float gainDb)
{
	ViperBass *v = &jdsp->viperBass;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	if (freq < 20.0f) freq = 20.0f;
	if (freq > 300.0f) freq = 300.0f;
	if (gainDb < 0.0f) gainDb = 0.0f;
	if (gainDb > 12.0f) gainDb = 12.0f;
	v->mode = mode;
	if (mode == 2)
		vx2Biquad(v->f, fs, freq, 1.2f, gainDb, 1);   // subwoofer: peaking
	else
		vx2Biquad(v->f, fs, freq, 0.707f, gainDb, 0); // shelf
	// pure bass+: gentle sub harmonics
	vx2Biquad(v->lp, fs, freq, 0.7071f, 0.0f, 2);     // reuse as HP? no: need LP
	// build lowpass manually for harmonic path
	{
		float w0 = 2.0f * 3.14159265358979f * freq / fs;
		float cw = cosf(w0), sw = sinf(w0);
		float alpha = sw / (2.0f * 0.7071f);
		float a0 = 1.0f + alpha;
		v->lp[0] = ((1.0f - cw) * 0.5f) / a0;
		v->lp[1] = (1.0f - cw) / a0;
		v->lp[2] = v->lp[0];
		v->lp[3] = (-2.0f * cw) / a0;
		v->lp[4] = (1.0f - alpha) / a0;
	}
	v->harm = (mode == 1) ? gainDb * 0.03f : 0.0f;
}

void ViperBassProcess(JamesDSPLib *jdsp, size_t n)
{
	ViperBass *v = &jdsp->viperBass;
	size_t i;
	int c;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		for (c = 0; c < 2; c++)
		{
			float x = jdsp->tmpBuffer[c][i];
			float y = vx2Bq(v->f, v->fz[c], x);
			if (v->harm > 0.0f)
			{
				float sub = vx2Bq(v->lp, v->lpz[c], x);
				float h = fabsf(sub);
				h -= v->dc[c];
				v->dc[c] += h * 0.0005f;
				y += v->harm * (h / (1.0f + fabsf(h)));
			}
			jdsp->tmpBuffer[c][i] = y;
		}
	}
}

void ViperBassEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->viperBassEnabled)
	{
		memset(jdsp->viperBass.fz, 0, sizeof(jdsp->viperBass.fz));
		memset(jdsp->viperBass.lpz, 0, sizeof(jdsp->viperBass.lpz));
		jdsp->viperBass.dc[0] = jdsp->viperBass.dc[1] = 0.0f;
	}
	jdsp->viperBassEnabled = 1;
}
void ViperBassDisable(JamesDSPLib *jdsp) { jdsp->viperBassEnabled = 0; }

// ---------------- Reverberation (freeverb style) ----------------
static const int vrCombBase[4] = { 1116, 1188, 1277, 1356 };
static const int vrApBase[2] = { 556, 441 };

static float vrCombProc(VReverb *v, int c, int k, float input)
{
	int idx = v->cidx[c][k];
	float out = v->comb[c][k][idx];
	v->cflt[c][k] = out * (1.0f - v->damp) + v->cflt[c][k] * v->damp;
	v->comb[c][k][idx] = input + v->cflt[c][k] * v->fb;
	if (++idx >= v->clen[c][k]) idx = 0;
	v->cidx[c][k] = idx;
	return out;
}

static float vrApProc(VReverb *v, int c, int k, float input)
{
	int idx = v->aidx[c][k];
	float bufout = v->ap[c][k][idx];
	float out = -input + bufout;
	v->ap[c][k][idx] = input + bufout * 0.5f;
	if (++idx >= v->alen[c][k]) idx = 0;
	v->aidx[c][k] = idx;
	return out;
}

/* ------------------------------------------------------------------------
 * Reverberation models
 *
 * "Classic" is the original ViPER-style freeverb network, kept untouched so
 * existing presets sound the same. The added models are independent
 * re-implementations of well-known topologies, written from the published
 * algorithms rather than copied from any codebase:
 *   Plate - Dattorro's figure-eight tank (Dattorro 1997), the topology MVerb
 *           follows; smooth and dense, without the metallic comb ringing.
 *   Hall  - 8-line feedback delay network with Hadamard mixing and per-line
 *           damping plus a bass multiplier, in the spirit of zita-rev1.
 *   Room  - early-reflection tap pattern feeding a shorter FDN tail, the
 *           arrangement Dragonfly Room Reverb uses.
 *
 * Hall and Room ring. Their eight lines are spaced almost evenly, nothing
 * diffuses the input, the modulation setting is never read, and the input
 * enters every line with the same sign and gain, which is exactly the vector
 * the Hadamard matrix folds into a single line. The result is a few strong
 * discrete echoes and comb peaks that line up into a pitch. They are kept as
 * they are, because presets were tuned against them, and rebuilt alongside:
 *   Concert hall (smooth) - model 4
 *   Room (smooth)         - model 5
 * which fix each of those faults rather than covering them with damping.
 * ------------------------------------------------------------------------ */

static const int vrDiffBase[VREV_DIFF_N]  = { 142, 107, 379, 277 };
static const int vrTankBase[VREV_TANK_N]  = { 672, 4453, 1800, 908, 4217, 2656 };
static const int vrFdnBase[VREV_FDN_N]    = { 1153, 1319, 1499, 1657, 1801, 1949, 2111, 2273 };
static const int vrErBase[12]             = { 113, 271, 421, 613, 787, 947, 1097, 1259, 1427, 1583, 1741, 1901 };

static inline int vrDiffMax(float scale, int k) { return (int)(vrDiffBase[k] * scale) + 8; }
static inline int vrTankMax(float scale, int k) { return (int)(vrTankBase[k] * scale * 1.45f) + 8; }
static inline int vrFdnMax(float scale, int k)  { return (int)(vrFdnBase[k] * scale * 1.45f) + 8; }
static inline int vrErMax(float scale)          { return (int)(vrErBase[11] * scale * 1.6f) + 8; }

/* Smooth models. Line lengths run 37 to 85 ms at 44.1 kHz with the ratio
   between neighbours wandering from 1.10 to 1.14 and never repeating, so no
   two lines share a spacing for their comb peaks to line up on. */
static const int vsLineBase[VREV_FDN_N]  = { 1637, 1871, 2069, 2347, 2593, 2927, 3271, 3733 };
/* One allpass inside each line, which is what turns each pass round the loop
   into a smear instead of a click */
static const int vsApBase[VREV_FDN_N]    = { 109, 163, 211, 271, 337, 401, 467, 541 };
static const int vsDiffBase[VREV_DIFF_N] = { 199, 151, 431, 331 };
/* Early reflections for the smooth room, a separate set per ear. Eighteen
   taps each, and every gap between any two taps of a set differs from every
   other: a repeated gap is a comb filter, which is heard as a pitch. Gains
   fall over 30 ms with mixed polarity. The strongest repetition in each set
   is a third of the old twelve-tap pattern's (0.10 against 0.33). */
#define VS_ER_N 18
static const int vsErL[VS_ER_N] = { 173, 255, 291, 336, 369, 400, 500, 549, 606,
	685, 836, 880, 932, 1357, 1489, 1581, 1807, 1900 };
static const float vsErGL[VS_ER_N] = { -0.42317f, 0.39774f, -0.38706f, 0.37412f, 0.36490f,
	-0.35645f, 0.33050f, -0.31849f, -0.30506f, -0.28737f, 0.25638f, 0.24799f, 0.23843f,
	0.17292f, -0.15650f, 0.14599f, 0.12306f, 0.11471f };
static const int vsErR[VS_ER_N] = { 391, 438, 474, 506, 558, 623, 682, 716, 751,
	789, 919, 1025, 1141, 1186, 1420, 1595, 1636, 1972 };
static const float vsErGR[VS_ER_N] = { 0.40349f, 0.38941f, -0.37896f, -0.36990f, 0.35564f,
	-0.33859f, -0.32382f, -0.31561f, -0.30737f, 0.29867f, 0.27071f, -0.24987f, 0.22890f,
	-0.22124f, -0.18537f, -0.16241f, 0.15745f, -0.12214f };
/* Input weights: unequal and of mixed sign, unit length. The old network fed
   every line the same, which is the one input the mixing folds into a line. */
static const float vsIn[VREV_FDN_N] = { 0.42727f, -0.30989f, 0.24885f, 0.39441f,
	-0.27233f, 0.45544f, -0.37093f, 0.29111f };
/* Output weights per ear. Each is unit length and the two are orthogonal to
   within 0.002, so the ears get uncorrelated tails: width without a hole. */
static const float vsOutL[VREV_FDN_N] = { 0.55964f, 0.23370f, -0.31980f, 0.41204f,
	0.17835f, -0.45509f, 0.28290f, -0.20295f };
static const float vsOutR[VREV_FDN_N] = { 0.22856f, -0.45077f, 0.39363f, 0.17142f,
	-0.34919f, 0.26030f, 0.52695f, -0.30475f };
/* Slow modulation, one unrelated rate per line */
static const float vsLfoHz[VREV_FDN_N] = { 0.31f, 0.43f, 0.57f, 0.67f, 0.79f, 0.89f, 1.03f, 1.13f };
/* Level into the network. Set so the smooth models land at the same loudness
   as the ones they sit beside, which tools/reverb_harness.c checks. */
#define VS_IN_GAIN_HALL 0.50f
#define VS_IN_GAIN_ROOM 0.40f
/* Modulation depth at 100%, in samples at 44.1 kHz. At the fastest line rate
   that is a pitch drift of about five cents at most, well short of chorus. */
#define VS_MOD_DEPTH 20.0f

static inline int vsModMax(float scale)         { return (int)((VS_MOD_DEPTH + 4.0f) * scale) + 2; }
static inline int vsLineCap(float scale, int k) { return (int)(vsLineBase[k] * scale * 1.45f) + vsModMax(scale) + 8; }
static inline int vsApLen(float scale, int k)   { return (int)(vsApBase[k] * scale) + 1; }
static inline int vsDiffLen(float scale, int k) { return (int)(vsDiffBase[k] * scale) + 1; }

static inline float vrRead(const float *buf, int len, int idx, int back)
{
	int p = idx - back;
	while (p < 0) p += len;
	return buf[p % len];
}

static void vrFreeModels(VReverb *rv)
{
	if (rv->preMem) { free(rv->preMem); rv->preMem = 0; }
	if (rv->datMem)
	{
		free(rv->datMem); rv->datMem = 0;
		memset(rv->diff, 0, sizeof(rv->diff));
		memset(rv->tank, 0, sizeof(rv->tank));
	}
	if (rv->fdnMem)
	{
		free(rv->fdnMem); rv->fdnMem = 0;
		memset(rv->fdn, 0, sizeof(rv->fdn));
	}
	if (rv->erMem) { free(rv->erMem); rv->erMem = 0; }
	if (rv->smMem)
	{
		free(rv->smMem); rv->smMem = 0;
		memset(rv->smDiff, 0, sizeof(rv->smDiff));
		memset(rv->smLine, 0, sizeof(rv->smLine));
		memset(rv->smAp, 0, sizeof(rv->smAp));
	}
}

/* All or nothing: on failure nothing is left allocated */
static int vrAllocModels(VReverb *rv, float scale)
{
	size_t total;
	float *p;
	int k;

	/* Pre-delay line, shared by the added models */
	rv->preMem = (float*)calloc(VREV_PREMAX, sizeof(float));
	/* Dattorro plate: input diffusers plus the figure-eight tank */
	total = 0;
	for (k = 0; k < VREV_DIFF_N; k++) total += vrDiffMax(scale, k);
	for (k = 0; k < VREV_TANK_N; k++) total += vrTankMax(scale, k);
	rv->datMem = (float*)calloc(total, sizeof(float));
	if (rv->datMem)
	{
		p = rv->datMem;
		for (k = 0; k < VREV_DIFF_N; k++) { rv->diff[k] = p; p += vrDiffMax(scale, k); }
		for (k = 0; k < VREV_TANK_N; k++) { rv->tank[k] = p; p += vrTankMax(scale, k); }
	}
	/* FDN lines plus the early-reflection tap line */
	total = 0;
	for (k = 0; k < VREV_FDN_N; k++) total += vrFdnMax(scale, k);
	rv->fdnMem = (float*)calloc(total, sizeof(float));
	if (rv->fdnMem)
	{
		p = rv->fdnMem;
		for (k = 0; k < VREV_FDN_N; k++) { rv->fdn[k] = p; p += vrFdnMax(scale, k); }
	}
	rv->erMem = (float*)calloc(vrErMax(scale), sizeof(float));
	/* Smooth models: diffusers, lines with room to modulate, line allpasses */
	total = 0;
	for (k = 0; k < VREV_DIFF_N; k++) total += vsDiffLen(scale, k);
	for (k = 0; k < VREV_FDN_N; k++) total += vsLineCap(scale, k) + vsApLen(scale, k);
	rv->smMem = (float*)calloc(total, sizeof(float));
	if (rv->smMem)
	{
		p = rv->smMem;
		for (k = 0; k < VREV_DIFF_N; k++) { rv->smDiff[k] = p; p += vsDiffLen(scale, k); }
		for (k = 0; k < VREV_FDN_N; k++) { rv->smLine[k] = p; p += vsLineCap(scale, k); }
		for (k = 0; k < VREV_FDN_N; k++) { rv->smAp[k] = p; p += vsApLen(scale, k); }
	}

	if (!rv->preMem || !rv->datMem || !rv->fdnMem || !rv->erMem || !rv->smMem)
	{
		vrFreeModels(rv);
		return 0;
	}
	rv->allocScale = scale;
	return 1;
}

static void vrClearModels(VReverb *rv)
{
	const float scale = rv->allocScale;
	int k;
	memset(rv->preMem, 0, VREV_PREMAX * sizeof(float));
	memset(rv->erMem, 0, vrErMax(scale) * sizeof(float));
	rv->preIdx = 0;
	rv->erIdx = 0;
	rv->modPhase = 0.0f;
	rv->tankLp[0] = rv->tankLp[1] = 0.0f;
	for (k = 0; k < VREV_DIFF_N; k++) { rv->diffIdx[k] = 0; memset(rv->diff[k], 0, vrDiffMax(scale, k) * sizeof(float)); }
	for (k = 0; k < VREV_TANK_N; k++) { rv->tankIdx[k] = 0; memset(rv->tank[k], 0, vrTankMax(scale, k) * sizeof(float)); }
	for (k = 0; k < VREV_FDN_N; k++)
	{
		rv->fdnIdx[k] = 0;
		rv->fdnLp[k] = 0.0f;
		rv->fdnBass[k] = 0.0f;
		memset(rv->fdn[k], 0, vrFdnMax(scale, k) * sizeof(float));
	}
	for (k = 0; k < VREV_DIFF_N; k++) { rv->smDiffIdx[k] = 0; memset(rv->smDiff[k], 0, vsDiffLen(scale, k) * sizeof(float)); }
	for (k = 0; k < VREV_FDN_N; k++)
	{
		rv->smLineIdx[k] = 0;
		rv->smApIdx[k] = 0;
		rv->smLp[k] = 0.0f;
		rv->smBass[k] = 0.0f;
		memset(rv->smLine[k], 0, vsLineCap(scale, k) * sizeof(float));
		memset(rv->smAp[k], 0, vsApLen(scale, k) * sizeof(float));
		/* Oscillators start spread round the circle, so no two lines ever
		   drift in step */
		rv->smOscC[k] = cosf(0.78539816f * (float)k);
		rv->smOscS[k] = sinf(0.78539816f * (float)k);
	}
}

/* Turns the stored settings into lengths and gains for the current rate.
   Called with the lock held. */
static void vrApply(JamesDSPLib *jdsp)
{
	VReverb *v = &jdsp->vreverb;
	const int model = v->pModel;
	const float roomPct = v->pRaw[0], dampPct = v->pRaw[1], widthPct = v->pRaw[2];
	const float predelayMs = v->pRaw[3], decayPct = v->pRaw[4], diffusionPct = v->pRaw[5];
	const float modPct = v->pRaw[6], bassPct = v->pRaw[7], erPct = v->pRaw[8];
	const float wetPct = v->pRaw[9], dryPct = v->pRaw[10];
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	v->fs = fs;
	float scale = fs / 44100.0f;
	int c, k;

	/* Every length below is clamped to a buffer sized for this rate. If the
	   buffers exist and were sized for another rate, those clamps would be a
	   lie: switched on at 44.1 kHz, then moved to 48 kHz and touched, the
	   lines wrote up to a tenth past their ends into the heap. Resize first. */
	if (v->preMem && v->allocScale != scale)
	{
		vrFreeModels(v);
		if (vrAllocModels(v, scale))
			vrClearModels(v);
		else
			jdsp->vreverbEnabled = 0;
	}

	v->model = model;
	v->roomSize = roomPct * 0.01f;
	v->dampAmt = dampPct * 0.01f;
	v->width = widthPct * 0.01f;
	v->decay = decayPct * 0.01f;
	v->diffusionAmt = diffusionPct * 0.01f;
	v->modDepth = modPct * 0.01f;
	v->bassMult = 0.5f + bassPct * 0.015f;   /* 0.5x .. 2.0x low-frequency decay */
	v->erLevel = erPct * 0.01f;

	/* Classic network (unchanged) */
	for (c = 0; c < 2; c++)
	{
		for (k = 0; k < 4; k++)
		{
			int len = (int)(vrCombBase[k] * scale) + (c == 1 ? 23 : 0);
			if (len > VREV_COMBLEN - 1) len = VREV_COMBLEN - 1;
			v->clen[c][k] = len;
		}
		for (k = 0; k < 2; k++)
		{
			int len = (int)(vrApBase[k] * scale) + (c == 1 ? 23 : 0);
			if (len > VREV_APLEN - 1) len = VREV_APLEN - 1;
			v->alen[c][k] = len;
		}
	}
	v->fb = 0.70f + 0.28f * roomPct * 0.01f;
	v->damp = 0.05f + 0.9f * dampPct * 0.01f;

	float wet = wetPct * 0.01f * 0.9f;
	float width = widthPct * 0.01f;
	v->wet1 = wet * (width * 0.5f + 0.5f);
	v->wet2 = wet * ((1.0f - width) * 0.5f);
	v->dry = dryPct * 0.01f;
	v->wetAmt = wetPct * 0.01f;

	/* Pre-delay */
	int pre = (int)(predelayMs * 0.001f * fs);
	if (pre < 1) pre = 1;
	if (pre > VREV_PREMAX - 1) pre = VREV_PREMAX - 1;
	v->preLen = pre;

	/* Plate tank sizing: room size stretches the whole tank */
	float sz = 0.55f + v->roomSize * 0.9f;
	for (k = 0; k < VREV_DIFF_N; k++)
		v->diffLen[k] = vrDiffMax(scale, k);
	for (k = 0; k < VREV_TANK_N; k++)
	{
		int len = (int)(vrTankBase[k] * scale * sz);
		int max = vrTankMax(scale, k);
		v->tankLen[k] = (len < 32) ? 32 : (len > max ? max : len);
	}

	/* FDN sizing and per-line RT60 gains */
	float rt60 = 0.25f + v->decay * 11.75f;           /* seconds */
	float rtLo = rt60 * v->bassMult;
	if (rtLo < 0.05f) rtLo = 0.05f;
	for (k = 0; k < VREV_FDN_N; k++)
	{
		int len = (int)(vrFdnBase[k] * scale * sz);
		int max = vrFdnMax(scale, k);
		if (len < 32) len = 32;
		if (len > max) len = max;
		v->fdnLen[k] = len;
		float t = (float)len / fs;
		/* Separate decay rates per band. The bass multiplier lengthens the low
		   end by extending its RT60, never by adding gain - a boost here would
		   push loop gain past unity and the network would self-oscillate. */
		v->fdnGainHi[k] = powf(10.0f, -3.0f * t / rt60);
		v->fdnGainLo[k] = powf(10.0f, -3.0f * t / rtLo);
		if (v->fdnGainHi[k] > 0.995f) v->fdnGainHi[k] = 0.995f;
		if (v->fdnGainLo[k] > 0.995f) v->fdnGainLo[k] = 0.995f;
	}
	v->erLen = vrErMax(scale);

	/* Smooth hall and room. The room is the same network at half the size,
	   which is what a room is: the same physics with nearer walls. */
	{
		const float sizeK = (model == 5) ? 0.5f * sz : sz;
		const int modMax = vsModMax(scale);
		v->smModAmt = v->modDepth * VS_MOD_DEPTH * scale;
		v->smDiffG = 0.40f + 0.35f * v->diffusionAmt;
		v->smApG = 0.25f + 0.40f * v->diffusionAmt;
		/* Full strength damping. The old FDN scaled this down to 0.6, which
		   left the top end ringing on long after the body had gone. */
		v->smDampC = 0.10f + 0.60f * v->dampAmt;
		for (k = 0; k < VREV_DIFF_N; k++)
			v->smDiffLen[k] = vsDiffLen(scale, k);
		for (k = 0; k < VREV_FDN_N; k++)
		{
			const int cap = vsLineCap(scale, k);
			float d = vsLineBase[k] * scale * sizeK;
			const float dMax = (float)(cap - modMax - 4);
			if (d < 64.0f) d = 64.0f;
			if (d > dMax) d = dMax;
			v->smDelay[k] = d;
			v->smLineCap[k] = cap;
			v->smApLen[k] = vsApLen(scale, k);
			/* An allpass delays by its own length on average, so the loop is
			   that much longer than the line, and decays over that time */
			float t = (d + (float)v->smApLen[k]) / fs;
			v->smGainHi[k] = powf(10.0f, -3.0f * t / rt60);
			v->smGainLo[k] = powf(10.0f, -3.0f * t / rtLo);
			if (v->smGainHi[k] > 0.995f) v->smGainHi[k] = 0.995f;
			if (v->smGainLo[k] > 0.995f) v->smGainLo[k] = 0.995f;
			const float w = 6.28318530718f * vsLfoHz[k] / fs;
			v->smRotC[k] = cosf(w);
			v->smRotS[k] = sinf(w);
		}
	}
}

void VReverbSetParam(JamesDSPLib *jdsp, int model, float roomPct, float dampPct,
	float widthPct, float predelayMs, float decayPct, float diffusionPct,
	float modPct, float bassPct, float erPct, float wetPct, float dryPct)
{
	/* Held for the whole update: Process reads every length and gain set
	   here, and may have to have its buffers replaced if the rate moved. */
	jdsp_lock(jdsp);
	VReverb *v = &jdsp->vreverb;
	v->pModel = model;
	v->pRaw[0] = roomPct;  v->pRaw[1] = dampPct;    v->pRaw[2] = widthPct;
	v->pRaw[3] = predelayMs; v->pRaw[4] = decayPct; v->pRaw[5] = diffusionPct;
	v->pRaw[6] = modPct;   v->pRaw[7] = bassPct;    v->pRaw[8] = erPct;
	v->pRaw[9] = wetPct;   v->pRaw[10] = dryPct;
	/* Hold every value to the card's own slider range. The buffers are sized
	   for those ranges: a modulation far above 100 % swung the smooth models'
	   read more than a whole line away, and a room size below 0 turned the
	   early reflection taps negative, both reading far outside their
	   buffers. In root mode any app can send these, not only the card. The
	   test is written so a NaN lands on the low end too. */
	for (int k = 0; k < 11; k++)
	{
		const float hi = k == 3 ? 200.0f : 100.0f;
		if (!(v->pRaw[k] >= 0.0f)) v->pRaw[k] = 0.0f;
		if (v->pRaw[k] > hi) v->pRaw[k] = hi;
	}
	vrApply(jdsp);
	jdsp_unlock(jdsp);
}

/* Sample rate changed. The caller holds the lock. */
void VReverbRefresh(JamesDSPLib *jdsp)
{
	vrApply(jdsp);
}

static inline float vrDamp1(float *state, float x, float coeff)
{
	*state = x + (*state - x) * coeff;
	return *state;
}

/* --- Plate: Dattorro figure-eight ------------------------------------- */
static void vrPlate(VReverb *v, float in, float *outL, float *outR)
{
	float x = in;
	float kd = 0.55f + v->diffusionAmt * 0.4f;
	int i;
	for (i = 0; i < VREV_DIFF_N; i++)
	{
		float d = vrRead(v->diff[i], v->diffLen[i], v->diffIdx[i], v->diffLen[i] - 1);
		float y = x + kd * d;
		v->diff[i][v->diffIdx[i]] = y;
		x = d - kd * y;
		v->diffIdx[i] = (v->diffIdx[i] + 1) % v->diffLen[i];
	}

	float decayG = 0.20f + v->decay * 0.62f;
	float dampC = 0.05f + v->dampAmt * 0.9f;

	/* Modulated first allpass in each branch keeps the tail from ringing */
	float mod = sinf(v->modPhase) * v->modDepth * 12.0f;
	int m = (int)mod;

	/* branch A */
	float a = x + vrRead(v->tank[5], v->tankLen[5], v->tankIdx[5], v->tankLen[5] - 1) * decayG;
	float ad = vrRead(v->tank[0], v->tankLen[0], v->tankIdx[0], v->tankLen[0] - 1 - m);
	float ay = a + 0.7f * ad;
	v->tank[0][v->tankIdx[0]] = ay;
	a = ad - 0.7f * ay;
	v->tankIdx[0] = (v->tankIdx[0] + 1) % v->tankLen[0];
	v->tank[1][v->tankIdx[1]] = a;
	a = vrRead(v->tank[1], v->tankLen[1], v->tankIdx[1], v->tankLen[1] - 1);
	v->tankIdx[1] = (v->tankIdx[1] + 1) % v->tankLen[1];
	a = vrDamp1(&v->tankLp[0], a, dampC) * decayG;
	float a2d = vrRead(v->tank[2], v->tankLen[2], v->tankIdx[2], v->tankLen[2] - 1);
	float a2y = a + 0.5f * a2d;
	v->tank[2][v->tankIdx[2]] = a2y;
	a = a2d - 0.5f * a2y;
	v->tankIdx[2] = (v->tankIdx[2] + 1) % v->tankLen[2];

	/* branch B */
	float b = x + a * decayG;
	float bd = vrRead(v->tank[3], v->tankLen[3], v->tankIdx[3], v->tankLen[3] - 1 + m);
	float by = b + 0.7f * bd;
	v->tank[3][v->tankIdx[3]] = by;
	b = bd - 0.7f * by;
	v->tankIdx[3] = (v->tankIdx[3] + 1) % v->tankLen[3];
	v->tank[4][v->tankIdx[4]] = b;
	b = vrRead(v->tank[4], v->tankLen[4], v->tankIdx[4], v->tankLen[4] - 1);
	v->tankIdx[4] = (v->tankIdx[4] + 1) % v->tankLen[4];
	b = vrDamp1(&v->tankLp[1], b, dampC) * decayG;
	float b2d = vrRead(v->tank[5], v->tankLen[5], v->tankIdx[5], v->tankLen[5] - 1);
	float b2y = b + 0.5f * b2d;
	v->tank[5][v->tankIdx[5]] = b2y;
	b = b2d - 0.5f * b2y;
	v->tankIdx[5] = (v->tankIdx[5] + 1) % v->tankLen[5];

	/* Tap both branches at different points for a wide, uncorrelated pair */
	*outL = (vrRead(v->tank[1], v->tankLen[1], v->tankIdx[1], v->tankLen[1] / 3)
	      + vrRead(v->tank[4], v->tankLen[4], v->tankIdx[4], v->tankLen[4] / 5) * 0.7f) * 0.6f;
	*outR = (vrRead(v->tank[4], v->tankLen[4], v->tankIdx[4], v->tankLen[4] / 3)
	      + vrRead(v->tank[1], v->tankLen[1], v->tankIdx[1], v->tankLen[1] / 7) * 0.7f) * 0.6f;

	v->modPhase += 6.28318530718f * 0.7f / v->fs;
	if (v->modPhase > 6.28318530718f) v->modPhase -= 6.28318530718f;
}

/* --- Hall / Room: 8-line FDN with Hadamard mixing --------------------- */
static void vrFdn(VReverb *v, float in, float *outL, float *outR)
{
	float s[VREV_FDN_N];
	int i;
	for (i = 0; i < VREV_FDN_N; i++)
		s[i] = vrRead(v->fdn[i], v->fdnLen[i], v->fdnIdx[i], v->fdnLen[i] - 1);

	/* Hadamard: lossless, and cheap enough to run per sample */
	float t[VREV_FDN_N];
	for (i = 0; i < VREV_FDN_N; i += 2)
	{
		t[i]     = s[i] + s[i + 1];
		t[i + 1] = s[i] - s[i + 1];
	}
	float u[VREV_FDN_N];
	for (i = 0; i < VREV_FDN_N; i += 4)
	{
		u[i]     = t[i]     + t[i + 2];
		u[i + 1] = t[i + 1] + t[i + 3];
		u[i + 2] = t[i]     - t[i + 2];
		u[i + 3] = t[i + 1] - t[i + 3];
	}
	float w[VREV_FDN_N];
	for (i = 0; i < 4; i++)
	{
		w[i]     = (u[i] + u[i + 4]) * 0.35355339f;
		w[i + 4] = (u[i] - u[i + 4]) * 0.35355339f;
	}

	float dampC = 0.05f + v->dampAmt * 0.9f;
	for (i = 0; i < VREV_FDN_N; i++)
	{
		/* Split the returning signal into two bands and decay each at its own
		   rate, so the low end can ring longer without the loop gaining. */
		float sig = w[i];
		float lo = vrDamp1(&v->fdnBass[i], sig, 0.985f);
		float hi = sig - lo;
		float y = lo * v->fdnGainLo[i] + hi * v->fdnGainHi[i];
		/* Damping rolls the top off a little more on every pass */
		y = vrDamp1(&v->fdnLp[i], y, dampC * 0.6f);
		y += in;
		if (!isfinite(y)) y = 0.0f;
		v->fdn[i][v->fdnIdx[i]] = y;
		v->fdnIdx[i] = (v->fdnIdx[i] + 1) % v->fdnLen[i];
	}

	*outL = (s[0] + s[2] + s[4] + s[6]) * 0.25f;
	*outR = (s[1] + s[3] + s[5] + s[7]) * 0.25f;
}

/* Below this a decaying tail is inaudible by a factor of a trillion, and
   letting it go on shrinking into subnormal numbers costs real CPU on cores
   that handle them in microcode. Flushing to zero is free by comparison. */
#define VS_FLUSH(x) do { if (fabsf(x) < 1e-20f) (x) = 0.0f; } while (0)

/* Schroeder allpass, lattice form: (-g + z^-D) / (1 - g z^-D) */
static inline float vsAllpass(float *buf, int len, int *idx, float g, float x)
{
	float d = buf[*idx];
	float w = x + g * d;
	float y = d - g * w;
	VS_FLUSH(w);
	buf[*idx] = w;
	if (++*idx >= len) *idx = 0;
	return y;
}

/* --- Smooth hall / room ------------------------------------------------ */
static void vrSmooth(VReverb *v, float in, float *outL, float *outR)
{
	float s[VREV_FDN_N];
	int i;

	/* Diffuse first, so what enters the network is already a smear */
	float x = in;
	for (i = 0; i < VREV_DIFF_N; i++)
		x = vsAllpass(v->smDiff[i], v->smDiffLen[i], &v->smDiffIdx[i], v->smDiffG, x);

	for (i = 0; i < VREV_FDN_N; i++)
	{
		/* Advance this line's oscillator by rotation, which costs four
		   multiplies instead of a sinf. The correction keeps it on the unit
		   circle against rounding drift. */
		const float c = v->smOscC[i] * v->smRotC[i] - v->smOscS[i] * v->smRotS[i];
		const float sn = v->smOscS[i] * v->smRotC[i] + v->smOscC[i] * v->smRotS[i];
		const float g = 1.5f - 0.5f * (c * c + sn * sn);
		v->smOscC[i] = c * g;
		v->smOscS[i] = sn * g;

		/* Fractional read. The delay wanders by a few samples, so each line's
		   comb peaks keep moving and never settle into a pitch. */
		const int cap = v->smLineCap[i];
		float pos = (float)v->smLineIdx[i] - (v->smDelay[i] + v->smModAmt * v->smOscS[i]);
		if (pos < 0.0f) pos += (float)cap;
		int i0 = (int)pos;
		const float fr = pos - (float)i0;
		/* pos can round up to exactly cap after the wrap */
		if (i0 >= cap) i0 -= cap;
		int i1 = i0 + 1;
		if (i1 >= cap) i1 = 0;
		const float a = v->smLine[i][i0];
		const float r = a + fr * (v->smLine[i][i1] - a);
		/* Allpass sign alternates line to line */
		s[i] = vsAllpass(v->smAp[i], v->smApLen[i], &v->smApIdx[i],
			(i & 1) ? -v->smApG : v->smApG, r);
	}

	/* Hadamard, then a Householder reflection. Each on its own is its own
	   inverse, so a network mixed by either keeps handing energy back to the
	   shape it started in; the product is not, and is still lossless. */
	float t[VREV_FDN_N], u[VREV_FDN_N], w[VREV_FDN_N];
	for (i = 0; i < VREV_FDN_N; i += 2)
	{
		t[i]     = s[i] + s[i + 1];
		t[i + 1] = s[i] - s[i + 1];
	}
	for (i = 0; i < VREV_FDN_N; i += 4)
	{
		u[i]     = t[i]     + t[i + 2];
		u[i + 1] = t[i + 1] + t[i + 3];
		u[i + 2] = t[i]     - t[i + 2];
		u[i + 3] = t[i + 1] - t[i + 3];
	}
	float sum = 0.0f;
	for (i = 0; i < 4; i++)
	{
		w[i]     = (u[i] + u[i + 4]) * 0.35355339f;
		w[i + 4] = (u[i] - u[i + 4]) * 0.35355339f;
		sum += w[i] + w[i + 4];
	}
	sum *= 0.25f;   /* 2 / N */

	for (i = 0; i < VREV_FDN_N; i++)
	{
		const float sig = w[i] - sum;
		const float lo = vrDamp1(&v->smBass[i], sig, 0.985f);
		float y = lo * v->smGainLo[i] + (sig - lo) * v->smGainHi[i];
		y = vrDamp1(&v->smLp[i], y, v->smDampC);
		VS_FLUSH(v->smBass[i]);
		VS_FLUSH(v->smLp[i]);
		y += x * vsIn[i];
		if (!isfinite(y)) y = 0.0f;
		VS_FLUSH(y);
		v->smLine[i][v->smLineIdx[i]] = y;
		if (++v->smLineIdx[i] >= v->smLineCap[i]) v->smLineIdx[i] = 0;
	}

	float l = 0.0f, r = 0.0f;
	for (i = 0; i < VREV_FDN_N; i++)
	{
		l += vsOutL[i] * s[i];
		r += vsOutR[i] * s[i];
	}
	*outL = l;
	*outR = r;
}

void VReverbProcess(JamesDSPLib *jdsp, size_t n)
{
	VReverb *v = &jdsp->vreverb;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	if (!v->combMem)
		return;
	/* A NaN or Inf in stays in the recirculating state of every model that
	   lacks a guard on its loop: classic put out NaN, dry signal included,
	   and plate and the smooth models went silent, until the card was
	   switched off and on (and for classic not even then). Keep it out. */
	for (i = 0; i < n; i++)
	{
		if (!isfinite(jdsp->tmpBuffer[0][i])) jdsp->tmpBuffer[0][i] = 0.0f;
		if (!isfinite(jdsp->tmpBuffer[1][i])) jdsp->tmpBuffer[1][i] = 0.0f;
	}

	if (v->model == 0)
	{
		/* Original ViPER network, untouched */
		for (i = 0; i < n; i++)
		{
			float l = jdsp->tmpBuffer[0][i];
			float r = jdsp->tmpBuffer[1][i];
			float input = (l + r) * 0.015f;
			float outL = 0.0f, outR = 0.0f;
			int k;
			for (k = 0; k < 4; k++)
			{
				outL += vrCombProc(v, 0, k, input);
				outR += vrCombProc(v, 1, k, input);
			}
			for (k = 0; k < 2; k++)
			{
				outL = vrApProc(v, 0, k, outL);
				outR = vrApProc(v, 1, k, outR);
			}
			jdsp->tmpBuffer[0][i] = outL * v->wet1 + outR * v->wet2 + l * v->dry;
			jdsp->tmpBuffer[1][i] = outR * v->wet1 + outL * v->wet2 + r * v->dry;
		}
		return;
	}

	if (!v->preMem || !v->datMem || !v->fdnMem || !v->erMem || !v->smMem)
		return;

	float wetL = v->wetAmt * (v->width * 0.5f + 0.5f);
	float wetR = v->wetAmt * ((1.0f - v->width) * 0.5f);

	for (i = 0; i < n; i++)
	{
		float l = jdsp->tmpBuffer[0][i];
		float r = jdsp->tmpBuffer[1][i];

		/* Pre-delay: the gap before the tail arrives, which is most of what
		   makes a space read as large rather than small and boxy */
		float mono = (l + r) * 0.5f;
		v->preMem[v->preIdx] = mono;
		float pre = vrRead(v->preMem, v->preLen, v->preIdx, v->preLen - 1);
		v->preIdx = (v->preIdx + 1) % v->preLen;

		float outL = 0.0f, outR = 0.0f;
		if (v->model == 1)
		{
			vrPlate(v, pre * 0.5f, &outL, &outR);
		}
		else if (v->model == 2)
		{
			vrFdn(v, pre * 0.25f, &outL, &outR);
		}
		else if (v->model == 4)
		{
			vrSmooth(v, pre * VS_IN_GAIN_HALL, &outL, &outR);
		}
		else if (v->model == 5)
		{
			/* Smooth room: reflections go straight to the ears and the tail is
			   fed the plain signal. Feeding the reflections into the tail as
			   well sounds like a good way to join them up, but it hands their
			   comb filter to the whole tail, which measured as more twang, not
			   less. */
			v->erMem[v->erIdx] = pre;
			const float span = (v->fs / 44100.0f) * (0.5f + v->roomSize);
			float erL = 0.0f, erR = 0.0f;
			int k;
			for (k = 0; k < VS_ER_N; k++)
			{
				int bl = (int)(vsErL[k] * span), br = (int)(vsErR[k] * span);
				if (bl >= v->erLen) bl = v->erLen - 1;
				if (br >= v->erLen) br = v->erLen - 1;
				/* and from below, or a negative tap reads past the end */
				if (bl < 0) bl = 0;
				if (br < 0) br = 0;
				int pl = v->erIdx - bl, pr = v->erIdx - br;
				if (pl < 0) pl += v->erLen;
				if (pr < 0) pr += v->erLen;
				erL += v->erMem[pl] * vsErGL[k];
				erR += v->erMem[pr] * vsErGR[k];
			}
			if (++v->erIdx >= v->erLen) v->erIdx = 0;
			float tailL, tailR;
			vrSmooth(v, pre * VS_IN_GAIN_ROOM, &tailL, &tailR);
			outL = tailL + erL * v->erLevel * 0.5f;
			outR = tailR + erR * v->erLevel * 0.5f;
		}
		else
		{
			/* Room: early reflections plus a shorter FDN tail */
			v->erMem[v->erIdx] = pre;
			float er = 0.0f, erAlt = 0.0f;
			int k;
			for (k = 0; k < 12; k++)
			{
				int back = (int)(vrErBase[k] * (v->fs / 44100.0f) * (0.5f + v->roomSize));
				if (back >= v->erLen) back = v->erLen - 1;
				float tap = vrRead(v->erMem, v->erLen, v->erIdx, back);
				float g = 1.0f / (1.0f + k * 0.45f);
				if (k & 1) erAlt += tap * g; else er += tap * g;
			}
			v->erIdx = (v->erIdx + 1) % v->erLen;
			float tailL, tailR;
			vrFdn(v, pre * 0.22f, &tailL, &tailR);
			outL = tailL + er * v->erLevel * 0.5f;
			outR = tailR + erAlt * v->erLevel * 0.5f;
		}

		if (!isfinite(outL)) outL = 0.0f;
		if (!isfinite(outR)) outR = 0.0f;

		jdsp->tmpBuffer[0][i] = outL * wetL + outR * wetR + l * v->dry;
		jdsp->tmpBuffer[1][i] = outR * wetL + outL * wetR + r * v->dry;
	}
}

void VReverbEnable(JamesDSPLib *jdsp)
{
	// Under the lock: Process may be running on the audio thread, and it must
	// not observe a half-built set of buffers.
	jdsp_lock(jdsp);
	VReverb *rv = &jdsp->vreverb;
	if (!jdsp->vreverbEnabled)
	{
		float fs = rv->fs > 8000.0f ? rv->fs : 48000.0f;
		float scale = fs / 44100.0f;
		int k;

		/* Classic network */
		if (!rv->combMem)
		{
			size_t total = (size_t)2 * 4 * VREV_COMBLEN + (size_t)2 * 2 * VREV_APLEN;
			rv->combMem = (float*)calloc(total, sizeof(float));
			if (!rv->combMem) { jdsp->vreverbEnabled = 0; jdsp_unlock(jdsp); return; }
			float *p = rv->combMem;
			int c;
			for (c = 0; c < 2; c++)
				for (k = 0; k < 4; k++) { rv->comb[c][k] = p; p += VREV_COMBLEN; }
			for (c = 0; c < 2; c++)
				for (k = 0; k < 2; k++) { rv->ap[c][k] = p; p += VREV_APLEN; }
		}
		memset(rv->combMem, 0,
			((size_t)2 * 4 * VREV_COMBLEN + (size_t)2 * 2 * VREV_APLEN) * sizeof(float));
		/* The classic combs' damping state and positions live outside
		   combMem and were never cleared, so a bad value there outlived
		   switching the card off and on. */
		memset(rv->cflt, 0, sizeof(rv->cflt));
		memset(rv->cidx, 0, sizeof(rv->cidx));
		memset(rv->aidx, 0, sizeof(rv->aidx));

		/* Everything else is sized for one rate. Buffers left from another
		   rate are replaced, never reused. */
		if (rv->preMem && rv->allocScale != scale)
			vrFreeModels(rv);
		if (!rv->preMem && !vrAllocModels(rv, scale))
		{
			jdsp->vreverbEnabled = 0;
			jdsp_unlock(jdsp);
			return;
		}
		vrClearModels(rv);
	}
	jdsp->vreverbEnabled = 1;
	jdsp_unlock(jdsp);
}

void VReverbDisable(JamesDSPLib *jdsp)
{
	VReverb *rv = &jdsp->vreverb;
	jdsp->vreverbEnabled = 0;
	// Clear the flag first so no further block enters, then take the lock,
	// which waits for any block already inside Process to leave. Freeing
	// without that wait is a use-after-free on the audio thread.
	jdsp_lock(jdsp);
	if (rv->combMem)
	{
		free(rv->combMem);
		rv->combMem = 0;
		memset(rv->comb, 0, sizeof(rv->comb));
		memset(rv->ap, 0, sizeof(rv->ap));
	}
	vrFreeModels(rv);
	jdsp_unlock(jdsp);
}

// ---------------- Speaker optimization ----------------
void SpeakerOptSetParam(JamesDSPLib *jdsp, float strengthPct)
{
	SpeakerOpt *s = &jdsp->speakerOpt;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	float k = strengthPct * 0.01f;
	/* Tuned for the speakers this actually runs on. The old curve spent most
	   of its effect removing sub-bass a phone speaker cannot reproduce, so it
	   measured large but sounded like nothing. Now the work happens where a
	   small driver can be heard: less low-mid boxiness, more presence and air.
	   Gains scale with strength and the wet/dry blend stays at unity, so the
	   dial maps directly onto the curve instead of halving it. */
	vx2Biquad(s->hp, fs, 110.0f, 0.7071f, 0.0f, 2);
	vx2Biquad(s->pk, fs, 400.0f, 1.1f, -4.0f * k, 1);
	vx2Biquad(s->sh, fs, 3000.0f, 0.9f, 8.0f * k, 1);
	vx2Biquad(s->air, fs, 9000.0f, 0.8f, 5.0f * k, 1);
	s->mix = (k > 0.001f) ? 1.0f : 0.0f;
}

void SpeakerOptProcess(JamesDSPLib *jdsp, size_t n)
{
	SpeakerOpt *s = &jdsp->speakerOpt;
	size_t i;
	int c;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		for (c = 0; c < 2; c++)
		{
			float x = jdsp->tmpBuffer[c][i];
			float y = vx2Bq(s->hp, s->hpz[c], x);
			y = vx2Bq(s->pk, s->pkz[c], y);
			y = vx2Bq(s->sh, s->shz[c], y);
			y = vx2Bq(s->air, s->airz[c], y);
			jdsp->tmpBuffer[c][i] = x + s->mix * (y - x);
		}
	}
}

void SpeakerOptEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->speakerOptEnabled)
	{
		memset(jdsp->speakerOpt.hpz, 0, sizeof(jdsp->speakerOpt.hpz));
		memset(jdsp->speakerOpt.pkz, 0, sizeof(jdsp->speakerOpt.pkz));
		memset(jdsp->speakerOpt.shz, 0, sizeof(jdsp->speakerOpt.shz));
		memset(jdsp->speakerOpt.airz, 0, sizeof(jdsp->speakerOpt.airz));
	}
	jdsp->speakerOptEnabled = 1;
}
void SpeakerOptDisable(JamesDSPLib *jdsp) { jdsp->speakerOptEnabled = 0; }
