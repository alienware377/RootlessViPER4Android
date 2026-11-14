// Does the sub-harmonic expander add the notes it says, and only those, and
// does it stay clean on the bass real music has?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/subharmonic_harness.c $J/Effects/subHarmonic.c tools/lockstub.c -lm -o subharmonic
//
// Most checks look at what the effect added (output minus input), since at
// 0 dB output level that is exactly the sub. Levels are compared with what the
// filters on the way should give, worked out from their analogue prototypes;
// at these frequencies the bilinear warping is far below the tolerances.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "jdsp_header.h"

#define MAXN (48000 * 8)
#define BLOCK 256

static JamesDSPLib g_lib;
static float bufL[MAXN], bufR[MAXN];
static float inL[MAXN], inR[MAXN];
static double d[MAXN];
static int failures = 0;

static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-58s %11.5g  [%9.4g..%9.4g]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static void checkTrue(const char *what, int ok)
{
	printf("  %-58s %s\n", what, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static void prepare(float fs)
{
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = fs;
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
}

static void setOnly(int divider, float amount, float band, float mix, float outDb)
{
	float a[SUBH_DIVIDERS] = { 0 };
	if (divider >= 2) a[divider - 2] = amount;
	SubHarmonicSetParam(&g_lib, a, band, mix, outDb);
}

/* Runs [from, to) through the effect in blocks, keeping a copy of the input
   and the difference the effect made. */
static void run(int from, int to, int block)
{
	memcpy(inL + from, bufL + from, sizeof(float) * (size_t)(to - from));
	memcpy(inR + from, bufR + from, sizeof(float) * (size_t)(to - from));
	for (int i = from; i < to; i += block)
	{
		const int n = i + block <= to ? block : to - i;
		float *sl = g_lib.tmpBuffer[0], *sr = g_lib.tmpBuffer[1];
		g_lib.tmpBuffer[0] = bufL + i;
		g_lib.tmpBuffer[1] = bufR + i;
		if (g_lib.subHarmonicEnabled)
			SubHarmonicProcess(&g_lib, (size_t)n);
		g_lib.tmpBuffer[0] = sl;
		g_lib.tmpBuffer[1] = sr;
	}
	for (int i = from; i < to; i++)
		d[i] = (double)bufL[i] - (double)inL[i];
}

/* Least-squares fit of a sine at f over [a, b): its amplitude, and what is
   left once it is taken out, relative to the whole. */
static double fitAmp(const double *x, int a, int b, double f, double fs, double *residualDb)
{
	double ss = 0, cc = 0, sc = 0, xs = 0, xc = 0, xx = 0;
	for (int i = a; i < b; i++)
	{
		const double w = 2.0 * M_PI * f * (double)i / fs;
		const double s = sin(w), c = cos(w);
		ss += s * s; cc += c * c; sc += s * c;
		xs += x[i] * s; xc += x[i] * c; xx += x[i] * x[i];
	}
	const double det = ss * cc - sc * sc;
	const double A = (xs * cc - xc * sc) / det;
	const double B = (xc * ss - xs * sc) / det;
	if (residualDb)
	{
		double rr = 0;
		for (int i = a; i < b; i++)
		{
			const double w = 2.0 * M_PI * f * (double)i / fs;
			const double r = x[i] - A * sin(w) - B * cos(w);
			rr += r * r;
		}
		*residualDb = 10.0 * log10((rr + 1e-300) / (xx + 1e-300));
	}
	return sqrt(A * A + B * B);
}

/* Upward zero crossings of x in [a, b), interpolated; returns their count. */
static int crossings(const double *x, int a, int b, double *t, int max)
{
	int n = 0;
	for (int i = a + 1; i < b && n < max; i++)
		if (x[i - 1] < 0.0 && x[i] >= 0.0)
			t[n++] = (double)(i - 1) + x[i - 1] / (x[i - 1] - x[i]);
	return n;
}

static double peakOf(const double *x, int a, int b)
{
	double p = 0;
	for (int i = a; i < b; i++) if (fabs(x[i]) > p) p = fabs(x[i]);
	return p;
}

static double maxStep(const double *x, int a, int b)
{
	double p = 0;
	for (int i = a + 1; i < b; i++) if (fabs(x[i] - x[i - 1]) > p) p = fabs(x[i] - x[i - 1]);
	return p;
}

static double meanOf(const double *x, int a, int b)
{
	double s = 0;
	for (int i = a; i < b; i++) s += x[i];
	return s / (double)(b - a);
}

static double rmsOf(const double *x, int a, int b)
{
	double s = 0;
	for (int i = a; i < b; i++) s += x[i] * x[i];
	return sqrt(s / (double)(b - a));
}

/* Magnitudes of the analogue prototypes of the effect's filters. */
static double lr4(double f, double fc) { return 1.0 / (1.0 + pow(f / fc, 4.0)); }
static double bwLp(double f, double fc) { return 1.0 / sqrt(1.0 + pow(f / fc, 4.0)); }
static double bwHp(double f, double fc) { return 1.0 / sqrt(1.0 + pow(fc / f, 4.0)); }

/* What a steady sine of amplitude a at f should come out as at f/N. */
static double expected(double a, double f, int N, double amount, double band, double mix)
{
	const double fs = f / (double)N;
	return a * lr4(f, band) * bwHp(f, 20.0) * amount * mix * bwHp(fs, 12.0) * bwLp(fs, band);
}

static void sine(int from, int to, double f, double a, double fs)
{
	for (int i = from; i < to; i++)
		bufL[i] = bufR[i] = (float)(a * sin(2.0 * M_PI * f * (double)i / fs));
}

static void silence(int from, int to)
{
	memset(bufL + from, 0, sizeof(float) * (size_t)(to - from));
	memset(bufR + from, 0, sizeof(float) * (size_t)(to - from));
}

static unsigned rngState = 12345u;
static double rnd(void)
{
	rngState = rngState * 1664525u + 1013904223u;
	return (double)(rngState >> 8) / 16777216.0 * 2.0 - 1.0;
}

/* The bass band as the effect takes it, in double precision, for bounds. */
static void bandOf(const float *x, double *y, int n, double fc, double fs)
{
	double b0, b1, b2, a1, a2;
	{
		const double w0 = 2.0 * M_PI * fc / fs, cw = cos(w0), al = sin(w0) / (2.0 * 0.70710678118654752);
		const double a0 = 1.0 + al;
		b0 = (1.0 - cw) * 0.5 / a0; b1 = (1.0 - cw) / a0; b2 = b0;
		a1 = -2.0 * cw / a0; a2 = (1.0 - al) / a0;
	}
	double z[2][2] = { { 0, 0 }, { 0, 0 } };
	for (int i = 0; i < n; i++)
	{
		double v = x[i];
		for (int s = 0; s < 2; s++)
		{
			const double o = b0 * v + z[s][0];
			z[s][0] = b1 * v - a1 * o + z[s][1];
			z[s][1] = b2 * v - a2 * o;
			v = o;
		}
		y[i] = v;
	}
}
static double bandBuf[MAXN];

int main(void)
{
	printf("== sub-harmonic expander ==\n\n");
	const double fs = 48000.0;
	const int S = 48000;

	printf("a steady 80 Hz note, f/2 at 100 %%, mix 50 %%\n");
	{
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 50.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, 3 * S, 80.0, 0.25, fs);
		run(0, 3 * S, BLOCK);
		double resid;
		const double want = expected(0.25, 80.0, 2, 1.0, 120.0, 0.5);
		const double got = fitAmp(d, S, 3 * S, 40.0, fs, &resid);
		check("40 Hz level against the filters' prediction, dB", 20.0 * log10(got / want), -0.5, 0.5);
		check("everything else in what was added, dB", resid, -200.0, -40.0);
		check("80 Hz in what was added (the note left alone), dB re note",
			  20.0 * log10(fitAmp(d, S, 3 * S, 80.0, fs, NULL) / 0.25 + 1e-12), -200.0, -50.0);
		double t[256];
		const int nc = crossings(d, S, 3 * S, t, 256);
		double worst = 0;
		for (int i = 1; i < nc; i++)
		{
			const double f = fs / (t[i] - t[i - 1]);
			if (fabs(f - 40.0) > worst) worst = fabs(f - 40.0);
		}
		check("cycles counted in 2 s", nc, 79, 81);
		check("worst cycle's frequency off 40 Hz, Hz (warble)", worst, 0.0, 0.05);
		checkTrue("left and right get the same", memcmp(bufL, bufR, sizeof(float) * 3 * S) == 0);
		check("DC in what was added, fraction of its RMS", fabs(meanOf(d, S, 3 * S)) / rmsOf(d, S, 3 * S), 0.0, 0.002);
	}

	printf("\nf/2 off, f/3 on\n");
	{
		prepare(48000.0f);
		setOnly(3, 100.0f, 120.0f, 50.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, 3 * S, 80.0, 0.25, fs);
		run(0, 3 * S, BLOCK);
		/* 1.95 s holds whole cycles of both 40 and 26.7 Hz, so neither leaks
		   into the other's fit. */
		const int e = S + 26 * 3600;
		check("nothing at 40 Hz, dB re note",
			  20.0 * log10(fitAmp(d, S, e, 40.0, fs, NULL) / 0.25 + 1e-12), -200.0, -80.0);
		double resid;
		const double want = expected(0.25, 80.0, 3, 1.0, 120.0, 0.5);
		const double got = fitAmp(d, S, e, 80.0 / 3.0, fs, &resid);
		check("26.7 Hz level against the prediction, dB", 20.0 * log10(got / want), -0.5, 0.5);
		check("everything else, dB", resid, -200.0, -40.0);
	}

	printf("\nf/2 and f/4 together on 100 Hz, each on its own\n");
	{
		prepare(48000.0f);
		float a[SUBH_DIVIDERS] = { 0 };
		a[0] = 60.0f; a[2] = 100.0f;
		SubHarmonicSetParam(&g_lib, a, 150.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, 3 * S, 100.0, 0.25, fs);
		run(0, 3 * S, BLOCK);
		check("50 Hz level, dB re prediction",
			  20.0 * log10(fitAmp(d, S, 3 * S, 50.0, fs, NULL) / expected(0.25, 100.0, 2, 0.6, 150.0, 1.0)), -0.5, 0.5);
		check("25 Hz level, dB re prediction",
			  20.0 * log10(fitAmp(d, S, 3 * S, 25.0, fs, NULL) / expected(0.25, 100.0, 4, 1.0, 150.0, 1.0)), -0.5, 0.5);
		check("75 Hz (the sum the shared phase made), dB re note",
			  20.0 * log10(fitAmp(d, S, 3 * S, 75.0, fs, NULL) / 0.25 + 1e-12), -200.0, -50.0);
	}

	printf("\nnothing below the hearing range\n");
	{
		prepare(48000.0f);
		setOnly(10, 100.0f, 120.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, 2 * S, 80.0, 0.25, fs);
		run(0, 2 * S, BLOCK);
		check("f/10 of an 80 Hz note (8 Hz), largest sample added", peakOf(d, 0, 2 * S), 0.0, 0.0);
	}

	printf("\na note gliding from 60 to 120 Hz over 4 s, f/2 at 100 %%, mix 100 %%\n");
	{
		prepare(48000.0f);
		setOnly(2, 100.0f, 150.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		const int n = 5 * S;
		double ph = 0;
		for (int i = 0; i < n; i++)
		{
			const double tt = (double)i / fs;
			const double f = tt < 0.5 ? 60.0 : tt < 4.5 ? 60.0 * pow(2.0, (tt - 0.5) / 4.0) : 120.0;
			ph += 2.0 * M_PI * f / fs;
			bufL[i] = bufR[i] = (float)(0.25 * sin(ph));
		}
		run(0, n, BLOCK);
		/* The oscillator never runs faster than 1.5 times its own speed, so
		   no step can be steeper than a clean sine at 1.5 x 60 Hz. */
		check("largest step, share of a clean 60 Hz sine at that peak",
			  maxStep(d, S / 2, n) / (2.0 * M_PI * 60.0 / fs * peakOf(d, S / 2, n)), 0.0, 1.5);
		double t[1024];
		const int nc = crossings(d, S / 2, n, t, 1024);
		double worst = 0;
		for (int i = 1; i < nc; i++)
		{
			const double mid = 0.5 * (t[i] + t[i - 1]) / fs;
			const double fin = mid < 0.5 ? 60.0 : mid < 4.5 ? 60.0 * pow(2.0, (mid - 0.5) / 4.0) : 120.0;
			const double rel = fabs(fs / (t[i] - t[i - 1]) / (fin / 2.0) - 1.0);
			if (rel > worst) worst = rel;
		}
		check("worst cycle off half the note, fraction", worst, 0.0, 0.02);
		check("DC, fraction of the RMS", fabs(meanOf(d, S / 2, n)) / rmsOf(d, S / 2, n), 0.0, 0.01);
	}

	printf("\ntwo notes at once (55 and 82.4 Hz), f/2 at 100 %%, mix 100 %%\n");
	{
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		const int n = 4 * S;
		for (int i = 0; i < n; i++)
			bufL[i] = bufR[i] = (float)(0.2 * sin(2.0 * M_PI * 55.0 * i / fs) + 0.2 * sin(2.0 * M_PI * 82.4 * i / fs + 1.0));
		run(0, n, BLOCK);
		bandOf(inL, bandBuf, n, 120.0, fs);
		/* At full amount the sub follows the band's level, so it should never
		   get above the band's own peak. */
		check("peak added, share of the band's peak", peakOf(d, S, n) / peakOf(bandBuf, S, n), 0.0, 1.0);
		/* Bursts: the level in 20 ms windows against its median. */
		double w[256]; int nw = 0;
		for (int i = S; i + 960 <= n && nw < 256; i += 960) w[nw++] = rmsOf(d, i, i + 960);
		double sorted[256]; memcpy(sorted, w, sizeof(double) * nw);
		for (int i = 0; i < nw; i++) for (int j = i + 1; j < nw; j++) if (sorted[j] < sorted[i]) { double x = sorted[i]; sorted[i] = sorted[j]; sorted[j] = x; }
		double mx = 0; for (int i = 0; i < nw; i++) if (w[i] > mx) mx = w[i];
		check("loudest 20 ms against the median 20 ms", mx / sorted[nw / 2], 0.0, 2.0);
		check("largest step, share of a clean 41 Hz sine at that peak",
			  maxStep(d, S, n) / (2.0 * M_PI * 41.2 / fs * peakOf(d, S, n)), 0.0, 1.5);
	}

	printf("\nplucked bass with a kick and noise, f/2 and f/3 up\n");
	{
		prepare(48000.0f);
		float a[SUBH_DIVIDERS] = { 0 };
		a[0] = 100.0f; a[1] = 50.0f;
		SubHarmonicSetParam(&g_lib, a, 120.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		const int n = 6 * S;
		const double notes[4] = { 41.2, 55.0, 73.4, 49.0 };
		for (int i = 0; i < n; i++)
		{
			const double t = (double)i / fs;
			const int k = (int)(t / 0.5);
			const double tn = t - 0.5 * k;
			const double f = notes[k % 4];
			double v = 0;
			for (int h = 1; h <= 10; h++)
				v += sin(2.0 * M_PI * f * h * tn) / h;
			v *= 0.18 * exp(-tn / 0.35);
			const double tk = fmod(t + 0.25, 0.5);
			if (tk < 0.15)
				v += 0.45 * exp(-tk / 0.04) * sin(2.0 * M_PI * (45.0 * tk + 300.0 * tk * exp(-tk / 0.02)));
			v += 0.01 * rnd();
			bufL[i] = (float)(v + 0.02 * rnd());
			bufR[i] = (float)(v + 0.02 * rnd());
		}
		run(0, n, BLOCK);
		/* Mid of the input, as the effect sees it. */
		static float midIn[MAXN];
		for (int i = 0; i < n; i++) midIn[i] = 0.5f * (inL[i] + inR[i]);
		bandOf(midIn, bandBuf, n, 120.0, fs);
		/* The bound: the amounts together (1.5) times the band's peak over the
		   last 60 ms. No 10 ms stretch of what is added may get above it. */
		double worst = 0;
		for (int i = S / 2; i + 480 <= n; i += 480)
		{
			const double r = peakOf(d, i, i + 480) / (1.5 * (peakOf(bandBuf, i - 2400, i + 480) + 1e-9));
			if (r > worst) worst = r;
		}
		check("worst 10 ms peak, share of the bound", worst, 0.0, 1.0);
		int finite = 1;
		for (int i = 0; i < n; i++) if (!isfinite(bufL[i]) || !isfinite(bufR[i])) finite = 0;
		checkTrue("all output finite", finite);
		check("DC, fraction of the RMS", fabs(meanOf(d, S / 2, n)) / rmsOf(d, S / 2, n), 0.0, 0.02);
	}

	/* Bass guitar often has a second harmonic as strong as the note itself,
	   which puts an extra wiggle near zero in every cycle: the case that throws
	   a tracker counting bare zero crossings. */
	printf("\nplucked notes with a strong second harmonic, in noise, f/2 alone\n");
	{
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		const int n = 8 * S;
		const double notes[8] = { 41.2, 55.0, 73.4, 49.0, 61.7, 82.4, 46.2, 98.0 };
		rngState = 99u;
		for (int i = 0; i < n; i++)
		{
			const double t = (double)i / fs;
			const int k = (int)(t / 0.5);
			const double tn = t - 0.5 * k;
			const double f = notes[k % 8];
			double v = sin(2.0 * M_PI * f * tn) + sin(2.0 * M_PI * 2.0 * f * tn + 0.7);
			for (int h = 3; h <= 10; h++)
				v += sin(2.0 * M_PI * f * h * tn) / h;
			v *= 0.18 * exp(-tn / 0.35);
			v += 0.01 * rnd();
			bufL[i] = (float)(v + 0.02 * rnd());
			bufR[i] = (float)(v + 0.02 * rnd());
		}
		run(0, n, BLOCK);
		/* Every whole cycle of the sub that starts 50 ms or more into a note,
		   and separately those from 150 ms, by which time the note has had
		   three or more cycles to settle the tracker. A wrong octave or a
		   stray crossing shows as a cycle a third or more out. */
		double early = 0, settled = 0, sumSq = 0;
		int cycles = 0;
		for (int k = 0; k < 16; k++)
		{
			double t[64];
			const int a = k * S / 2 + S * 5 / 100, b = k * S / 2 + S * 45 / 100;
			const int nc = crossings(d, a, b, t, 64);
			for (int i = 1; i < nc; i++)
			{
				const double rel = fabs(fs / (t[i] - t[i - 1]) / (notes[k % 8] / 2.0) - 1.0);
				if (rel > early) early = rel;
				if (t[i - 1] >= k * S / 2 + S * 15 / 100)
				{
					if (rel > settled) settled = rel;
					sumSq += rel * rel;
					cycles++;
				}
			}
		}
		check("worst cycle from 50 ms in, fraction off half the note", early, 0.0, 0.15);
		check("worst cycle from 150 ms in, fraction off", settled, 0.0, 0.01);
		check("RMS of those, fraction off (warble)", sqrt(sumSq / cycles), 0.0, 0.003);
	}

	printf("\nsilence after a note\n");
	{
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, S, 80.0, 0.25, fs);
		silence(S, 4 * S);
		run(0, 4 * S, BLOCK);
		check("added 300 ms into the silence, dB re note", 20.0 * log10(peakOf(d, S + S * 3 / 10, 4 * S) / 0.25 + 1e-30), -400.0, -80.0);
		check("largest output sample in the last 2 s", peakOf(d, 2 * S, 4 * S), 0.0, 0.0);
		checkTrue("followers and filters back at exactly zero",
				  g_lib.subHarmonic.env2 == 0.0f && g_lib.subHarmonic.outHp.y1 == 0.0f &&
				  g_lib.subHarmonic.outLp.y1 == 0.0f && g_lib.subHarmonic.conf == 0.0f);
	}

	printf("\nnothing to add is bit for bit\n");
	{
		prepare(48000.0f);
		setOnly(2, 0.0f, 120.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		for (int i = 0; i < S; i++) { bufL[i] = (float)(0.5 * rnd()); bufR[i] = (float)(0.5 * rnd()); }
		bufL[100] = -0.0f;
		run(0, S, BLOCK);
		checkTrue("every amount at 0: output is the input", memcmp(bufL, inL, sizeof(float) * S) == 0 && memcmp(bufR, inR, sizeof(float) * S) == 0);
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 0.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		for (int i = 0; i < S; i++) { bufL[i] = (float)(0.5 * rnd()); bufR[i] = (float)(0.5 * rnd()); }
		run(0, S, BLOCK);
		checkTrue("mix at 0: output is the input", memcmp(bufL, inL, sizeof(float) * S) == 0 && memcmp(bufR, inR, sizeof(float) * S) == 0);
		/* The output level still applies at mix 0, as the card says: nothing
		   is added, but everything is turned down. */
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 0.0f, -6.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, S, 80.0, 0.25, fs);
		run(0, S, BLOCK);
		const float g6 = powf(10.0f, -6.0f / 20.0f);
		double off = 0;
		for (int i = S / 2; i < S; i++)
		{
			const double e = fabs((double)bufL[i] - (double)inL[i] * g6) + fabs((double)bufR[i] - (double)inR[i] * g6);
			if (e > off) off = e;
		}
		check("mix at 0, level -6 dB: largest difference from input 6 dB down", off, 0.0, 1e-7);
		/* And with the level brought back to 0 dB it gets all the way there:
		   bit for bit again once it has settled. */
		SubHarmonicSetParam(&g_lib, NULL, 120.0f, 0.0f, 0.0f);
		for (int i = S; i < 2 * S; i++) { bufL[i] = (float)(0.5 * rnd()); bufR[i] = (float)(0.5 * rnd()); }
		run(S, 2 * S, BLOCK);
		checkTrue("level back at 0 dB: output is the input again",
				  memcmp(bufL + S + S / 2, inL + S + S / 2, sizeof(float) * (S / 2)) == 0 &&
				  memcmp(bufR + S + S / 2, inR + S + S / 2, sizeof(float) * (S / 2)) == 0);
	}

	printf("\nswitching on and off\n");
	{
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 100.0f, -6.0f);
		sine(0, 4 * S, 80.0, 0.25, fs);
		run(0, S / 2, BLOCK);
		checkTrue("off before it is switched on: output is the input", memcmp(bufL, inL, sizeof(float) * (S / 2)) == 0);
		SubHarmonicEnable(&g_lib);
		run(S / 2, 3 * S, BLOCK);
		/* With the trim at -6 dB the whole output changes level; the step
		   bound compares output with output. */
		static double o[MAXN];
		for (int i = 0; i < 4 * S; i++) o[i] = bufL[i];
		/* Before it is on the output is the plain note; once on it is the note
		   6 dB down with the sub. The switch should step no more than either. */
		double steady = maxStep(o, 2 * S, 3 * S);
		if (maxStep(o, 0, S / 2 - 1) > steady) steady = maxStep(o, 0, S / 2 - 1);
		check("largest step while it switches on, share of steady", maxStep(o, S / 2 - 1, S / 2 + 4800) / steady, 0.0, 1.05);
		SubHarmonicDisable(&g_lib);
		checkTrue("still on until the fade has run", g_lib.subHarmonicEnabled == 1);
		run(3 * S, 4 * S, BLOCK);
		for (int i = 0; i < 4 * S; i++) o[i] = bufL[i];
		check("largest step while it switches off, share of steady", maxStep(o, 3 * S - 1, 3 * S + 4800) / steady, 0.0, 1.05);
		checkTrue("off once the 20 ms fade is done", g_lib.subHarmonicEnabled == 0);
		checkTrue("and from then on the output is the input",
				  memcmp(bufL + 3 * S + 4800, inL + 3 * S + 4800, sizeof(float) * (S - 4800)) == 0);
		/* Switching on again in the middle of the fade turns it round. */
		SubHarmonicEnable(&g_lib);
		run(0, S, BLOCK);
		SubHarmonicDisable(&g_lib);
		run(S, S + 240, BLOCK);
		SubHarmonicEnable(&g_lib);
		run(S + 240, 2 * S, BLOCK);
		checkTrue("switched on mid-fade, it stays on", g_lib.subHarmonicEnabled == 1);
	}

	printf("\nsettings changed while playing\n");
	{
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, 4 * S, 80.0, 0.25, fs);
		run(0, 2 * S, BLOCK);
		const double steady = maxStep(d, S, 2 * S);
		float a[SUBH_DIVIDERS] = { 30.0f, 100.0f, 0, 80.0f };
		SubHarmonicSetParam(&g_lib, a, 90.0f, 70.0f, 0.0f);
		run(2 * S, 4 * S, BLOCK);
		/* Steady before and steady once the new settings have settled. */
		const double after = maxStep(d, 3 * S, 4 * S);
		check("largest step while it changes, share of steady",
			  maxStep(d, 2 * S - 1, 3 * S) / (after > steady ? after : steady), 0.0, 1.05);
	}

	/* Each divider is pulled towards where the count says it should be, never
	   faster than 1.5 or slower than 0.5 times its own speed, so a sub that has
	   lost its place gets back without a jump. On every input above the
	   tracker keeps that error small (a new note starts the relation afresh),
	   so here it is put there directly, at a crest of the sub where a jump
	   would step furthest: 0.9 of half a cycle ahead, then behind. The band is
	   at 300 Hz so that the output lowpass would let a jump through as one. */
	for (int dir = 0; dir < 2; dir++)
	{
		const double moved = dir ? -0.9 * M_PI : 0.9 * M_PI;
		printf("\nf/2 put %s, band at 300 Hz\n", dir ? "0.45 of a cycle behind" : "0.45 of a cycle ahead");
		prepare(48000.0f);
		setOnly(2, 100.0f, 300.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		const int n = 3 * S;
		sine(0, n, 60.0, 0.25, fs);
		run(0, 2 * S, BLOCK);
		SubHarmonic *s = &g_lib.subHarmonic;
		int at = 2 * S;
		while (fabsf(sinf(s->phase[0])) < 0.999f && at < 2 * S + 1600)
		{
			run(at, at + 1, 1);
			at++;
		}
		s->refOff[0] += (float)moved;
		if (s->refOff[0] >= (float)(2.0 * M_PI)) s->refOff[0] -= (float)(2.0 * M_PI);
		if (s->refOff[0] < 0.0f) s->refOff[0] += (float)(2.0 * M_PI);
		/* A sample at a time for half a second, to see how far the oscillator
		   moves on each one against its own speed. */
		double fastest = 0, slowest = 1e9;
		for (; at < 2 * S + S / 2; at++)
		{
			const float before = s->phase[0];
			run(at, at + 1, 1);
			double step = (double)s->phase[0] - (double)before;
			if (step < 0.0) step += 2.0 * M_PI;
			const double r = step / (M_PI / (2.0 * (double)s->half));
			if (r > fastest) fastest = r;
			if (r < slowest) slowest = r;
		}
		run(at, n, BLOCK);
		check("fastest it runs, share of its own speed", fastest, 1.0, 1.501);
		check("slowest it runs, share of its own speed", slowest, 0.499, 1.0);
		/* 1.5 for the speed, and a little for the output filters ringing as
		   the frequency moves. */
		check("largest step while it catches up, share of steady",
			  maxStep(d, 2 * S - 1, n) / maxStep(d, S, 2 * S), 0.0, 1.75);
		/* One 30 Hz cycle before against one a third of a second on: 11 whole
		   cycles apart, so what is left is how far it moved. */
		double ps = 0, pc = 0, qs = 0, qc = 0;
		for (int i = 0; i < 1600; i++)
		{
			const double w = 2.0 * M_PI * 30.0 * (double)i / fs;
			ps += d[2 * S - 1600 + i] * sin(w); pc += d[2 * S - 1600 + i] * cos(w);
			qs += d[2 * S + 16000 + i] * sin(w); qc += d[2 * S + 16000 + i] * cos(w);
		}
		check("a third of a second on it has moved that far, rad (0.9 pi)",
			  fabs(remainder(atan2(qc, qs) - atan2(pc, ps), 2.0 * M_PI)), 0.9 * M_PI - 0.05, 0.9 * M_PI + 0.05);
		check("at its level again, dB re prediction",
			  20.0 * log10(fitAmp(d, 2 * S + S / 2, n, 30.0, fs, NULL) / expected(0.25, 60.0, 2, 1.0, 300.0, 1.0)), -0.5, 0.5);
	}

	printf("\nNaN and nonsense settings\n");
	{
		prepare(48000.0f);
		const float junk[SUBH_DIVIDERS] = { NAN, 1e30f, -1e30f, INFINITY, -INFINITY, NAN, 50.0f, NAN, 1e9f };
		SubHarmonicSetParam(&g_lib, junk, NAN, INFINITY, NAN);
		SubHarmonic *s = &g_lib.subHarmonic;
		int held = 1;
		for (int k = 0; k < SUBH_DIVIDERS; k++) held = held && s->amount[k] >= 0.0f && s->amount[k] <= 1.0f;
		checkTrue("amounts held to 0..100 %", held && s->amount[0] == 0.0f && s->amount[1] == 1.0f && s->amount[2] == 0.0f);
		checkTrue("band, mix and level held, NaN to the bottom of the range",
				  s->bandHz == 40.0f && s->mix == 1.0f && fabsf(s->outGain - powf(10.0f, -12.0f / 20.0f)) < 1e-6f);
		SubHarmonicSetParam(&g_lib, junk, 1e30f, -1e30f, 1e30f);
		checkTrue("huge values to the top", s->bandHz == 300.0f && s->mix == 0.0f && fabsf(s->outGain - powf(10.0f, 6.0f / 20.0f)) < 1e-5f);
		const float ok[SUBH_DIVIDERS] = { 100.0f };
		SubHarmonicSetParam(&g_lib, ok, 120.0f, 50.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, 3 * S, 80.0, 0.25, fs);
		bufL[S / 2] = NAN;
		bufR[S / 2 + 10] = 1e30f;
		bufL[S / 2 + 20] = INFINITY;
		run(0, 3 * S, BLOCK);
		int finite = 1;
		for (int i = S / 2 + 21; i < 3 * S; i++) if (!isfinite(bufL[i]) || !isfinite(bufR[i])) finite = 0;
		checkTrue("NaN, 1e30 and infinity in: everything after them finite", finite);
		check("and the sub is back at its level, dB re prediction",
			  20.0 * log10(fitAmp(d, 2 * S, 3 * S, 40.0, fs, NULL) / expected(0.25, 80.0, 2, 1.0, 120.0, 0.5)), -0.5, 0.5);
	}

	printf("\n48 kHz to 44.1 kHz mid-note\n");
	{
		prepare(48000.0f);
		setOnly(2, 100.0f, 120.0f, 50.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, 2 * S, 80.0, 0.25, fs);
		run(0, 2 * S, BLOCK);
		const float half48 = g_lib.subHarmonic.half;
		g_lib.fs = 44100.0f;
		SubHarmonicRefresh(&g_lib);
		check("the period followed carries over to the new rate, samples",
			  g_lib.subHarmonic.half, half48 * 44100.0f / 48000.0f - 0.01f, half48 * 44100.0f / 48000.0f + 0.01f);
		const int S2 = 44100;
		double ph = 2.0 * M_PI * 80.0 * (2 * S) / fs;
		for (int i = 2 * S; i < 2 * S + 3 * S2; i++)
		{
			bufL[i] = bufR[i] = (float)(0.25 * sin(ph));
			ph += 2.0 * M_PI * 80.0 / 44100.0;
		}
		run(2 * S, 2 * S + 3 * S2, BLOCK);
		double t[256];
		const int nc = crossings(d, 2 * S + S2 / 50, 2 * S + S2 / 2, t, 256);
		double worst = 0;
		for (int i = 1; i < nc; i++)
		{
			const double f = 44100.0 / (t[i] - t[i - 1]);
			if (fabs(f - 40.0) > worst) worst = fabs(f - 40.0);
		}
		check("worst cycle in the first half second, Hz off 40", worst, 0.0, 0.2);
		const int a = 2 * S + S2, b = 2 * S + 3 * S2;
		/* fitAmp works in sample index; shift the window to start at 0. */
		check("level at 44.1 kHz, dB re prediction",
			  20.0 * log10(fitAmp(d + a, 0, b - a, 40.0, 44100.0, NULL) / expected(0.25, 80.0, 2, 1.0, 120.0, 0.5)), -0.5, 0.5);
	}

	printf("\nany block size gives the same output\n");
	{
		static float ref[MAXN];
		const int sizes[4] = { 1, 37, BLOCK, 4096 };
		double worst = 0;
		for (int z = 0; z < 4; z++)
		{
			prepare(48000.0f);
			rngState = 777u;
			setOnly(2, 100.0f, 120.0f, 80.0f, -3.0f);
			SubHarmonicEnable(&g_lib);
			for (int i = 0; i < 2 * S; i++)
				bufL[i] = bufR[i] = (float)(0.25 * sin(2.0 * M_PI * 61.0 * i / fs) + 0.02 * rnd());
			run(0, 2 * S, sizes[z]);
			if (z == 0) memcpy(ref, bufL, sizeof(float) * 2 * S);
			else for (int i = 0; i < 2 * S; i++) if (fabs((double)bufL[i] - ref[i]) > worst) worst = fabs((double)bufL[i] - ref[i]);
		}
		check("largest difference between 1, 37, 256 and 4096", worst, 0.0, 1e-7);
	}

	printf("\ncost\n");
	{
		prepare(48000.0f);
		float a[SUBH_DIVIDERS];
		for (int k = 0; k < SUBH_DIVIDERS; k++) a[k] = 50.0f;
		SubHarmonicSetParam(&g_lib, a, 300.0f, 100.0f, 0.0f);
		SubHarmonicEnable(&g_lib);
		sine(0, 8 * S, 220.0, 0.25, fs);
		const clock_t c0 = clock();
		run(0, 8 * S, BLOCK);
		const double sec = (double)(clock() - c0) / CLOCKS_PER_SEC;
		printf("  all nine dividers: %.2f %% of a core at 48 kHz\n", 100.0 * sec / 8.0);
	}

	printf("\n%s (%d failed)\n", failures ? "FAIL" : "PASS", failures);
	return failures ? 1 : 0;
}
