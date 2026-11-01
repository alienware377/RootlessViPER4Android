// Does the binaural upmix put the music where it says, and nothing else?
//
// Built for x86_64 and run on the emulator:
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       -DBINAURAL_STATS -Wl,--wrap=calloc -Wl,--wrap=free \
//       tools/binaural_harness.c $J/Effects/binauralUpmix.c \
//       $J/Effects/eel2/numericSys/codelet.c tools/lockstub.c -lm -o binaural
//   adb -s emulator-5554 push binaural /data/local/tmp/ && \
//   adb -s emulator-5554 shell /data/local/tmp/binaural
//
// Every check names the deliberately broken build it has to FAIL on, so each
// one is known to be able to see the fault it is there for. Build with one of
// -DBROKEN_LATENCY, -DBROKEN_DRYRESYNTH, -DBROKEN_FIFO, -DBROKEN_CENTRE,
// -DBROKEN_SWAP, -DBROKEN_MIRROR, -DBROKEN_NOCOH, -DBROKEN_NOPAD,
// -DBROKEN_FOLD, -DBROKEN_NOSCRUB, -DBROKEN_RESET, -DBROKEN_SWAPGAP,
// -DBROKEN_NOFADE, -DBROKEN_NOREFRESH, -DBROKEN_RATECHECK or -DBROKEN_FDNGAIN
// (the defines reach the engine file too) and the named check fails. The
// speed check has no broken build; it is a budget.
//
// Head sets are made here from published head models, so no measured data
// and no data licence is involved: the interaural delay from Woodworth's
// formula per ear (as written by Brown and Duda, IEEE TSAP 6(5), 1998), the
// head shadow from Brown and Duda's one-pole one-zero model, a = 8.75 cm. The
// set is exactly left/right symmetric. Noise is generated here and seeded, so
// every run is identical.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "jdsp_header.h"
#include "Effects/eel2/numericSys/codelet.h"

#define MAXS (96000 * 8)
#define MAXBLK_SAMPLES 4096
#define SETMAX (BIN_SPEAKERS * 2 * 1024)

static JamesDSPLib g_lib;
static float bufL[MAXBLK_SAMPLES], bufR[MAXBLK_SAMPLES];
static float inL[MAXS], inR[MAXS], outL[MAXS], outR[MAXS];
static float out2L[MAXS], out2R[MAXS];
static float g_set[SETMAX], g_set2[SETMAX], g_ident[SETMAX];
static int failures = 0;

/* ---------------- guard bytes around every allocation ---------------- */
void *__real_calloc(size_t a, size_t b);
void __real_free(void *p);
#define GUARD 4096
#define MAXALLOC 64
static struct { unsigned char *p; size_t n; } g_blk[MAXALLOC];
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
	for (int i = 0; i < MAXALLOC; i++)
		if (!g_blk[i].p) { g_blk[i].p = p; g_blk[i].n = n; break; }
	return p;
}

void __wrap_free(void *q)
{
	if (!q) return;
	for (int i = 0; i < MAXALLOC; i++)
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
	for (int i = 0; i < MAXALLOC; i++)
		if (g_blk[i].p && !guardIntact(g_blk[i].p, g_blk[i].n)) bad++;
	return bad + g_overruns;
}

static int liveBlocks(void)
{
	int n = 0;
	for (int i = 0; i < MAXALLOC; i++)
		if (g_blk[i].p) n++;
	return n;
}

/* ---------------- helpers ---------------- */
static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-60s %10.4f  [%8.3f..%8.3f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static void info(const char *what, double got)
{
	printf("  %-60s %10.4f  (reported)\n", what, got);
}

static uint64_t g_rng = 88172645463325252ull;
static double urand(void)
{
	g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17;
	return (double)(g_rng >> 11) * (1.0 / 9007199254740992.0);
}
static double grand(void)
{
	double u1 = urand(), u2 = urand();
	if (u1 < 1e-300) u1 = 1e-300;
	return sqrt(-2.0 * log(u1)) * cos(2.0 * M_PI * u2);
}
static void seed(uint64_t s) { g_rng = s ? s : 1; for (int i = 0; i < 10; i++) urand(); }

static void white(float *x, int n, double rms)
{
	for (int i = 0; i < n; i++) x[i] = (float)(grand() * rms);
}

/* Pink through the widely published Kellet filter. */
static void pink(float *x, int n, double rms)
{
	double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0, acc = 0;
	double *tmp = (double *)malloc(sizeof(double) * n);
	for (int i = 0; i < n; i++)
	{
		const double w = grand();
		b0 = 0.99886 * b0 + w * 0.0555179;
		b1 = 0.99332 * b1 + w * 0.0750759;
		b2 = 0.96900 * b2 + w * 0.1538520;
		b3 = 0.86650 * b3 + w * 0.3104856;
		b4 = 0.55000 * b4 + w * 0.5329522;
		b5 = -0.7616 * b5 - w * 0.0168980;
		const double p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362;
		b6 = w * 0.115926;
		tmp[i] = p;
		acc += p * p;
	}
	const double g = rms / sqrt(acc / n);
	for (int i = 0; i < n; i++) x[i] = (float)(tmp[i] * g);
	free(tmp);
}

/* A stereo "mix": a shared part panned a little left, plus a part only in each
   channel, so every mask has something to do. */
static void mix(float *l, float *r, int n, uint64_t sd)
{
	static float a[MAXS], b[MAXS];
	seed(sd);
	pink(l, n, 0.08);
	pink(a, n, 0.05);
	pink(b, n, 0.05);
	for (int i = 0; i < n; i++)
	{
		const float s = l[i];
		l[i] = 0.8f * s + a[i];
		r[i] = 0.5f * s + b[i];
	}
}

typedef struct { double b0, b1, b2, a1, a2, z1, z2; } Bq;
static void bqHp(Bq *q, double f0, double fs, double Q)
{
	const double w0 = 2 * M_PI * f0 / fs, al = sin(w0) / (2 * Q), c = cos(w0), a0 = 1 + al;
	q->b0 = (1 + c) / 2 / a0; q->b1 = -(1 + c) / a0; q->b2 = (1 + c) / 2 / a0;
	q->a1 = -2 * c / a0; q->a2 = (1 - al) / a0; q->z1 = q->z2 = 0;
}
static void bqLp(Bq *q, double f0, double fs, double Q)
{
	const double w0 = 2 * M_PI * f0 / fs, al = sin(w0) / (2 * Q), c = cos(w0), a0 = 1 + al;
	q->b0 = (1 - c) / 2 / a0; q->b1 = (1 - c) / a0; q->b2 = (1 - c) / 2 / a0;
	q->a1 = -2 * c / a0; q->a2 = (1 - al) / a0; q->z1 = q->z2 = 0;
}
static double bqRun(Bq *q, double x)
{
	const double y = q->b0 * x + q->z1;
	q->z1 = q->b1 * x - q->a1 * y + q->z2;
	q->z2 = q->b2 * x - q->a2 * y;
	return y;
}

static double dB(double r) { return 10.0 * log10(r > 1e-300 ? r : 1e-300); }

static double nowSec(void)
{
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return (double)t.tv_sec + 1e-9 * (double)t.tv_nsec;
}

static double energy(const float *x, int a, int b)
{
	double e = 0;
	for (int i = a; i < b; i++) e += (double)x[i] * x[i];
	return e;
}

/* ---------------- Welch spectrum with the engine's FHT ---------------- */
#define WN 4096
static float w_sin[WN], w_win[WN], w_buf[WN];
static unsigned w_rev[WN];
static void welchInit(void)
{
	for (int i = 0; i < WN; i++)
	{
		unsigned r = 0, v = (unsigned)i;
		for (int b = 0; b < 12; b++) { r = (r << 1) | (v & 1u); v >>= 1; }
		w_rev[i] = r;
		w_sin[i] = (float)sin(2.0 * M_PI * i / WN);
		w_win[i] = (float)(0.5 - 0.5 * cos(2.0 * M_PI * i / WN));
	}
}
static void welch(const float *x, int start, int len, double *P)
{
	for (int k = 0; k <= WN / 2; k++) P[k] = 0;
	int count = 0;
	for (int s = start; s + WN <= start + len; s += WN / 2)
	{
		for (int i = 0; i < WN; i++) w_buf[w_rev[i]] = x[s + i] * w_win[i];
		DFT4096(w_buf, w_sin);
		for (int k = 0; k <= WN / 2; k++)
		{
			const double a = w_buf[k], b = w_buf[(WN - k) & (WN - 1)];
			P[k] += 0.5 * (a * a + b * b);
		}
		count++;
	}
	for (int k = 0; k <= WN / 2; k++) P[k] /= count ? count : 1;
}
static double bandPow(const double *P, double f1, double f2, double fs)
{
	int k1 = (int)ceil(f1 * WN / fs), k2 = (int)floor(f2 * WN / fs);
	if (k2 < k1) k2 = k1;
	double s = 0;
	for (int k = k1; k <= k2; k++) s += P[k];
	return s;
}
static const double thirds[] = { 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250,
	1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000 };
#define NTHIRDS 23

/* ---------------- head sets ---------------- */
static const double spkAz[BIN_SPEAKERS] = { 0, 30, -30, 60, -60, 110, -110, 45, -45 };
static const double spkEl[BIN_SPEAKERS] = { 0, 0, 0, 0, 0, 0, 0, 40, 40 };
#define SOUND 343.0

/* One ear's response to a far source: delay by the path round a sphere,
   then the head shadow filter, both after Brown and Duda. */
static void synthEar(double fs, double a, double az, double el, int left, float *h, int taps)
{
	const double ux = cos(el) * cos(az), uy = cos(el) * sin(az);
	(void)ux;
	const double cth = (left ? uy : -uy);
	const double th = acos(cth > 1 ? 1 : (cth < -1 ? -1 : cth));
	const double T = th < M_PI / 2 ? (a / SOUND) * (1 - cos(th)) : (a / SOUND) * (1 + th - M_PI / 2);
	const double d = 8.0 + T * fs;
	const double alpha = 1.05 + 0.95 * cos(th / (5.0 * M_PI / 6.0) * M_PI);
	const double beta = 2.0 * SOUND / a, t2 = 2.0 * fs;
	const double b0 = (t2 * alpha + beta) / (t2 + beta), b1 = (beta - t2 * alpha) / (t2 + beta);
	const double a1 = (beta - t2) / (t2 + beta);
	double prevV = 0, prevY = 0;
	for (int n = 0; n < taps; n++)
	{
		const double x = n - d;
		double v = 0;
		if (fabs(x) < 16.0)
			v = (fabs(x) < 1e-9 ? 1.0 : sin(M_PI * x) / (M_PI * x)) * (0.5 + 0.5 * cos(M_PI * x / 16.0));
		const double y = b0 * v + b1 * prevV - a1 * prevY;
		prevV = v; prevY = y;
		h[n] = (float)(y * 0.5);
	}
}

/* Builds the nine pairs at fs, returns taps and where the centre pair peaks,
   which is how the app works out the offset the dry is lined up with. */
static int makeSet(double fs, double a, float *out, int *cOff)
{
	int taps = (int)(256.0 * fs / 48000.0 + 0.5);
	if (taps > 1024) taps = 1024;
	for (int s = 0; s < BIN_SPEAKERS; s++)
		for (int e = 0; e < 2; e++)
			synthEar(fs, a, spkAz[s] * M_PI / 180.0, spkEl[s] * M_PI / 180.0, e == 0,
				out + ((size_t)s * 2 + e) * taps, taps);
	float pk = 0;
	int at = 0;
	for (int n = 0; n < taps; n++) if (fabsf(out[n]) > pk) { pk = fabsf(out[n]); at = n; }
	*cOff = at;
	return taps;
}

/* Every speaker a unit impulse to its own side, the centre 1 / sqrt(2) to both. */
static int makeIdentity(float *out)
{
	const int taps = 64;
	memset(out, 0, sizeof(float) * BIN_SPEAKERS * 2 * taps);
	for (int s = 0; s < BIN_SPEAKERS; s++)
	{
		if (s == BIN_C)
		{
			out[0] = out[taps] = (float)M_SQRT1_2;
			continue;
		}
		const int left = (s == BIN_FL || s == BIN_WL || s == BIN_SL || s == BIN_TL);
		out[((size_t)s * 2 + (left ? 0 : 1)) * taps] = 1.0f;
	}
	return taps;
}

/* ---------------- running the effect ---------------- */
static void setup(float fs, const float *set, int taps, int cOff,
	float amount, float width, float sur, float height, float room)
{
	BinauralUpmixFree(&g_lib);
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = fs;
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
	BinauralUpmixSetParam(&g_lib, amount, width, sur, height, room);
	if (set) BinauralUpmixLoadHrir(&g_lib, set, BIN_SPEAKERS, taps, fs, cOff);
	BinauralUpmixEnable(&g_lib);
}

static void clearStats(void)
{
#ifdef BINAURAL_STATS
	for (int s = 0; s < BIN_SPEAKERS; s++) g_lib.binaural.feedEnergy[s] = 0;
	g_lib.binaural.primaryEnergy = g_lib.binaural.ambienceEnergy = 0;
#endif
}

static void runRange(const float *iL, const float *iR, float *oL, float *oR,
	int from, int to, int blk, uint64_t *rs)
{
	int i = from;
	while (i < to)
	{
		int b = blk;
		if (!b)
		{
			*rs ^= *rs << 13; *rs ^= *rs >> 7; *rs ^= *rs << 17;
			b = 1 + (int)((*rs >> 20) % 1500);
		}
		if (b > to - i) b = to - i;
		memcpy(bufL, iL + i, sizeof(float) * b);
		memcpy(bufR, iR + i, sizeof(float) * b);
		if (g_lib.binauralEnabled) BinauralUpmixProcess(&g_lib, (size_t)b);
		memcpy(oL + i, bufL, sizeof(float) * b);
		memcpy(oR + i, bufR, sizeof(float) * b);
		i += b;
	}
}
static void run(const float *iL, const float *iR, float *oL, float *oR, int n, int blk)
{
	uint64_t rs = 12345;
	runRange(iL, iR, oL, oR, 0, n, blk, &rs);
}

/* Lag of the biggest cross-correlation of y against x over [lo, hi], with a
   parabola through the peak. */
static double xcorrLag(const float *x, const float *y, int start, int len, int lo, int hi)
{
	double best = -1e300; int bi = lo;
	static double c[4096];
	for (int lag = lo; lag <= hi; lag++)
	{
		double s = 0;
		for (int i = start; i < start + len; i++) s += (double)x[i] * y[i + lag];
		c[lag - lo] = s;
		if (s > best) { best = s; bi = lag; }
	}
	if (bi > lo && bi < hi)
	{
		const double a = c[bi - 1 - lo], b = c[bi - lo], d = c[bi + 1 - lo];
		const double den = a - 2 * b + d;
		if (den != 0) return bi + 0.5 * (a - d) / den;
	}
	return bi;
}

/* y = h * x, delayed by del. */
static void convolve(const float *x, const float *h, int taps, float *y, int n, int del)
{
	for (int i = 0; i < n; i++)
	{
		double s = 0;
		for (int k = 0; k < taps; k++)
		{
			const int j = i - del - k;
			if (j >= 0) s += (double)h[k] * x[j];
		}
		y[i] = (float)s;
	}
}

/* ====================================================================== */

static void testLatency(void)
{
	printf("\n[1] delay is what it reports, at 44.1, 48 and 96 kHz (BROKEN_LATENCY)\n");
	const float rates[3] = { 44100, 48000, 96000 };
	for (int r = 0; r < 3; r++)
	{
		const float fs = rates[r];
		int cOff;
		const int taps = makeSet(fs, 0.0875, g_set, &cOff);
		const int n = (int)fs * 2;
		seed(10 + r);
		white(inL, n, 0.1);
		memcpy(inR, inL, sizeof(float) * n);
		setup(fs, g_set, taps, cOff, 0, 40, 60, 40, 25);
		run(inL, inR, outL, outR, n, 480);
		const int rep = BinauralUpmixLatency(&g_lib);
		/* Amount 0 is the input, delayed: find the lag that matches exactly. */
		int found = -1;
		for (int lag = 0; lag < 6000 && found < 0; lag++)
		{
			int ok = 1;
			for (int i = n - 4000; i < n && ok; i++)
				if (outL[i] != inL[i - lag]) ok = 0;
			if (ok) found = lag;
		}
		char what[96];
		snprintf(what, sizeof what, "%.1f kHz: dry delay minus reported, samples", fs / 1000);
		check(what, found - rep, 0, 0);
		snprintf(what, sizeof what, "%.1f kHz: reported delay, samples", fs / 1000);
		info(what, rep);
		/* Fully spatial, the centre lines up with the dry to within a sample,
		   so a part-way Amount does not comb. */
		setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 0);
		run(inL, inR, outL, outR, n, 480);
		const double lag = xcorrLag(inL, outL, n / 2, (int)fs / 2, 0, 3000);
		snprintf(what, sizeof what, "%.1f kHz: wet centre delay minus reported, us", fs / 1000);
		check(what, 1e6 * (lag - rep) / fs, -15, 15);
	}
}

static void testDryExact(void)
{
	printf("\n[2] Amount 0 is the input delayed, bit for bit (BROKEN_DRYRESYNTH)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 2;
	mix(inL, inR, n, 21);
	const float ps[3][4] = { { 40, 60, 40, 25 }, { 100, 100, 100, 100 }, { 0, 0, 0, 0 } };
	int worst = 0;
	for (int p = 0; p < 3; p++)
	{
		setup(fs, g_set, taps, cOff, 0, ps[p][0], ps[p][1], ps[p][2], ps[p][3]);
		run(inL, inR, outL, outR, n, 480);
		const int lat = BinauralUpmixLatency(&g_lib);
		int bad = 0;
		for (int i = lat + 480 + 1000; i < n; i++)
			if (outL[i] != inL[i - lat] || outR[i] != inR[i - lat]) bad++;
		if (bad > worst) worst = bad;
	}
	check("samples that differ from the delayed input, worst of 3 settings", worst, 0, 0);
}

static void testBlocks(void)
{
	printf("\n[3] the same output for any block size (BROKEN_FIFO)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 2;
	mix(inL, inR, n, 31);
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 25);
	run(inL, inR, out2L, out2R, n, 4096);
	const int sizes[7] = { 1, 7, 64, 441, 480, 4096, 0 };
	int worst = 0;
	for (int s = 0; s < 7; s++)
	{
		setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 25);
		run(inL, inR, outL, outR, n, sizes[s]);
		int bad = 0;
		for (int i = 0; i < n; i++)
			if (outL[i] != out2L[i] || outR[i] != out2R[i]) bad++;
		if (bad > worst) worst = bad;
	}
	check("samples that differ from 4096-sample blocks, worst size", worst, 0, 0);
}

static void testCentre(void)
{
	printf("\n[4] a mono centre stays in the centre (BROKEN_CENTRE)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 4;
	seed(41);
	pink(inL, n, 0.1);
	memcpy(inR, inL, sizeof(float) * n);
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 0);
	run(inL, inR, outL, outR, 48000, 480);
	clearStats();
	uint64_t rs = 1;
	runRange(inL, inR, outL, outR, 48000, n, 480, &rs);
	const int st = 48000 + 4096, len = n - st - 4096;
	check("broadband ear level difference, dB",
		fabs(dB(energy(outL, st, st + len) / energy(outR, st, st + len))), 0, 0.1);
	static double PL[WN / 2 + 1], PR[WN / 2 + 1];
	welch(outL, st, len, PL);
	welch(outR, st, len, PR);
	double worst = 0;
	for (int t = 0; t < NTHIRDS; t++)
	{
		const double f = thirds[t];
		const double d = fabs(dB(bandPow(PL, f / 1.122, f * 1.122, fs) / bandPow(PR, f / 1.122, f * 1.122, fs)));
		if (d > worst) worst = d;
	}
	check("worst third octave ear difference, 100 Hz to 16 kHz, dB", worst, 0, 0.25);
	check("interaural correlation peak lag, samples", xcorrLag(outL, outR, st, 24000, -40, 40), -1, 1);
#ifdef BINAURAL_STATS
	double tot = 0;
	for (int s = 0; s < BIN_SPEAKERS; s++) tot += g_lib.binaural.feedEnergy[s];
	/* C carries sqrt(2) times the primary pair's sum, so its energy equals
	   the primary energy when all of it is centre. */
	check("energy on the centre feed, % of primary",
		100.0 * g_lib.binaural.feedEnergy[BIN_C] / (g_lib.binaural.primaryEnergy + 1e-30), 95, 100.5);
	info("energy on the centre feed, % of all feeds", 100.0 * g_lib.binaural.feedEnergy[BIN_C] / tot);
#else
	check("built without -DBINAURAL_STATS", 0, 1, 1);
#endif
}

static double itdOf(const float *l, const float *r, int st, int len, double fs)
{
	static float a[MAXS], b[MAXS];
	Bq q1, q2, q3, q4;
	bqLp(&q1, 1500, fs, 0.5412); bqLp(&q2, 1500, fs, 1.3066);
	bqLp(&q3, 1500, fs, 0.5412); bqLp(&q4, 1500, fs, 1.3066);
	for (int i = 0; i < st + len + 200; i++)
	{
		a[i] = (float)bqRun(&q2, bqRun(&q1, l[i]));
		b[i] = (float)bqRun(&q4, bqRun(&q3, r[i]));
	}
	/* Positive when the left ear is earlier. */
	return xcorrLag(a, b, st, len, -60, 60) / fs;
}

static double ild4k(const float *l, const float *r, int st, int len, double fs)
{
	static double PL[WN / 2 + 1], PR[WN / 2 + 1];
	welch(l, st, len, PL);
	welch(r, st, len, PR);
	return dB(bandPow(PL, 4000 / 1.122, 4000 * 1.122, fs) / bandPow(PR, 4000 / 1.122, 4000 * 1.122, fs));
}

static void testHardLeft(void)
{
	printf("\n[5] hard left sounds left, at 30 and at 60 degrees (BROKEN_SWAP)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 3;
	seed(51);
	white(inL, n, 0.1);
	memset(inR, 0, sizeof(float) * n);
	const int st = 48000, len = 48000;
	for (int w = 0; w < 2; w++)
	{
		const int spk = w ? BIN_WL : BIN_FL;
		const float width = w ? 100.0f : 0.0f;
		setup(fs, g_set, taps, cOff, 100, width, 60, 40, 0);
		run(inL, inR, outL, outR, n, 480);
		const int Nw1 = BinauralUpmixLatency(&g_lib) - g_lib.binaural.specCOff;
		/* What that speaker alone would give. */
		convolve(inL, g_set + (size_t)spk * 2 * taps, taps, out2L, n, Nw1);
		convolve(inL, g_set + ((size_t)spk * 2 + 1) * taps, taps, out2R, n, Nw1);
		const double wantItd = itdOf(out2L, out2R, st, len, fs);
		const double gotItd = itdOf(outL, outR, st, len, fs);
		const double wantIld = ild4k(out2L, out2R, st, len, fs);
		const double gotIld = ild4k(outL, outR, st, len, fs);
		const double wood = 0.0875 / SOUND * (spkAz[spk] * M_PI / 180 + sin(spkAz[spk] * M_PI / 180));
		char what[100];
		snprintf(what, sizeof what, "Width %d: ITD minus the speaker's own, us", (int)width);
		check(what, 1e6 * (gotItd - wantItd), -40, 40);
		snprintf(what, sizeof what, "Width %d: ITD, us (left earlier is positive)", (int)width);
		check(what, 1e6 * gotItd, 50, 2000);
		snprintf(what, sizeof what, "Width %d: Woodworth at %d degrees, us", (int)width, (int)spkAz[spk]);
		info(what, 1e6 * wood);
		snprintf(what, sizeof what, "Width %d: ILD at 4 kHz minus the speaker's own, dB", (int)width);
		check(what, gotIld - wantIld, -1, 1);
		snprintf(what, sizeof what, "Width %d: ILD at 4 kHz, dB (left louder is positive)", (int)width);
		check(what, gotIld, 1, 40);
		double err = 0, ref = 0;
		for (int i = st; i < st + len; i++)
		{
			err += pow(outL[i] - out2L[i], 2) + pow(outR[i] - out2R[i], 2);
			ref += pow(out2L[i], 2) + pow(out2R[i], 2);
		}
		snprintf(what, sizeof what, "Width %d: output against that speaker alone, dB", (int)width);
		check(what, dB(err / ref), -300, -60);
	}
}

static void testMirror(void)
{
	printf("\n[6] swapping the input channels swaps the ears (BROKEN_MIRROR)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 2;
	mix(inL, inR, n, 61);
	/* Hard panned parts as well, so the width path is used. */
	seed(62);
	for (int i = 0; i < n; i++) inL[i] += (float)(0.05 * grand());
	setup(fs, g_set, taps, cOff, 100, 60, 0, 0, 0);
	run(inL, inR, outL, outR, n, 480);
	setup(fs, g_set, taps, cOff, 100, 60, 0, 0, 0);
	run(inR, inL, out2L, out2R, n, 480);
	double err = 0, ref = 0;
	for (int i = 0; i < n; i++)
	{
		err += pow(outL[i] - out2R[i], 2) + pow(outR[i] - out2L[i], 2);
		ref += pow(outL[i], 2) + pow(outR[i], 2);
	}
	check("left of one against right of the other, dB", dB(err / ref), -400, -80);
}

static void testAmbience(void)
{
	printf("\n[7] ambience goes round and above, a mono source does not (BROKEN_NOCOH)\n");
#ifdef BINAURAL_STATS
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 4;
	for (int m = 0; m < 2; m++)
	{
		seed(71);
		pink(inL, n, 0.1);
		if (m == 0) pink(inR, n, 0.1);
		else memcpy(inR, inL, sizeof(float) * n);
		setup(fs, g_set, taps, cOff, 100, 40, 100, 100, 0);
		run(inL, inR, outL, outR, 48000, 480);
		clearStats();
		uint64_t rs = 1;
		runRange(inL, inR, outL, outR, 48000, n, 480, &rs);
		const double *e = g_lib.binaural.feedEnergy;
		double tot = 0;
		for (int s = 0; s < BIN_SPEAKERS; s++) tot += e[s];
		const double around = 100.0 * (e[BIN_SL] + e[BIN_SR] + e[BIN_TL] + e[BIN_TR]) / tot;
		if (m == 0) check("independent noises: energy to sides and above, %", around, 60, 100);
		else check("mono: energy to sides and above, %", around, 0, 5);
	}
	/* The coherence each part is called ambience at, so the thresholds can be
	   seen against what averaging over a few frames reads. */
	for (int m = 0; m < 3; m++)
	{
		const double mixk[3] = { 0.0, 0.5, 0.8 };
		seed(72);
		pink(inL, n, 0.1);
		pink(inR, n, 0.1);
		for (int i = 0; i < n; i++)
		{
			const double c = mixk[m];
			const float s = inL[i];
			inR[i] = (float)(c * s + sqrt(1 - c * c) * inR[i]);
		}
		setup(fs, g_set, taps, cOff, 100, 40, 100, 100, 0);
		run(inL, inR, outL, outR, 48000, 480);
		clearStats();
		uint64_t rs = 1;
		runRange(inL, inR, outL, outR, 48000, n, 480, &rs);
		char what[80];
		snprintf(what, sizeof what, "true correlation %.1f: ambience share of energy, %%", mixk[m]);
		info(what, 100.0 * g_lib.binaural.ambienceEnergy /
			(g_lib.binaural.ambienceEnergy + g_lib.binaural.primaryEnergy));
	}
#else
	check("built without -DBINAURAL_STATS", 0, 1, 1);
#endif
}

static void testClickTrain(void)
{
	printf("\n[8] a click comes out as a click, with nothing before it (BROKEN_NOPAD)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 3, gap = 4801;
	memset(inL, 0, sizeof(float) * n);
	memset(inR, 0, sizeof(float) * n);
	/* Alternately centre, left and a wide stereo pair, over quiet noise so the
	   masks have something to settle on. */
	seed(81);
	for (int i = 0; i < n; i++) { inL[i] = (float)(1e-5 * grand()); inR[i] = (float)(1e-5 * grand()); }
	int clicks = 0;
	for (int t = 9000; t + gap < n - 9000; t += gap, clicks++)
	{
		if (clicks % 3 == 0) { inL[t] += 0.8f; inR[t] += 0.8f; }
		else if (clicks % 3 == 1) inL[t] += 0.8f;
		else { inL[t] += 0.8f; inR[t] -= 0.5f; }
	}
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 0);
	run(inL, inR, outL, outR, n, 480);
	const int lat = BinauralUpmixLatency(&g_lib);
	const int pre = (int)(0.002 * fs);
	double worst = -400;
	int c = 0;
	for (int t = 9000; t + gap < n - 9000; t += gap, c++)
	{
		const int at = t + lat;
		const double eClick = energy(outL, at - pre, at + 1200) + energy(outR, at - pre, at + 1200);
		const double eBefore = energy(outL, at - 2400, at - pre) + energy(outR, at - 2400, at - pre);
		const double r = dB(eBefore / eClick);
		if (r > worst) worst = r;
	}
	check("worst energy 2 to 50 ms before a click, dB re the click", worst, -400, -40);
}

static void testEnergy(void)
{
	printf("\n[9] with plain speakers the parts add back to the input (BROKEN_FOLD)\n");
	const float fs = 48000;
	const int taps = makeIdentity(g_ident);
	const int n = 48000 * 3;
	for (int sig = 0; sig < 3; sig++)
	{
		const char *names[3] = { "independent noise", "stereo mix", "mono sweep" };
		if (sig == 0) { seed(91); white(inL, n, 0.1); white(inR, n, 0.1); }
		else if (sig == 1) mix(inL, inR, n, 92);
		else
		{
			double ph = 0;
			for (int i = 0; i < n; i++)
			{
				const double f = 50.0 * pow(400.0, (double)i / n);
				ph += 2 * M_PI * f / fs;
				inL[i] = inR[i] = (float)(0.2 * sin(ph));
			}
		}
		setup(fs, g_ident, taps, 0, 100, 0, 0, 0, 0);
		run(inL, inR, outL, outR, n, 480);
		const int lat = BinauralUpmixLatency(&g_lib);
		double err = 0, ref = 0;
		for (int i = 24000; i < n; i++)
		{
			err += pow(outL[i] - inL[i - lat], 2) + pow(outR[i] - inR[i - lat], 2);
			ref += pow(inL[i - lat], 2) + pow(inR[i - lat], 2);
		}
		char what[100];
		snprintf(what, sizeof what, "%s, front only: output against the input, dB", names[sig]);
		check(what, dB(err / ref), -400, -60);
		setup(fs, g_ident, taps, 0, 100, 0, 60, 40, 0);
		run(inL, inR, outL, outR, n, 480);
		const double lv = dB((energy(outL, 24000, n) + energy(outR, 24000, n)) /
			(energy(inL, 24000 - lat, n - lat) + energy(inR, 24000 - lat, n - lat)));
		snprintf(what, sizeof what, "%s, default spread: level change, dB", names[sig]);
		check(what, lv, -0.5, 0.5);
	}
}

static int allFinite(const float *x, int a, int b)
{
	for (int i = a; i < b; i++) if (!isfinite(x[i])) return 0;
	return 1;
}

static void testNaN(void)
{
	printf("\n[10] NaN, Inf, huge and tiny input, and nonsense settings (BROKEN_NOSCRUB)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 3;
	mix(inL, inR, n, 101);
	memcpy(out2L, inL, sizeof(float) * n);
	memcpy(out2R, inR, sizeof(float) * n);
	inL[30000] = NAN;
	for (int i = 40000; i < 40480; i++) inR[i] = INFINITY;
	for (int i = 50000; i < 50100; i++) inL[i] = 1e30f;
	for (int i = 60000; i < 61000; i++) { inL[i] = 1e-40f; inR[i] = -1e-41f; }
	const int T = 61000;
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 100);
	run(inL, inR, outL, outR, n, 480);
	check("every output sample finite", allFinite(outL, 0, n) && allFinite(outR, 0, n), 1, 1);
	/* Back to normal within two FFT lengths: the same level as a clean run. */
	static float refL[MAXS], refR[MAXS];
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 100);
	run(out2L, out2R, refL, refR, n, 480);
	const int a = T + 2 * 2048, b = a + 48000;
	check("level after the bad stretch against a clean run, dB",
		dB((energy(outL, a, b) + energy(outR, a, b)) / (energy(refL, a, b) + energy(refR, a, b))), -1, 1);
	/* Settings nobody would send. */
	BinauralUpmixSetParam(&g_lib, NAN, -5, 1e30f, INFINITY, -INFINITY);
	const BinauralUpmix *bu = &g_lib.binaural;
	check("nonsense settings clamped",
		bu->amountPct == 100 && bu->widthPct == 0 && bu->surroundPct == 100 &&
		bu->heightPct == 100 && bu->roomPct == 0, 1, 1);
	run(out2L, out2R, outL, outR, 48000, 480);
	check("and the output stays finite", allFinite(outL, 0, 48000) && allFinite(outR, 0, 48000), 1, 1);
	/* Sets nobody should send: each refused, the loaded one kept. */
	static float bad[SETMAX];
	int refused = 0;
	memcpy(bad, g_set, sizeof(float) * BIN_SPEAKERS * 2 * taps);
	bad[100] = NAN;
	refused += BinauralUpmixLoadHrir(&g_lib, bad, BIN_SPEAKERS, taps, fs, cOff) < 0;
	bad[100] = 1e30f;
	refused += BinauralUpmixLoadHrir(&g_lib, bad, BIN_SPEAKERS, taps, fs, cOff) < 0;
	memset(bad, 0, sizeof(float) * BIN_SPEAKERS * 2 * taps);
	refused += BinauralUpmixLoadHrir(&g_lib, bad, BIN_SPEAKERS, taps, fs, cOff) < 0;
	refused += BinauralUpmixLoadHrir(&g_lib, g_set, 7, taps, fs, cOff) < 0;
	refused += BinauralUpmixLoadHrir(&g_lib, g_set, BIN_SPEAKERS, 4000, fs, cOff) < 0;
	refused += BinauralUpmixLoadHrir(&g_lib, g_set, BIN_SPEAKERS, taps, NAN, cOff) < 0;
	refused += BinauralUpmixLoadHrir(&g_lib, g_set, BIN_SPEAKERS, taps, fs, taps) < 0;
	refused += BinauralUpmixLoadHrir(&g_lib, 0, BIN_SPEAKERS, taps, fs, cOff) < 0;
	check("bad sets refused, of 8", refused, 8, 8);
	check("and the good one still loaded", bu->hrir && memcmp(bu->hrir, g_set, sizeof(float) * taps) == 0, 1, 1);
}

/* High-passed energy and the biggest sample step in a window. */
static void clickStats(const float *y, int a, int b, double *hpE, double *step)
{
	Bq h1, h2; bqHp(&h1, 16000, 48000, 0.5412); bqHp(&h2, 16000, 48000, 1.3066);
	double e = 0, s = 0;
	for (int i = a - 2000; i < b; i++)
	{
		const double v = bqRun(&h2, bqRun(&h1, y[i]));
		if (i < a) continue;
		e += v * v;
		const double d = fabs((double)y[i] - y[i - 1]);
		if (d > s) s = d;
	}
	*hpE = e; *step = s;
}

/* For each change point, the worst HF energy and sample step in the stretch
   it reaches the output against steady stretches before and after. */
static void clickScore(const float *y, const int *at, int count, int lat, const char **names,
	double *worstE, double *worstS)
{
	const int w = 240;   /* 5 ms */
	*worstE = -1e9; *worstS = 0;
	for (int c = 0; c < count; c++)
	{
		const int tc = at[c];
		double refE = 0, refS = 0, e, s;
		for (int k = 0; k < 8; k++)
		{
			clickStats(y, tc - 20 * w + k * w, tc - 19 * w + k * w, &e, &s);
			if (e > refE) refE = e; if (s > refS) refS = s;
			clickStats(y, tc + lat + 4096 + 4800 + k * w, tc + lat + 4096 + 4800 + (k + 1) * w, &e, &s);
			if (e > refE) refE = e; if (s > refS) refS = s;
		}
		double maxE = 0, maxS = 0;
		for (int a = tc; a < tc + lat + 2048 + 2 * w; a += w / 2)
		{
			clickStats(y, a, a + w, &e, &s);
			if (e > maxE) maxE = e; if (s > maxS) maxS = s;
		}
		const double over = dB(maxE / refE), sr = maxS / refS;
		printf("    %-26s HF energy %+6.2f dB, biggest step %.2fx steady\n", names[c], over, sr);
		if (over > *worstE) *worstE = over;
		if (sr > *worstS) *worstS = sr;
	}
}

static void signalForClicks(int n, uint64_t sd)
{
	mix(inL, inR, n, sd);
	for (int i = 0; i < n; i++)
	{
		const float v = (float)(0.1 * sin(2 * M_PI * 2501 * i / 48000.0));
		inL[i] += v; inR[i] += 0.7f * v;
	}
}

static void testParamClicks(void)
{
	printf("\n[11] moving any control does not click (BROKEN_RESET)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int seg = 48000 * 3 / 10;
	const int changes = 11;
	const int n = seg * (changes + 3);
	signalForClicks(n, 111);
	/* amount, width, surround, height, room */
	const float steps[11][5] = {
		{ 0, 40, 60, 40, 25 }, { 100, 40, 60, 40, 25 }, { 100, 0, 60, 40, 25 }, { 100, 100, 60, 40, 25 },
		{ 100, 40, 0, 40, 25 }, { 100, 40, 100, 40, 25 }, { 100, 40, 60, 0, 25 }, { 100, 40, 60, 100, 25 },
		{ 100, 40, 60, 40, 0 }, { 100, 40, 60, 40, 100 }, { 100, 40, 60, 40, 25 },
	};
	const char *names[11] = { "Amount to 0", "Amount to 100", "Width to 0", "Width to 100",
		"Surround to 0", "Surround to 100", "Height to 0", "Height to 100", "Room to 0",
		"Room to 100", "back to defaults" };
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 25);
	uint64_t rs = 1;
	runRange(inL, inR, outL, outR, 0, seg * 2, 480, &rs);
	int at[11];
	for (int c = 0; c < changes; c++)
	{
		const float *p = steps[c];
		BinauralUpmixSetParam(&g_lib, p[0], p[1], p[2], p[3], p[4]);
		at[c] = seg * (2 + c);
		runRange(inL, inR, outL, outR, seg * (2 + c), seg * (3 + c), 480, &rs);
	}
	double e, s;
	clickScore(outL, at, changes, BinauralUpmixLatency(&g_lib), names, &e, &s);
	check("worst HF energy over steady, dB", e, -100, 3.0);
	check("worst sample step against steady, times", s, 0, 4.0);
}

static double worstDip(const float *y, int a, int b, double refRms)
{
	const int w = 48;
	double worst = 0;
	for (int i = a; i + w <= b; i += w / 2)
	{
		const double r = sqrt(energy(y, i, i + w) / w);
		const double d = dB(r * r / (refRms * refRms));
		if (d < worst) worst = d;
	}
	return worst;
}

static void testSetSwap(void)
{
	printf("\n[12] a new head set mid-song: no click, no gap (BROKEN_SWAPGAP)\n");
	const float fs = 48000;
	int cOff, cOff2;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	makeSet(fs, 0.095, g_set2, &cOff2);
	const int seg = 48000 * 3 / 10;
	const int n = seg * 6;
	signalForClicks(n, 121);
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 25);
	uint64_t rs = 1;
	int at[2];
	runRange(inL, inR, outL, outR, 0, seg * 2 + 77, 480, &rs);
	at[0] = seg * 2 + 77;
	BinauralUpmixLoadHrir(&g_lib, g_set2, BIN_SPEAKERS, taps, fs, cOff2);
	runRange(inL, inR, outL, outR, at[0], seg * 3 + 77, 480, &rs);
	at[1] = seg * 3 + 77;
	BinauralUpmixLoadHrir(&g_lib, g_set, BIN_SPEAKERS, taps, fs, cOff);
	runRange(inL, inR, outL, outR, at[1], n, 480, &rs);
	const char *names[2] = { "bigger head", "back again" };
	double e, s;
	clickScore(outL, at, 2, BinauralUpmixLatency(&g_lib), names, &e, &s);
	check("worst HF energy over steady, dB", e, -100, 3.0);
	check("worst sample step against steady, times", s, 0, 4.0);
	const double ref = sqrt(energy(outL, seg, 2 * seg) / seg);
	check("deepest 1 ms dip around the swaps, dB", worstDip(outL, at[0], at[1] + 4096, ref), -15, 0);
}

static void testSwitch(void)
{
	printf("\n[13] switching on and off mid-song fades (BROKEN_NOFADE)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 2;
	signalForClicks(n, 131);
	/* Off before, then on: the wet signal waits for the delay, then fades. */
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 25);
	BinauralUpmixDisable(&g_lib);
	run(inL, inR, outL, outR, 2048, 1);   /* fade out completes, flag clears */
	BinauralUpmixDisable(&g_lib);         /* buffers go */
	check("off and faded: buffers let go", g_lib.binaural.mem == 0, 1, 1);
	BinauralUpmixEnable(&g_lib);
	const int lat = BinauralUpmixLatency(&g_lib);
	int first = -1, full = -1;
	uint64_t rs = 1;
	for (int i = 0; i < 48000; i++)
	{
		runRange(inL, inR, outL, outR, i, i + 1, 1, &rs);
		const float g = g_lib.binaural.wetGain;
		if (first < 0 && g > 0) first = i;
		if (full < 0 && g >= 1) full = i;
	}
	check("switch on: samples before the fade starts minus the delay", first - lat, 0, 0);
	check("switch on: fade length, ms", 1000.0 * (full - first + 1) / fs, 9, 11);
	const double ref = sqrt(energy(outL, 30000, 40000) / 10000);
	check("switch on: deepest 1 ms dip, dB", worstDip(outL, 0, 48000, ref), -15, 0);
	BinauralUpmixDisable(&g_lib);
	int off = -1;
	for (int i = 48000; i < n; i++)
	{
		runRange(inL, inR, outL, outR, i, i + 1, 1, &rs);
		if (off < 0 && !g_lib.binauralEnabled) off = i;
	}
	check("switch off: fade length, ms", 1000.0 * (off - 48000 + 1) / fs, 9, 11);
	check("switch off: deepest 1 ms dip, dB", worstDip(outL, 47000, 50000, ref), -15, 0);
	double s0 = 0, s1 = 0;
	for (int i = 30000; i < 40000; i++) { const double d = fabs(outL[i] - outL[i - 1]); if (d > s0) s0 = d; }
	for (int i = 47900; i < 49000; i++) { const double d = fabs(outL[i] - outL[i - 1]); if (d > s1) s1 = d; }
	check("switch off: biggest step against steady, times", s1 / s0, 0, 4);
	BinauralUpmixDisable(&g_lib);
	check("and the buffers are let go, the set kept",
		g_lib.binaural.mem == 0 && g_lib.binaural.spec == 0 && g_lib.binaural.hrir != 0, 1, 1);
}

static void testRates(void)
{
	printf("\n[14] rate changes: delay follows, a set at the wrong rate is not played\n");
	printf("     (BROKEN_NOREFRESH, BROKEN_RATECHECK)\n");
	int cOff;
	int taps = makeSet(44100, 0.0875, g_set, &cOff);
	const int n = 48000;
	mix(inL, inR, n, 141);
	setup(44100, g_set, taps, cOff, 100, 40, 60, 40, 25);
	run(inL, inR, outL, outR, n, 480);
	const float rates[3] = { 48000, 96000, 48000 };
	for (int r = 0; r < 3; r++)
	{
		const float fs = rates[r];
		g_lib.fs = fs;
		BinauralUpmixRefresh(&g_lib);
		/* The set is still the old rate's: fades to dry and waits. */
		run(inL, inR, outL, outR, n, 480);
		int bad = 0;
		for (int i = n / 2; i < n; i++) if (outL[i] != inL[i] || outR[i] != inR[i]) bad++;
		char what[100];
		snprintf(what, sizeof what, "%.0f kHz, old set: samples that are not the input", fs / 1000);
		check(what, bad, 0, 0);
		taps = makeSet(fs, 0.0875, g_set, &cOff);
		BinauralUpmixLoadHrir(&g_lib, g_set, BIN_SPEAKERS, taps, fs, cOff);
		run(inL, inR, outL, outR, n, 480);
		const int want = (fs < 64000 ? 1023 : 2047) + cOff;
		snprintf(what, sizeof what, "%.0f kHz, its own set: reported delay minus expected", fs / 1000);
		check(what, BinauralUpmixLatency(&g_lib) - want, 0, 0);
		snprintf(what, sizeof what, "%.0f kHz, its own set: output finite and moving", fs / 1000);
		check(what, allFinite(outL, 0, n) && energy(outL, n / 2, n) > 0, 1, 1);
	}
	check("guard bytes touched", liveOverruns(), 0, 0);
}

static void testRoom(void)
{
	printf("\n[15] the room: nothing at 0, a tail that dies away, NaN cleared (BROKEN_FDNGAIN)\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 3;
	mix(inL, inR, n, 151);
	/* Room 0 against a run whose room was filled with rubbish first: if the
	   room reaches the output at 0, they differ. */
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 0);
	run(inL, inR, outL, outR, n, 480);
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 0);
	{
		BinauralUpmix *b = &g_lib.binaural;
		int total = 0;
		for (int i = 0; i < BINAURAL_FDN_LINES; i++) total += b->fdnLen[i];
		seed(152);
		for (int i = 0; i < total; i++) b->fdnBuf[i] = (float)grand();
	}
	run(inL, inR, out2L, out2R, n, 480);
	int bad = 0;
	for (int i = 0; i < n; i++) if (outL[i] != out2L[i] || outR[i] != out2R[i]) bad++;
	check("Room 0: samples changed by what is in the room", bad, 0, 0);
	/* A burst, then silence: how long until the tail is 60 dB down. */
	memset(inL, 0, sizeof(float) * n);
	memset(inR, 0, sizeof(float) * n);
	seed(153);
	for (int i = 0; i < 24000; i++) { inL[i] = (float)(0.1 * grand()); inR[i] = (float)(0.1 * grand()); }
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 100);
	run(inL, inR, outL, outR, n, 480);
	const int lat = BinauralUpmixLatency(&g_lib);
	const double burst = energy(outL, 12000 + lat, 24000 + lat) / 12000;
	int below = -1;
	for (int i = 24000 + lat; i + 480 < n; i += 480)
		if (energy(outL, i, i + 480) / 480 < burst * 1e-6) { below = i; break; }
	check("tail 60 dB under the burst after, s", below < 0 ? 99 : (double)(below - 24000 - lat) / fs, 0, 1.5);
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 100);
	run(inL, inR, outL, outR, 24000, 480);
	g_lib.binaural.fdnLp[3] = NAN;
	g_lib.binaural.fdnBuf[5] = INFINITY;
	run(inL + 24000, inR + 24000, outL, outR, 24000, 480);
	check("NaN in the room: output finite", allFinite(outL, 0, 24000) && allFinite(outR, 0, 24000), 1, 1);
	run(inL + 24000, inR + 24000, outL, outR, 24000, 480);
	check("and the room is clean again", isfinite(g_lib.binaural.fdnLp[3]), 1, 1);
	/* How loud the room is at 100 % against the direct sound, on noise. */
	seed(154);
	white(inL, n, 0.1); white(inR, n, 0.1);
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 0);
	run(inL, inR, outL, outR, n, 480);
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 100);
	run(inL, inR, out2L, out2R, n, 480);
	double d = 0;
	for (int i = 48000; i < n; i++) d += pow(out2L[i] - outL[i], 2);
	info("Room 100: room level re the direct sound, dB", dB(d / energy(outL, 48000, n)));
}

static int cmpd(const void *a, const void *b)
{
	const double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}

static void testSpeed(void)
{
	printf("\n[16] cost at 48 kHz\n");
	const float fs = 48000;
	int cOff;
	const int taps = makeSet(fs, 0.0875, g_set, &cOff);
	const int n = 48000 * 10, blk = 480, nb = n / blk;
	mix(inL, inR, n, 161);
	for (int i = 48000 * 6; i < n; i++) inL[i] = inR[i] = 0;
	setup(fs, g_set, taps, cOff, 100, 40, 60, 40, 25);
	static double tb[48000 * 10 / 480];
	for (int b = 0; b < nb; b++)
	{
		memcpy(bufL, inL + b * blk, sizeof(float) * blk);
		memcpy(bufR, inR + b * blk, sizeof(float) * blk);
		const double t0 = nowSec();
		BinauralUpmixProcess(&g_lib, blk);
		tb[b] = nowSec() - t0;
	}
	double total = 0;
	for (int b = 0; b < nb; b++) total += tb[b];
	static double music[48000 * 10 / 480], silent[48000 * 10 / 480];
	int nm = 0, ns = 0;
	for (int b = 48000 / blk; b < 48000 * 6 / blk; b++) music[nm++] = tb[b];
	for (int b = 48000 * 7 / blk; b < nb; b++) silent[ns++] = tb[b];
	qsort(music, nm, sizeof(double), cmpd);
	qsort(silent, ns, sizeof(double), cmpd);
	info("ns per stereo sample", 1e9 * total / n);
	info("median music block, us", 1e6 * music[nm / 2]);
	info("median silent block, us", 1e6 * silent[ns / 2]);
	info("worst silent block, us", 1e6 * silent[ns - 1]);
	check("share of one core at 48 kHz, %", 100.0 * total / (n / fs), 0, 4.0);
	check("silent tail median against music median, times", silent[ns / 2] / music[nm / 2], 0, 1.5);
	size_t bytes = 0;
	for (int i = 0; i < MAXALLOC; i++) if (g_blk[i].p) bytes += g_blk[i].n;
	info("memory held while on, KB", bytes / 1024.0);
}

int main(int argc, char **argv)
{
	welchInit();
	const int only = argc > 1 ? atoi(argv[1]) : 0;
	/* Nothing is allocated until it is switched on. */
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = 48000;
	BinauralUpmixSetParam(&g_lib, 100, 40, 60, 40, 25);
	if (!only)
	{
		printf("\n[0] off costs nothing\n");
		check("blocks held before switch-on", liveBlocks(), 0, 0);
	}
	if (!only || only == 1) testLatency();
	if (!only || only == 2) testDryExact();
	if (!only || only == 3) testBlocks();
	if (!only || only == 4) testCentre();
	if (!only || only == 5) testHardLeft();
	if (!only || only == 6) testMirror();
	if (!only || only == 7) testAmbience();
	if (!only || only == 8) testClickTrain();
	if (!only || only == 9) testEnergy();
	if (!only || only == 10) testNaN();
	if (!only || only == 11) testParamClicks();
	if (!only || only == 12) testSetSwap();
	if (!only || only == 13) testSwitch();
	if (!only || only == 14) testRates();
	if (!only || only == 15) testRoom();
	if (!only || only == 16) testSpeed();
	BinauralUpmixFree(&g_lib);
	check("guard bytes touched over the whole run", liveOverruns(), 0, 0);
	printf("\n%s (%d failure%s)\n", failures ? "FAILURES PRESENT" : "all checks passed",
		failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
