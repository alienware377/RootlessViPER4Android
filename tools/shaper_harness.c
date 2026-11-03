// Does the spectral shaper turn down what sticks out, and nothing else?
//
// Built for x86_64 and run on the emulator:
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       -Wl,--wrap=calloc -Wl,--wrap=free \
//       tools/shaper_harness.c $J/Effects/spectralShaper.c \
//       $J/Effects/eel2/numericSys/codelet.c tools/lockstub.c -lm -o shaper
//   adb -s emulator-5554 push shaper /data/local/tmp/ && \
//   adb -s emulator-5554 shell /data/local/tmp/shaper
//
// Every check names the deliberately broken build it has to FAIL on, so each
// one is known to be able to see the fault it is there for. Build with one of
// -DBROKEN_NOSCALE, -DBROKEN_LATENCY, -DBROKEN_FIFO, -DBROKEN_NOCLIP,
// -DBROKEN_SIGN, -DBROKEN_NOSMOOTH, -DBROKEN_NOTONAL, -DBROKEN_BROADBAND,
// -DBROKEN_RESET, -DBROKEN_NOFADE, -DBROKEN_NOSCRUB, -DBROKEN_NOREFRESH,
// -DBROKEN_NOPERSIST, -DBROKEN_MSMIX or -DBROKEN_MSSWAP
// (the defines reach the engine file too) and the named check fails. The FFT
// check has its own: -DBROKEN_FHT_NOSCALE.
//
// Measurement notes. Spectra are Welch averages with a 4096-point Hann window
// at half overlap, computed with the engine's own FHT. Output is always
// compared with the input over the same stretch of music, shifted by the
// reported delay, so a level difference is the shaper and not the material.
// Noise is generated here (a Gaussian from xorshift, pink through the
// widely published Kellet filter) and seeded, so every run is identical.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "jdsp_header.h"
#include "Effects/eel2/numericSys/codelet.h"

#ifndef SHAPER_A6_LO
#define SHAPER_A6_LO 4.5
#endif
/* Set from measurement. With the tone gate off the swing is 0.70 dB and with
   it on 0.79: what is left is the level estimate's own spread on noise, the
   same order as the noise's own level in a 50 ms window (the bandpass ratio
   below reads 1.4 dB). Pumping proper is the 60 Hz burst case, held to 0.5. */
#ifndef SHAPER_STEADY_SWING
#define SHAPER_STEADY_SWING 1.0
#endif

#define MAXS (48000 * 12)
#define MAXBLK_SAMPLES 4096

static JamesDSPLib g_lib;
static float bufL[MAXBLK_SAMPLES], bufR[MAXBLK_SAMPLES];
static float inL[MAXS], inR[MAXS], outL[MAXS], outR[MAXS];
static float out2L[MAXS], out2R[MAXS];
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

/* ---------------- helpers ---------------- */
static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-58s %10.4f  [%8.3f..%8.3f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static void info(const char *what, double got)
{
	printf("  %-58s %10.4f  (reported)\n", what, got);
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

/* RBJ cookbook biquads, in double. */
typedef struct { double b0, b1, b2, a1, a2, z1, z2; } Bq;
static void bqPeak(Bq *q, double f0, double fs, double db, double Q)
{
	const double A = pow(10.0, db / 40.0), w0 = 2 * M_PI * f0 / fs, al = sin(w0) / (2 * Q);
	const double a0 = 1 + al / A;
	q->b0 = (1 + al * A) / a0; q->b1 = -2 * cos(w0) / a0; q->b2 = (1 - al * A) / a0;
	q->a1 = -2 * cos(w0) / a0; q->a2 = (1 - al / A) / a0; q->z1 = q->z2 = 0;
}
static void bqHp(Bq *q, double f0, double fs, double Q)
{
	const double w0 = 2 * M_PI * f0 / fs, al = sin(w0) / (2 * Q), c = cos(w0), a0 = 1 + al;
	q->b0 = (1 + c) / 2 / a0; q->b1 = -(1 + c) / a0; q->b2 = (1 + c) / 2 / a0;
	q->a1 = -2 * c / a0; q->a2 = (1 - al) / a0; q->z1 = q->z2 = 0;
}
static void bqBp(Bq *q, double f0, double fs, double Q)
{
	const double w0 = 2 * M_PI * f0 / fs, al = sin(w0) / (2 * Q), a0 = 1 + al;
	q->b0 = al / a0; q->b1 = 0; q->b2 = -al / a0;
	q->a1 = -2 * cos(w0) / a0; q->a2 = (1 - al) / a0; q->z1 = q->z2 = 0;
}
static double bqRun(Bq *q, double x)
{
	const double y = q->b0 * x + q->z1;
	q->z1 = q->b1 * x - q->a1 * y + q->z2;
	q->z2 = q->b2 * x - q->a2 * y;
	return y;
}
static void bqApply(Bq q, float *x, int n)
{
	for (int i = 0; i < n; i++) x[i] = (float)bqRun(&q, x[i]);
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
/* Mean power per bin over [start, start + len), bins 0..WN/2. */
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
	double s = 0;
	for (int k = k1; k <= k2; k++) s += P[k];
	return s;
}
static double dB(double r) { return 10.0 * log10(r > 1e-300 ? r : 1e-300); }

/* ---------------- running the effect ---------------- */
static void setupAt(float fs, float amount, float sens, float lo, float hi,
	float speed, int focus, int tones, int listen, int ms)
{
	SpectralShaperFree(&g_lib);
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = fs;
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
	SpectralShaperSetParam(&g_lib, amount, sens, lo, hi, speed, focus, tones, listen, ms);
	SpectralShaperEnable(&g_lib);
}
#ifndef DEFAULT_TONES
#define DEFAULT_TONES 0
#endif
static void setupDefault(float fs) { setupAt(fs, 6, 60, 2000, 10000, 50, SHAPER_FOCUS_BALANCED, DEFAULT_TONES, 0, MS_MODE_STEREO); }

/* Like the chain: the effect only runs while its flag is set. blk 0 is a
   random mix of block sizes. */
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
		if (g_lib.shaperEnabled) SpectralShaperProcess(&g_lib, (size_t)b);
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

/* Reduction of the output against the delayed input over the band, in dB. */
static double bandChange(const float *in, const float *out, int start, int len, int lat,
	double f1, double f2, double fs)
{
	static double Pi[WN / 2 + 1], Po[WN / 2 + 1];
	welch(in, start, len, Pi);
	welch(out, start + lat, len, Po);
	return dB(bandPow(Po, f1, f2, fs) / bandPow(Pi, f1, f2, fs));
}

static const double thirds[] = { 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500 };
#define NTHIRDS 10

/* ====================================================================== */

static void testFht(void)
{
	printf("\n[1] FHT round trip (DFT1024 twice is N times the input)\n");
	static float x[1024], y[1024], sinT[1024];
	static unsigned rev[1024];
	for (int i = 0; i < 1024; i++)
	{
		unsigned r = 0, v = (unsigned)i;
		for (int b = 0; b < 10; b++) { r = (r << 1) | (v & 1u); v >>= 1; }
		rev[i] = r;
		sinT[i] = (float)sin(2.0 * M_PI * i / 1024);
	}
	seed(1);
	for (int i = 0; i < 1024; i++) x[i] = (float)grand();
	for (int i = 0; i < 1024; i++) y[rev[i]] = x[i];
	DFT1024(y, sinT);
	static float z[1024];
	for (int i = 0; i < 1024; i++) z[rev[i]] = y[i];
	DFT1024(z, sinT);
	double err = 0, ref = 0;
#ifdef BROKEN_FHT_NOSCALE
	const double sc = 1.0;
#else
	const double sc = 1.0 / 1024.0;
#endif
	for (int i = 0; i < 1024; i++)
	{
		const double d = z[i] * sc - x[i];
		err += d * d; ref += (double)x[i] * x[i];
	}
	check("round trip error, dB re signal (BROKEN_FHT_NOSCALE)", dB(err / ref), -400, -110);
}

static int findLag(const float *in, const float *out, int start, int len, int maxLag)
{
	int best = 0; double bv = -1e300;
	for (int lag = 0; lag <= maxLag; lag++)
	{
		double s = 0;
		for (int i = start; i < start + len; i++) s += (double)in[i] * out[i + lag];
		if (s > bv) { bv = s; best = lag; }
	}
	return best;
}

static void testLatency(void)
{
	printf("\n[2] delay equals SpectralShaperLatency() (BROKEN_LATENCY, BROKEN_NOSCALE)\n");
	const float rates[3] = { 44100.0f, 48000.0f, 96000.0f };
	for (int r = 0; r < 3; r++)
	{
		const float fs = rates[r];
		const int n = (int)fs * 2;
		seed(2 + r);
		white(inL, n, 0.1); white(inR, n, 0.1);
		/* Amount 0: whatever comes out is the input, delayed. */
		setupAt(fs, 0, 60, 2000, 10000, 50, SHAPER_FOCUS_BALANCED, 0, 0, MS_MODE_STEREO);
		run(inL, inR, outL, outR, n, 480);
		const int rep = SpectralShaperLatency(&g_lib);
		const int lag = findLag(inL, outL, (int)fs / 2, 8192, 5000);
		char what[96];
		snprintf(what, sizeof what, "%.1f kHz: measured lag minus reported (%d)", fs / 1000, rep);
		check(what, lag - rep, 0, 0);
		snprintf(what, sizeof what, "%.1f kHz: delay in ms", fs / 1000);
		check(what, 1000.0 * lag / fs, 20.0, 24.0);
		double e = 0, s = 0;
		for (int i = (int)fs / 2; i < n - lag; i++)
		{
			const double d = (double)outL[i + lag] - inL[i];
			e += d * d; s += (double)inL[i] * inL[i];
		}
		snprintf(what, sizeof what, "%.1f kHz: Amount 0 residual, dB re signal", fs / 1000);
		check(what, dB(e / s), -400, -110);
	}
}

static void makePeaky(float fs, int n, double f0, double Q, double rms, uint64_t sd)
{
	seed(sd);
	pink(inL, n, rms); pink(inR, n, rms);
	if (f0 > 0)
	{
		Bq q; bqPeak(&q, f0, fs, 12.0, Q);
		bqApply(q, inL, n); bqApply(q, inR, n);
	}
}

static void testBlocks(void)
{
	printf("\n[3] output does not depend on block size (BROKEN_FIFO)\n");
	const float fs = 48000;
	const int n = 48000 * 2;
	makePeaky(fs, n, 5000, 4, 0.05, 3);
	setupDefault(fs);
	run(inL, inR, out2L, out2R, n, 4096);
	const int sizes[7] = { 1, 7, 64, 441, 480, 4096, 0 };
	for (int s = 0; s < 7; s++)
	{
		setupDefault(fs);
		run(inL, inR, outL, outR, n, sizes[s]);
		int diff = 0;
		for (int i = 0; i < n; i++)
			if (memcmp(&outL[i], &out2L[i], 4) || memcmp(&outR[i], &out2R[i], 4)) diff++;
		char what[96];
		if (sizes[s]) snprintf(what, sizeof what, "blocks of %d: samples differing from blocks of 4096", sizes[s]);
		else snprintf(what, sizeof what, "random block sizes: samples differing");
		check(what, diff, 0, 0);
	}
}

static void testResonance(void)
{
	printf("\n[4] a steady peak is turned down by the Amount (BROKEN_NOCLIP, BROKEN_SIGN)\n");
	const float fs = 48000;
	const int n = 48000 * 10, st = 48000 * 2, len = 48000 * 8;
	const double f0s[3] = { 3000, 5000, 7000 }, Qs[3] = { 8, 4, 2 };
	const float amounts[3] = { 3, 6, 12 };
	for (int p = 0; p < 3; p++)
	{
		makePeaky(fs, n, f0s[p], Qs[p], 0.05, 40 + p);
		for (int a = 0; a < 3; a++)
		{
			setupAt(fs, amounts[a], 60, 2000, 10000, 50, SHAPER_FOCUS_BALANCED, 0, 0, MS_MODE_STEREO);
			run(inL, inR, outL, outR, n, 480);
			const int lat = SpectralShaperLatency(&g_lib);
			const double binHz = fs / WN;
			const double red = -bandChange(inL, outL, st, len - lat, lat,
				f0s[p] - 1.5 * binHz, f0s[p] + 1.5 * binHz, fs);
			char what[96];
			snprintf(what, sizeof what, "%.0f Hz Q%.0f, Amount %.0f: cut at the peak, dB", f0s[p], Qs[p], amounts[a]);
			if (amounts[a] == 3) check(what, red, 2.5, 3.5);
			else if (amounts[a] == 6) check(what, red, SHAPER_A6_LO, 7.5);
			else check(what, red, 4.0, 12.0);
			if (amounts[a] == 6)
			{
				double worst = 0;
				for (int b = 0; b < NTHIRDS; b++)
				{
					const double f1 = thirds[b] * pow(2, -1.0 / 6), f2 = thirds[b] * pow(2, 1.0 / 6);
					if (f2 > f0s[p] / 2 && f1 < f0s[p] * 2) continue;
					const double c = fabs(bandChange(inL, outL, st, len - lat, lat, f1, f2, fs));
					if (c > worst) worst = c;
				}
				snprintf(what, sizeof what, "%.0f Hz Q%.0f: worst 1/3 octave beyond an octave, dB", f0s[p], Qs[p]);
				check(what, worst, 0, 0.3);
			}
		}
	}
}

static void testNoise(void)
{
	printf("\n[5] plain noise is left alone (BROKEN_NOSMOOTH)\n");
	const float fs = 48000;
	const int n = 48000 * 10, st = 48000 * 1, len = 48000 * 9;
	for (int kind = 0; kind < 2; kind++)
	{
		seed(50 + kind);
		if (kind == 0) { white(inL, n, 0.1); white(inR, n, 0.1); }
		else { pink(inL, n, 0.1); pink(inR, n, 0.1); }
		setupDefault(fs);
		run(inL, inR, outL, outR, n, 480);
		const int lat = SpectralShaperLatency(&g_lib);
		double worst = 0;
		for (int b = 0; b < NTHIRDS; b++)
		{
			const double f1 = thirds[b] * pow(2, -1.0 / 6), f2 = thirds[b] * pow(2, 1.0 / 6);
			const double c = fabs(bandChange(inL, outL, st, len - lat, lat, f1, f2, fs));
			if (c > worst) worst = c;
		}
		double ei = 0, eo = 0;
		for (int i = st; i < st + len - lat; i++) { ei += (double)inL[i] * inL[i]; eo += (double)outL[i + lat] * outL[i + lat]; }
		check(kind ? "pink: worst 1/3 octave 1.6 to 12.5 kHz, dB" : "white: worst 1/3 octave 1.6 to 12.5 kHz, dB", worst, 0, 0.25);
		check(kind ? "pink: broadband level change, dB" : "white: broadband level change, dB", fabs(dB(eo / ei)), 0, 0.1);
	}
	/* The other two focus settings, on pink noise. */
	for (int focus = 0; focus <= 2; focus += 2)
	{
		setupAt(fs, 6, 60, 2000, 10000, 50, focus, 0, 0, MS_MODE_STEREO);
		run(inL, inR, outL, outR, n, 480);
		const int lat = SpectralShaperLatency(&g_lib);
		double worst = 0;
		for (int b = 0; b < NTHIRDS; b++)
		{
			const double f1 = thirds[b] * pow(2, -1.0 / 6), f2 = thirds[b] * pow(2, 1.0 / 6);
			const double c = fabs(bandChange(inL, outL, st, len - lat, lat, f1, f2, fs));
			if (c > worst) worst = c;
		}
		check(focus ? "pink, broad focus: worst 1/3 octave, dB" : "pink, narrow focus: worst 1/3 octave, dB", worst, 0, 0.5);
	}
}

/* Level of one tone, by the Welch spectrum summed over its main lobe. */
static double toneLevel(const float *x, int start, int len, double f, double fs)
{
	static double P[WN / 2 + 1];
	welch(x, start, len, P);
	const double binHz = fs / WN;
	return bandPow(P, f - 3 * binHz, f + 3 * binHz, fs);
}

static void testTones(void)
{
	printf("\n[6] a clean tone is left alone (BROKEN_NOTONAL)\n");
	const float fs = 48000;
	const int n = 48000 * 6, st = 48000 * 1, len = 48000 * 5;
	const double fr[3] = { 2501, 5017, 8124 };
	for (int t = 0; t < 3; t++)
	{
		for (int withNoise = 0; withNoise < 2; withNoise++)
		{
			const double amp = 0.3;
			seed(60 + t);
			if (withNoise) { pink(inL, n, amp / sqrt(2) * 0.01); memcpy(inR, inL, sizeof(float) * n); }
			else { memset(inL, 0, sizeof(float) * n); memset(inR, 0, sizeof(float) * n); }
			for (int i = 0; i < n; i++)
			{
				const float v = (float)(amp * sin(2 * M_PI * fr[t] * i / fs));
				inL[i] += v; inR[i] += v;
			}
			setupDefault(fs);
			run(inL, inR, outL, outR, n, 480);
			const int lat = SpectralShaperLatency(&g_lib);
			const double ch = dB(toneLevel(outL, st + lat, len - lat, fr[t], fs) / toneLevel(inL, st, len - lat, fr[t], fs));
			char what[96];
			snprintf(what, sizeof what, "%.0f Hz%s: tone level change, dB", fr[t], withNoise ? " over noise" : "");
			check(what, ch, -0.05, 0.05);
			if (!withNoise)
			{
				double e = 0, s = 0;
				for (int i = st; i < st + len - lat; i++)
				{
					const double d = (double)outL[i + lat] - inL[i];
					e += d * d; s += (double)inL[i] * inL[i];
				}
				snprintf(what, sizeof what, "%.0f Hz: everything that is not the tone, dB", fr[t]);
				check(what, dB(e / s), -400, -90);
			}
		}
	}
}

/* Effective gain in a band, per window, from the delayed input to the output. */
static void bandGainTrace(const float *in, const float *out, int start, int len, int lat,
	double f0, double fs, int win, double *mn, double *mx)
{
	Bq a, b; bqBp(&a, f0, fs, 4.3); bqBp(&b, f0, fs, 4.3);
	*mn = 1e9; *mx = -1e9;
	double ei = 0, eo = 0; int c = 0;
	/* Run the filters in from a little earlier so they have settled. */
	for (int i = start - 4800; i < start + len; i++)
	{
		const double yi = bqRun(&a, in[i]), yo = bqRun(&b, out[i + lat]);
		if (i < start) continue;
		ei += yi * yi; eo += yo * yo;
		if (++c == win)
		{
			const double g = dB(eo / ei);
			if (g < *mn) *mn = g;
			if (g > *mx) *mx = g;
			ei = eo = 0; c = 0;
		}
	}
}

static void testPumping(void)
{
	printf("\n[7] nothing pumps (BROKEN_BROADBAND)\n");
	const float fs = 48000;
	const int n = 48000 * 8, st = 48000 * 2, len = 48000 * 5;
	double mn, mx;
	/* 60 Hz bursts at 2 Hz, well outside the range, over pink noise. */
	seed(70);
	pink(inL, n, 0.03); pink(inR, n, 0.03);
	for (int i = 0; i < n; i++)
	{
		const double gate = fmod((double)i / fs, 0.5) < 0.25 ? 1.0 : 0.0;
		const float v = (float)(0.3 * gate * sin(2 * M_PI * 60 * i / fs));
		inL[i] += v; inR[i] += v;
	}
	setupDefault(fs);
	run(inL, inR, outL, outR, n, 480);
	int lat = SpectralShaperLatency(&g_lib);
	bandGainTrace(inL, outL, st, len, lat, 5000, fs, 2400, &mn, &mx);
	check("60 Hz bursts: 5 kHz gain swing over 50 ms windows, dB", mx - mn, 0, 0.5);
	/* A steady resonance should be held down steadily, not wobble. */
	/* A steady resonance should be held down steadily, not wobble. Read from
	   the engine's own gain at 5 kHz, averaged over 50 ms: a bandpass ratio
	   would also swing with how the noise happens to fill the band in each
	   window, since the cut is shaped across it. */
	makePeaky(fs, n, 5000, 4, 0.05, 71);
	setupDefault(fs);
	const int k5 = (int)(5000.0f * g_lib.shaper.N / fs + 0.5f);
	double acc = 0; int cnt = 0;
	mn = 1e9; mx = -1e9;
	uint64_t rs = 7;
	for (int i = 0; i < n; i += 480)
	{
		runRange(inL, inR, outL, outR, i, i + 480, 480, &rs);
		if (i < st) continue;
		acc += g_lib.shaper.gain[k5];
		if (++cnt == 5)
		{
			const double g = acc / cnt;
			if (g < mn) mn = g;
			if (g > mx) mx = g;
			acc = 0; cnt = 0;
		}
	}
	check("steady peak: 5 kHz gain swing over 50 ms windows, dB", mx - mn, 0, SHAPER_STEADY_SWING);
	info("steady peak: 5 kHz gain, middle of the swing, dB", 0.5 * (mx + mn));
	lat = SpectralShaperLatency(&g_lib);
	bandGainTrace(inL, outL, st, len, lat, 5000, fs, 2400, &mn, &mx);
	info("steady peak: bandpass ratio swing (includes the noise), dB", mx - mn);
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

static void testClicks(void)
{
	printf("\n[8] moving any control does not click (BROKEN_RESET)\n");
	const float fs = 48000;
	const int seg = 48000 * 3 / 10;   /* a change every 0.3 s */
	const int changes = 13;
	const int n = seg * (changes + 3);
	makePeaky(fs, n, 5000, 4, 0.01, 80);
	for (int i = 0; i < n; i++)
	{
		const float v = (float)(0.1 * sin(2 * M_PI * 2501 * i / fs));
		inL[i] += v; inR[i] += v;
	}
	/* amount, sens, lo, hi, speed, focus, tones, listen, ms */
	const float steps[13][9] = {
		{ 0, 60, 2000, 10000, 50, 1, 0, 0, 0 },
		{ 12, 60, 2000, 10000, 50, 1, 0, 0, 0 },
		{ 12, 100, 2000, 10000, 50, 1, 0, 0, 0 },
		{ 12, 0, 2000, 10000, 50, 1, 0, 0, 0 },
		{ 12, 60, 4000, 10000, 50, 1, 0, 0, 0 },
		{ 12, 60, 4000, 6000, 50, 1, 0, 0, 0 },
		{ 12, 60, 2000, 10000, 100, 1, 0, 0, 0 },
		{ 12, 60, 2000, 10000, 0, 2, 0, 0, 0 },
		{ 12, 60, 2000, 10000, 50, 0, 1, 0, 0 },
		{ 12, 60, 2000, 10000, 50, 1, 1, 1, 0 },
		{ 12, 60, 2000, 10000, 50, 1, 0, 0, 1 },
		{ 12, 60, 2000, 10000, 50, 1, 0, 0, 2 },
		{ 6, 60, 2000, 10000, 50, 1, 0, 0, 0 },
	};
	const char *names[13] = { "Amount to 0", "Amount to 12", "Sensitivity to 100", "Sensitivity to 0",
		"lowest to 4 kHz", "highest to 6 kHz", "Speed to 100", "Speed 0, broad focus", "narrow, catch tones",
		"listen to removed", "centre only", "edges only", "back to defaults" };
	setupDefault(fs);
	uint64_t rs = 1;
	runRange(inL, inR, outL, outR, 0, seg * 2, 480, &rs);
	for (int c = 0; c < changes; c++)
	{
		const float *p = steps[c];
		SpectralShaperSetParam(&g_lib, p[0], p[1], p[2], p[3], p[4], (int)p[5], (int)p[6], (int)p[7], (int)p[8]);
		runRange(inL, inR, outL, outR, seg * (2 + c), seg * (3 + c), 480, &rs);
	}
	const int lat = SpectralShaperLatency(&g_lib);
	double worstE = -1e9, worstS = 0;
	int worstAt = -1;
	for (int c = 0; c < changes; c++)
	{
		const int tc = seg * (2 + c);
		/* Steady stretches just before the change shows up, and well after. */
		const int w = 240;   /* 5 ms */
		double refE = 0, refS = 0, e, s;
		for (int k = 0; k < 8; k++)
		{
			clickStats(outL, tc - 20 * w + k * w, tc - 19 * w + k * w, &e, &s);
			if (e > refE) refE = e; if (s > refS) refS = s;
			clickStats(outL, tc + lat + 2048 + 4800 + k * w, tc + lat + 2048 + 4800 + (k + 1) * w, &e, &s);
			if (e > refE) refE = e; if (s > refS) refS = s;
		}
		double maxE = 0, maxS = 0;
		for (int a = tc; a < tc + lat + 1024 + 2 * w; a += w / 2)
		{
			clickStats(outL, a, a + w, &e, &s);
			if (e > maxE) maxE = e; if (s > maxS) maxS = s;
		}
		const double over = dB(maxE / refE);
		const double sr = maxS / refS;
		printf("    %-22s HF energy %+6.2f dB, biggest step %.2fx steady\n", names[c], over, sr);
		if (over > worstE) { worstE = over; worstAt = c; }
		if (sr > worstS) worstS = sr;
	}
	char what[96];
	snprintf(what, sizeof what, "worst HF energy over steady (at '%s'), dB", worstAt >= 0 ? names[worstAt] : "?");
	check(what, worstE, -100, 3.0);
	check("worst sample step against steady, times", worstS, 0, 4.0);
}

static void testSwitch(void)
{
	printf("\n[9] switching on and off mid-song (BROKEN_NOFADE)\n");
	const float fs = 48000;
	const int n = 48000 * 3;
	seed(90);
	pink(inL, n, 0.05); pink(inR, n, 0.05);
	for (int i = 0; i < n; i++)
	{
		const float v = (float)(0.2 * sin(2 * M_PI * 1000 * i / fs));
		inL[i] += v; inR[i] += v;
	}
	/* Off for a second (dry), then on mid-signal, then off again. */
	SpectralShaperFree(&g_lib);
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = fs; g_lib.tmpBuffer[0] = bufL; g_lib.tmpBuffer[1] = bufR;
	SpectralShaperSetParam(&g_lib, 0, 60, 2000, 10000, 50, 1, 0, 0, 0);
	uint64_t rs = 2;
	const int tOn = 48000, tOff = 48000 * 2;
	runRange(inL, inR, outL, outR, 0, tOn, 480, &rs);
	SpectralShaperEnable(&g_lib);
	runRange(inL, inR, outL, outR, tOn, tOff, 480, &rs);
	const int lat = SpectralShaperLatency(&g_lib);
	SpectralShaperDisable(&g_lib);
	int flagClearedAt = -1;
	for (int i = tOff; i < n; i += 480)
	{
		runRange(inL, inR, outL, outR, i, i + 480 < n ? i + 480 : n, 480, &rs);
		if (flagClearedAt < 0 && !g_lib.shaperEnabled) flagClearedAt = i + 480;
	}
	/* Worst 1 ms window against the steady level. */
	double steady = 0;
	for (int i = 20000; i < 40000; i++) steady += (double)outL[i] * outL[i];
	steady /= 20000;
	double worst = 0;
	for (int a = tOn - 4800; a < n - 48; a += 12)
	{
		double e = 0;
		for (int i = a; i < a + 48; i++) e += (double)outL[i] * outL[i];
		const double r = dB(e / 48 / steady);
		if (r < worst) worst = r;
	}
	check("quietest 1 ms around both switches, dB re steady", worst, -40, 10);
	/* How long the switch-on takes: from the first sample that is not dry to
	   the first that is the delayed input (Amount 0, so wet is the input). */
	int first = -1, last = -1;
	for (int i = tOn; i < tOff; i++)
	{
		const int dry = outL[i] == inL[i];
		const double wetErr = fabs((double)outL[i] - inL[i - lat]);
		if (first < 0 && !dry) first = i;
		if (first >= 0 && wetErr < 1e-5 && last < 0)
		{
			/* Must stay wet from here. */
			int stays = 1;
			for (int j = i; j < i + 480; j++) if (fabs((double)outL[j] - inL[j - lat]) > 1e-5) stays = 0;
			if (stays) last = i;
		}
	}
	check("switch-on waits for the delay to fill, samples", first - tOn, lat, lat + 1);
	check("switch-on fade length, ms", 1000.0 * (last - first) / fs, 9.0, 11.0);
	double steadyStep = 0;
	for (int i = 20001; i < 40000; i++) { const double d = fabs((double)outL[i] - outL[i - 1]); if (d > steadyStep) steadyStep = d; }
	double offStep = 0;
	for (int i = tOff; i < tOff + 4800; i++) { const double d = fabs((double)outL[i] - outL[i - 1]); if (d > offStep) offStep = d; }
	check("switch-off biggest step against steady, times", offStep / steadyStep, 0, 1.5);
	check("switch-off: flag cleared after, ms", 1000.0 * (flagClearedAt - tOff) / fs, 9.0, 21.0);
}

static void testNaN(void)
{
	printf("\n[10] NaN, Inf and absurd values (BROKEN_NOSCRUB)\n");
	const float fs = 48000;
	const int n = 48000 * 3;
	makePeaky(fs, n, 5000, 4, 0.05, 100);
	const int tNan = 48000, tInf = 48000 + 5000, tBig = 48000 + 9000;
	inL[tNan] = NAN;
	for (int i = tInf; i < tInf + 480; i++) { inL[i] = INFINITY; inR[i] = -INFINITY; }
	for (int i = tBig; i < tBig + 100; i++) { inL[i] = 1e30f; inR[i] = -1e30f; }
	setupDefault(fs);
	run(inL, inR, outL, outR, n, 480);
	int bad = 0;
	for (int i = 0; i < n; i++) if (!isfinite(outL[i]) || !isfinite(outR[i])) bad++;
	check("non-finite output samples", bad, 0, 0);
	const int lat = SpectralShaperLatency(&g_lib);
	/* Normal again within 2N of the last bad sample. */
	const int from = tBig + 100 + 2 * 1024;
	double ei = 0, eo = 0;
	for (int i = from; i < from + 4800; i++) { ei += (double)inL[i - lat] * inL[i - lat]; eo += (double)outL[i] * outL[i]; }
	check("level 2N after the last bad sample, dB re input", dB(eo / ei), -1.5, 0.5);
	/* What any app can send through the root HAL. */
	const float garbage[6][5] = {
		{ NAN, NAN, NAN, NAN, NAN },
		{ -50, -50, -50, -50, -50 },
		{ 1e30f, 1e30f, 1e30f, 1e30f, 1e30f },
		{ 6, 60, 9000, 2000, 50 },
		{ INFINITY, -INFINITY, INFINITY, -INFINITY, INFINITY },
		{ 6, 60, 2000, 10000, 50 },
	};
	bad = 0;
	uint64_t rs = 3;
	for (int g = 0; g < 6; g++)
	{
		SpectralShaperSetParam(&g_lib, garbage[g][0], garbage[g][1], garbage[g][2], garbage[g][3], garbage[g][4],
			g == 0 ? -7 : 99, g, -3, g == 2 ? 77 : -1);
		runRange(inL, inR, outL, outR, 0, 48000, 480, &rs);
		for (int i = 0; i < 48000; i++) if (!isfinite(outL[i]) || !isfinite(outR[i])) bad++;
	}
	check("non-finite output after parameter fuzz", bad, 0, 0);
	SpectralShaper *s = &g_lib.shaper;
	check("fuzzed range stays ordered (hi >= 1.5 lo)", s->hiHz - 1.5f * s->loHz, 0, 1e9);
}

static void testListen(void)
{
	printf("\n[11] shaped plus what was removed is the input\n");
	const float fs = 48000;
	const int n = 48000 * 4;
	makePeaky(fs, n, 4000, 4, 0.05, 110);
	setupDefault(fs);
	run(inL, inR, outL, outR, n, 480);
	setupAt(fs, 6, 60, 2000, 10000, 50, SHAPER_FOCUS_BALANCED, 0, 1, MS_MODE_STEREO);
	run(inL, inR, out2L, out2R, n, 480);
	const int lat = SpectralShaperLatency(&g_lib);
	double e = 0, s = 0, d2 = 0;
	for (int i = 48000; i < n; i++)
	{
		const double sum = (double)outL[i] + out2L[i];
		const double d = sum - inL[i - lat];
		e += d * d; s += (double)inL[i - lat] * inL[i - lat];
		d2 += (double)out2L[i] * out2L[i];
	}
	check("shaped + removed against the delayed input, dB", dB(e / s), -400, -100);
	info("level of what was removed, dB re input", dB(d2 / s));
}

static void testRates(void)
{
	printf("\n[12] sample rate changes (BROKEN_NOREFRESH)\n");
	const float rates[4] = { 44100, 48000, 96000, 48000 };
	const int expect[4] = { 1023, 1023, 2047, 1023 };
	setupDefault(44100);
	uint64_t rs = 4;
	seed(120);
	white(inL, 96000, 0.1); white(inR, 96000, 0.1);
	for (int r = 0; r < 4; r++)
	{
		g_lib.fs = rates[r];
		SpectralShaperRefresh(&g_lib);
		runRange(inL, inR, outL, outR, 0, 96000, 0, &rs);
		char what[96];
		snprintf(what, sizeof what, "%.1f kHz: reported delay, samples", rates[r] / 1000);
		check(what, SpectralShaperLatency(&g_lib), expect[r], expect[r]);
	}
	int bad = 0;
	for (int i = 0; i < 96000; i++) if (!isfinite(outL[i])) bad++;
	check("non-finite samples after the changes", bad, 0, 0);
	check("guard bytes overwritten", liveOverruns(), 0, 0);
	SpectralShaperFree(&g_lib);
	check("guard bytes overwritten, after free", liveOverruns(), 0, 0);
}

static double nowSec(void)
{
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return t.tv_sec + t.tv_nsec * 1e-9;
}
static int cmpd(const void *a, const void *b)
{
	const double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}

/* Subnormal floats in the shaper's arrays: the actual fault the timing
   comparison below stands in for. Read straight from the state, so it does not
   depend on how busy the machine is. */
static int subnormalsIn(const float *a, int n)
{
	int c = 0;
	if (!a) return 0;
	for (int i = 0; i < n; i++)
	{
		const float v = fabsf(a[i]);
		if (v != 0.0f && v < 1.17549435e-38f) c++;
	}
	return c;
}
static int shaperSubnormals(const SpectralShaper *s)
{
	const int bins = s->N / 2 + 1;
	const float *perBin[] = { s->pow, s->ld, s->ref, s->tmp, s->tgt, s->gain, s->gainLin,
		s->band, s->mag1, s->mag2, s->u1re, s->u1im, s->u2re, s->u2im, s->cSm, s->cPrev,
		s->tonal, s->age };
	int c = 0;
	for (size_t k = 0; k < sizeof(perBin) / sizeof(perBin[0]); k++)
		c += subnormalsIn(perBin[k], bins);
	for (int ch = 0; ch < 2; ch++)
		c += subnormalsIn(s->in[ch], s->N) + subnormalsIn(s->acc[ch], s->N);
	return c;
}

static void testSpeed(void)
{
	printf("\n[13] cost at 48 kHz\n");
	const float fs = 48000;
	const int n = 48000 * 10, blk = 480, nb = n / blk;
	makePeaky(fs, n, 5000, 4, 0.05, 130);
	/* The last 4 s silent, for the tail where denormals would show. */
	for (int i = 48000 * 6; i < n; i++) inL[i] = inR[i] = 0;
	setupDefault(fs);
	static double tb[48000 * 10 / 480];
	long subs = 0;
	for (int b = 0; b < nb; b++)
	{
		memcpy(bufL, inL + b * blk, sizeof(float) * blk);
		memcpy(bufR, inR + b * blk, sizeof(float) * blk);
		const double t0 = nowSec();
		SpectralShaperProcess(&g_lib, blk);
		tb[b] = nowSec() - t0;
		if (b * blk >= 48000 * 6)
			subs += shaperSubnormals(&g_lib.shaper);
	}
	double total = 0;
	for (int b = 0; b < nb; b++) total += tb[b];
	static double music[48000 * 10 / 480], silent[48000 * 10 / 480];
	int nm = 0, ns = 0;
	for (int b = 48000 / blk; b < 48000 * 6 / blk; b++) music[nm++] = tb[b];
	for (int b = 48000 * 7 / blk; b < nb; b++) silent[ns++] = tb[b];
	qsort(music, nm, sizeof(double), cmpd);
	qsort(silent, ns, sizeof(double), cmpd);
	const double cpu = 100.0 * total / (n / fs);
	info("ns per stereo sample", 1e9 * total / n);
	info("median music block, us", 1e6 * music[nm / 2]);
	info("median silent block, us", 1e6 * silent[ns / 2]);
	info("worst silent block, us", 1e6 * silent[ns - 1]);
	check("share of one core at 48 kHz, %", cpu, 0, 2.0);
	/* The scan is the real test. The timing ratio stays as a second opinion,
	   on the lower quartile: on a shared machine the median of either stretch
	   moved by 2x from run to run with nothing changed, and a burst of other
	   work inflates the slow blocks, not the fast ones. A state stuck in
	   denormals would slow every silent block, quartile included. */
	{
		float planted[4] = { 0.0f, 1e-40f, 0.5f, 0.0f };
		check("scan sees a planted subnormal (scanner self-test)", subnormalsIn(planted, 4), 1, 1);
	}
	check("subnormal values in the state over the silent tail", (double)subs, 0, 0);
	info("silent tail median against music median, times", silent[ns / 2] / music[nm / 2]);
	check("silent tail lower quartile against music lower quartile, times",
		silent[ns / 4] / music[nm / 4], 0, 1.5);
}

static void testOnset(void)
{
	printf("\n[14] a note that starts mid-song is not ducked (BROKEN_NOPERSIST)\n");
	const float fs = 48000;
	const int n = 48000 * 4, t0 = 48000 * 2;
	seed(140);
	pink(inL, n, 0.003); memcpy(inR, inL, sizeof(float) * n);
	for (int i = t0; i < n; i++)
	{
		const float v = (float)(0.1 * sin(2 * M_PI * 5017 * (i - t0) / fs));
		inL[i] += v; inR[i] += v;
	}
	setupDefault(fs);
	run(inL, inR, outL, outR, n, 480);
	const int lat = SpectralShaperLatency(&g_lib);
	double mn = 1e9, mnAt = 0;
	for (int w = 0; w < 30; w++)
	{
		const int a = t0 + w * 240;
		double ei = 0, eo = 0;
		Bq q1, q2; bqBp(&q1, 5017, fs, 8); bqBp(&q2, 5017, fs, 8);
		for (int i = a - 2400; i < a + 240; i++)
		{
			const double yi = bqRun(&q1, inL[i]), yo = bqRun(&q2, outL[i + lat]);
			if (i >= a) { ei += yi * yi; eo += yo * yo; }
		}
		const double g = dB(eo / ei);
		if (g < mn) { mn = g; mnAt = w * 5.0; }
	}
	check("deepest dip on the new note in its first 150 ms, dB", mn, -1.0, 0.5);
	info("  ... at ms after the note starts", mnAt);
}

/* Where the tone gate's measure sits for noise and for steady tones: the
   thresholds in spectralShaper.c come from these numbers. */
/* Centre only and edges only. Every other test runs both channels as they
   arrive, so a right channel rebuilt wrongly in these two modes, or the cut put
   on the wrong half, passed everything above. Here the centre and the edges
   carry different pink noise, one of them with a +12 dB peak at 5 kHz. The mode
   aimed at the peaky half has to cut it, and the other half has to come out as
   it went in, delayed, to the last bit. */
static void testMidSide(void)
{
	printf("\n[16] centre only and edges only (BROKEN_MSMIX, BROKEN_MSSWAP)\n");
	const float fs = 48000;
	const int n = 48000 * 6, st = 48000 * 2, len = 48000 * 4;
	const double binHz = fs / WN;
	static float mid[MAXS], side[MAXS];
	for (int c = 0; c < 2; c++)
	{
		const int ms = c ? MS_MODE_SIDE : MS_MODE_MID;
		const char *name = c ? "edges only" : "centre only";
		/* The peaky half is the one the mode works on. */
		makePeaky(fs, n, 5000, 4, 0.05, 160 + c);
		float *peaky = c ? side : mid, *plain = c ? mid : side;
		memcpy(peaky, inL, sizeof(float) * n);
		/* Plain pink for the other half, not the filtered right channel. */
		seed(170 + c);
		pink(plain, n, 0.05);
		for (int i = 0; i < n; i++)
		{
			inL[i] = mid[i] + side[i];
			inR[i] = mid[i] - side[i];
		}
		setupAt(fs, 6, 60, 2000, 10000, 50, SHAPER_FOCUS_BALANCED, 0, 0, ms);
		run(inL, inR, outL, outR, n, 480);
		const int lat = SpectralShaperLatency(&g_lib);
		/* Back to centre and edges, in place. */
		for (int i = 0; i < n; i++)
		{
			const float l = outL[i], r = outR[i];
			outL[i] = 0.5f * (l + r);
			outR[i] = 0.5f * (l - r);
		}
		const float *oPeaky = c ? outR : outL, *oPlain = c ? outL : outR;
		const double red = -bandChange(peaky, oPeaky, st, len - lat, lat,
			5000 - 1.5 * binHz, 5000 + 1.5 * binHz, fs);
		char what[96];
		snprintf(what, sizeof what, "%s: cut at the peak in its own half, dB", name);
		check(what, red, SHAPER_A6_LO, 7.5);
		double e = 0, s = 0;
		for (int i = st; i < st + len; i++)
		{
			const double d = (double)oPlain[i] - plain[i - lat];
			e += d * d;
			s += (double)plain[i - lat] * plain[i - lat];
		}
		snprintf(what, sizeof what, "%s: the other half against its input, dB", name);
		check(what, dB(e / s), -400, -100);
	}
}

static void testGateCalibration(void)
{
	printf("\n[info] tone gate calibration (smoothed unpredictability, per bin per frame)\n");
	const float fs = 48000;
	const int n = 48000 * 4;
	static float vals[400000];
	for (int kind = 0; kind < 2; kind++)
	{
		seed(150 + kind);
		pink(inL, n, 0.05); memcpy(inR, inL, sizeof(float) * n);
		if (kind)
			for (int i = 0; i < n; i++)
			{
				const float v = (float)(0.2 * (sin(2 * M_PI * 3011 * i / fs) + sin(2 * M_PI * 5523 * i / fs) + sin(2 * M_PI * 7877 * i / fs)));
				inL[i] += v; inR[i] += v;
			}
		setupDefault(fs);
		int nv = 0;
		uint64_t rs = 9;
		for (int i = 0; i < n; i += 256)
		{
			runRange(inL, inR, outL, outR, i, i + 256, 256, &rs);
			if (i < 48000) continue;
			const SpectralShaper *s = &g_lib.shaper;
			if (!kind)
				for (int k = s->kLo; k <= s->kHi && nv < 400000; k++) vals[nv++] = s->cSm[k];
			else
			{
				const double f[3] = { 3011, 5523, 7877 };
				for (int t = 0; t < 3; t++)
				{
					const int k = (int)(f[t] * s->N / fs + 0.5);
					for (int d = -1; d <= 1 && nv < 400000; d++) vals[nv++] = s->cSm[k + d];
				}
			}
		}
		static double dv[400000];
		for (int i = 0; i < nv; i++) dv[i] = vals[i];
		qsort(dv, nv, sizeof(double), cmpd);
		printf("  %-14s 1%% %.3f  5%% %.3f  25%% %.3f  50%% %.3f  75%% %.3f  99%% %.3f\n",
			kind ? "tone bins" : "pink noise", dv[nv / 100], dv[nv / 20], dv[nv / 4], dv[nv / 2], dv[3 * nv / 4], dv[99 * nv / 100]);
	}
}

int main(int argc, char **argv)
{
	welchInit();
	const int only = argc > 1 ? atoi(argv[1]) : 0;
	if (!only || only == 1) testFht();
	if (!only || only == 2) testLatency();
	if (!only || only == 3) testBlocks();
	if (!only || only == 4) testResonance();
	if (!only || only == 5) testNoise();
	if (!only || only == 6) testTones();
	if (!only || only == 7) testPumping();
	if (!only || only == 8) testClicks();
	if (!only || only == 9) testSwitch();
	if (!only || only == 10) testNaN();
	if (!only || only == 11) testListen();
	if (!only || only == 12) testRates();
	if (!only || only == 13) testSpeed();
	if (!only || only == 14) testOnset();
	if (!only || only == 15) testGateCalibration();
	if (!only || only == 16) testMidSide();
	printf("\n%s (%d failure%s)\n", failures ? "FAILURES PRESENT" : "all checks passed",
		failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
