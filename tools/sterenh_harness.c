// Does the stereo widening survive a NaN, and follow a rate change?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) and
//   app/src/main/cpp/EELStdOutExtension.c with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/sterenh_harness.c \
//       <engine objects> -lm -llog -o tools/sterenh_x86
//
// The widening splits the signal into five warped bands and follows each
// band's sum and difference power with a running average. Nothing ever reset
// those: Enable only sets a flag, and the filterbank is designed only at
// construction and in the forceRefresh branch nothing passes. So one NaN or
// Inf sample in made every sample out NaN for the life of the engine, card
// off and on included. And because the design is never redone, after a 48 to
// 44.1 kHz move its band split and envelope times stayed at 48 kHz.
//
// A tone goes through the widening, then a block with one bad sample, then
// 1 s more, which must be finite; then the real JamesDSPSetSampleRate, after
// which the design must match an engine made at the new rate.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }

#define N 1024
static JamesDSPLib *g;
static long t = 0;
static int failures = 0;

static int run(int blocks, float bad)
{
	int nf = 0;
	for (int b = 0; b < blocks; b++)
	{
		float *L = g->tmpBuffer[0], *R = g->tmpBuffer[1];
		for (int i = 0; i < N; i++, t++)
		{
			L[i] = 0.3f * (float)sin(2 * M_PI * 440.0 * t / g->fs);
			R[i] = 0.2f * (float)sin(2 * M_PI * 660.0 * t / g->fs);
		}
		if (!b && bad != 0.0f) // true for NaN as well
			L[0] = bad;
		StereoEnhancementProcess(g, N);
		for (int i = 0; i < N; i++)
			nf += !isfinite(L[i]) || !isfinite(R[i]);
	}
	return nf;
}

static void check(const char *what, int ok, const char *detail)
{
	printf("  %-40s %-4s %s\n", what, ok ? "ok" : "FAIL", detail);
	if (!ok) failures++;
}

int main(void)
{
	char d[128];
	JamesDSPGlobalMemoryAllocation();
	g = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPInit(g, N, 48000);
	StereoEnhancementSetParam(g, 0.6f);
	StereoEnhancementEnable(g);
	run(10, 0.0f);
	run(1, NAN);
	int nf = run(47, 0.0f);
	snprintf(d, sizeof d, "%d of %d samples non-finite", nf, 47 * N);
	check("1 s after one NaN sample", nf == 0, d);
	run(1, INFINITY);
	nf = run(47, 0.0f);
	snprintf(d, sizeof d, "%d of %d samples non-finite", nf, 47 * N);
	check("1 s after one +Inf sample", nf == 0, d);

	JamesDSPLib *ref = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPInit(ref, N, 44100);
	JamesDSPSetSampleRate(g, 44100, 0);
	snprintf(d, sizeof d, "envelope alpha %.6f, made at 44.1 kHz %.6f", g->sterEnh.emaAlpha[0], ref->sterEnh.emaAlpha[0]);
	check("design follows a 48 to 44.1 kHz move", g->sterEnh.emaAlpha[0] == ref->sterEnh.emaAlpha[0], d);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
