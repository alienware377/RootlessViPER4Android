// Does the per-channel EQ actually give each ear its own curve?
//
//   $NDK/aarch64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       -I $J/generalDSP -I $J/Effects/eel2/numericSys \
//       tools/percheq_harness.c tools/lockstub.c <engine objects> -lm -o percheq
//
// The claim being tested is narrow and the whole feature rests on it: the
// arbitrary magnitude EQ has always run through a 2x2 convolver that takes a
// left and a right impulse response, and every caller passed the same one
// twice. If that is true, giving each channel its own curve is free. If the
// convolver quietly mixes the two, or ignores the second, the feature is a lie
// and it would be a convincing one, because a curve applied to both ears still
// sounds like an equaliser.
//
// So: boost only the left, cut only the right, feed identical signal to both,
// and insist the two outputs differ in the direction asked for. Then check the
// case that would hide a bug, which is both channels being given the same
// curve: the stereo path must then be indistinguishable from the mono one.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <stdlib.h>
#include "jdsp_header.h"

#define FS 48000.0
#define N  4096

static JamesDSPLib g_lib;
static int failures = 0;

/* The engine owns tmpBuffer, and owns it as one allocation that the other
   four buffers index into, so writing our own pointers in there would leak its
   block and hand its free() ours. The HAL used to do exactly that. Read and
   write the engine's own buffers instead. */
#define bufL (g_lib.tmpBuffer[0])
#define bufR (g_lib.tmpBuffer[1])

static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-52s %9.2f  [%8.2f..%8.2f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

/* Level of one channel over the whole block, in dB. */
static double levelDb(const float *b)
{
	double a = 0.0;
	for (int i = 0; i < N; i++) a += (double)b[i] * (double)b[i];
	a = sqrt(a / N);
	return 20.0 * log10(a > 1e-12 ? a : 1e-12);
}

/* The filter is 8192 taps against a 4096 sample block, so a single block is
   measuring the convolver filling up rather than its response: everything
   read about 3 dB low and the flat-curve check caught it. Push enough blocks
   through for it to settle, keeping the tone's phase continuous across them,
   and measure the last one. */
static long g_phase = 0;

static void feed(double f, float amp)
{
	for (int i = 0; i < N; i++, g_phase++)
		bufL[i] = bufR[i] = (float)(amp * sin(2.0 * M_PI * f * (double)g_phase / FS));
}

static void settle(double f, float amp)
{
	g_phase = 0;
	for (int block = 0; block < 6; block++)
	{
		feed(f, amp);
		ArbitraryResponseEqualizerProcess(&g_lib, N);
	}
}

static void prepare(void)
{
	static int globalReady = 0;
	if (!globalReady) { JamesDSPGlobalMemoryAllocation(); globalReady = 1; }
	memset(&g_lib, 0, sizeof(g_lib));
	JamesDSPInit(&g_lib, N, (float)FS);
	if (!g_lib.tmpBuffer[0] || !g_lib.tmpBuffer[1])
	{
		printf("engine did not allocate its buffers\n");
		exit(2);
	}
}

int main(void)
{
	printf("== per-channel arbitrary magnitude EQ ==\n\n");

	/* A big, unmistakable difference at 1 kHz: up 12 dB on the left, down
	   12 dB on the right. Subtlety would not distinguish a working feature
	   from a rounding error. */
	char left[]  = "GraphicEQ: 20 0; 500 0; 1000 12; 2000 0; 20000 0";
	char right[] = "GraphicEQ: 20 0; 500 0; 1000 -12; 2000 0; 20000 0";
	char flat[]  = "GraphicEQ: 20 0; 20000 0";

	printf("each ear gets its own curve\n");
	{
		prepare();
		ArbitraryResponseEqualizerStringParserStereo(&g_lib, left, right);
		ArbitraryResponseEqualizerEnable(&g_lib, 1);
		settle(1000.0, 0.25f);
		const double l = levelDb(bufL), r = levelDb(bufR);
		printf("      left %.2f dB, right %.2f dB, difference %.2f dB\n", l, r, l - r);
		/* 24 dB asked for. The FIR will not hit it exactly at one frequency,
		   but anything under half of it means the two curves are being mixed
		   rather than kept apart. */
		check("left is louder than right by about 24 dB", l - r, 14.0, 30.0);
		check("left really was boosted", l, -18.0, 0.0);
		check("right really was cut", r, -40.0, -20.0);
	}

	printf("\nthe same curve on both ears is the same as not asking per channel\n");
	{
		prepare();
		ArbitraryResponseEqualizerStringParserStereo(&g_lib, left, left);
		ArbitraryResponseEqualizerEnable(&g_lib, 1);
		settle(1000.0, 0.25f);
		const double stereoL = levelDb(bufL), stereoR = levelDb(bufR);

		prepare();
		ArbitraryResponseEqualizerStringParser(&g_lib, left);
		ArbitraryResponseEqualizerEnable(&g_lib, 1);
		settle(1000.0, 0.25f);
		const double monoL = levelDb(bufL);

		printf("      stereo path L %.3f R %.3f, mono path %.3f dB\n", stereoL, stereoR, monoL);
		check("both ears match each other", fabs(stereoL - stereoR), 0.0, 0.01);
		check("and match the single-curve path", fabs(stereoL - monoL), 0.0, 0.01);
	}

	/* Asked first, because the per-channel checks are measured against it.
	   A flat curve ought to be a straight wire and it is not: the existing
	   single-curve path loses a constant three decibels through it as well, so
	   whatever that is, it is neither per-channel nor new. Measure it rather
	   than assume unity, and judge the per-channel results against it, so this
	   harness tests the feature instead of a quirk the feature inherited. */
	double flatOffset = 0.0;
	printf("\nwhat a flat curve does on the path that already shipped\n");
	{
		prepare();
		ArbitraryResponseEqualizerStringParser(&g_lib, flat);
		ArbitraryResponseEqualizerEnable(&g_lib, 1);
		settle(1000.0, 0.25f);
		const double l = levelDb(bufL);
		flatOffset = l - (-12.04);
		printf("      single flat curve: %.2f dB from a -12.04 dB input, offset %.2f dB\n", l, flatOffset);
		check("both ears agree on the old path", fabs(levelDb(bufL) - levelDb(bufR)), 0.0, 0.01);
		check("the offset is small and constant, not instability", fabs(flatOffset), 0.0, 4.0);
	}

	printf("\na flat curve on one side leaves that side alone\n");
	{
		prepare();
		ArbitraryResponseEqualizerStringParserStereo(&g_lib, flat, right);
		ArbitraryResponseEqualizerEnable(&g_lib, 1);
		const double inDb = -12.04;  /* 0.25 amplitude sine */
		settle(1000.0, 0.25f);
		const double l = levelDb(bufL), r = levelDb(bufR);
		printf("      input %.2f dB, flat side %.2f dB, cut side %.2f dB\n", inDb, l, r);
		check("the flat side matches the old path's flat response",
		      fabs(l - (inDb + flatOffset)), 0.0, 0.5);
		check("the cut side is well below it", inDb - r, 6.0, 30.0);
	}

	printf("\nswapping the sides swaps the result\n");
	{
		prepare();
		ArbitraryResponseEqualizerStringParserStereo(&g_lib, right, left);
		ArbitraryResponseEqualizerEnable(&g_lib, 1);
		settle(1000.0, 0.25f);
		const double l = levelDb(bufL), r = levelDb(bufR);
		printf("      left %.2f dB, right %.2f dB\n", l, r);
		check("now the right is the loud one", r - l, 14.0, 30.0);
	}

	printf("\nre-sending a per-channel pair does not drift\n");
	{
		prepare();
		double firstL = 0.0, firstR = 0.0;
		for (int pass = 0; pass < 4; pass++)
		{
			ArbitraryResponseEqualizerStringParserStereo(&g_lib, left, right);
			ArbitraryResponseEqualizerEnable(&g_lib, 1);
			settle(1000.0, 0.25f);
			if (!pass) { firstL = levelDb(bufL); firstR = levelDb(bufR); }
			else
			{
				check("left unchanged on a repeat", fabs(levelDb(bufL) - firstL), 0.0, 0.5);
				check("right unchanged on a repeat", fabs(levelDb(bufR) - firstR), 0.0, 0.5);
			}
		}
	}

	printf("\nnothing non-finite comes out\n");
	{
		int bad = 0;
		prepare();
		ArbitraryResponseEqualizerStringParserStereo(&g_lib, left, right);
		ArbitraryResponseEqualizerEnable(&g_lib, 1);
		settle(1000.0, 0.25f);
		for (int i = 0; i < N; i++)
			if (!isfinite(bufL[i]) || !isfinite(bufR[i])) bad++;
		check("non-finite samples", (double)bad, 0.0, 0.0);
	}

	printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
	       failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
