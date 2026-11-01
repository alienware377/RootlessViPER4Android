// Is the Signalsmith method of the pitch shifter in tune, steady, in time with
// its own dry signal, free of dropouts when anything is switched, and cheap
// enough to run?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   CC=$NDK/x86_64-linux-android29-clang      (or aarch64-linux-android29-clang)
//   $CC -O2 -w -ffp-contract=off -I $J -c tools/signalsmith_harness.c \
//       $J/Effects/pitchshift.c $J/Effects/rubberband.c
//   ${CC}++ -O2 -std=c++17 -w -I third_party -I $J -c $J/Effects/signalsmith.cpp
//   ${CC}++ -static-libstdc++ -Wl,--wrap=calloc -Wl,--wrap=free \
//       -Wl,--wrap=SignalsmithCreate *.o -lm -ldl -o signalsmith
//   adb -s emulator-5554 push signalsmith /data/local/tmp/ && \
//       adb -s emulator-5554 shell /data/local/tmp/signalsmith
//
// The engine's own lock is replaced here by one that times how long it is
// held, since every block of this card waits on it on the audio thread; and
// that lock is also where the test slips audio through while a new instance
// is being primed, which is what the audio thread would be doing then.
//
// CPU figures are for whatever this runs on. The emulator is x86_64 and runs
// arm64 binaries only through translation, so absolute numbers say nothing
// about a phone; compare the methods against each other within one run.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "jdsp_header.h"

#define MAXN (1 << 20)

static JamesDSPLib g_lib;
static float inL[MAXN], inR[MAXN], bufL[MAXN], bufR[MAXN];
static int failures = 0;

static double now(void)
{
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return (double)t.tv_sec + (double)t.tv_nsec * 1e-9;
}

/* ---------------- the lock: timed, and a place to let audio through ------ */
static double g_lockT0, g_lockMax;
static int g_lockCount, g_slipAt, g_slipSamples;
static void slipAudio(int n);
void jdsp_lock(JamesDSPLib *j)
{
	(void)j;
	g_lockCount++;
	if (g_slipAt && g_lockCount == g_slipAt)
		slipAudio(g_slipSamples);
	g_lockT0 = now();
}
void jdsp_unlock(JamesDSPLib *j)
{
	(void)j;
	const double d = now() - g_lockT0;
	if (d > g_lockMax) g_lockMax = d;
}

/* ---------------- guard bytes around every calloc ------------------------ */
void *__real_calloc(size_t a, size_t b);
void __real_free(void *p);
#define GUARD 4096
#define MAXBLK 512
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
	unsigned char *p = (unsigned char *)__real_calloc(1, n + GUARD);
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
static int liveBlocks(void)
{
	int n = 0;
	for (int i = 0; i < MAXBLK; i++)
		if (g_blk[i].p) n++;
	return n;
}

/* ---------------- a way to make Signalsmith refuse to start -------------- */
void *__real_SignalsmithCreate(float fs, int formant);
static int g_failCreate = 0;
void *__wrap_SignalsmithCreate(float fs, int formant)
{
	return g_failCreate ? 0 : __real_SignalsmithCreate(fs, formant);
}

/* ---------------- helpers ------------------------------------------------ */
static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-58s %10.3f  [%8.3f..%8.3f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static double g_fs = 48000.0;
static int g_block = 480;
static int64_t g_pos;     // stream position: next input sample to process

static void prepare(double fs)
{
	if (g_lib.pitchShift.hist[0] || g_lib.pitchShiftEnabled)
		PitchShiftDisable(&g_lib);
	SignalsmithDestroy(g_lib.pitchShift.ss);
	memset(&g_lib, 0, sizeof(g_lib));
	g_fs = fs;
	g_lib.fs = (float)fs;
	g_pos = 0;
}

// Processes the next n samples of the input stream in callback-sized blocks.
static double g_blockWorst;
// Every block time, for a percentile: the emulator is shared with the rest of
// the machine, and its single worst block is as often the host scheduler as
// anything this code did.
#define MAXT 400000
static double g_times[MAXT];
static int g_ntimes;
static int cmpd(const void *a, const void *b)
{
	const double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}
static double pct(double q)
{
	if (g_ntimes == 0) return 0.0;
	qsort(g_times, (size_t)g_ntimes, sizeof(double), cmpd);
	int i = (int)(q * (g_ntimes - 1) + 0.5);
	return g_times[i];
}
static void stream(int n)
{
	int64_t end = g_pos + n;
	if (end > MAXN) end = MAXN;
	while (g_pos < end)
	{
		const int k = (end - g_pos) > g_block ? g_block : (int)(end - g_pos);
		memcpy(bufL + g_pos, inL + g_pos, (size_t)k * sizeof(float));
		memcpy(bufR + g_pos, inR + g_pos, (size_t)k * sizeof(float));
		g_lib.tmpBuffer[0] = bufL + g_pos;
		g_lib.tmpBuffer[1] = bufR + g_pos;
		const double t = now();
		PitchShiftProcess(&g_lib, (size_t)k);
		const double d = now() - t;
		if (d > g_blockWorst) g_blockWorst = d;
		if (g_ntimes < MAXT) g_times[g_ntimes++] = d;
		g_pos += k;
	}
}
static void slipAudio(int n) { stream(n); }

static int secs(double s) { return (int)(s * g_fs); }

static void genTone(double f, double amp, int n)
{
	for (int i = 0; i < n; i++)
		inL[i] = inR[i] = (float)(amp * sin(2.0 * M_PI * f * (double)i / g_fs));
}
static void genChord(const double *f, int nf, double amp, int n)
{
	for (int i = 0; i < n; i++)
	{
		double s = 0.0;
		for (int k = 0; k < nf; k++) s += sin(2.0 * M_PI * f[k] * (double)i / g_fs + 0.7 * k);
		inL[i] = inR[i] = (float)(amp * s);
	}
}
static uint32_t g_rng = 12345;
static float rnd(void)
{
	g_rng = g_rng * 1664525u + 1013904223u;
	return (float)((double)(g_rng >> 8) / 16777216.0 * 2.0 - 1.0);
}
static void genNoise(double amp, int n)
{
	for (int i = 0; i < n; i++) { inL[i] = (float)(amp * rnd()); inR[i] = (float)(amp * rnd()); }
}

static double rmsDb(const float *x, int from, int len)
{
	double a = 0.0;
	for (int i = from; i < from + len; i++) a += (double)x[i] * (double)x[i];
	a = sqrt(a / len);
	return 20.0 * log10(a > 1e-9 ? a : 1e-9);
}

// Magnitude of the Hann-windowed spectrum at one exact frequency.
static double g_win[MAXN];
static double dtftMag(const float *x, int from, int len, double f)
{
	const double w = 2.0 * M_PI * f / g_fs;
	const double cr = cos(w), ci = -sin(w);
	double pr = 1.0, pi = 0.0, re = 0.0, im = 0.0;
	for (int i = 0; i < len; i++)
	{
		const double v = (double)x[from + i] * g_win[i];
		re += v * pr; im += v * pi;
		const double t = pr * cr - pi * ci;
		pi = pr * ci + pi * cr; pr = t;
	}
	return sqrt(re * re + im * im);
}

// The frequency of the strongest component within 100 cents of `hint`: the
// peak of the windowed spectrum, found on a grid of quarter bins and then
// narrowed down by golden section. Over two seconds that resolves to well
// under a hundredth of a cent on a clean tone. Counting zero crossings was
// tried first and is not good enough: any wiggle near zero adds a crossing.
static double measureFreq(const float *x, int from, int len, double hint)
{
	for (int i = 0; i < len; i++) g_win[i] = 0.5 - 0.5 * cos(2.0 * M_PI * (i + 0.5) / len);
	const double lo = hint * 0.944, hi = hint * 1.06, step = 0.25 * g_fs / len;
	double best = lo, bestM = -1.0;
	for (double f = lo; f <= hi; f += step)
	{
		const double m = dtftMag(x, from, len, f);
		if (m > bestM) { bestM = m; best = f; }
	}
	double a = best - step, b = best + step;
	const double gr = 0.6180339887;
	double c = b - gr * (b - a), d = a + gr * (b - a);
	double mc = dtftMag(x, from, len, c), md = dtftMag(x, from, len, d);
	for (int it = 0; it < 40; it++)
	{
		if (mc > md) { b = d; d = c; md = mc; c = b - gr * (b - a); mc = dtftMag(x, from, len, c); }
		else { a = c; c = d; mc = md; d = a + gr * (b - a); md = dtftMag(x, from, len, d); }
	}
	return 0.5 * (a + b);
}

// Least squares fit of sinusoids at the given frequencies; returns the fitted
// power against what is left over, in dB. Everything not at those frequencies
// (other partials, smearing, noise, wobble) counts against it.
static double purityDb(const float *x, int from, int len, const double *f, int nf)
{
	double total = 0.0, fit = 0.0;
	for (int i = from; i < from + len; i++) total += (double)x[i] * (double)x[i];
	for (int k = 0; k < nf; k++)
	{
		double c = 0.0, s = 0.0;
		const double w = 2.0 * M_PI * f[k] / g_fs;
		for (int i = from; i < from + len; i++)
		{
			c += (double)x[i] * cos(w * i);
			s += (double)x[i] * sin(w * i);
		}
		c *= 2.0 / len; s *= 2.0 / len;
		fit += (c * c + s * s) * 0.5 * len;
	}
	double rest = total - fit;
	if (rest < total * 1e-12) rest = total * 1e-12;
	return 10.0 * log10(fit / rest);
}

// How much the level wanders, in dB, over a stretch: the spread of 1024
// sample windows.
static double wobble(const float *x, int from, int len)
{
	double mn = 1e30, mx = -1e30;
	for (int p = from; p + 1024 <= from + len; p += 1024)
	{
		const double db = rmsDb(x, p, 1024);
		if (db < mn) mn = db;
		if (db > mx) mx = db;
	}
	return mx - mn;
}

static double minWinDb(const float *x, int from, int len, int win)
{
	double mn = 1e30;
	for (int p = from; p + win <= from + len; p += win / 2)
	{
		const double db = rmsDb(x, p, win);
		if (db < mn) mn = db;
	}
	return mn;
}

static double maxStep(const float *x, int from, int len)
{
	double mx = 0.0;
	for (int i = from + 1; i < from + len; i++)
	{
		const double d = fabs((double)x[i] - (double)x[i - 1]);
		if (d > mx) mx = d;
	}
	return mx;
}

static int nonFinite(int n)
{
	int bad = 0;
	for (int i = 0; i < n; i++)
		if (!isfinite(bufL[i]) || !isfinite(bufR[i])) bad++;
	return bad;
}

static void startCard(float semis, float mix, int mode)
{
	PitchShiftSetParam(&g_lib, semis, mix, mode);
	PitchShiftEnable(&g_lib);
}

static const char *modeName(int m)
{
	static const char *n[] = { "granular", "smooth", "rubber band", "rubber band formant",
	                           "signalsmith", "signalsmith formant" };
	return (m >= 0 && m < 6) ? n[m] : "?";
}

/* ======================================================================== */

// The library run on its own, the way its README says to use it, on the same
// input: what the engine should reproduce exactly at a full wet mix. The
// engine seeks the new instance over its (silent) history before the first
// block, so this does the same.
static float refL[MAXN], refR[MAXN], zeros[1 << 16];
static void libraryReference(int n, float semis, int formant)
{
	void *s = SignalsmithCreateCustom((float)g_fs, formant, SIGNALSMITH_BLOCK_SEC,
	                                  SIGNALSMITH_INTERVAL_SEC, SIGNALSMITH_SPLIT);
	SignalsmithSetRatio(s, powf(2.0f, semis / 12.0f));
	const float *z[2] = { zeros, zeros };
	SignalsmithSeekRing(s, z, (1 << 16) - 1, 0, SignalsmithSeekLength(s));
	const float *ring[2] = { inL, inR };
	for (int i = 0; i < n; i += 480)
	{
		float *out[2] = { refL + i, refR + i };
		SignalsmithProcessRing(s, ring, MAXN - 1, i, out, n - i < 480 ? n - i : 480);
	}
	SignalsmithDestroy(s);
}

static void testPitch(void)
{
	printf("\n1. pitch accuracy, cents off the target (and purity in dB)\n");
	const double rates[2] = { 48000.0, 44100.0 };
	const double tones[4] = { 80.0, 220.0, 1000.0, 5000.0 };
	// Signalsmith Stretch is not exact on a lone low sine: the library on its
	// own, run the way its README says, lands up to about 50 cents off at
	// 80 Hz and 16 at 220 Hz with its default set-up, and a cent or two from
	// 1 kHz up (section 6 compares set-ups on this). Smooth, a plain phase
	// vocoder, is exact. So these limits only pin down what the library does;
	// the check after them is the one that says the engine adds nothing to it.
	const double limit[4] = { 55.0, 20.0, 5.0, 5.0 };
	const float shifts[6] = { -12.0f, -7.0f, -1.0f, 0.5f, 3.0f, 12.0f };
	double worst[4] = { 0, 0, 0, 0 }, worstSmooth[4] = { 0, 0, 0, 0 };
	double worstPurity = 1e9, worstDiff = 0.0;
	for (int r = 0; r < 2; r++)
		for (int t = 0; t < 4; t++)
		{
			printf("    %5.1fk %5.0f Hz:", rates[r] / 1000.0, tones[t]);
			for (int s = 0; s < 6; s++)
			{
				prepare(rates[r]);
				const int n = secs(3.0);
				genTone(tones[t], 0.5, n);
				startCard(shifts[s], 100.0f, PITCH_MODE_SIGNALSMITH);
				stream(n);
				const double want = tones[t] * pow(2.0, shifts[s] / 12.0);
				const double got = measureFreq(bufL, secs(1.0), secs(2.0), want);
				const double cents = 1200.0 * log2(got / want);
				const double pur = purityDb(bufL, secs(1.0), secs(2.0), &got, 1);
				printf(" %+6.2f(%3.0f)", cents, pur);
				if (fabs(cents) > worst[t]) worst[t] = fabs(cents);
				if (pur < worstPurity) worstPurity = pur;
				libraryReference(n, shifts[s], 0);
				for (int i = secs(0.5); i < n; i++)
				{
					const double d = fabs((double)bufL[i] - (double)refL[i]) + fabs((double)bufR[i] - (double)refR[i]);
					if (d > worstDiff) worstDiff = d;
				}
				// Smooth on the same tone, for comparison.
				prepare(rates[r]);
				genTone(tones[t], 0.5, n);
				startCard(shifts[s], 100.0f, PITCH_MODE_SMOOTH);
				stream(n);
				const double sc = fabs(1200.0 * log2(measureFreq(bufL, secs(1.0), secs(2.0), want) / want));
				if (sc > worstSmooth[t]) worstSmooth[t] = sc;
			}
			printf("\n");
		}
	printf("    shifts across: -12 -7 -1 +0.5 +3 +12 semitones\n");
	for (int t = 0; t < 4; t++)
	{
		char what[96];
		snprintf(what, sizeof(what), "%4.0f Hz worst error (cents; smooth's is %.1f)", tones[t], worstSmooth[t]);
		check(what, worst[t], 0.0, limit[t]);
	}
	check("worst purity: shifted tone over everything else (dB)", worstPurity, 30.0, 400.0);
	// From half a second in: before that the engine is still fading over
	// from the dry signal it plays while the delayed one fills.
	check("engine output against the library's own, worst difference", worstDiff, 0.0, 1e-5);

	{
		double worstF = 0.0, diffF = 0.0;
		const double ft[2] = { 220.0, 1000.0 };
		const float fsh[3] = { -7.0f, 5.0f, 12.0f };
		for (int t = 0; t < 2; t++)
			for (int s = 0; s < 3; s++)
			{
				prepare(48000.0);
				genTone(ft[t], 0.5, secs(3.0));
				startCard(fsh[s], 100.0f, PITCH_MODE_SIGNALSMITH_FORMANT);
				stream(secs(3.0));
				const double want = ft[t] * pow(2.0, fsh[s] / 12.0);
				const double got = measureFreq(bufL, secs(1.0), secs(2.0), want);
				const double c = fabs(1200.0 * log2(got / want));
				if (c > worstF) worstF = c;
				libraryReference(secs(3.0), fsh[s], 1);
				for (int i = secs(0.5); i < secs(3.0); i++)
				{
					const double d = fabs((double)bufL[i] - (double)refL[i]);
					if (d > diffF) diffF = d;
				}
			}
		check("keep-voice-character variant, worst error (cents)", worstF, 0.0, 20.0);
		check("and it too matches the library's own output", diffF, 0.0, 1e-5);
	}

	{
		// Three notes at once. A shifter that smears neighbouring partials into
		// each other shows it here and not on a lone sine.
		const double chords[3][3] = { { 220.0, 277.18, 329.63 }, { 440.0, 554.37, 659.26 },
		                             { 110.0, 138.59, 164.81 } };
		const float csh[3] = { -5.0f, 3.0f, 7.0f };
		double worstC = 0.0, worstP = 1e9, worstLeft = -1e9, bassP = 1e9;
		for (int c = 0; c < 3; c++)
			for (int q = 0; q < 3; q++)
			{
				double up[3];
				prepare(48000.0);
				genChord(chords[c], 3, 0.2, secs(3.0));
				startCard(csh[q], 100.0f, PITCH_MODE_SIGNALSMITH);
				stream(secs(3.0));
				for (int k = 0; k < 3; k++)
				{
					const double want = chords[c][k] * pow(2.0, csh[q] / 12.0);
					up[k] = measureFreq(bufL, secs(1.0), secs(2.0), want);
					const double e = fabs(1200.0 * log2(up[k] / want));
					if (c < 2 && e > worstC) worstC = e;
				}
				const double pur = purityDb(bufL, secs(1.0), secs(2.0), up, 3);
				// Only the original notes the shifted chord does not also play: up
				// three semitones, the old E is the new C sharp, and so on.
				double orig[3];
				int no = 0;
				for (int k = 0; k < 3; k++)
				{
					int shared = 0;
					for (int j = 0; j < 3; j++)
						if (fabs(chords[c][k] - up[j]) < 3.0) shared = 1;
					if (!shared) orig[no++] = chords[c][k];
				}
				const double left = no ? purityDb(bufL, secs(1.0), secs(2.0), orig, no) : -400.0;
				printf("      chord on %3.0f Hz, %+2.0f semitones: notes %5.1f dB over the rest, originals %6.1f dB\n",
				       chords[c][0], csh[q], pur, left);
				if (c < 2) { if (pur < worstP) worstP = pur; if (left > worstLeft) worstLeft = left; }
				else if (pur < bassP) bassP = pur;
			}
		check("chords on 220 and 440 Hz: worst note (cents)", worstC, 0.0, 20.0);
		check("and the shifted notes over everything else (dB)", worstP, 20.0, 400.0);
		check("and little left at the original notes (dB)", worstLeft, -400.0, -25.0);
		printf("      a chord down on 110 Hz, notes under 30 Hz apart, is beyond the library: as\n"
		       "      little as %.1f dB of it is the shifted notes. Reported, not asserted.\n", bassP);
	}
}

static void testLevel(void)
{
	printf("\n2. level: steady on a held note and a glide, and the right gain\n");
	double w[3];
	const int modes[3] = { PITCH_MODE_GRANULAR, PITCH_MODE_SMOOTH, PITCH_MODE_SIGNALSMITH };
	for (int m = 0; m < 3; m++)
	{
		prepare(48000.0);
		genTone(440.0, 0.5, secs(3.0));
		startCard(7.0f, 100.0f, modes[m]);
		stream(secs(3.0));
		w[m] = wobble(bufL, secs(1.0), secs(2.0));
	}
	printf("      held 440 Hz up a fifth wanders: granular %.2f dB, smooth %.2f dB, signalsmith %.2f dB\n",
	       w[0], w[1], w[2]);
	check("signalsmith holds a held note steady (dB)", w[2], 0.0, 1.0);
	check("no worse than smooth (dB more wobble)", w[2] - w[1], -100.0, 0.5);
	check("and clearly steadier than granular (dB less wobble)", w[0] - w[2], 1.0, 100.0);

	double gw[3];
	for (int m = 0; m < 3; m++)
	{
		prepare(48000.0);
		const int n = secs(4.0);
		double ph = 0.0;
		for (int i = 0; i < n; i++)
		{
			// 220 Hz to 440 Hz over four seconds, exponentially.
			const double f = 220.0 * pow(2.0, (double)i / n);
			ph += 2.0 * M_PI * f / g_fs;
			inL[i] = inR[i] = (float)(0.5 * sin(ph));
		}
		startCard(5.0f, 100.0f, modes[m]);
		stream(n);
		gw[m] = wobble(bufL, secs(1.0), secs(2.5));
	}
	printf("      gliding tone up a fourth wanders: granular %.2f dB, smooth %.2f dB, signalsmith %.2f dB\n",
	       gw[0], gw[1], gw[2]);
	check("signalsmith keeps a glide steady (dB)", gw[2], 0.0, 1.5);
	check("glide: no worse than smooth (dB more wobble)", gw[2] - gw[1], -100.0, 0.5);

	const float sh[3] = { 0.0f, -12.0f, 12.0f };
	double worst = 0.0;
	for (int s = 0; s < 3; s++)
	{
		prepare(48000.0);
		genTone(1000.0, 0.5, secs(3.0));
		startCard(sh[s], 100.0f, PITCH_MODE_SIGNALSMITH);
		stream(secs(3.0));
		const double g = rmsDb(bufL, secs(1.0), secs(2.0)) - rmsDb(inL, secs(1.0), secs(2.0));
		printf("      gain at %+3.0f semitones: %+.2f dB\n", sh[s], g);
		if (fabs(g) > worst) worst = fabs(g);
	}
	check("gain at 0 and +/-12 semitones within 1 dB", worst, 0.0, 1.0);
}

static void testLatency(void)
{
	printf("\n3. the reported delay is the real one, and the dry signal is delayed to match\n");
	const double rates[2] = { 48000.0, 44100.0 };
	for (int r = 0; r < 2; r++)
	{
		prepare(rates[r]);
		const int n = secs(3.0);
		memset(inL, 0, sizeof(float) * n);
		memset(inR, 0, sizeof(float) * n);
		// A click every quarter second: broadband, so its delay is the same
		// at every frequency, and unmistakable to cross-correlate.
		for (int i = secs(0.5); i < n; i += secs(0.25))
			for (int j = 0; j < 48 && i + j < n; j++)
				inL[i + j] = inR[i + j] = (float)(0.8 * exp(-j / 6.0) * cos(j * 1.3));
		startCard(0.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
		stream(n);
		const int L = PitchShiftLatency(&g_lib);
		int best = 0;
		double bestC = -1e30;
		for (int lag = 0; lag < 2 * L; lag++)
		{
			double c = 0.0;
			for (int i = secs(1.0); i < secs(1.5); i++) c += (double)inL[i - lag] * (double)bufL[i];
			if (c > bestC) { bestC = c; best = lag; }
		}
		printf("      %.1fk: reports %d samples (%.1f ms), measured %d\n", rates[r] / 1000.0, L,
		       1000.0 * L / rates[r], best);
		check(rates[r] == 48000.0 ? "48k: measured minus reported delay (samples)"
		                          : "44.1k: measured minus reported delay (samples)",
		      (double)(best - L), -4.0, 4.0);
	}

	// At half mix and no shift, the wet output is the input delayed by L, so
	// blending it with a dry copy delayed by the same L is the input at full
	// level. Blended with the undelayed input instead, a tone at an odd
	// multiple of fs/2L lands in a comb notch: that is what the dry delay
	// line is for.
	prepare(48000.0);
	startCard(0.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
	const int L = PitchShiftLatency(&g_lib);
	PitchShiftDisable(&g_lib);
	const double notch = (2.0 * floor(300.0 * 2.0 * L / 48000.0 / 2.0) + 1.0) * 48000.0 / (2.0 * L);
	const double freqs[2] = { notch, 1234.5 };
	for (int k = 0; k < 2; k++)
	{
		prepare(48000.0);
		genTone(freqs[k], 0.5, secs(3.0));
		startCard(0.0f, 50.0f, PITCH_MODE_SIGNALSMITH);
		stream(secs(3.0));
		const double half = rmsDb(bufL, secs(1.0), secs(2.0)) - rmsDb(inL, secs(1.0), secs(2.0));
		// The same blend done against the undelayed input, from a full-wet run.
		prepare(48000.0);
		genTone(freqs[k], 0.5, secs(3.0));
		startCard(0.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
		stream(secs(3.0));
		for (int i = 0; i < secs(3.0); i++) bufL[i] = 0.5f * (bufL[i] + inL[i]);
		const double naive = rmsDb(bufL, secs(1.0), secs(2.0)) - rmsDb(inL, secs(1.0), secs(2.0));
		printf("      %.1f Hz at 50%% mix: %+.2f dB (against undelayed dry it would be %+.1f dB)\n",
		       freqs[k], half, naive);
		check(k == 0 ? "50% mix at the would-be notch keeps full level (dB)"
		             : "50% mix away from it keeps full level too (dB)", half, -1.0, 1.0);
		if (k == 0)
			check("and undelayed dry really would have notched it (dB)", naive, -200.0, -20.0);
	}
}

typedef struct { int from, to; const char *name; } Switch;

// Plays mode `from` for a second, switches to `to`, and plays on. Returns the
// worst dip below the steady level and the worst step against the steady
// signal's own largest step.
static void runSwitch(int from, int to, int sig, int slip, double *dip, double *click,
                      double *setMs, double *lockMs, double *blockMs, int *catchUp)
{
	prepare(48000.0);
	const int n = secs(2.5), sw = secs(1.0);
	if (sig == 0) genNoise(0.3, n);
	else if (sig == 1) { const double c[3] = { 220.0, 277.18, 329.63 }; genChord(c, 3, 0.2, n); }
	else genTone(440.0, 0.5, n);
	startCard(5.0f, 100.0f, from);
	stream(sw);
	g_lockMax = 0.0;
	g_lockCount = 0;
	g_slipAt = slip ? 3 : 0;   // lock 1 reads the state, 2 copies the history, 3 installs
	g_slipSamples = slip;
	const double t0 = now();
	PitchShiftSetParam(&g_lib, 5.0f, 100.0f, to);
	*setMs = (now() - t0) * 1000.0;
	*lockMs = g_lockMax * 1000.0;
	g_slipAt = 0;
	g_blockWorst = 0.0;
	stream(secs(0.1));
	*blockMs = g_blockWorst * 1000.0;
	stream(n - (int)g_pos);
	*catchUp = g_lib.pitchShift.ssCatchUpMax;

	const int win = secs(0.005);
	const int from0 = sw - secs(0.2), len0 = secs(0.85);
	const double steadyA = minWinDb(bufL, secs(0.5), secs(0.45), win);
	const double steadyB = minWinDb(bufL, secs(1.9), secs(0.55), win);
	const double ref = steadyA < steadyB ? steadyA : steadyB;
	*dip = ref - minWinDb(bufL, from0, len0, win);
	const double sA = maxStep(bufL, secs(0.5), secs(0.45));
	const double sB = maxStep(bufL, secs(1.9), secs(0.55));
	*click = maxStep(bufL, from0, len0) / (sA > sB ? sA : sB);
	if (nonFinite(n)) *dip = 999.0;
}

static void testSwitching(void)
{
	printf("\n4. no dropout or click when switching (full wet, up 5 semitones)\n");
	const Switch sw[] = {
		{ PITCH_MODE_GRANULAR, PITCH_MODE_SIGNALSMITH, 0 },
		{ PITCH_MODE_SMOOTH, PITCH_MODE_SIGNALSMITH, 0 },
		{ PITCH_MODE_SIGNALSMITH, PITCH_MODE_SIGNALSMITH_FORMANT, 0 },
		{ PITCH_MODE_SIGNALSMITH_FORMANT, PITCH_MODE_SIGNALSMITH, 0 },
		{ PITCH_MODE_SIGNALSMITH, PITCH_MODE_GRANULAR, 0 },
		{ PITCH_MODE_SIGNALSMITH, PITCH_MODE_SMOOTH, 0 },
		{ PITCH_MODE_GRANULAR, PITCH_MODE_SMOOTH, 0 },
		{ PITCH_MODE_SMOOTH, PITCH_MODE_GRANULAR, 0 },
		// Rubber Band asked for without the download: lands on Signalsmith.
		{ PITCH_MODE_GRANULAR, PITCH_MODE_RUBBERBAND, 0 },
	};
	const char *sig[3] = { "noise", "chord", "sine" };
	double worstDip[3] = { 0, 0, 0 }, worstClick[3] = { 0, 0, 0 };
	double worstSet = 0, worstLock = 0, worstBlock = 0;
	printf("      %-44s %22s %22s %22s\n", "", "noise dip/step", "chord dip/step", "sine dip/step");
	for (size_t k = 0; k < sizeof(sw) / sizeof(sw[0]); k++)
	{
		char name[80];
		snprintf(name, sizeof(name), "%s -> %s", modeName(sw[k].from), modeName(sw[k].to));
		printf("      %-44s", name);
		for (int s = 0; s < 3; s++)
		{
			double dip, click, setMs, lockMs, blockMs;
			int cu;
			runSwitch(sw[k].from, sw[k].to, s, 0, &dip, &click, &setMs, &lockMs, &blockMs, &cu);
			printf("       %6.2f dB /%5.2fx", dip, click);
			if (dip > worstDip[s]) worstDip[s] = dip;
			if (click > worstClick[s]) worstClick[s] = click;
			if (setMs > worstSet) worstSet = setMs;
			if (lockMs > worstLock) worstLock = lockMs;
			if (blockMs > worstBlock) worstBlock = blockMs;
		}
		printf("\n");
	}
	check("noise: worst dip below the steady level (dB)", worstDip[0], -100.0, 3.0);
	check("chord: worst dip below the steady level (dB)", worstDip[1], -100.0, 3.0);
	check("chord: worst step against the steady signal's (x)", worstClick[1], 0.0, 1.5);
	check("sine: worst step against the steady signal's (x)", worstClick[2], 0.0, 1.5);
	printf("      sine dips are reported, not asserted: two methods playing the same\n"
	       "      sine at different delays can meet in any phase, including opposite.\n");
	printf("      worst sine dip %.2f dB\n", worstDip[2]);

	// Audio keeps playing while the new instance is primed on another thread.
	// Let 30ms of it through between the history copy and the install, and
	// make sure the instance catches up on exactly that and nothing is lost.
	{
		double dip, click, setMs, lockMs, blockMs;
		int cu;
		runSwitch(PITCH_MODE_GRANULAR, PITCH_MODE_SIGNALSMITH, 1, secs(0.03), &dip, &click,
		          &setMs, &lockMs, &blockMs, &cu);
		printf("      30ms of audio played during priming: caught up %d samples, dip %.2f dB, step %.2fx\n",
		       cu, dip, click);
		check("it catches up on exactly the audio it missed (samples)", (double)cu,
		      (double)secs(0.03), (double)secs(0.03) + 480.0);
		check("with no dip (dB)", dip, -100.0, 3.0);
		check("and no click (x)", click, 0.0, 1.5);
	}

	printf("      switching to signalsmith: SetParam %.2f ms on the settings thread, of which the\n"
	       "      lock was held at most %.3f ms; the slowest audio block after it took %.3f ms\n",
	       worstSet, worstLock, worstBlock);
	check("longest the audio thread could wait on the lock (ms)", worstLock, 0.0, 1.0);

	// Switching the card on in Signalsmith mode: the dry signal plays until the
	// delayed one has music in it, rather than 100ms of silence.
	{
		prepare(48000.0);
		const double c[3] = { 220.0, 277.18, 329.63 };
		genChord(c, 3, 0.2, secs(1.5));
		startCard(5.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
		stream(secs(1.5));
		const double ref = minWinDb(bufL, secs(0.8), secs(0.6), secs(0.005));
		const double first = minWinDb(bufL, 0, secs(0.4), secs(0.005));
		printf("      card switched on: steady %.1f dB, worst 5ms window in the first 0.4 s %.1f dB\n",
		       ref, first);
		check("switching the card on does not open a silent gap (dB dip)", ref - first, -100.0, 3.0);
	}
}

static void testDrag(void)
{
	printf("\n5. dragging the slider through zero, and pausing\n");
	prepare(48000.0);
	const double c[3] = { 220.0, 277.18, 329.63 };
	genChord(c, 3, 0.2, secs(3.0));
	startCard(-1.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
	stream(secs(1.0));
	const int L0 = PitchShiftLatency(&g_lib);
	int latChanged = 0, bypassed = 0;
	for (int k = 0; k <= 40; k++)
	{
		PitchShiftSetParam(&g_lib, -1.0f + 0.05f * k, 100.0f, PITCH_MODE_SIGNALSMITH);
		if (PitchShiftLatency(&g_lib) != L0) latChanged++;
		if (g_lib.pitchShift.bypass) bypassed++;
		stream(secs(0.01));
	}
	stream(secs(3.0) - (int)g_pos);
	const int win = secs(0.005);
	const double ref = minWinDb(bufL, secs(0.5), secs(0.45), win);
	const double drag = minWinDb(bufL, secs(1.0), secs(0.6), win);
	const double step = maxStep(bufL, secs(1.0), secs(0.6)) / maxStep(bufL, secs(0.5), secs(0.45));
	printf("      latency %d samples throughout; drag dip %.2f dB, step %.2fx\n", L0, ref - drag, step);
	check("the delay never changes on the way through 0 (blocks that did)", (double)latChanged, 0, 0);
	check("and the stage is never skipped (blocks bypassed)", (double)bypassed, 0, 0);
	check("no dip while dragging (dB)", ref - drag, -100.0, 3.0);
	check("no click while dragging (x)", step, 0.0, 1.5);

	// Tone, a second of digital silence (long enough for Signalsmith to switch
	// to its pass-through), then the tone again.
	prepare(48000.0);
	const int n = secs(3.0);
	genTone(440.0, 0.5, n);
	for (int i = secs(1.0); i < secs(2.0); i++) inL[i] = inR[i] = 0.0f;
	startCard(5.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
	stream(n);
	const int L = PitchShiftLatency(&g_lib);
	// The library hands silence straight through once it has had two blocks
	// of it, so from then on (plus the delay) the output must be exactly zero.
	// Before that it is the tail of the last note dying away.
	double stray = 0.0, tail = 0.0;
	const int quiet = secs(1.0) + L + 2 * (int)(SIGNALSMITH_BLOCK_SEC * 48000.0f) + secs(0.01);
	const int tailFrom = secs(1.0) + L + (int)(SIGNALSMITH_BLOCK_SEC * 48000.0f) / 2 + secs(0.01);
	for (int i = tailFrom; i < quiet; i++)
		if (fabs(bufL[i]) > tail) tail = fabs(bufL[i]);
	for (int i = quiet; i < secs(2.0); i++)
		if (fabs(bufL[i]) > stray) stray = fabs(bufL[i]);
	int onset = -1;
	for (int i = secs(2.0); i < n; i++)
		if (fabs(bufL[i]) > 0.01) { onset = i - secs(2.0); break; }
	const double before = rmsDb(bufL, secs(0.5), secs(0.4));
	const double after = rmsDb(bufL, secs(2.0) + L + secs(0.1), secs(0.5));
	printf("      pause: the last note dies away to %.1e (peak, from half a block after it ends), then %.1e;\n"
	       "      sound returns %d samples after the input does (delay %d)\n", tail, stray, onset, L);
	check("the tail of the last note is down by 60 dB (peak)", tail, 0.0, 5e-4);
	check("then silence comes through as silence (peak)", stray, 0.0, 1e-7);
	check("the tone returns one delay later, give or take half a block (ms)",
	      1000.0 * (onset - L) / 48000.0, -45.0, 5.0);
	check("at the same level as before the pause (dB)", after - before, -1.0, 1.0);
	check("no non-finite samples", (double)nonFinite(n), 0, 0);
}

typedef struct { const char *name; float block, interval; int split; } Config;

static void testCpu(void)
{
	printf("\n6. CPU at 48 kHz, 20 s of stereo noise, up 5 semitones\n");
	g_fs = 48000.0;
	const int n = secs(20.0);
	genNoise(0.3, n);
	const int blocks[4] = { 128, 256, 480, 1024 };
	const int modes[3] = { PITCH_MODE_GRANULAR, PITCH_MODE_SMOOTH, PITCH_MODE_SIGNALSMITH };
	double ss480 = 0.0, sm480 = 0.0;
	printf("      %-12s %6s %10s %10s %12s %12s %12s\n", "method", "block", "ns/sample", "% of core",
	       "99.9% block", "worst block", "block length");
	for (int m = 0; m < 3; m++)
		for (int b = 0; b < 4; b++)
		{
			prepare(48000.0);
			g_block = blocks[b];
			startCard(5.0f, 100.0f, modes[m]);
			g_blockWorst = 0.0;
			g_ntimes = 0;
			const double t0 = now();
			stream(n);
			const double ns = (now() - t0) / n * 1e9;
			printf("      %-12s %6d %10.1f %9.2f%% %9.3f ms %9.3f ms %9.2f ms\n", modeName(modes[m]), blocks[b], ns,
			       ns * 48000.0 / 1e9 * 100.0, pct(0.999) * 1000.0, g_blockWorst * 1000.0,
			       1000.0 * blocks[b] / 48000.0);
			if (blocks[b] == 480 && modes[m] == PITCH_MODE_SIGNALSMITH) ss480 = ns;
			if (blocks[b] == 480 && modes[m] == PITCH_MODE_SMOOTH) sm480 = ns;
		}
	g_block = 480;
	printf("      signalsmith costs %.1fx smooth here\n", ss480 / sm480);

	// The library's own presets against the one chosen, straight through the
	// library, on cost, delay and sound.
	printf("\n      set-ups compared (block / interval in seconds, split = work spread out):\n");
	printf("      %-26s %7s %9s %11s %11s %11s %9s\n", "", "delay", "% core", "99.9% 128", "99.9% 1024",
	       "bass cents", "chord dB");
	const Config cfg[] = {
		{ "library default", 0.12f, 0.03f, 0 },
		{ "library default, split", 0.12f, 0.03f, 1 },
		{ "library cheaper (split)", 0.10f, 0.04f, 1 },
		{ "shorter, split", 0.08f, 0.02f, 1 },
		{ "more overlap, split", 0.12f, 0.02f, 1 },
		{ "longer, split", 0.14f, 0.035f, 1 },
		{ "chosen", SIGNALSMITH_BLOCK_SEC, SIGNALSMITH_INTERVAL_SEC, SIGNALSMITH_SPLIT },
	};
	const int mask = MAXN - 1;
	for (size_t k = 0; k < sizeof(cfg) / sizeof(cfg[0]); k++)
	{
		double worst[2] = { 0, 0 }, ns = 0.0;
		int lat = 0;
		const int bs[2] = { 128, 1024 };
		const float *ring[2] = { inL, inR };
		for (int b = 0; b < 2; b++)
		{
			void *s = SignalsmithCreateCustom(48000.0f, 0, cfg[k].block, cfg[k].interval, cfg[k].split);
			SignalsmithSetRatio(s, powf(2.0f, 5.0f / 12.0f));
			lat = SignalsmithLatency(s);
			const double t0 = now();
			g_ntimes = 0;
			for (int i = 0; i + bs[b] <= n; i += bs[b])
			{
				float *out[2] = { bufL + i, bufR + i };
				const double a = now();
				SignalsmithProcessRing(s, ring, mask, i, out, bs[b]);
				if (g_ntimes < MAXT) g_times[g_ntimes++] = now() - a;
			}
			worst[b] = pct(0.999);
			if (b == 1) ns = (now() - t0) / n * 1e9;
			SignalsmithDestroy(s);
		}
		// Sound: the worst error on a bass tone (55, 80 and 110 Hz, six shifts),
		// where the library is least accurate, and the wobble on a held 440 Hz
		// up a fifth.
		double cents = 0.0, wob;
		{
			const double bass[3] = { 55.0, 80.0, 110.0 };
			const float bsh[6] = { -12.0f, -7.0f, -3.0f, 3.0f, 7.0f, 12.0f };
			for (int t = 0; t < 3; t++)
				for (int q = 0; q < 6; q++)
				{
					for (int i = 0; i < secs(3.0); i++) inL[i] = inR[i] = (float)(0.5 * sin(2.0 * M_PI * bass[t] * i / 48000.0));
					void *s = SignalsmithCreateCustom(48000.0f, 0, cfg[k].block, cfg[k].interval, cfg[k].split);
					SignalsmithSetRatio(s, powf(2.0f, bsh[q] / 12.0f));
					for (int i = 0; i < secs(3.0); i += 480) { float *o[2] = { bufL + i, bufR + i }; SignalsmithProcessRing(s, ring, mask, i, o, 480); }
					const double want = bass[t] * pow(2.0, bsh[q] / 12.0);
					const double c = fabs(1200.0 * log2(measureFreq(bufL, secs(1.0), secs(1.9), want) / want));
					if (c > cents) cents = c;
					SignalsmithDestroy(s);
				}
			for (int i = 0; i < secs(3.0); i++) inL[i] = inR[i] = (float)(0.5 * sin(2.0 * M_PI * 440.0 * i / 48000.0));
			void *s = SignalsmithCreateCustom(48000.0f, 0, cfg[k].block, cfg[k].interval, cfg[k].split);
			SignalsmithSetRatio(s, powf(2.0f, 7.0f / 12.0f));
			for (int i = 0; i < secs(3.0); i += 480) { float *o[2] = { bufL + i, bufR + i }; SignalsmithProcessRing(s, ring, mask, i, o, 480); }
			SignalsmithDestroy(s);
			// Worst purity over the 220 Hz chord at three shifts.
			wob = 1e9;
			const double ch[3] = { 220.0, 277.18, 329.63 };
			const float chs[3] = { -5.0f, 3.0f, 7.0f };
			for (int q = 0; q < 3; q++)
			{
				genChord(ch, 3, 0.2, secs(3.0));
				s = SignalsmithCreateCustom(48000.0f, 0, cfg[k].block, cfg[k].interval, cfg[k].split);
				SignalsmithSetRatio(s, powf(2.0f, chs[q] / 12.0f));
				for (int i = 0; i < secs(3.0); i += 480) { float *o[2] = { bufL + i, bufR + i }; SignalsmithProcessRing(s, ring, mask, i, o, 480); }
				double up[3];
				for (int j = 0; j < 3; j++) up[j] = measureFreq(bufL, secs(1.0), secs(1.9), ch[j] * pow(2.0, chs[q] / 12.0));
				const double pur = purityDb(bufL, secs(1.0), secs(1.9), up, 3);
				if (pur < wob) wob = pur;
				SignalsmithDestroy(s);
			}
			genNoise(0.3, n);
		}
		printf("      %-26s %5.0fms %8.2f%% %8.3f ms %8.3f ms %11.2f %9.1f\n", cfg[k].name,
		       1000.0 * lat / 48000.0, ns * 48000.0 / 1e9 * 100.0, worst[0] * 1000.0, worst[1] * 1000.0,
		       cents, wob);
	}
}

static void testRobust(void)
{
	printf("\n7. robustness\n");
	{
		// Sample rate changes mid-stream, with guard bytes behind every calloc.
		const double rates[4] = { 44100.0, 48000.0, 96000.0, 44100.0 };
		double worst = 0.0;
		int bad = 0;
		prepare(44100.0);
		startCard(3.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
		for (int r = 0; r < 4; r++)
		{
			if (r > 0)
			{
				g_lib.fs = (float)rates[r];
				g_fs = rates[r];
				PitchShiftRefresh(&g_lib);
			}
			g_pos = 0;
			genTone(1000.0, 0.5, secs(2.0));
			stream(secs(2.0));
			const double got = measureFreq(bufL, secs(1.0), secs(0.9), 1000.0 * pow(2.0, 3.0 / 12.0));
			const double c = fabs(1200.0 * log2(got / (1000.0 * pow(2.0, 3.0 / 12.0))));
			printf("      at %.1f kHz: %.3f cents off\n", rates[r] / 1000.0, c);
			if (c > worst) worst = c;
			bad += nonFinite(secs(2.0));
		}
		check("in tune after every rate change (worst cents)", worst, 0.0, 5.0);
		check("no non-finite samples", (double)bad, 0, 0);
		check("nothing written past the end of a buffer", (double)liveOverruns(), 0, 0);
		PitchShiftDisable(&g_lib);
	}
	{
		// Switching the card on and off, and changing method in between.
		const int modes[4] = { PITCH_MODE_SIGNALSMITH, PITCH_MODE_SMOOTH, PITCH_MODE_SIGNALSMITH_FORMANT,
		                       PITCH_MODE_GRANULAR };
		prepare(48000.0);
		genNoise(0.3, secs(1.0));
		int bad = 0;
		for (int k = 0; k < 300; k++)
		{
			g_pos = 0;
			startCard((float)(k % 25 - 12), 100.0f, modes[k % 4]);
			stream(2048);
			PitchShiftSetParam(&g_lib, 2.0f, 100.0f, modes[(k + 1) % 4]);
			stream(2048);
			bad += nonFinite(4096);
			PitchShiftDisable(&g_lib);
		}
		check("300 on/off cycles: non-finite samples", (double)bad, 0, 0);
		check("and nothing left allocated after the last one (blocks)", (double)liveBlocks(), 0, 0);
		check("and nothing overrun", (double)liveOverruns(), 0, 0);
	}
	{
		// Signalsmith refusing to start must walk down to Smooth, not go quiet.
		prepare(48000.0);
		genTone(440.0, 0.5, secs(2.0));
		g_failCreate = 1;
		startCard(7.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
		stream(secs(2.0));
		g_failCreate = 0;
		printf("      Signalsmith would not start: mode ended up %s\n", modeName(g_lib.pitchShift.mode));
		check("it fell back to Smooth", (double)(g_lib.pitchShift.mode == PITCH_MODE_SMOOTH), 1, 1);
		const double want = 440.0 * pow(2.0, 7.0 / 12.0);
		const double got = measureFreq(bufL, secs(1.0), secs(0.9), want);
		check("and still shifts (cents off)", fabs(1200.0 * log2(got / want)), 0.0, 10.0);
		check("at a sensible level (dB)", rmsDb(bufL, secs(1.0), secs(0.9)), -12.0, -6.0);
	}
	{
		// Fifteen minutes of one held tone. Smooth once drifted off pitch over
		// this long; anything accumulating phase can.
		prepare(48000.0);
		startCard(3.0f, 100.0f, PITCH_MODE_SIGNALSMITH);
		const int64_t total = (int64_t)15 * 60 * 48000;
		const int seg = secs(10.0);
		const double f = 1000.0, want = f * pow(2.0, 3.0 / 12.0);
		double firstC = 0, lastC = 0, firstDb = 0, lastDb = 0;
		int bad = 0;
		for (int64_t done = 0; done < total; done += seg)
		{
			for (int i = 0; i < seg; i++)
				inL[i] = inR[i] = (float)(0.5 * sin(2.0 * M_PI * f * (double)((done + i) % 48000) / 48000.0));
			g_pos = 0;
			stream(seg);
			bad += nonFinite(seg);
			if (done == 0 || done + seg >= total)
			{
				const double got = measureFreq(bufL, secs(2.0), secs(7.0), want);
				const double c = 1200.0 * log2(got / want);
				const double db = rmsDb(bufL, secs(2.0), secs(7.0));
				if (done == 0) { firstC = c; firstDb = db; } else { lastC = c; lastDb = db; }
			}
		}
		printf("      15 minutes: %+.4f cents and %.2f dB at the start, %+.4f cents and %.2f dB at the end\n",
		       firstC, firstDb, lastC, lastDb);
		check("pitch drift over 15 minutes (cents)", fabs(lastC - firstC), 0.0, 0.5);
		check("level drift over 15 minutes (dB)", fabs(lastDb - firstDb), 0.0, 0.2);
		check("no non-finite samples", (double)bad, 0, 0);
		PitchShiftDisable(&g_lib);
	}
}

int main(int argc, char **argv)
{
	const int quick = argc > 1 && !strcmp(argv[1], "quick");
	printf("== Signalsmith pitch shifting ==\n");
	testPitch();
	testLevel();
	testLatency();
	testSwitching();
	testDrag();
	testCpu();
	if (!quick) testRobust();
	prepare(48000.0);
	printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "PASSED", failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
