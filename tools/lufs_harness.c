// Does the LUFS normalizer bring music to its target, and leave pauses alone?
//
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/lufs_harness.c $J/Effects/lufsNormalizer.c tools/lockstub.c -lm -o lufs
//
// The output is checked with a separate BS.1770 meter written here from the
// standard's published 48 kHz coefficients, not with the engine's own
// measurement, so a mistake in the engine's meter cannot hide itself.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

#define MAXSEC 24
#define MAXN (48000 * MAXSEC)

static JamesDSPLib g_lib;
static float bufL[4096], bufR[4096];
static float capL[MAXN], capR[MAXN];
static int failures = 0;

static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-58s %10.4f  [%9.4f..%9.4f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

// ---- the published BS.1770 48 kHz coefficients and a meter built on them --

static const double pubShelfB[3] = { 1.53512485958697, -2.69169618940638, 1.19839281085285 };
static const double pubShelfA[2] = { -1.69065929318241, 0.73248077421585 };
static const double pubHpB[3] = { 1.0, -2.0, 1.0 };
static const double pubHpA[2] = { -1.99004745483398, 0.99007225036621 };

/* Integrated loudness, BS.1770: 400 ms blocks every 100 ms, gated at -70 LUFS
   and then 10 LU under the mean. 48 kHz only. */
static double refLufs(const float *L, const float *R, size_t n)
{
	double s[2][2][2] = { { { 0 } } };
	const size_t sub = 4800;
	size_t nsub = n / sub;
	double *subE = calloc(nsub + 1, sizeof(double));
	for (size_t k = 0; k < nsub; k++)
	{
		double acc = 0.0;
		for (size_t i = k * sub; i < (k + 1) * sub; i++)
		{
			const float *ch[2] = { L, R };
			for (int c = 0; c < 2; c++)
			{
				double x = ch[c][i];
				double y = pubShelfB[0] * x + s[c][0][0];
				s[c][0][0] = pubShelfB[1] * x - pubShelfA[0] * y + s[c][0][1];
				s[c][0][1] = pubShelfB[2] * x - pubShelfA[1] * y;
				double z = pubHpB[0] * y + s[c][1][0];
				s[c][1][0] = pubHpB[1] * y - pubHpA[0] * z + s[c][1][1];
				s[c][1][1] = pubHpB[2] * y - pubHpA[1] * z;
				acc += z * z;
			}
		}
		subE[k] = acc / (double)sub;
	}
	double sum = 0.0, gsum = 0.0;
	int cnt = 0, gcnt = 0;
	const double absGate = pow(10.0, (-70.0 + 0.691) / 10.0);
	for (size_t k = 3; k < nsub; k++)
	{
		double b = 0.25 * (subE[k] + subE[k - 1] + subE[k - 2] + subE[k - 3]);
		if (b > absGate) { sum += b; cnt++; }
	}
	if (!cnt) { free(subE); return -200.0; }
	const double rel = sum / cnt * 0.1;
	for (size_t k = 3; k < nsub; k++)
	{
		double b = 0.25 * (subE[k] + subE[k - 1] + subE[k - 2] + subE[k - 3]);
		if (b > absGate && b > rel) { gsum += b; gcnt++; }
	}
	free(subE);
	return -0.691 + 10.0 * log10(gsum / gcnt);
}

static double biquadDb(const double *b, const double *a, double f, double fs)
{
	const double w = 2.0 * M_PI * f / fs;
	const double c1 = cos(w), s1 = sin(w), c2 = cos(2 * w), s2 = sin(2 * w);
	const double nr = b[0] + b[1] * c1 + b[2] * c2, ni = -(b[1] * s1 + b[2] * s2);
	const double dr = 1.0 + a[0] * c1 + a[1] * c2, di = -(a[0] * s1 + a[1] * s2);
	return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di));
}

static double pubDb(double f)
{
	return biquadDb(pubShelfB, pubShelfA, f, 48000.0) + biquadDb(pubHpB, pubHpA, f, 48000.0);
}

static double engineDb(const LufsNormalizer *l, double f, double fs)
{
	return biquadDb(l->kb[0], l->ka[0], f, fs) + biquadDb(l->kb[1], l->ka[1], f, fs);
}

// ---- signals ---------------------------------------------------------------

typedef struct
{
	int kind;          // 0 silence, 1 sine, 2 pink, 3 sine with clicks
	double amp, freq;
	int leftOnly;
	unsigned int rng;
	double p[7];
	long t;
} Gen;

static float white(Gen *g)
{
	g->rng ^= g->rng << 13; g->rng ^= g->rng >> 17; g->rng ^= g->rng << 5;
	return (float)((double)g->rng / 4294967296.0 * 2.0 - 1.0);
}

/* Paul Kellet's refined pink filter. */
static double pink(Gen *g)
{
	double w = white(g);
	g->p[0] = 0.99886 * g->p[0] + w * 0.0555179;
	g->p[1] = 0.99332 * g->p[1] + w * 0.0750759;
	g->p[2] = 0.96900 * g->p[2] + w * 0.1538520;
	g->p[3] = 0.86650 * g->p[3] + w * 0.3104856;
	g->p[4] = 0.55000 * g->p[4] + w * 0.5329522;
	g->p[5] = -0.7616 * g->p[5] - w * 0.0168980;
	double out = g->p[0] + g->p[1] + g->p[2] + g->p[3] + g->p[4] + g->p[5] + g->p[6] + w * 0.5362;
	g->p[6] = w * 0.115926;
	return out * 0.11;
}

static void gen(Gen *g, float *L, float *R, size_t n, double fs)
{
	for (size_t i = 0; i < n; i++, g->t++)
	{
		double l = 0.0, r = 0.0;
		switch (g->kind)
		{
		case 1:
			l = r = g->amp * sin(2.0 * M_PI * g->freq * (double)g->t / fs);
			break;
		case 2:
			l = g->amp * pink(g);
			r = g->amp * pink(g);
			break;
		case 3:
			l = r = g->amp * sin(2.0 * M_PI * g->freq * (double)g->t / fs);
			if (g->t % (long)(fs * 0.5) == 1000) l = r = 0.5;
			break;
		}
		L[i] = (float)l;
		R[i] = g->leftOnly ? 0.0f : (float)r;
	}
}

static Gen sine(double amp, double f) { Gen g; memset(&g, 0, sizeof(g)); g.kind = 1; g.amp = amp; g.freq = f; return g; }
static Gen pinkAt(double amp, unsigned int seed) { Gen g; memset(&g, 0, sizeof(g)); g.kind = 2; g.amp = amp; g.rng = seed; return g; }
static Gen silence(void) { Gen g; memset(&g, 0, sizeof(g)); return g; }

/* Amplitude for unit pink noise to measure at the given loudness. */
static double pinkAmpFor(double lufs)
{
	Gen g = pinkAt(1.0, 12345u);
	const size_t n = 48000 * 10;
	gen(&g, capL, capR, n, 48000.0);
	return pow(10.0, (lufs - refLufs(capL, capR, n)) / 20.0);
}

// ---- running the effect ------------------------------------------------------

static double fsNow = 48000.0;
static size_t capPos = 0;
static double gainMin, gainMax;

static void setup(double fs, float target, float boost, float cut, int speed, int guard)
{
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = (float)fs;
	fsNow = fs;
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
	g_lib.limiter.threshold = (float)pow(10.0, -0.1 / 20.0);
	g_lib.limiter.mode = 0;
	g_lib.postGain = 1.0f;
	LufsNormalizerSetParam(&g_lib, target, boost, cut, speed, guard);
	LufsNormalizerEnable(&g_lib);
	capPos = 0;
}

/* Runs seconds of g through the effect in blocks of blk, captures the output,
   and keeps the lowest and highest gain seen. */
static void run(Gen *g, double seconds, size_t blk)
{
	size_t total = (size_t)(seconds * fsNow + 0.5);
	gainMin = 1e9; gainMax = -1e9;
	while (total)
	{
		size_t n = total < blk ? total : blk;
		gen(g, bufL, bufR, n, fsNow);
		LufsNormalizerProcess(&g_lib, n);
		for (size_t i = 0; i < n && capPos < MAXN; i++, capPos++)
		{
			capL[capPos] = bufL[i];
			capR[capPos] = bufR[i];
		}
		const double gd = g_lib.lufsNorm.gainDb;
		if (gd < gainMin) gainMin = gd;
		if (gd > gainMax) gainMax = gd;
		total -= n;
	}
}

static double outLufs(double from, double to)
{
	size_t a = (size_t)(from * fsNow), b = (size_t)(to * fsNow);
	if (b > capPos) b = capPos;
	return refLufs(capL + a, capR + a, b - a);
}

int main(void)
{
	LufsNormalizer *l = &g_lib.lufsNorm;
	printf("== LUFS normalizer ==\n\n");

	printf("K-weighting\n");
	{
		setup(48000.0, -14, 6, 12, 1, 1);
		double worst = 0.0;
		for (int i = 0; i < 3; i++) worst = fmax(worst, fabs(l->kb[0][i] - pubShelfB[i]));
		for (int i = 0; i < 2; i++) worst = fmax(worst, fabs(l->ka[0][i] - pubShelfA[i]));
		for (int i = 0; i < 2; i++) worst = fmax(worst, fabs(l->ka[1][i] - pubHpA[i]));
		check("48 kHz: largest difference from the published coefficients", worst, 0.0, 1e-8);

		// The rate change, as JamesDSPSetSampleRate makes it.
		const double freqs[] = { 20, 40, 100, 300, 1000, 1500, 2000, 3000, 5000, 8000, 12000, 16000 };
		g_lib.fs = 44100.0f;
		LufsNormalizerRefresh(&g_lib);
		double dev = 0.0;
		printf("    Hz       published 48k   ours 44.1k\n");
		for (size_t i = 0; i < sizeof(freqs) / sizeof(freqs[0]); i++)
		{
			const double p = pubDb(freqs[i]), o = engineDb(l, freqs[i], 44100.0);
			printf("    %6.0f   %+10.4f dB   %+10.4f dB\n", freqs[i], p, o);
			if (freqs[i] <= 12000) dev = fmax(dev, fabs(p - o));
		}
		check("44.1 kHz after a rate change: worst dB off, 20 Hz to 12 kHz", dev, 0.0, 0.02);
		const double d16 = fabs(pubDb(16000) - engineDb(l, 16000, 44100.0));
		check("44.1 kHz: dB off at 16 kHz", d16, 0.0, 0.05);

		// The same 48 kHz coefficients left running at 44.1 kHz, which is what
		// a missing Refresh would do: the check above would catch it.
		LufsNormalizer stale = *l;
		memcpy(stale.kb[0], pubShelfB, sizeof(pubShelfB));
		memcpy(stale.ka[0], pubShelfA, sizeof(pubShelfA));
		memcpy(stale.ka[1], pubHpA, sizeof(pubHpA));
		double staleDev = 0.0;
		for (size_t i = 0; i < sizeof(freqs) / sizeof(freqs[0]); i++)
			if (freqs[i] <= 12000)
				staleDev = fmax(staleDev, fabs(pubDb(freqs[i]) - engineDb(&stale, freqs[i], 44100.0)));
		check("(48 kHz filters left at 44.1 kHz would be off by)", staleDev, 0.1, 100.0);
		g_lib.fs = 48000.0f;
		LufsNormalizerRefresh(&g_lib);
		worst = 0.0;
		for (int i = 0; i < 3; i++) worst = fmax(worst, fabs(l->kb[0][i] - pubShelfB[i]));
		for (int i = 0; i < 2; i++) worst = fmax(worst, fabs(l->ka[0][i] - pubShelfA[i]));
		check("and back to 48 kHz", worst, 0.0, 1e-8);
	}

	printf("\nmeasurement (EBU Tech 3341: 997 Hz at 0 dBFS in one channel is -3.01 LUFS)\n");
	{
		const double rates[2] = { 48000.0, 44100.0 };
		for (int r = 0; r < 2; r++)
		{
			setup(rates[r], -14, 0, 0, 1, 0);
			Gen g = sine(1.0, 997.0);
			g.leftOnly = 1;
			run(&g, 3.0, 512);
			char what[80];
			snprintf(what, sizeof(what), "%.1f kHz: measured LUFS", rates[r] / 1000.0);
			check(what, l->measured, -3.06, -2.96);
		}
		// Across a rate change mid-stream, with the history from 48 kHz kept.
		setup(48000.0, -14, 0, 0, 1, 0);
		Gen g = sine(0.1, 997.0);
		run(&g, 2.0, 512);
		g_lib.fs = 44100.0f; fsNow = 44100.0;
		LufsNormalizerRefresh(&g_lib);
		g.t = 0;
		run(&g, 3.0, 512);
		check("48 then 44.1 kHz, both -20 LUFS: measured", l->measured, -20.05, -19.95);
	}

	printf("\nunity until something is measured\n");
	{
		setup(48000.0, -14, 6, 12, 1, 1);
		Gen g = pinkAt(0.05, 99u), ref = pinkAt(0.05, 99u);
		run(&g, 0.3, 480);
		static float expL[48000], expR[48000];
		gen(&ref, expL, expR, 14400, 48000.0);
		int same = 1;
		for (int i = 0; i < 14400; i++) same &= capL[i] == expL[i] && capR[i] == expR[i];
		check("first 300 ms bit for bit the input", same, 1, 1);
	}

	printf("\nconvergence\n");
	{
		// A 997 Hz tone at -20 LUFS, target -14: 6 dB of boost at normal speed.
		setup(48000.0, -14, 6, 12, 1, 1);
		Gen g = sine(0.1, 997.0);
		run(&g, 12.0, 1024);
		check("tone -20 to -14: gain after 12 s (dB)", l->gainDb, 5.95, 6.0);
		check("tone: output loudness from 6 to 9 s (LUFS)", outLufs(6.0, 9.0), -14.5, -13.5);
		check("tone: output loudness from 9 to 12 s (LUFS)", outLufs(9.0, 12.0), -14.1, -13.9);

		// Pink noise at -6 LUFS, target -14: 8 dB of cut, which is fast.
		const double a6 = pinkAmpFor(-6.0);
		setup(48000.0, -14, 6, 12, 1, 1);
		g = pinkAt(a6, 777u);
		run(&g, 8.0, 1024);
		check("pink -6 to -14: output loudness from 3 to 6 s (LUFS)", outLufs(3.0, 6.0), -14.5, -13.5);
		check("pink -6 to -14: output loudness from 6 to 8 s (LUFS)", outLufs(6.0, 8.0), -14.25, -13.75);

		// Pink noise at -24 LUFS with 12 dB allowed and the guard off.
		const double a24 = pinkAmpFor(-24.0);
		setup(48000.0, -14, 12, 12, 1, 0);
		g = pinkAt(a24, 4242u);
		run(&g, 14.0, 1024);
		check("pink -24 to -14: output loudness from 8 to 11 s (LUFS)", outLufs(8.0, 11.0), -14.5, -13.5);
		check("pink -24 to -14: output loudness from 11 to 14 s (LUFS)", outLufs(11.0, 14.0), -14.25, -13.75);

		// The fast speed gets there sooner.
		setup(48000.0, -14, 6, 12, 2, 1);
		g = sine(0.1, 997.0);
		run(&g, 5.0, 1024);
		check("fast: tone output loudness from 3 to 5 s (LUFS)", outLufs(3.0, 5.0), -14.5, -13.5);
	}

	printf("\nlimits\n");
	{
		setup(48000.0, -14, 6, 12, 1, 0);
		Gen g = sine(0.01, 997.0); // -40 LUFS, wants 26 dB
		run(&g, 20.0, 1024);
		check("tone at -40 LUFS, 6 dB allowed: highest gain", gainMax, 5.9, 6.0);
		setup(48000.0, -14, 0, 12, 1, 0);
		g = sine(0.01, 997.0);
		run(&g, 10.0, 1024);
		check("no boost allowed: highest gain", gainMax, 0.0, 0.0);
		setup(48000.0, -14, 6, 3, 1, 0);
		g = sine(0.9, 997.0); // about -1 LUFS, wants -13 dB
		run(&g, 10.0, 1024);
		check("3 dB of cut allowed: lowest gain", gainMin, -3.0, -2.9);
	}

	printf("\npeak guard\n");
	{
		// A -26 LUFS tone with a -6 dBFS click every half second. 12 dB would
		// reach the target; the clicks leave room for 5 dB under -1 dBFS.
		Gen g;
		setup(48000.0, -14, 12, 12, 1, 1);
		memset(&g, 0, sizeof(g)); g.kind = 3; g.amp = 0.05; g.freq = 997.0;
		run(&g, 15.0, 1024);
		check("guard on: gain (dB)", l->gainDb, 4.9, 5.05);
		float pk = 0.0f;
		for (size_t i = (size_t)(10 * 48000); i < capPos; i++) pk = fmaxf(pk, fabsf(capL[i]));
		check("guard on: highest output sample after 10 s (dBFS)", 20.0 * log10(pk), -1.2, -1.0);
		setup(48000.0, -14, 12, 12, 1, 0);
		memset(&g, 0, sizeof(g)); g.kind = 3; g.amp = 0.05; g.freq = 997.0;
		run(&g, 15.0, 1024);
		check("guard off: gain (dB)", l->gainDb, 11.5, 12.0);
		// A limiter starting at -6 dB leaves no room at all.
		setup(48000.0, -14, 12, 12, 1, 1);
		g_lib.limiter.threshold = 0.5f;
		memset(&g, 0, sizeof(g)); g.kind = 3; g.amp = 0.05; g.freq = 997.0;
		run(&g, 15.0, 1024);
		check("limiter at -6 dBFS: highest gain", gainMax, 0.0, 0.05);
		// Six dB of post gain after it does the same.
		setup(48000.0, -14, 12, 12, 1, 1);
		g_lib.postGain = 2.0f;
		memset(&g, 0, sizeof(g)); g.kind = 3; g.amp = 0.05; g.freq = 997.0;
		run(&g, 15.0, 1024);
		check("6 dB post gain: highest gain", gainMax, 0.0, 0.0);
		// The guard never cuts: loud peaks with a quiet average just get no boost.
		setup(48000.0, -14, 12, 12, 1, 1);
		g_lib.postGain = 4.0f;
		memset(&g, 0, sizeof(g)); g.kind = 3; g.amp = 0.05; g.freq = 997.0;
		run(&g, 10.0, 1024);
		check("guard with no room: lowest gain", gainMin, 0.0, 0.0);
	}

	printf("\nsilence and near silence\n");
	{
		// Boosted music, then ten seconds of digital silence. "Never rises"
		// allows 0.002 dB: it takes the 100 ms after the music stops to know
		// it has, and a gain still on its way up moves that little in them.
		setup(48000.0, -14, 6, 12, 1, 1);
		Gen g = sine(0.1, 997.0);
		run(&g, 16.0, 1024);
		const double before = l->gainDb;
		Gen s = silence();
		run(&s, 10.0, 256);
		check("boosted, then silence: highest gain minus gain before", gainMax - before, -1.0, 0.002);
		check("boosted, then silence: gain after 10 s (dB)", l->gainDb, 0.0, 0.1);

		// Cut music, then silence: the cut is held, never raised.
		const double a6 = pinkAmpFor(-6.0);
		setup(48000.0, -14, 6, 12, 1, 1);
		g = pinkAt(a6, 31u);
		run(&g, 8.0, 1024);
		const double cut = l->gainDb;
		run(&s, 10.0, 256);
		check("cut, then silence: highest gain minus gain before", gainMax - cut, -1.0, 0.002);
		check("cut, then silence: gain after 10 s minus before", l->gainDb - cut, -0.1, 0.002);

		// Hiss at -60 LUFS from a cold start, and after cut music.
		const double a60 = pinkAmpFor(-60.0);
		setup(48000.0, -14, 6, 12, 1, 1);
		g = pinkAt(a60, 5u);
		run(&g, 20.0, 1024);
		check("hiss at -60 LUFS from the start: highest gain", gainMax, 0.0, 0.0);
		setup(48000.0, -14, 6, 12, 1, 1);
		g = pinkAt(a6, 6u);
		run(&g, 8.0, 1024);
		const double cut2 = l->gainDb;
		g = pinkAt(a60, 7u);
		run(&g, 10.0, 1024);
		check("cut, then hiss: highest gain minus gain before", gainMax - cut2, -1.0, 0.002);
		check("cut, then hiss: history dropped", l->histCount, 0, 0);

		// A one second pause inside a song changes nothing.
		setup(48000.0, -14, 6, 12, 1, 1);
		g = sine(0.1, 997.0);
		run(&g, 12.0, 1024);
		const double mid = l->gainDb;
		run(&s, 1.0, 1024);
		const double paused = gainMax;
		run(&g, 2.0, 1024);
		check("one second pause: highest gain during it minus before", paused - mid, -0.01, 0.002);
		check("one second pause: gain after the song resumes minus before", l->gainDb - mid, -0.05, 0.05);
	}

	printf("\na song starting after silence\n");
	{
		// A song already at the target, starting after silence, on a cold start
		// and after another song. The blocks that straddle the start are part
		// silence and must not be measured, or they read low and push the gain
		// up. The onset is put partway into a sub-block on purpose.
		const char *names[3] = { "slow", "normal", "fast" };
		for (int sp = 0; sp < 3; sp++)
		{
			Gen s = silence(), g = sine(0.1995, 997.0); // -14 LUFS
			setup(48000.0, -14, 6, 12, sp, 1);
			run(&s, 3.05, 1024);
			run(&g, 22.0, 1024);
			char what[80];
			snprintf(what, sizeof(what), "%s: song at target after silence, highest gain", names[sp]);
			check(what, gainMax, -0.1, 0.1);
			snprintf(what, sizeof(what), "%s: and lowest gain", names[sp]);
			check(what, gainMin, -0.1, 0.1);
			run(&s, 3.03, 1024);
			g.t = 0;
			run(&g, 22.0, 1024);
			snprintf(what, sizeof(what), "%s: the next song after a gap, highest gain", names[sp]);
			check(what, gainMax, -0.1, 0.1);
		}
	}

	printf("\nNaN and nonsense\n");
	{
		setup(48000.0, -14, 6, 12, 1, 1);
		LufsNormalizerSetParam(&g_lib, NAN, NAN, NAN, 7, 5);
		check("NaN target lands on the quietest (LUFS)", l->target, -30, -30);
		check("NaN boost lands on none", l->maxBoost, 0, 0);
		check("NaN cut lands on none", l->maxCut, 0, 0);
		check("speed 7 lands on normal", l->speed, 1, 1);
		check("guard 5 is on", l->peakGuard, 1, 1);
		LufsNormalizerSetParam(&g_lib, INFINITY, INFINITY, -INFINITY, -3, 0);
		check("+inf target is held at -5", l->target, -5, -5);
		check("+inf boost is held at 12", l->maxBoost, 12, 12);
		check("-inf cut is none", l->maxCut, 0, 0);
		LufsNormalizerSetParam(&g_lib, -1e30f, 1e30f, 1e30f, 2, 0);
		check("-1e30 target is held at -30", l->target, -30, -30);
		check("1e30 cut is held at 24", l->maxCut, 24, 24);
		check("the history fits the longest setting", l->histLen, 1, LUFS_HISTORY_MAX);

		// Garbage samples in the input: NaN, infinities, 1e30, denormals.
		setup(48000.0, -14, 6, 12, 1, 1);
		Gen g = sine(0.1, 997.0);
		run(&g, 3.0, 1024);
		const double clean = l->measured;
		for (int i = 0; i < 1024; i++)
		{
			const float v[5] = { NAN, INFINITY, -INFINITY, 1e30f, 1e-40f };
			bufL[i] = v[i % 5];
			bufR[i] = v[(i + 2) % 5];
		}
		LufsNormalizerProcess(&g_lib, 1024);
		int finite = isfinite(l->gainDb) && isfinite(l->measured) && isfinite(l->gainLin);
		for (int c = 0; c < 2; c++)
			for (int s2 = 0; s2 < 2; s2++)
				for (int k = 0; k < 2; k++)
					finite &= isfinite(l->kz[c][s2][k]);
		check("after a burst of garbage: state finite", finite, 1, 1);
		run(&g, 5.0, 1024);
		check("five seconds later: measured minus clean (LU)", l->measured - clean, -0.1, 0.1);
	}

	printf("\nblock size\n");
	{
		const size_t sizes[4] = { 1, 37, 1024, 4096 };
		static float firstL[48000 * 5];
		int same = 1;
		for (int k = 0; k < 4; k++)
		{
			setup(48000.0, -14, 6, 12, 1, 1);
			Gen g = pinkAt(0.02, 2024u);
			run(&g, 5.0, sizes[k]);
			if (k == 0) memcpy(firstL, capL, sizeof(firstL));
			else same &= memcmp(firstL, capL, sizeof(firstL)) == 0;
		}
		check("blocks of 1, 37, 1024 and 4096 give the same output", same, 1, 1);
	}

	printf("\nswitching\n");
	{
		setup(48000.0, -14, 6, 12, 1, 1);
		check("on: history allocated", l->histPow && l->histPeak, 1, 1);
		LufsNormalizerDisable(&g_lib);
		check("off: history let go", !l->histPow && !l->histPeak && !g_lib.lufsNormEnabled, 1, 1);
		LufsNormalizerProcess(&g_lib, 64);
		check("Process with nothing allocated does nothing", 1, 1, 1);
	}

	printf("\n%s (%d failed)\n", failures ? "FAILED" : "all passed", failures);
	return failures ? 1 : 0;
}
