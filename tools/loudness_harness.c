// Does the loudness correction lift what the ear loses at a lower volume, by
// the amounts it claims, and leave the sound alone at the reference?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/loudness_harness.c $J/Effects/loudnessCorrection.c tools/lockstub.c -lm -o loudness
//
// Every response here is measured: a sine goes through the effect in 10 ms
// blocks and its level is read off the settled output with a single-bin DFT.
// What it is compared against is worked out separately in this file, from
// EqualizerAPO's shelf formulas and from the ISO 226:2023 contours, so a
// mistake in the engine's design does not cancel against itself.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

#define BLOCK 480
#define SECONDS 1.2

static JamesDSPLib g_lib;
static float blkL[BLOCK], blkR[BLOCK];
static int failures = 0;

static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-56s %10.4f  [%9.4f..%9.4f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static void prepare(float fs)
{
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = fs;
	g_lib.tmpBuffer[0] = blkL;
	g_lib.tmpBuffer[1] = blkR;
}

// The card's defaults, with the mode and the volume difference chosen.
static void setDefaults(int mode)
{
	LoudnessSetParam(&g_lib, mode, 0.0f, 0.0f, 100.0f, 75.0f, 0.52f, 55.0f,
		10000.0f, 0.9f, 22.5f, 80.0f, 0, 20.0f, 4, 0.707f);
}

// Runs n samples of x through the effect, in blocks, into y (both channels
// get the same input; y takes the left, and the right is checked to match).
static int g_channelsDiffer;
static void run(const float *x, float *y, int n)
{
	for (int i = 0; i < n; i += BLOCK)
	{
		const int m = n - i < BLOCK ? n - i : BLOCK;
		memcpy(blkL, x + i, (size_t)m * sizeof(float));
		memcpy(blkR, x + i, (size_t)m * sizeof(float));
		LoudnessProcess(&g_lib, (size_t)m);
		for (int k = 0; k < m; k++)
			if (blkL[k] != blkR[k]) g_channelsDiffer = 1;
		memcpy(y + i, blkL, (size_t)m * sizeof(float));
	}
}

// The gain in dB at freq: a sine long enough to settle every section, read
// over its last half second.
static double measure(double freq)
{
	const double fs = g_lib.fs;
	const int n = (int)(SECONDS * fs);
	float *x = malloc(sizeof(float) * n), *y = malloc(sizeof(float) * n);
	for (int i = 0; i < n; i++)
		x[i] = (float)(0.1 * sin(2.0 * M_PI * freq * i / fs));
	run(x, y, n);
	const int from = n - (int)(0.5 * fs);
	double re = 0.0, im = 0.0;
	for (int i = from; i < n; i++)
	{
		re += y[i] * cos(2.0 * M_PI * freq * i / fs);
		im += y[i] * sin(2.0 * M_PI * freq * i / fs);
	}
	const double amp = 2.0 * sqrt(re * re + im * im) / (double)(n - from);
	free(x);
	free(y);
	return 20.0 * log10(amp / 0.1);
}

// ---- what the response should be, worked out here ---------------------------

typedef struct { double re, im; } cpx;

// |H| in dB of a biquad given unnormalised coefficients, at freq.
static double biquadDb(double b0, double b1, double b2, double a0, double a1, double a2,
	double freq, double fs)
{
	const double w = 2.0 * M_PI * freq / fs;
	const cpx z1 = { cos(w), -sin(w) }, z2 = { cos(2.0 * w), -sin(2.0 * w) };
	const cpx n = { b0 + b1 * z1.re + b2 * z2.re, b1 * z1.im + b2 * z2.im };
	const cpx d = { a0 + a1 * z1.re + a2 * z2.re, a1 * z1.im + a2 * z2.im };
	return 10.0 * log10((n.re * n.re + n.im * n.im) / (d.re * d.re + d.im * d.im));
}

// The cookbook shelf with slope S, as EqualizerAPO's LoudnessCorrectionFilter
// updates it.
static double shelfDb(int high, double g, double f0, double s, double freq, double fs)
{
	if (g == 0.0) return 0.0;
	const double A = pow(10.0, g / 40.0), w0 = 2.0 * M_PI * f0 / fs;
	const double cs = cos(w0), al = sin(w0) / 2.0 * sqrt((A + 1.0 / A) * (1.0 / s - 1.0) + 2.0);
	const double be = 2.0 * sqrt(A) * al;
	if (!high)
		return biquadDb(A * ((A + 1) - (A - 1) * cs + be), 2 * A * ((A - 1) - (A + 1) * cs),
			A * ((A + 1) - (A - 1) * cs - be), (A + 1) + (A - 1) * cs + be,
			-2 * ((A - 1) + (A + 1) * cs), (A + 1) + (A - 1) * cs - be, freq, fs);
	return biquadDb(A * ((A + 1) + (A - 1) * cs + be), -2 * A * ((A - 1) + (A + 1) * cs),
		A * ((A + 1) + (A - 1) * cs - be), (A + 1) - (A - 1) * cs + be,
		2 * ((A - 1) - (A + 1) * cs), (A + 1) - (A - 1) * cs - be, freq, fs);
}

// EqualizerAPO's classic correction at the default settings for a volume
// volDiff dB under the reference.
static double classicDb(double volDiff, double freq, double fs)
{
	double gLs = 0.0, pre = 0.0, gHs = 0.0;
	if (volDiff > 0) { gLs = volDiff * 0.55 / (1 - 0.55); pre = -gLs; }
	else if (volDiff < 0) gLs = volDiff * 0.55 * exp(volDiff / 90.0);
	const double hv = volDiff - pre;
	if (hv > 0) gHs = hv * 0.225 * exp(-hv / 100.0);
	else if (hv < 0) gHs = hv * 0.175 * exp(hv / 80.0);
	return pre + shelfDb(0, gLs, 75.0, 0.52, freq, fs) + shelfDb(1, gHs, 10000.0, 0.9, freq, fs);
}

// ISO 226:2023, Table 1 and formula (1), written out again here.
static const double tF[29] = { 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400,
	500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500 };
static const double tA[29] = { 0.635, 0.602, 0.569, 0.537, 0.509, 0.482, 0.456, 0.433, 0.412,
	0.391, 0.373, 0.357, 0.343, 0.330, 0.320, 0.311, 0.303, 0.300, 0.295, 0.292, 0.290, 0.290,
	0.289, 0.289, 0.289, 0.293, 0.303, 0.323, 0.354 };
static const double tL[29] = { -31.5, -27.2, -23.1, -19.3, -16.1, -13.1, -10.4, -8.2, -6.3,
	-4.6, -3.2, -2.1, -1.2, -0.5, 0.0, 0.4, 0.5, 0.0, -2.7, -4.2, -1.2, 1.4, 2.3, 1.0, -2.3,
	-7.2, -11.2, -10.9, -3.5 };
static const double tT[29] = { 78.1, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
	14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6,
	13.9, 12.3 };

static double contour(int i, double phon)
{
	return 10.0 / tA[i] * log10(pow(4e-10, 0.3 - tA[i]) * (pow(10.0, 0.03 * phon) - pow(10.0, 0.072)) +
		pow(10.0, tA[i] * (tT[i] + tL[i]) / 10.0)) - tL[i];
}

// How much more than 1 kHz band i needs at curPhon to sound as it did at refPhon.
static double contourDiff(int i, double refPhon, double curPhon)
{
	return contour(i, curPhon) - contour(i, refPhon) - (curPhon - refPhon);
}

// ---- the checks -----------------------------------------------------------

static int bitIdentical(void)
{
	static float x[48000], y[48000];
	for (int i = 0; i < 48000; i++)
		x[i] = (float)(0.5 * sin(2.0 * M_PI * 61.0 * i / 48000.0) + 0.3 * sin(2.0 * M_PI * 7919.0 * i / 48000.0));
	run(x, y, 48000);
	return memcmp(x, y, sizeof(x)) == 0;
}

// The largest step from one sample to the next over [a, b). A sine's is fixed
// by its level and frequency, so a click stands out against the steady parts;
// a level that glides from one value to another stays between them.
static double maxStep(const float *y, int a, int b)
{
	double m = 0.0;
	for (int i = a + 1; i < b; i++)
	{
		const double c = fabs((double)y[i] - y[i - 1]);
		if (c > m) m = c;
	}
	return m;
}

int main(void)
{
	printf("== loudness correction ==\n\n");

	printf("flat where it should be\n");
	for (int mode = 0; mode < 2; mode++)
	{
		prepare(48000.0f);
		setDefaults(mode);
		LoudnessEnable(&g_lib);
		check(mode ? "ISO, at the reference volume: bit-identical" :
			"classic, at the reference volume: bit-identical", bitIdentical(), 1, 1);
		check(mode ? "ISO, at the reference volume: 63 Hz in dB" :
			"classic, at the reference volume: 63 Hz in dB", measure(63.0), -0.1, 0.1);
		LoudnessSetParam(&g_lib, mode, -20.0f, 5.0f, 100.0f, 75.0f, 0.52f, 55.0f,
			10000.0f, 0.9f, 22.5f, 80.0f, 0, 20.0f, 4, 0.707f);
		LoudnessSetVolume(&g_lib, -25.0f);
		measure(1000.0);
		check("at a reference of -20 dB less 5 dB offset, volume -25 dB: 12.5 kHz",
			measure(12500.0), -0.1, 0.1);
		LoudnessSetParam(&g_lib, mode, 0.0f, 0.0f, 0.0f, 75.0f, 0.52f, 55.0f,
			10000.0f, 0.9f, 22.5f, 80.0f, 0, 20.0f, 4, 0.707f);
		LoudnessSetVolume(&g_lib, -30.0f);
		measure(1000.0);
		check("strength 0 at -30 dB: bit-identical", bitIdentical(), 1, 1);
	}

	printf("\nclassic at -30 dB matches EqualizerAPO's shelves\n");
	{
		prepare(48000.0f);
		setDefaults(LOUDNESS_MODE_CLASSIC);
		LoudnessSetVolume(&g_lib, -30.0f);
		LoudnessEnable(&g_lib);
		const double probes[] = { 25, 40, 75, 150, 400, 1000, 3000, 8000, 12000, 18000 };
		double worst = 0.0, top = -1e9, at1k = 0.0, at40 = 0.0, at12k = 0.0;
		for (int i = 0; i < (int)(sizeof(probes) / sizeof(probes[0])); i++)
		{
			const double got = measure(probes[i]), want = classicDb(30.0, probes[i], 48000.0);
			printf("    %6.0f Hz  %8.3f dB  (EqualizerAPO %8.3f)\n", probes[i], got, want);
			if (fabs(got - want) > worst) worst = fabs(got - want);
			if (got > top) top = got;
			if (probes[i] == 1000) at1k = got;
			if (probes[i] == 40) at40 = got;
			if (probes[i] == 12000) at12k = got;
		}
		check("largest difference from EqualizerAPO, dB", worst, 0.0, 0.02);
		check("40 Hz over 1 kHz, dB (bass lifted)", at40 - at1k, 15.0, 40.0);
		check("12 kHz over 1 kHz, dB (treble lifted)", at12k - at1k, 4.0, 20.0);
		check("never above 0 dB anywhere measured", top, -100.0, 0.01);
		check("both channels alike", g_channelsDiffer, 0, 0);
	}

	printf("\nISO 226:2023 at -30 dB: contour differences 80 to 50 phon\n");
	{
		prepare(48000.0f);
		setDefaults(LOUDNESS_MODE_ISO);
		LoudnessSetVolume(&g_lib, -30.0f);
		LoudnessEnable(&g_lib);
		const double at1k = measure(1000.0);
		double worst = 0.0, worstMid = 0.0, top = at1k;
		for (int i = 0; i < 29; i++)
		{
			const double got = measure(tF[i]) - at1k, want = contourDiff(i, 80.0, 50.0);
			if (i % 3 == 0 || i == 28)
				printf("    %7.1f Hz  %8.3f dB  (ISO 226 %8.3f)\n", tF[i], got, want);
			if (fabs(got - want) > worst) worst = fabs(got - want);
			if (got + at1k > top) top = got + at1k;
			if (i > 0 && i < 28)
			{
				// Halfway between two centres, against the straight line
				// between their contour values on a log axis. Not below
				// 25 Hz, where nothing holds the response up from below.
				const double f = sqrt(tF[i] * tF[i + 1]);
				const double mid = measure(f) - at1k;
				const double line = 0.5 * (contourDiff(i, 80.0, 50.0) + contourDiff(i + 1, 80.0, 50.0));
				if (fabs(mid - line) > worstMid) worstMid = fabs(mid - line);
				if (mid + at1k > top) top = mid + at1k;
			}
		}
		check("worst miss at the 29 band centres, dB", worst, 0.0, 0.1);
		check("worst miss halfway between centres above 25 Hz, dB", worstMid, 0.0, 0.3);
		check("31.5 Hz over 1 kHz, dB (bass lifted)", measure(31.5) - at1k, 12.0, 20.0);
		check("12.5 kHz over 1 kHz, dB (treble lifted)", measure(12500.0) - at1k, 3.0, 6.0);
		check("never above 0 dB anywhere measured", top, -100.0, 0.01);
		check("1 kHz brought down by the bass lift, dB", at1k, -20.0, -10.0);
	}

	printf("\nabove the reference it takes some away instead\n");
	{
		prepare(48000.0f);
		LoudnessSetParam(&g_lib, LOUDNESS_MODE_CLASSIC, -30.0f, 0.0f, 100.0f, 75.0f, 0.52f, 55.0f,
			10000.0f, 0.9f, 22.5f, 80.0f, 0, 20.0f, 4, 0.707f);
		LoudnessSetVolume(&g_lib, 0.0f);
		LoudnessEnable(&g_lib);
		const double at1k = measure(1000.0);
		check("classic, 30 dB over: 30 Hz under 1 kHz, dB", measure(30.0) - at1k, -20.0, -3.0);
		check("classic, 30 dB over: matches EqualizerAPO at 30 Hz, dB",
			measure(30.0) - classicDb(-30.0, 30.0, 48000.0), -0.02, 0.02);
		check("1 kHz is not lifted, dB", at1k, -0.2, 0.01);
	}

	printf("\nsubsonic filter\n");
	{
		prepare(48000.0f);
		LoudnessSetParam(&g_lib, LOUDNESS_MODE_CLASSIC, 0.0f, 0.0f, 100.0f, 75.0f, 0.52f, 55.0f,
			10000.0f, 0.9f, 22.5f, 80.0f, 1, 20.0f, 4, 0.7071f);
		LoudnessEnable(&g_lib);
		check("order 4 at 20 Hz: -6 dB at 20 Hz (two Butterworth halves)", measure(20.0), -6.2, -5.8);
		check("order 4: 200 Hz untouched, dB", measure(200.0), -0.02, 0.02);
		const double o4 = measure(5.0);
		check("order 4: 5 Hz, dB", o4, -60.0, -40.0);
		LoudnessSetParam(&g_lib, LOUDNESS_MODE_CLASSIC, 0.0f, 0.0f, 100.0f, 75.0f, 0.52f, 55.0f,
			10000.0f, 0.9f, 22.5f, 80.0f, 1, 20.0f, 3, 0.7071f);
		const double o3 = measure(5.0);
		LoudnessSetParam(&g_lib, LOUDNESS_MODE_CLASSIC, 0.0f, 0.0f, 100.0f, 75.0f, 0.52f, 55.0f,
			10000.0f, 0.9f, 22.5f, 80.0f, 1, 20.0f, 2, 0.7071f);
		const double o2 = measure(5.0);
		check("order 3 sits between 2 and 4 at 5 Hz (below 2)", o3 - o2, -14.0, -10.0);
		check("order 3 sits between 2 and 4 at 5 Hz (above 4)", o3 - o4, 10.0, 14.0);
		LoudnessSetParam(&g_lib, LOUDNESS_MODE_CLASSIC, 0.0f, 0.0f, 100.0f, 75.0f, 0.52f, 55.0f,
			10000.0f, 0.9f, 22.5f, 80.0f, 0, 20.0f, 4, 0.7071f);
		measure(1000.0);
		check("switched off again: bit-identical", bitIdentical(), 1, 1);
	}

	printf("\na volume change glides\n");
	for (int mode = 0; mode < 2; mode++)
	{
		const double fs = 48000.0, f = mode ? 63.0 : 80.0;
		const int n = (int)(1.5 * fs), at = (int)(0.75 * fs);
		float *x = malloc(sizeof(float) * n), *y = malloc(sizeof(float) * n);
		for (int i = 0; i < n; i++)
			x[i] = (float)(0.5 * sin(2.0 * M_PI * f * i / fs));
		double steady = 0.0;
		for (int dir = 0; dir < 2; dir++)
		{
			// Two key presses' worth at once, landing between blocks, down
			// and then up.
			const float from = dir ? -16.0f : -10.0f, to = dir ? -10.0f : -16.0f;
			prepare((float)fs);
			setDefaults(mode);
			LoudnessSetVolume(&g_lib, from);
			LoudnessEnable(&g_lib);
			run(x, y, at);
			LoudnessSetVolume(&g_lib, to);
			run(x + at, y + at, n - at);
			const double st = fmax(maxStep(y, (int)(0.3 * fs), (int)(0.7 * fs)),
				maxStep(y, (int)(1.0 * fs), n));
			if (!dir) steady = st;
			char what[96];
			snprintf(what, sizeof(what), "%s, %.0f to %.0f dB: largest step over steady",
				mode ? "ISO" : "classic", from, to);
			check(what, maxStep(y, at - 480, at + 4800) / st, 0.0, 1.05);
			// Finished well inside 100 ms: from then on it is the new level.
			double a = 0.0;
			for (int i = at + 4800; i < at + 4800 + 9600; i++) a = fmax(a, fabs(y[i]));
			double b = 0.0;
			for (int i = n - 9600; i < n; i++) b = fmax(b, fabs(y[i]));
			check("settled at the new level 100 ms later, dB", 20.0 * log10(a / b), -0.05, 0.05);
		}

		// The test can see a click: a jump with the filters restarted.
		prepare((float)fs);
		setDefaults(mode);
		LoudnessSetVolume(&g_lib, -10.0f);
		LoudnessEnable(&g_lib);
		run(x, y, at);
		g_lib.loudness.volumeDb = -16.0f;
		LoudnessRefresh(&g_lib);
		run(x + at, y + at, n - at);
		check("a hard jump instead would show (step over steady)",
			maxStep(y, at - 480, at + 4800) / steady, 1.5, 1e9);

		// A volume key held down: a step every 20 ms, some inside a block.
		prepare((float)fs);
		setDefaults(mode);
		LoudnessSetVolume(&g_lib, -6.0f);
		LoudnessEnable(&g_lib);
		run(x, y, at);
		int pos = at;
		for (int k = 0; k < 15; k++)
		{
			LoudnessSetVolume(&g_lib, -6.0f - 2.0f * (k + 1));
			const int len = k % 2 ? 960 : 977;
			run(x + pos, y + pos, len);
			pos += len;
		}
		run(x + pos, y + pos, n - pos);
		double big = 0.0;
		for (int i = at; i < n; i++) big = fmax(big, fabs(y[i]));
		const double before = maxStep(y, (int)(0.3 * fs), at);
		check("held volume key: largest step over the level before", maxStep(y, at, n) / before, 0.0, 1.05);
		check("held volume key: output stays finite and below input", big, 0.0, 0.5);
		free(x);
		free(y);
	}

	printf("\nnonsense is held\n");
	{
		prepare(48000.0f);
		setDefaults(LOUDNESS_MODE_ISO);
		LoudnessSetVolume(&g_lib, -30.0f);
		LoudnessEnable(&g_lib);
		const double good = measure(40.0);
		LoudnessSetVolume(&g_lib, NAN);
		check("a NaN volume leaves the volume as it was", g_lib.loudness.volumeDb, -30.0, -30.0);
		check("and the response as it was, dB", measure(40.0) - good, -0.001, 0.001);
		LoudnessSetVolume(&g_lib, INFINITY);
		check("+inf volume is full volume", g_lib.loudness.volumeDb, 0.0, 0.0);
		LoudnessSetVolume(&g_lib, -1e30f);
		check("-1e30 volume is -100 dB", g_lib.loudness.volumeDb, -100.0, -100.0);
		LoudnessSetParam(&g_lib, 77, NAN, INFINITY, 1e30f, NAN, -1e30f, 1e9f,
			INFINITY, NAN, -5.0f, NAN, 9, NAN, 99, INFINITY);
		const LoudnessCorrection *lc = &g_lib.loudness;
		check("mode 77 is classic", lc->mode, LOUDNESS_MODE_CLASSIC, LOUDNESS_MODE_CLASSIC);
		check("bass amount 1e9 stops at 90 %", lc->lsRatio, 90.0, 90.0);
		check("subsonic order 99 stops at 8", lc->subOrder, 8, 8);
		check("strength 1e30 stops at 200 %", lc->strength, 200.0, 200.0);
		check("NaN reference level is the bottom of its range", lc->refLevel, -60.0, -60.0);
		int finite = isfinite(lc->gainTgt) && lc->gainTgt > 0.0;
		for (int s = 0; s < LOUD_SECTIONS; s++)
			for (int k = 0; k < 5; k++)
				finite = finite && isfinite(lc->tgt[s][k]);
		check("every coefficient finite", finite, 1, 1);
		double worst = -1e9;
		const double probes[] = { 12, 30, 100, 1000, 9000 };
		for (int i = 0; i < 5; i++)
		{
			const double r = measure(probes[i]);
			if (!isfinite(r)) worst = 1e9; else if (r > worst) worst = r;
		}
		check("the worst of it still never boosts, dB", worst, -200.0, 0.1);
		LoudnessSetParam(&g_lib, LOUDNESS_MODE_ISO, 0.0f, 0.0f, 200.0f, 75.0f, 0.52f, 90.0f,
			10000.0f, 0.9f, 100.0f, 90.0f, 0, 20.0f, 4, 0.707f);
		LoudnessSetVolume(&g_lib, -100.0f);
		const double deep = measure(20.0);
		check("ISO at -100 dB, strength 200 %: finite, at most 0 dB", deep, -200.0, 0.01);
		// A NaN sample does not stay in the filters.
		setDefaults(LOUDNESS_MODE_CLASSIC);
		LoudnessSetVolume(&g_lib, -30.0f);
		measure(100.0);
		blkL[0] = NAN; blkR[0] = INFINITY;
		for (int i = 1; i < BLOCK; i++) blkL[i] = blkR[i] = 0.1f;
		LoudnessProcess(&g_lib, BLOCK);
		int ok = 1;
		for (int i = 0; i < BLOCK; i++) ok = ok && isfinite(blkL[i]) && isfinite(blkR[i]);
		check("NaN and inf samples come out finite", ok, 1, 1);
		check("and the response is back to normal after, dB",
			measure(100.0) - classicDb(30.0, 100.0, 48000.0), -0.02, 0.02);
	}

	printf("\na 48 to 44.1 kHz change designs it again\n");
	{
		prepare(48000.0f);
		setDefaults(LOUDNESS_MODE_ISO);
		LoudnessSetVolume(&g_lib, -30.0f);
		LoudnessEnable(&g_lib);
		const double b0at48 = g_lib.loudness.cur[LOUD_SUB_SECTIONS][0];
		g_lib.fs = 44100.0f;
		LoudnessRefresh(&g_lib);
		check("coefficients changed", fabs(g_lib.loudness.cur[LOUD_SUB_SECTIONS][0] - b0at48), 1e-9, 1.0);
		const double at1k = measure(1000.0);
		double worst = 0.0;
		for (int i = 0; i < 29; i += 4)
		{
			const double d = measure(tF[i]) - at1k - contourDiff(i, 80.0, 50.0);
			if (fabs(d) > worst) worst = fabs(d);
		}
		check("ISO at 44.1 kHz, worst miss at the centres, dB", worst, 0.0, 0.1);
		setDefaults(LOUDNESS_MODE_CLASSIC);
		g_lib.fs = 48000.0f;
		LoudnessRefresh(&g_lib);
		g_lib.fs = 44100.0f;
		LoudnessRefresh(&g_lib);
		double cworst = 0.0;
		const double probes[] = { 40, 300, 1000, 9000, 16000 };
		for (int i = 0; i < 5; i++)
		{
			const double d = measure(probes[i]) - classicDb(30.0, probes[i], 44100.0);
			if (fabs(d) > cworst) cworst = fabs(d);
		}
		check("classic at 44.1 kHz against EqualizerAPO at 44.1, dB", cworst, 0.0, 0.02);
	}

	printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
	       failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
