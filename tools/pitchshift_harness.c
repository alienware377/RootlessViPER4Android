// Does the smooth mode actually shift the pitch, and is it really steadier
// than the granular one?
//
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/pitchshift_harness.c $J/Effects/pitchshift.c tools/lockstub.c -lm -o pitch
//
// The second question is the whole reason the mode exists, so it is asserted
// rather than assumed. Granular splices two read heads together, and on a held
// note they drift in and out of phase - the level wanders. A phase vocoder
// tracks each partial instead, so its level should sit still. That difference
// is measurable: chop the output into short windows and look at how much the
// level moves between them.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

#define FS 48000.0
#define N  (48000 * 3)

static JamesDSPLib g_lib;
static float bufL[N], bufR[N];
static int failures = 0;

static void prepare(void)
{
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = (float)FS;
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
}

static void tone(double f, float amp)
{
	for (int i = 0; i < N; i++)
		bufL[i] = bufR[i] = (float)(amp * sin(2.0 * M_PI * f * (double)i / FS));
}

/* Goertzel over the settled tail. */
static double toneDb(double freq, int from, int len)
{
	const double w = 2.0 * M_PI * freq / FS;
	const double c = 2.0 * cos(w);
	double s1 = 0.0, s2 = 0.0;
	for (int i = from; i < from + len; i++)
	{
		const double s0 = c * s1 - s2 + (double)bufL[i];
		s2 = s1; s1 = s0;
	}
	const double re = s1 - s2 * cos(w), im = s2 * sin(w);
	const double mag = 2.0 * sqrt(re * re + im * im) / (double)len;
	return 20.0 * log10(mag > 1e-12 ? mag : 1e-12);
}

/* How much the level wanders, in dB, over the settled tail. */
static double levelWobble(void)
{
	const int win = 1024;
	const int start = N / 2;
	double mn = 1e30, mx = -1e30;
	for (int p = start; p + win < N; p += win)
	{
		double a = 0.0;
		for (int i = p; i < p + win; i++) a += (double)bufL[i] * (double)bufL[i];
		a = sqrt(a / win);
		const double db = 20.0 * log10(a > 1e-9 ? a : 1e-9);
		if (db < mn) mn = db;
		if (db > mx) mx = db;
	}
	return mx - mn;
}

static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-48s %9.2f  [%8.2f..%8.2f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static void run(int mode, float semis)
{
	prepare();
	PitchShiftSetParam(&g_lib, semis, 100.0f, mode);
	PitchShiftEnable(&g_lib);
	tone(440.0, 0.5f);
	PitchShiftProcess(&g_lib, N);
}

int main(void)
{
	printf("== pitch shift ==\n\n");

	printf("at rest\n");
	{
		prepare();
		PitchShiftSetParam(&g_lib, 0.0f, 100.0f, PITCH_MODE_SMOOTH);
		PitchShiftEnable(&g_lib);
		tone(440.0, 0.5f);
		float ref[64]; memcpy(ref, bufL, sizeof(ref));
		PitchShiftProcess(&g_lib, N);
		check("no shift and full mix is bit-identical",
		      memcmp(ref, bufL, sizeof(ref)) == 0 ? 1 : 0, 1, 1);
	}

	printf("\nsmooth mode shifts the pitch\n");
	{
		/* Up a fifth: 440 should land near 659.3 and little should be left at
		   the original. */
		run(PITCH_MODE_SMOOTH, 7.0f);
		const double at440 = toneDb(440.0, N / 2, 16384);
		const double at659 = toneDb(659.26, N / 2, 16384);
		printf("      440Hz %.1f dB, 659Hz %.1f dB\n", at440, at659);
		check("the shifted partial is the loud one (dB)", at659 - at440, 6.0, 80.0);
	}
	{
		run(PITCH_MODE_SMOOTH, -12.0f);
		const double at440 = toneDb(440.0, N / 2, 16384);
		const double at220 = toneDb(220.0, N / 2, 16384);
		printf("      440Hz %.1f dB, 220Hz %.1f dB\n", at440, at220);
		check("an octave down lands an octave down (dB)", at220 - at440, 6.0, 80.0);
	}

	printf("\nsmooth is steadier than granular - the reason it exists\n");
	{
		run(PITCH_MODE_GRANULAR, 7.0f);
		const double gran = levelWobble();
		run(PITCH_MODE_SMOOTH, 7.0f);
		const double smooth = levelWobble();
		printf("      granular wanders %.2f dB, smooth %.2f dB\n", gran, smooth);
		check("smooth holds a steadier level (dB)", smooth, 0.0, 3.0);
		check("and is steadier than granular", gran - smooth, 1.0, 100.0);
	}

	printf("\nthe detector fires on transients and nothing else\n");
	{
		/* A held note must not trip it - that was the bug that cost 20dB. */
		run(PITCH_MODE_SMOOTH, 7.0f);
		const int onTone = g_lib.pitchShift.pvTransients;

		/* Clicks every 100ms: unmistakably transients. */
		prepare();
		PitchShiftSetParam(&g_lib, 7.0f, 100.0f, PITCH_MODE_SMOOTH);
		PitchShiftEnable(&g_lib);
		memset(bufL, 0, sizeof(bufL)); memset(bufR, 0, sizeof(bufR));
		for (int i = 0; i < N; i += (int)(FS / 10))
			for (int j = 0; j < 64 && i + j < N; j++)
				bufL[i + j] = bufR[i + j] = (float)(0.8 * exp(-j / 12.0));
		PitchShiftProcess(&g_lib, N);
		const int onClicks = g_lib.pitchShift.pvTransients;

		printf("      held tone %d resets, click train %d resets\n", onTone, onClicks);
		check("a held note barely trips it", (double)onTone, 0.0, 4.0);
		check("a click train does trip it", (double)onClicks, 8.0, 400.0);
	}

	printf("\nstability\n");
	{
		int bad = 0; double pk = 0.0;
		const float semis[3] = { 12.0f, -12.0f, 3.0f };
		for (int s = 0; s < 3; s++)
		{
			run(PITCH_MODE_SMOOTH, semis[s]);
			for (int i = 0; i < N; i++)
			{
				if (!isfinite(bufL[i]) || !isfinite(bufR[i])) bad++;
				const double a = fabs((double)bufL[i]);
				if (a > pk) pk = a;
			}
			PitchShiftDisable(&g_lib);
		}
		check("non-finite samples", (double)bad, 0.0, 0.0);
		check("peak stays bounded", pk, 0.05, 4.0);
	}

	printf("\nswitching modes while running does not explode\n");
	{
		prepare();
		PitchShiftSetParam(&g_lib, 5.0f, 100.0f, PITCH_MODE_GRANULAR);
		PitchShiftEnable(&g_lib);
		tone(440.0, 0.5f);
		int bad = 0;
		for (int pass = 0; pass < 6; pass++)
		{
			PitchShiftSetParam(&g_lib, 5.0f, 100.0f,
			                   (pass & 1) ? PITCH_MODE_SMOOTH : PITCH_MODE_GRANULAR);
			PitchShiftProcess(&g_lib, N / 6);
			for (int i = 0; i < N / 6; i++)
				if (!isfinite(bufL[i])) bad++;
		}
		PitchShiftDisable(&g_lib);
		check("no non-finite samples across mode flips", (double)bad, 0.0, 0.0);
	}

	printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
	       failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
