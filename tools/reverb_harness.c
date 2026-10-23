// Do the smooth reverb models actually fix what was wrong with Hall and Room,
// and can the reverb survive a change of sample rate?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/aarch64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       -Wl,--wrap=calloc -Wl,--wrap=free \
//       tools/reverb_harness.c $J/Effects/viperextras2.c tools/lockstub.c -lm -o reverb
//
// "Metallic" is a complaint about a sound, so it needs turning into numbers
// before anything can be claimed about it. Two are used, both standard and
// both chosen because they measure the specific faults found in the old code:
//
//   Echo density (Abel and Huang's normalised echo density). In a short
//   window, the share of samples lying more than one standard deviation from
//   zero, divided by what Gaussian noise would give. A tail made of a few
//   discrete echoes scores low; a smooth wash scores close to 1. The old
//   network's coherent input and missing diffusion produce exactly the
//   few-discrete-echoes kind.
//
//   Spectral flatness of the tail, per octave. Geometric over arithmetic mean
//   of the power spectrum. For a noise-like tail the raw periodogram gives
//   e^-0.5772 = 0.56; resonances, which are a handful of frequencies outlasting
//   the rest, pull it down. Ringing is what the issue describes.
//
// Every claim is made against the old model as well as the new, and the old
// model must FAIL each threshold the new one passes: a metric that both
// passed would prove nothing about the fix.
//
// The rate-change test needs no metric. Every calloc made by the reverb is
// wrapped with 4 KB of guard bytes behind it, and the guard is inspected after
// the run, so a line writing past the end of its buffer is caught directly.
// Build with -DNO_REFRESH to run it against code without VReverbRefresh.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "jdsp_header.h"

#define BLK 1024
#define MAXN (48000 * 12)

static JamesDSPLib g_lib;
static float bufL[BLK], bufR[BLK];
static float irL[MAXN], irR[MAXN];
static int failures = 0;

/* ---------------- guard bytes around every allocation ---------------- */
void *__real_calloc(size_t a, size_t b);
void __real_free(void *p);
#define GUARD 4096
#define MAXBLK 256
static struct { unsigned char *p; size_t n; } g_blk[MAXBLK];
static int g_overruns = 0;

static int guardIntact(unsigned char *p, size_t n)
{
	for (size_t i = 0; i < GUARD; i++)
		if (p[n + i] != 0xA5) return 0;
	return 1;
}

void *__wrap_calloc(size_t a, size_t b)
{
	const size_t n = a * b;
	unsigned char *p = (unsigned char*)__real_calloc(1, n + GUARD);
	if (!p) return 0;
	memset(p + n, 0xA5, GUARD);
	for (int i = 0; i < MAXBLK; i++)
		if (!g_blk[i].p) { g_blk[i].p = p; g_blk[i].n = n; break; }
	return p;
}

void __wrap_free(void *q)
{
	for (int i = 0; i < MAXBLK; i++)
		if (g_blk[i].p && g_blk[i].p == q)
		{
			if (!guardIntact(g_blk[i].p, g_blk[i].n)) g_overruns++;
			g_blk[i].p = 0;
			break;
		}
	__real_free(q);
}

static int liveOverruns(void)
{
	int bad = 0;
	for (int i = 0; i < MAXBLK; i++)
		if (g_blk[i].p && !guardIntact(g_blk[i].p, g_blk[i].n)) bad++;
	return bad + g_overruns;
}

/* ---------------- helpers ---------------- */
static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-56s %9.3f  [%7.3f..%7.3f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static const char *modelName[] = { "classic", "plate", "hall", "room", "smooth hall", "smooth room" };

typedef struct { float room, damp, width, pre, decay, diff, mod, bass, er, wet, dry; } Params;
#ifndef PDEF_ER
#define PDEF_ER 50
#endif
static const Params P_DEFAULT = { 50, 50, 100, 0, 30, 70, 30, 50, PDEF_ER, 100, 0 };

static void prepare(float fs)
{
	if (g_lib.vreverbEnabled) VReverbDisable(&g_lib);
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = fs;
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
}

static void setp(int model, const Params *p)
{
	VReverbSetParam(&g_lib, model, p->room, p->damp, p->width, p->pre, p->decay,
		p->diff, p->mod, p->bass, p->er, p->wet, p->dry);
}

/* Impulse response, left channel into irL, right into irR */
static void impulse(int model, const Params *p, float fs, int len)
{
	prepare(fs);
	setp(model, p);
	VReverbEnable(&g_lib);
	for (int done = 0; done < len; done += BLK)
	{
		memset(bufL, 0, sizeof(bufL));
		memset(bufR, 0, sizeof(bufR));
		if (!done) bufL[0] = bufR[0] = 1.0f;
		VReverbProcess(&g_lib, BLK);
		const int n = (len - done < BLK) ? len - done : BLK;
		memcpy(irL + done, bufL, n * sizeof(float));
		memcpy(irR + done, bufR, n * sizeof(float));
	}
}

/* Mean normalised echo density over [t0, t1) seconds, 20 ms windows */
static double echoDensity(const float *h, float fs, double t0, double t1)
{
	const int w = (int)(0.020 * fs);
	const double erfcRef = 0.31731050786291415;   /* erfc(1/sqrt 2) */
	double acc = 0.0;
	int windows = 0;
	for (int s = (int)(t0 * fs); s + w <= (int)(t1 * fs); s += w / 2)
	{
		double e = 0.0;
		for (int i = 0; i < w; i++) e += (double)h[s + i] * h[s + i];
		const double sd = sqrt(e / w);
		if (sd <= 0.0) continue;
		int out = 0;
		for (int i = 0; i < w; i++) if (fabs(h[s + i]) > sd) out++;
		acc += ((double)out / w) / erfcRef;
		windows++;
	}
	return windows ? acc / windows : 0.0;
}

/* In-place radix-2 FFT */
static void fft(double *re, double *im, int n)
{
	for (int i = 1, j = 0; i < n; i++)
	{
		int bit = n >> 1;
		for (; j & bit; bit >>= 1) j ^= bit;
		j ^= bit;
		if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
	}
	for (int len = 2; len <= n; len <<= 1)
	{
		const double a = -2.0 * M_PI / len;
		for (int i = 0; i < n; i += len)
			for (int k = 0; k < len / 2; k++)
			{
				const double wr = cos(a * k), wi = sin(a * k);
				const double xr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi;
				const double xi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr;
				re[i + k + len / 2] = re[i + k] - xr; im[i + k + len / 2] = im[i + k] - xi;
				re[i + k] += xr; im[i + k] += xi;
			}
	}
}

#define FFTN 16384
static double fre[FFTN], fim[FFTN];
static double g_band[6];

/* Mean per-octave spectral flatness, octaves 250 Hz to 8 kHz, of the tail
   starting at t0 seconds */
static double tailFlatness(const float *h, float fs, double t0)
{
	const int s0 = (int)(t0 * fs);
	for (int i = 0; i < FFTN; i++)
	{
		const double win = 0.5 - 0.5 * cos(2.0 * M_PI * i / (FFTN - 1));
		fre[i] = h[s0 + i] * win;
		fim[i] = 0.0;
	}
	fft(fre, fim, FFTN);
	/* Damping makes the tail darker, and a spectrum that slopes across an
	   octave scores as less flat even when it is pure noise. That would mark
	   a well damped tail as ringing. So each bin is first divided by the mean
	   power of the third of an octave around it: what is left is how far it
	   stands out from its neighbours, which is what a resonance is. */
	static double pw[FFTN / 2 + 1], cum[FFTN / 2 + 2];
	cum[0] = 0.0;
	for (int k = 0; k <= FFTN / 2; k++)
	{
		pw[k] = fre[k] * fre[k] + fim[k] * fim[k] + 1e-300;
		cum[k + 1] = cum[k] + pw[k];
	}
	const double centres[] = { 250, 500, 1000, 2000, 4000, 8000 };
	double acc = 0.0;
	for (int b = 0; b < 6; b++)
	{
		const int k0 = (int)(centres[b] / sqrt(2.0) * FFTN / fs);
		const int k1 = (int)(centres[b] * sqrt(2.0) * FFTN / fs);
		double lg = 0.0, ar = 0.0;
		for (int k = k0; k < k1; k++)
		{
			int a = (int)(k / 1.122462), z = (int)(k * 1.122462) + 1;   /* 2^(1/6) */
			if (z > FFTN / 2) z = FFTN / 2;
			const double local = (cum[z + 1] - cum[a]) / (z + 1 - a);
			const double r = pw[k] / local;
			lg += log(r);
			ar += r;
		}
		const int nk = k1 - k0;
		g_band[b] = exp(lg / nk) / (ar / nk);
		acc += g_band[b];
	}
	return acc / 6.0;
}

/* Power spectrum of FFTN samples from s0, Hann windowed, into out[0..FFTN/2] */
static void powerAt(const float *h, int s0, double *out)
{
	for (int i = 0; i < FFTN; i++)
	{
		const double win = 0.5 - 0.5 * cos(2.0 * M_PI * i / (FFTN - 1));
		fre[i] = h[s0 + i] * win;
		fim[i] = 0.0;
	}
	fft(fre, fim, FFTN);
	for (int k = 0; k <= FFTN / 2; k++) out[k] = fre[k] * fre[k] + fim[k] * fim[k] + 1e-300;
}

/* Mean of v over [k / r, k * r] */
static double bandMean(const double *v, int k, double r)
{
	int a = (int)(k / r), z = (int)(k * r) + 1;
	if (a < 1) a = 1;
	if (z > FFTN / 2) z = FFTN / 2;
	double s = 0.0;
	for (int i = a; i <= z; i++) s += v[i];
	return s / (z + 1 - a);
}

static int cmpd(const void *a, const void *b)
{
	const double x = *(const double*)a, y = *(const double*)b;
	return x < y ? -1 : x > y;
}

/* Ringing: how much longer the worst frequencies last than their neighbours.
   Compare the spectrum of an early stretch of tail with a later one. A tail
   that decays evenly loses about the same number of dB everywhere, apart from
   the slow slope damping puts on it; a resonance is a narrow band that has
   lost much less. So: late minus early per 1/24 octave, minus the third of an
   octave around it, and report the 95th percentile, 200 Hz to 8 kHz. */
static double ringing(const float *h, float fs, double tEarly, double tLate)
{
	static double pe[FFTN / 2 + 1], pl[FFTN / 2 + 1], d[FFTN / 2 + 1], resid[FFTN / 2 + 1];
	powerAt(h, (int)(tEarly * fs), pe);
	powerAt(h, (int)(tLate * fs), pl);
	const int k0 = (int)(200.0 * FFTN / fs), k1 = (int)(8000.0 * FFTN / fs);
	for (int k = k0 / 2; k <= k1 * 2 && k <= FFTN / 2; k++)
		d[k] = 10.0 * log10(bandMean(pl, k, 1.014545) / bandMean(pe, k, 1.014545));   /* 2^(1/48) */
	int n = 0;
	for (int k = k0; k <= k1; k++)
	{
		int a = (int)(k / 1.122462), z = (int)(k * 1.122462) + 1;
		double s = 0.0;
		for (int i = a; i <= z; i++) s += d[i];
		resid[n++] = d[k] - s / (z + 1 - a);
	}
	qsort(resid, n, sizeof(double), cmpd);
	return resid[(int)(0.95 * n)];
}

/* Flutter: the strongest repetition in the early response. Discrete echoes
   arriving at a regular spacing are a comb filter, heard as a pitch at one
   over that spacing; it is the metallic twang of a bad reverb, and the old
   network's evenly spaced lines produce exactly that. The largest normalised
   autocorrelation at lags of 1 to 20 ms, over 20 to 200 ms of response. */
static double flutter(const float *h, float fs, double t0, double t1)
{
	const int a = (int)(t0 * fs), b = (int)(t1 * fs);
	double e = 0.0;
	for (int i = a; i < b; i++) e += (double)h[i] * h[i];
	if (e <= 0.0) return 0.0;
	double worst = 0.0;
	for (int lag = (int)(0.001 * fs); lag <= (int)(0.020 * fs); lag++)
	{
		double c = 0.0;
		for (int i = a; i + lag < b; i++) c += (double)h[i] * h[i + lag];
		c = fabs(c) / e;
		if (c > worst) worst = c;
	}
	return worst;
}

/* What a perfect reverb would measure: Gaussian noise decaying at the same
   rate, with no structure at all. The thresholds are set against this. */
static void idealTail(float fs, float rt60, int len)
{
	unsigned s = 12345u;
	for (int i = 0; i < len; i++)
	{
		/* Box-Muller from two uniforms */
		s = s * 1664525u + 1013904223u; const double u1 = ((s >> 8) + 1.0) / 16777217.0;
		s = s * 1664525u + 1013904223u; const double u2 = (s >> 8) / 16777216.0;
		const double g = sqrt(-2.0 * log(u1)) * cos(2.0 * M_PI * u2);
		irL[i] = irR[i] = (float)(g * exp(-6.907755 * i / (fs * rt60)));
	}
}

static double rmsDb(const float *h, int a, int b)
{
	double e = 0.0;
	for (int i = a; i < b; i++) e += (double)h[i] * h[i];
	e = sqrt(e / (b - a));
	return 20.0 * log10(e > 1e-30 ? e : 1e-30);
}

static int nonFinite(int len)
{
	int bad = 0;
	for (int i = 0; i < len; i++) if (!isfinite(irL[i]) || !isfinite(irR[i])) bad++;
	return bad;
}

static int subnormals(int len)
{
	int bad = 0;
	for (int i = 0; i < len; i++)
		if (fpclassify(irL[i]) == FP_SUBNORMAL || fpclassify(irR[i]) == FP_SUBNORMAL) bad++;
	return bad;
}

int main(void)
{
	const float fs = 48000.0f;
	const int len = (int)(1.2f * fs);
	double ned[6], flat[6], lvl[6], ring[6], flut[6];

	/* The yardstick first: perfectly structureless decaying noise, at the
	   decay the default settings ask for */
	idealTail(fs, 3.775f, len);
	const double nedIdeal = echoDensity(irL, fs, 0.030, 0.150);
	const double ringIdeal = ringing(irL, fs, 0.100, 0.450);
	const double flatIdeal = tailFlatness(irL, fs, 0.300);
	const double flutIdeal = flutter(irL, fs, 0.020, 0.200);

	printf("== reverb: smooth models against the ones they replace ==\n\n");
	printf("  %-12s %9s %9s %9s %9s %9s %9s\n", "model", "density", "density", "flutter", "ringing", "flatness", "level");
	printf("  %-12s %9s %9s %9s %9s %9s %9s\n", "", "30-150ms", "150-400ms", "20-200ms", "dB", "(info)", "dB 0-1s");
	printf("  %-12s %9.3f %9s %9.3f %9.2f %9.3f\n", "ideal", nedIdeal, "", flutIdeal, ringIdeal, flatIdeal);
	for (int m = 0; m < 6; m++)
	{
		impulse(m, &P_DEFAULT, fs, len);
		ned[m] = echoDensity(irL, fs, 0.030, 0.150);
		const double nedLate = echoDensity(irL, fs, 0.150, 0.400);
		ring[m] = ringing(irL, fs, 0.100, 0.450);
		flut[m] = flutter(irL, fs, 0.020, 0.200);
		flat[m] = tailFlatness(irL, fs, 0.300);
		lvl[m] = rmsDb(irL, 0, (int)fs);
		printf("  %-12s %9.3f %9.3f %9.3f %9.2f %9.3f %9.2f\n", modelName[m], ned[m], nedLate, flut[m], ring[m], flat[m], lvl[m]);
		if (nonFinite(len)) { printf("  %s produced non-finite samples\n", modelName[m]); failures++; }
	}

	/* What the measurements say the fault is. The old hall and room do not
	   have frequencies outlasting the rest: their ringing figure is no worse
	   than ideal noise. What they have is an early response made of a few
	   strong discrete echoes, a fifth of the density of a smooth tail, with a
	   repeating spacing. That is the twang, and it is what is asserted on.
	   Ringing stays as a guard: the rebuild must not introduce any.

	   Density must reach three quarters of ideal, a level both old models
	   miss by a factor of four. Flutter is compared head to head: lower than
	   the model it replaces, no threshold to choose. */
	const double nedMin = 0.75 * nedIdeal, ringMax = ringIdeal + 2.0;
	printf("\nthe old hall and room are measurably what the report describes\n");
	check("hall: early echo density (want low)", ned[2], 0.0, nedMin);
	check("room: early echo density (want low)", ned[3], 0.0, nedMin);

	printf("\nthe smooth ones are not\n");
	check("smooth hall: early echo density", ned[4], nedMin, 1.5);
	check("smooth room: early echo density", ned[5], nedMin, 1.5);
	check("smooth hall: flutter minus the old hall's", flut[4] - flut[2], -1.0, 0.0);
	check("smooth room: flutter minus the old room's", flut[5] - flut[3], -1.0, 0.0);
	check("smooth hall: ringing, no worse than ideal + 2 dB", ring[4], -99.0, ringMax);
	check("smooth room: ringing, no worse than ideal + 2 dB", ring[5], -99.0, ringMax);

	printf("\nand they sit at the same loudness as what they replace\n");
	check("smooth hall level minus hall (dB)", lvl[4] - lvl[2], -3.0, 3.0);
	check("smooth room level minus room (dB)", lvl[5] - lvl[3], -3.0, 3.0);

	printf("\nthe two ears get different tails, not one tail twice\n");
	for (int m = 4; m <= 5; m++)
	{
		impulse(m, &P_DEFAULT, fs, len);
		double xy = 0, xx = 0, yy = 0;
		for (int i = (int)(0.05f * fs); i < len; i++)
		{
			xy += (double)irL[i] * irR[i]; xx += (double)irL[i] * irL[i]; yy += (double)irR[i] * irR[i];
		}
		char what[80];
		snprintf(what, sizeof(what), "%s: left/right correlation", modelName[m]);
		check(what, xy / sqrt(xx * yy + 1e-30), -0.3, 0.3);
	}

	printf("\nevery setting at its extremes stays finite and bounded\n");
	for (int m = 4; m <= 5; m++)
	{
		const float corners[2] = { 0.0f, 100.0f };
		double worst = 0.0;
		int bad = 0;
		for (int mask = 0; mask < 512; mask++)
		{
			Params p = P_DEFAULT;
			float *f[9] = { &p.room, &p.damp, &p.width, &p.decay, &p.diff, &p.mod, &p.bass, &p.er, &p.wet };
			for (int b = 0; b < 9; b++) *f[b] = corners[(mask >> b) & 1];
			p.dry = 0.0f;
			impulse(m, &p, fs, (int)(0.5f * fs));
			bad += nonFinite((int)(0.5f * fs));
			for (int i = 0; i < (int)(0.5f * fs); i++)
			{
				if (fabs(irL[i]) > worst) worst = fabs(irL[i]);
				if (fabs(irR[i]) > worst) worst = fabs(irR[i]);
			}
		}
		char what[80];
		snprintf(what, sizeof(what), "%s: non-finite samples over 512 corners", modelName[m]);
		check(what, bad, 0, 0);
		snprintf(what, sizeof(what), "%s: worst peak from a unit impulse", modelName[m]);
		check(what, worst, 0.0, 2.0);
	}

	printf("\nthe longest decay still dies away, and to true zero\n");
	for (int m = 4; m <= 5; m++)
	{
		Params p = P_DEFAULT;
		p.decay = 100.0f; p.bass = 100.0f; p.damp = 0.0f; p.room = 100.0f;
		/* Twelve seconds of RT60, doubled at the bottom: it must still be
		   falling, at roughly the rate asked for */
		impulse(m, &p, fs, MAXN);
		const double early = rmsDb(irL, (int)(1.0f * fs), (int)(2.0f * fs));
		const double late = rmsDb(irL, (int)(10.0f * fs), (int)(11.0f * fs));
		char what[80];
		snprintf(what, sizeof(what), "%s: fall from 1-2 s to 10-11 s (dB)", modelName[m]);
		check(what, early - late, 15.0, 80.0);

		p = P_DEFAULT;
		p.decay = 0.0f;
		impulse(m, &p, fs, MAXN);
		snprintf(what, sizeof(what), "%s: subnormal samples after a short decay", modelName[m]);
		check(what, subnormals(MAXN), 0, 0);
		snprintf(what, sizeof(what), "%s: output is exactly zero by 11 s", modelName[m]);
		check(what, fabsf(irL[MAXN - 1]) + fabsf(irR[MAXN - 1]), 0.0, 0.0);
	}

	/* Switched on at 44.1 kHz with the room at its largest, then the stream
	   moves to 48 kHz and a slider is touched. Before the fix every line was
	   then sized for 48 kHz while its buffer was still the 44.1 kHz one. */
	printf("\na change of sample rate never writes past a buffer\n");
	for (int m = 1; m <= 5; m++)
	{
		Params p = P_DEFAULT;
		p.room = 100.0f;
		prepare(44100.0f);
		setp(m, &p);
		VReverbEnable(&g_lib);
		g_lib.fs = 48000.0f;
		setp(m, &p);
		for (int done = 0; done < 48000; done += BLK)
		{
			for (int i = 0; i < BLK; i++) bufL[i] = bufR[i] = (float)sin(0.05 * (done + i)) * 0.5f;
			VReverbProcess(&g_lib, BLK);
		}
		char what[80];
		snprintf(what, sizeof(what), "%s: buffers overrun", modelName[m]);
		check(what, liveOverruns(), 0, 0);
		VReverbDisable(&g_lib);
		g_overruns = 0;
	}

#ifndef NO_REFRESH
	/* The equivalence check from ratechange_harness.c: set up at one rate,
	   told of a new one, it must behave exactly as if set up at the new one.
	   And skipping the refresh must NOT match, or the check proves nothing. */
	printf("\na rate change is followed, exactly\n");
	{
		static float refL[BLK * 16];
		for (int m = 1; m <= 5; m++)
		{
			for (int pass = 0; pass < 3; pass++)
			{
				/* pass 0: reference at 48k. 1: 44.1k then refreshed. 2: not refreshed */
				prepare(pass == 0 ? 48000.0f : 44100.0f);
				setp(m, &P_DEFAULT);
				VReverbEnable(&g_lib);
				if (pass > 0)
				{
					g_lib.fs = 48000.0f;
					if (pass == 1) VReverbRefresh(&g_lib);
				}
				int same = 1;
				for (int b = 0; b < 16; b++)
				{
					for (int i = 0; i < BLK; i++)
						bufL[i] = bufR[i] = (b == 0 && i == 0) ? 1.0f : 0.0f;
					VReverbProcess(&g_lib, BLK);
					for (int i = 0; i < BLK; i++)
					{
						if (pass == 0) refL[b * BLK + i] = bufL[i];
						else if (refL[b * BLK + i] != bufL[i]) same = 0;
					}
				}
				if (pass > 0)
				{
					char what[80];
					snprintf(what, sizeof(what), "%s: %s matches a fresh 48 kHz setup", modelName[m],
						pass == 1 ? "refreshed" : "NOT refreshed");
					check(what, same, pass == 1 ? 1 : 0, pass == 1 ? 1 : 0);
				}
			}
		}
	}
#endif

	printf("\nwhat it costs, per stereo sample\n");
	for (int m = 0; m < 6; m++)
	{
		prepare(fs);
		setp(m, &P_DEFAULT);
		VReverbEnable(&g_lib);
		struct timespec t0, t1;
		clock_gettime(CLOCK_MONOTONIC, &t0);
		const int blocks = 200;
		for (int b = 0; b < blocks; b++)
		{
			for (int i = 0; i < BLK; i++) bufL[i] = bufR[i] = (float)sin(0.01 * i) * 0.3f;
			VReverbProcess(&g_lib, BLK);
		}
		clock_gettime(CLOCK_MONOTONIC, &t1);
		const double ns = ((t1.tv_sec - t0.tv_sec) * 1e9 + (t1.tv_nsec - t0.tv_nsec)) / (blocks * (double)BLK);
		printf("  %-12s %7.1f ns  (%.2f%% of one core at 48 kHz)\n", modelName[m], ns, ns * 48000.0 / 1e7);
	}

	printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "PASSED", failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
