/*
 * Loudness correction: puts back the bass and treble the ear stops hearing as
 * the volume goes down, by as much as the volume went down.
 *
 * Ported from EqualizerAPO's LoudnessCorrectionFilter (Copyright (C) 2017
 * Alexander Walch, GPL-2.0-or-later), by way of evoeram's Loudness / PEQ fork
 * of RootlessJamesDSP (GPL-3.0), which added the ISO 226:2023 mode, the
 * adjustable shelves and the subsonic filter. Here it is one engine effect for
 * every host, and the volume comes from the app instead of a polling thread.
 *
 * Both modes work from how far the volume is below the reference, in dB:
 *
 *   Classic   EqualizerAPO's two shelves. The low shelf (75 Hz) is raised by
 *             ratio / (1 - ratio) of that difference and the same amount comes
 *             off everything, so the bass stays where the volume control put
 *             it and the rest follows the volume down further. The high shelf
 *             (10 kHz) works from the volume after that cut. Above the
 *             reference both shelves dip a little instead.
 *   ISO       The difference between the ISO 226:2023 equal-loudness contours
 *             at the reference loudness and at the quieter one, relative to
 *             1 kHz, as 29 peaking filters on the third-octave centres. The
 *             fork made each a third of an octave wide and gave it its own
 *             band's value; the skirts then added up to about half as much
 *             again at the centres and left a 2.5 dB ripple between them.
 *             Here each is two thirds of an octave wide, so the sum is smooth,
 *             and the gains are solved for so that the whole cascade lands on
 *             the contour difference at every centre.
 *
 * Nothing is ever boosted: the highest point of the whole response, subsonic
 * filter included, is brought down to 0 dB, so nothing new can clip. In the
 * classic mode with its usual settings that is exactly EqualizerAPO's own cut.
 * At the reference volume every section is flat and the signal passes through
 * untouched.
 *
 * The sections are trapezoidal state-variable filters (Andrew Simper's form)
 * rather than biquads. They give the same responses as the cookbook filters,
 * but their state is the level in each integrator, which still means the same
 * thing when the coefficients move, which is why the form is the usual choice
 * for a filter that has to be swept. A volume change moves every coefficient
 * in a straight line to its new value over 40 ms, so nothing steps on a held
 * note when the volume keys are pressed.
 */
#include <math.h>
#include <string.h>
#include "../jdsp_header.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define LOUD_GLIDE_MS 40.0
// No section goes past this, whatever the settings: by then the music is
// inaudible anyway, and the filters stay well conditioned.
#define LOUD_MAX_DB 40.0
// Below this a section is left exactly flat.
#define LOUD_ZERO_DB 1e-4
// Two thirds of an octave: twice the band spacing, wide enough that the
// cascade is smooth between centres.
#define LOUD_ISO_Q 2.0
#define LOUD_FIT_PASSES 8
// Where the highest point is looked for: every 24th of an octave from
// 5 Hz up, plus every band centre.
#define LOUD_PEAK_POINTS 288

// ISO 226:2023, Table 1: the preferred third-octave frequencies, the exponent
// for loudness perception, the magnitude of the linear transfer function
// normalised at 1 kHz, and the threshold of hearing.
static const double isoFreq[LOUD_ISO_BANDS] = {
	20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
	200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
	2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0
};
static const double isoAf[LOUD_ISO_BANDS] = {
	0.635, 0.602, 0.569, 0.537, 0.509, 0.482, 0.456, 0.433, 0.412, 0.391,
	0.373, 0.357, 0.343, 0.330, 0.320, 0.311, 0.303, 0.300, 0.295, 0.292,
	0.290, 0.290, 0.289, 0.289, 0.289, 0.293, 0.303, 0.323, 0.354
};
static const double isoLu[LOUD_ISO_BANDS] = {
	-31.5, -27.2, -23.1, -19.3, -16.1, -13.1, -10.4, -8.2, -6.3, -4.6,
	-3.2, -2.1, -1.2, -0.5, 0.0, 0.4, 0.5, 0.0, -2.7, -4.2,
	-1.2, 1.4, 2.3, 1.0, -2.3, -7.2, -11.2, -10.9, -3.5
};
static const double isoTf[LOUD_ISO_BANDS] = {
	78.1, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
	14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
	-1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3
};

// ISO 226:2023 formula (1): the sound pressure level of a tone at band i that
// sounds as loud as a 1 kHz tone at the given loudness level.
static double isoSpl(int i, double phon)
{
	const double af = isoAf[i];
	const double inner = pow(4e-10, 0.3 - af) * (pow(10.0, 0.03 * phon) - pow(10.0, 0.072)) +
		pow(10.0, af * (isoTf[i] + isoLu[i]) / 10.0);
	return 10.0 / af * log10(inner) - isoLu[i];
}

/* NaN fails every comparison, so it is caught first and counts as the bottom
   of the range; the infinities clamp like any other value. In root mode any
   app can send these. */
static float loudHold(float v, float lo, float hi)
{
	if (!(v == v)) return lo;
	return v < lo ? lo : (v > hi ? hi : v);
}

static double loudClampDb(double g)
{
	return g < -LOUD_MAX_DB ? -LOUD_MAX_DB : (g > LOUD_MAX_DB ? LOUD_MAX_DB : g);
}

/* A section is g (the prewarped cutoff), k (the damping) and the mix of the
   input, band-pass and low-pass outputs, m0, m1 and m2. Flat is m0 = 1 and the
   other two 0, whatever g and k are; a flat section keeps the g and k it would
   have, so turning it up later only moves the mix. */
static void loudSvf(double *c, double freq, double k, double m0, double m1, double m2, double fs)
{
	c[0] = tan(M_PI * freq / fs);
	c[1] = k;
	c[2] = m0;
	c[3] = m1;
	c[4] = m2;
}

static int loudIsFlat(const double *c)
{
	return c[2] == 1.0 && c[3] == 0.0 && c[4] == 0.0;
}

// The cookbook shelf with a slope S, as EqualizerAPO designs it, in Simper's
// form: the same bilinear transform of the same analogue prototype.
static void loudShelf(double *c, int high, double gainDb, double freq, double slope, double fs)
{
	const double A = pow(10.0, gainDb / 40.0);
	const double k = sqrt((A + 1.0 / A) * (1.0 / slope - 1.0) + 2.0);
	if (!high)
	{
		loudSvf(c, freq, k, 1.0, k * (A - 1.0), A * A - 1.0, fs);
		c[0] /= sqrt(A);
	}
	else
	{
		loudSvf(c, freq, k, A * A, k * (1.0 - A) * A, 1.0 - A * A, fs);
		c[0] *= sqrt(A);
	}
}

static void loudPeak(double *c, double gainDb, double freq, double q, double fs)
{
	const double A = pow(10.0, gainDb / 40.0);
	const double k = 1.0 / (q * A);
	loudSvf(c, freq, k, 1.0, k * (A * A - 1.0), 0.0, fs);
}

static void loudHighPass(double *c, double freq, double q, double fs)
{
	loudSvf(c, freq, 1.0 / q, 1.0, -1.0 / q, -1.0, fs);
}

/* |H|^2 of a section at the angle whose cosine is cw (and cosine of twice
   it, c2w). The section as a biquad: H(s) = (m0 s^2 + (m0 k + m1) s +
   m0 + m2) / (s^2 + k s + 1) with s = (1 - 1/z) / (g (1 + 1/z)). */
static double loudMag2(const double *c, double cw, double c2w)
{
	const double g = c[0], k = c[1], m0 = c[2], m1 = c[3], m2 = c[4];
	const double g2 = g * g, mid = (m0 * k + m1) * g, low = (m0 + m2) * g2;
	const double b0 = m0 + mid + low, b1 = 2.0 * (low - m0), b2 = m0 - mid + low;
	const double a0 = 1.0 + k * g + g2, a1 = 2.0 * (g2 - 1.0), a2 = 1.0 - k * g + g2;
	const double num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * cw + 2.0 * b0 * b2 * c2w;
	const double den = a0 * a0 + a1 * a1 + a2 * a2 + 2.0 * (a0 * a1 + a1 * a2) * cw + 2.0 * a0 * a2 * c2w;
	return den > 1e-30 ? num / den : 1.0;
}

static double loudCascadeDb(const double (*d)[5], int count, double freq, double fs)
{
	const double w = 2.0 * M_PI * freq / fs;
	const double cw = cos(w), c2w = cos(2.0 * w);
	double m = 1.0;
	for (int j = 0; j < count; j++)
		if (!loudIsFlat(d[j]))
			m *= loudMag2(d[j], cw, c2w);
	return 10.0 * log10(m > 1e-30 ? m : 1e-30);
}

// The trapezoidal one-pole high-pass used for odd orders, in dB.
static double loudFirstOrderDb(double g, double freq, double fs)
{
	const double t = tan(M_PI * freq / fs) / g;
	return 10.0 * log10(t * t / (1.0 + t * t));
}

// Returns the cut in dB EqualizerAPO puts ahead of the shelves.
static double loudDesignClassic(const LoudnessCorrection *lc, double volDiff, double att, double fs,
	double (*d)[5])
{
	const double r = lc->lsRatio * 0.01;
	double gLs = 0.0, gHs = 0.0, pre = 0.0;
	if (volDiff > 0.0)
		gLs = volDiff * r / (1.0 - r) * att;
	else if (volDiff < 0.0)
		gLs = volDiff * r * exp(volDiff / 90.0) * att;
	gLs = loudClampDb(gLs);
	if (gLs > 0.0)
		pre = -gLs;
	// EqualizerAPO works the treble out from the volume after the cut.
	const double hv = volDiff - pre;
	const double t = lc->hsRatio * 0.01;
	if (hv > 0.0)
		gHs = hv * t * exp(-hv / 100.0) * att;
	else if (hv < 0.0)
		gHs = hv * t * 0.778 * exp(hv / 80.0) * att;
	gHs = loudClampDb(gHs);
	loudShelf(d[0], 0, fabs(gLs) > LOUD_ZERO_DB ? gLs : 0.0, lc->lsFreq, lc->lsSlope, fs);
	loudShelf(d[1], 1, fabs(gHs) > LOUD_ZERO_DB ? gHs : 0.0, lc->hsFreq, lc->hsSlope, fs);
	return pre;
}

/* Solves a x = b for x in place of b, by Gaussian elimination with partial
   pivoting; a is overwritten. 29 unknowns, so this is quick. */
static void loudSolve(double a[LOUD_ISO_BANDS][LOUD_ISO_BANDS], double *b)
{
	const int n = LOUD_ISO_BANDS;
	for (int k = 0; k < n; k++)
	{
		int p = k;
		for (int i = k + 1; i < n; i++)
			if (fabs(a[i][k]) > fabs(a[p][k])) p = i;
		if (p != k)
		{
			for (int j = 0; j < n; j++) { const double t = a[k][j]; a[k][j] = a[p][j]; a[p][j] = t; }
			const double t = b[k]; b[k] = b[p]; b[p] = t;
		}
		if (fabs(a[k][k]) < 1e-12)
			continue;
		for (int i = k + 1; i < n; i++)
		{
			const double m = a[i][k] / a[k][k];
			for (int j = k; j < n; j++) a[i][j] -= m * a[k][j];
			b[i] -= m * b[k];
		}
	}
	for (int k = n - 1; k >= 0; k--)
	{
		double v = b[k];
		for (int j = k + 1; j < n; j++) v -= a[k][j] * b[j];
		b[k] = fabs(a[k][k]) < 1e-12 ? 0.0 : v / a[k][k];
	}
}

static void loudDesignIso(const LoudnessCorrection *lc, double volDiff, double att, double fs,
	double (*d)[5])
{
	double refPhon = lc->basePhon, curPhon = lc->basePhon - volDiff;
	// The contours are defined from 20 to 90 phon.
	if (refPhon < 20.0) refPhon = 20.0;
	if (refPhon > 90.0) refPhon = 90.0;
	if (curPhon < 20.0) curPhon = 20.0;
	if (curPhon > 90.0) curPhon = 90.0;
	double target[LOUD_ISO_BANDS], g[LOUD_ISO_BANDS], cw[LOUD_ISO_BANDS], c2w[LOUD_ISO_BANDS];
	int any = 0;
	for (int i = 0; i < LOUD_ISO_BANDS; i++)
	{
		// How much more (or less) level this band needs than 1 kHz does to
		// keep its loudness at the quieter setting.
		target[i] = loudClampDb((isoSpl(i, curPhon) - isoSpl(i, refPhon) - (curPhon - refPhon)) * att);
		if (fabs(target[i]) > LOUD_ZERO_DB)
			any = 1;
		const double w = 2.0 * M_PI * isoFreq[i] / fs;
		cw[i] = cos(w);
		c2w[i] = cos(2.0 * w);
	}
	if (!any)
	{
		for (int j = 0; j < LOUD_ISO_BANDS; j++)
			loudPeak(d[j], 0.0, isoFreq[j], LOUD_ISO_Q, fs);
		return;
	}
	// What 1 dB on each band does at every centre. A peak's shape in dB
	// barely changes with its gain, so this stands in for the true slope
	// throughout, and each pass corrects every gain by what is still missing.
	// The usual settings are within 0.01 dB after three passes.
	double shape[LOUD_ISO_BANDS][LOUD_ISO_BANDS], a[LOUD_ISO_BANDS][LOUD_ISO_BANDS];
	for (int j = 0; j < LOUD_ISO_BANDS; j++)
	{
		loudPeak(d[j], 1.0, isoFreq[j], LOUD_ISO_Q, fs);
		for (int i = 0; i < LOUD_ISO_BANDS; i++)
			shape[i][j] = 10.0 * log10(loudMag2(d[j], cw[i], c2w[i]));
	}
	memcpy(g, target, sizeof(g));
	memcpy(a, shape, sizeof(a));
	loudSolve(a, g);
	for (int pass = 0; pass < LOUD_FIT_PASSES; pass++)
	{
		double miss[LOUD_ISO_BANDS];
		for (int j = 0; j < LOUD_ISO_BANDS; j++)
		{
			g[j] = loudClampDb(g[j]);
			loudPeak(d[j], g[j], isoFreq[j], LOUD_ISO_Q, fs);
		}
		for (int i = 0; i < LOUD_ISO_BANDS; i++)
		{
			double m = 1.0;
			for (int j = 0; j < LOUD_ISO_BANDS; j++)
				m *= loudMag2(d[j], cw[i], c2w[i]);
			miss[i] = target[i] - 10.0 * log10(m > 1e-30 ? m : 1e-30);
		}
		memcpy(a, shape, sizeof(a));
		loudSolve(a, miss);
		for (int j = 0; j < LOUD_ISO_BANDS; j++)
			g[j] += miss[j];
	}
	for (int j = 0; j < LOUD_ISO_BANDS; j++)
	{
		g[j] = loudClampDb(g[j]);
		loudPeak(d[j], fabs(g[j]) > LOUD_ZERO_DB ? g[j] : 0.0, isoFreq[j], LOUD_ISO_Q, fs);
	}
}

/* Works out every section and the overall gain from the kept settings and
   volume, at fs. */
static void loudDesign(LoudnessCorrection *lc, double fs, double (*c)[5], double fo[2], double *gain)
{
	// The subsonic filter: a one-pole for odd orders, then the second-order
	// sections, all at its frequency and flat while it is off.
	const int order = lc->subsonic ? lc->subOrder : 0;
	fo[0] = tan(M_PI * lc->subFreq / fs);
	fo[1] = (order & 1) ? 1.0 : 0.0;
	for (int k = 0; k < LOUD_SUB_SECTIONS; k++)
	{
		loudHighPass(c[k], lc->subFreq, lc->subQ, fs);
		if (k >= order / 2)
		{
			c[k][2] = 1.0;
			c[k][3] = c[k][4] = 0.0;
		}
	}
	const double att = lc->strength * 0.01;
	const double volDiff = (double)lc->refLevel - (double)lc->refOffset - (double)lc->volumeDb;
	double (*d)[5] = c + LOUD_SUB_SECTIONS;
	double preDb = 0.0;
	if (lc->mode == LOUDNESS_MODE_ISO)
		loudDesignIso(lc, volDiff, att, fs, d);
	else
	{
		preDb = loudDesignClassic(lc, volDiff, att, fs, d);
		// Flat, with what the ISO mode would use, ready for a change of mode.
		for (int j = 2; j < LOUD_ISO_BANDS; j++)
			loudPeak(d[j], 0.0, isoFreq[j], LOUD_ISO_Q, fs);
	}
	// The highest point of everything above, from 5 Hz to 20 kHz (or just
	// short of Nyquist), and at every band centre.
	double peak = 0.0;
	int any = fo[1] != 0.0;
	for (int s = 0; s < LOUD_SECTIONS; s++)
		any = any || !loudIsFlat(c[s]);
	if (any)
	{
		const double top = fs * 0.45 < 20000.0 ? fs * 0.45 : 20000.0;
		for (int i = 0; i < LOUD_PEAK_POINTS + LOUD_ISO_BANDS; i++)
		{
			const double f = i < LOUD_PEAK_POINTS ? 5.0 * pow(2.0, i / 24.0) : isoFreq[i - LOUD_PEAK_POINTS];
			if (f > top)
				continue;
			double r = loudCascadeDb((const double (*)[5])c, LOUD_SECTIONS, f, fs);
			if (fo[1] != 0.0)
				r += loudFirstOrderDb(fo[0], f, fs);
			if (r > peak)
				peak = r;
		}
	}
	if (-peak < preDb)
		preDb = -peak;
	*gain = preDb == 0.0 ? 1.0 : pow(10.0, preDb / 20.0);
}

// The integrator coefficients worked out from g and k.
static void loudCoefs(LoudnessCorrection *lc, int s)
{
	const double g = lc->cur[s][0], k = lc->cur[s][1];
	const double a1 = 1.0 / (1.0 + g * (g + k));
	lc->a[s][0] = a1;
	lc->a[s][1] = g * a1;
	lc->a[s][2] = g * g * a1;
}

/* Lists the sections that are not flat now or will not be at the end of the
   glide. One that joins the list starts from rest: whatever its state held
   from the last time it ran no longer belongs to the signal. */
static void loudListLive(LoudnessCorrection *lc)
{
	int n = 0;
	for (int s = 0; s < LOUD_SECTIONS; s++)
	{
		const int live = !loudIsFlat(lc->cur[s]) || !loudIsFlat(lc->tgt[s]);
		if (live && !lc->isLive[s])
		{
			memset(lc->z[0][s], 0, sizeof(lc->z[0][s]));
			memset(lc->z[1][s], 0, sizeof(lc->z[1][s]));
		}
		lc->isLive[s] = live;
		if (live)
		{
			lc->live[n++] = s;
			loudCoefs(lc, s);
		}
	}
	lc->liveCount = n;
	const int foLive = lc->fo[1] != 0.0 || lc->foTgt[1] != 0.0;
	if (foLive && !lc->foLive)
		lc->foZ[0] = lc->foZ[1] = 0.0;
	lc->foLive = foLive;
}

// Under the lock. With glide set and the effect running, it moves there over
// LOUD_GLIDE_MS; otherwise it is there at once.
static void loudInstall(JamesDSPLib *jdsp, int glide)
{
	LoudnessCorrection *lc = &jdsp->loudness;
	const double fs = jdsp->fs > 0.0f ? jdsp->fs : 48000.0;
	lc->fs = (float)fs;
	loudDesign(lc, fs, lc->tgt, lc->foTgt, &lc->gainTgt);
	const int len = (int)(LOUD_GLIDE_MS * 0.001 * fs);
	if (glide && jdsp->loudnessEnabled && len > 0)
	{
		const double inv = 1.0 / (double)len;
		for (int s = 0; s < LOUD_SECTIONS; s++)
			for (int k = 0; k < 5; k++)
				lc->step[s][k] = (lc->tgt[s][k] - lc->cur[s][k]) * inv;
		lc->foStep[0] = (lc->foTgt[0] - lc->fo[0]) * inv;
		lc->foStep[1] = (lc->foTgt[1] - lc->fo[1]) * inv;
		lc->gainStep = (lc->gainTgt - lc->gain) * inv;
		lc->glide = len;
	}
	else
	{
		memcpy(lc->cur, lc->tgt, sizeof(lc->cur));
		memcpy(lc->fo, lc->foTgt, sizeof(lc->fo));
		lc->gain = lc->gainTgt;
		lc->glide = 0;
	}
	loudListLive(lc);
}

void LoudnessSetParam(JamesDSPLib *jdsp, int mode, float refLevelDb, float refOffsetDb,
	float strengthPct, float bassHz, float bassSlope, float bassPct,
	float trebleHz, float trebleSlope, float treblePct, float phon,
	int subsonic, float subsonicHz, int subsonicOrder, float subsonicQ)
{
	jdsp_lock(jdsp);
	LoudnessCorrection *lc = &jdsp->loudness;
	lc->mode = mode == LOUDNESS_MODE_ISO ? LOUDNESS_MODE_ISO : LOUDNESS_MODE_CLASSIC;
	lc->refLevel = loudHold(refLevelDb, -60.0f, 0.0f);
	lc->refOffset = loudHold(refOffsetDb, -30.0f, 30.0f);
	lc->strength = loudHold(strengthPct, 0.0f, 200.0f);
	lc->lsFreq = loudHold(bassHz, 20.0f, 500.0f);
	lc->lsSlope = loudHold(bassSlope, 0.05f, 1.0f);
	// 100 % would divide by zero; 90 % already lifts the bass by nine times
	// the volume difference.
	lc->lsRatio = loudHold(bassPct, 0.0f, 90.0f);
	lc->hsFreq = loudHold(trebleHz, 2000.0f, 16000.0f);
	lc->hsSlope = loudHold(trebleSlope, 0.05f, 1.0f);
	lc->hsRatio = loudHold(treblePct, 0.0f, 100.0f);
	lc->basePhon = loudHold(phon, 40.0f, 90.0f);
	lc->subsonic = subsonic ? 1 : 0;
	lc->subFreq = loudHold(subsonicHz, 10.0f, 100.0f);
	lc->subOrder = subsonicOrder < 1 ? 1 : (subsonicOrder > 8 ? 8 : subsonicOrder);
	lc->subQ = loudHold(subsonicQ, 0.1f, 2.0f);
	loudInstall(jdsp, 1);
	jdsp_unlock(jdsp);
}

void LoudnessSetVolume(JamesDSPLib *jdsp, float volumeDb)
{
	// A volume that is not a number tells nothing; the last one stands.
	if (!(volumeDb == volumeDb))
		return;
	jdsp_lock(jdsp);
	LoudnessCorrection *lc = &jdsp->loudness;
	const float v = loudHold(volumeDb, -100.0f, 0.0f);
	if (v != lc->volumeDb)
	{
		lc->volumeDb = v;
		loudInstall(jdsp, 1);
	}
	jdsp_unlock(jdsp);
}

/* For a sample rate change; the caller holds the lock. Every coefficient is
   worked out from the rate. The stream starts over at a new rate, so the new
   design goes in at once and the filters start from rest. */
void LoudnessRefresh(JamesDSPLib *jdsp)
{
	LoudnessCorrection *lc = &jdsp->loudness;
	loudInstall(jdsp, 0);
	memset(lc->z, 0, sizeof(lc->z));
	lc->foZ[0] = lc->foZ[1] = 0.0;
}

static void loudGlideStep(LoudnessCorrection *lc)
{
	if (--lc->glide > 0)
	{
		for (int k = 0; k < lc->liveCount; k++)
		{
			const int s = lc->live[k];
			double *c = lc->cur[s];
			const double *st = lc->step[s];
			c[0] += st[0]; c[1] += st[1]; c[2] += st[2]; c[3] += st[3]; c[4] += st[4];
			loudCoefs(lc, s);
		}
		lc->fo[0] += lc->foStep[0];
		lc->fo[1] += lc->foStep[1];
		lc->gain += lc->gainStep;
		return;
	}
	// Exactly on target at the end, and the sections that ended flat leave
	// the list.
	memcpy(lc->cur, lc->tgt, sizeof(lc->cur));
	memcpy(lc->fo, lc->foTgt, sizeof(lc->fo));
	lc->gain = lc->gainTgt;
	loudListLive(lc);
}

void LoudnessProcess(JamesDSPLib *jdsp, size_t n)
{
	LoudnessCorrection *lc = &jdsp->loudness;
	if (lc->liveCount == 0 && !lc->foLive && lc->glide == 0 && lc->gain == 1.0)
		return;
	float *io[2] = { jdsp->tmpBuffer[0], jdsp->tmpBuffer[1] };
	for (size_t i = 0; i < n; i++)
	{
		if (lc->glide > 0)
			loudGlideStep(lc);
		for (int ch = 0; ch < 2; ch++)
		{
			double x = io[ch][i];
			// Silence in place of a sample that is not a number or not
			// finite, which would otherwise stay in the filters for good.
			if (!(fabs(x) <= 1e30)) x = 0.0;
			x *= lc->gain;
			if (lc->foLive)
			{
				double *s = &lc->foZ[ch];
				const double v = (x - *s) * lc->fo[0] / (1.0 + lc->fo[0]);
				const double lp = v + *s;
				*s = lp + v;
				x -= lc->fo[1] * lp;
			}
			for (int k = 0; k < lc->liveCount; k++)
			{
				const int s = lc->live[k];
				const double *a = lc->a[s], *c = lc->cur[s];
				double *z = lc->z[ch][s];
				const double v3 = x - z[1];
				const double v1 = a[0] * z[0] + a[1] * v3;
				const double v2 = z[1] + a[1] * z[0] + a[2] * v3;
				z[0] = 2.0 * v1 - z[0];
				z[1] = 2.0 * v2 - z[1];
				x = c[2] * x + c[3] * v1 + c[4] * v2;
			}
			io[ch][i] = (float)x;
		}
	}
	// A decaying tail would otherwise end up in denormals, which are slow.
	for (int k = 0; k < lc->liveCount; k++)
	{
		for (int ch = 0; ch < 2; ch++)
		{
			double *z = lc->z[ch][lc->live[k]];
			if (fabs(z[0]) < 1e-30) z[0] = 0.0;
			if (fabs(z[1]) < 1e-30) z[1] = 0.0;
		}
	}
	for (int ch = 0; ch < 2; ch++)
		if (fabs(lc->foZ[ch]) < 1e-30) lc->foZ[ch] = 0.0;
}

void LoudnessEnable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	if (jdsp->loudnessEnabled)
	{
		jdsp_unlock(jdsp);
		return;
	}
	// Straight to the design for the volume as it is now, from rest.
	LoudnessCorrection *lc = &jdsp->loudness;
	memset(lc->z, 0, sizeof(lc->z));
	lc->foZ[0] = lc->foZ[1] = 0.0;
	loudInstall(jdsp, 0);
	jdsp->loudnessEnabled = 1;
	jdsp_unlock(jdsp);
}

void LoudnessDisable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	jdsp->loudnessEnabled = 0;
	jdsp_unlock(jdsp);
}
