// Does one NaN sample from the player leave the effects working afterwards?
//
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/nanin_harness.c \
//       <engine objects> -lm -llog -o tools/nanin_x86
//
// A NaN or Inf reaching a recursive filter stays in its state, and most
// effects had no guard: one bad sample left them putting out NaN, which the
// output stage turns into silence, for as long as they were on. Found by
// tools/paramfuzz_harness.cpp, which feeds the odd non-finite block. The
// engine now scrubs such samples at its entry.
//
// Two engines set up the same way get the same noise, except that one gets a
// single NaN and a single Inf in one block. A second later they should be
// putting out the same thing. Then the spectrum extension on its own, called
// directly so the entry's scrub is not there to help, since its own filters
// were the ones the fuzzer caught.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

#define BLOCK 1024

static JamesDSPLib a, b;
static float in[2 * BLOCK], outA[2 * BLOCK], outB[2 * BLOCK];
static int failures = 0;
static unsigned rs = 77u;

static void noise(float *x, int n)
{
	for (int i = 0; i < n; i++)
	{
		rs = rs * 1664525u + 1013904223u;
		x[i] = 0.3f * ((float)((rs >> 9) & 0xFFFF) / 32768.0f - 1.0f);
	}
}

static void setup(JamesDSPLib *j)
{
	JamesDSPInit(j, BLOCK, 48000);
	VacuumTubeEnable(j);
	BassBoostEnable(j);
	SpectrumExtensionSetParam(j, 7600.0f, 30.0f);
	SpectrumExtensionEnable(j);
	ExciterSetParam(j, 150.0f, 900.0f, 4500.0f, 30.0f, 12.0f, 18.0f, 35.0f,
	                EXCITER_TUBE, 6.0f, 100.0f);
	ExciterEnable(j);
	LowEndSetParam(j, 30.0f, 90.0f, 3.0f, 300.0f, -3.0f, 100.0f);
	LowEndEnable(j);
	TapeSetParam(j, 25.0f, 30.0f, 35.0f, -20.0f, 3.0f, 100.0f);
	TapeEnable(j);
}

static void check(const char *what, int ok, const char *detail)
{
	printf("  %-50s %s  %s\n", what, detail, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

int main(void)
{
	JamesDSPGlobalMemoryAllocation();
	setup(&a);
	setup(&b);
	char d[96];

	printf("== one NaN and one Inf from the player ==\n");
	double eA = 0, eB = 0, diff = 0;
	long bad = 0;
	for (int blk = 0; blk < 100; blk++)
	{
		noise(in, 2 * BLOCK);
		float inB[2 * BLOCK];
		memcpy(inB, in, sizeof(in));
		if (blk == 20) { inB[200] = NAN; inB[701] = INFINITY; }
		a.processFloatMultiplexd(&a, in, outA, BLOCK);
		b.processFloatMultiplexd(&b, inB, outB, BLOCK);
		if (blk < 70) continue; /* a little over a second later */
		for (int i = 0; i < 2 * BLOCK; i++)
		{
			if (!isfinite(outB[i])) { bad++; continue; }
			eA += (double)outA[i] * outA[i];
			eB += (double)outB[i] * outB[i];
			diff += (double)(outA[i] - outB[i]) * (outA[i] - outB[i]);
		}
	}
	const double lvl = 10.0 * log10((eB + 1e-30) / (eA + 1e-30));
	snprintf(d, sizeof(d), "%+.2f dB", lvl);
	check("level a second later matches the clean engine", fabs(lvl) < 0.5 && bad == 0, d);
	const double res = 10.0 * log10((diff + 1e-30) / (eA + 1e-30));
	snprintf(d, sizeof(d), "%.1f dB", res);
	check("and so does the signal (difference under -40 dB)", res < -40.0, d);

	printf("== spectrum extension on its own ==\n");
	static float l[BLOCK], r[BLOCK];
	JamesDSPLib *j = &a;
	float *s0 = j->tmpBuffer[0], *s1 = j->tmpBuffer[1];
	j->tmpBuffer[0] = l; j->tmpBuffer[1] = r;
	SpectrumExtensionDisable(j);
	SpectrumExtensionEnable(j);
	bad = 0;
	double e = 0;
	for (int blk = 0; blk < 40; blk++)
	{
		noise(l, BLOCK); noise(r, BLOCK);
		if (blk == 5) { l[10] = NAN; r[20] = INFINITY; }
		SpectrumExtensionProcess(j, BLOCK);
		if (blk < 20) continue;
		for (int i = 0; i < BLOCK; i++)
		{
			if (!isfinite(l[i]) || !isfinite(r[i])) bad++;
			else e += (double)l[i] * l[i];
		}
	}
	snprintf(d, sizeof(d), "%ld non-finite", bad);
	check("finite again after a NaN and an Inf", bad == 0 && e > 0, d);
	/* A finite input big enough to overflow its filters to Inf. */
	l[0] = 3e38f; r[0] = -3e38f;
	for (int i = 1; i < BLOCK; i++) l[i] = r[i] = 3e38f * ((i & 1) ? 1.0f : -1.0f);
	SpectrumExtensionProcess(j, BLOCK);
	bad = 0;
	for (int blk = 0; blk < 10; blk++)
	{
		noise(l, BLOCK); noise(r, BLOCK);
		SpectrumExtensionProcess(j, BLOCK);
		for (int i = 0; i < BLOCK; i++)
			if (!isfinite(l[i]) || !isfinite(r[i])) bad++;
	}
	snprintf(d, sizeof(d), "%ld non-finite", bad);
	check("finite again after an overflowing block", bad == 0, d);

	/* Stereo widening squares each band for its level trackers. A huge but
	   finite sample squared to Inf, an Inf level never decays, and Inf over
	   Inf made every sample after it NaN. Found by tools/paramfuzz_harness.cpp
	   with an effect upstream fed a huge setting. */
	printf("== stereo widening after a huge sample ==\n");
	StereoEnhancementSetParam(j, 0.6f);
	for (int i = 0; i < BLOCK; i++)
	{
		l[i] = (i & 1) ? 1e20f : -3e19f;
		r[i] = -0.5f * l[i];
	}
	StereoEnhancementProcess(j, BLOCK);
	bad = 0;
	for (int blk = 0; blk < 200; blk++)
	{
		noise(l, BLOCK); noise(r, BLOCK);
		StereoEnhancementProcess(j, BLOCK);
		if (blk < 150) continue;
		for (int i = 0; i < BLOCK; i++)
			if (!isfinite(l[i]) || !isfinite(r[i])) bad++;
	}
	snprintf(d, sizeof(d), "%ld non-finite", bad);
	check("finite again a few seconds later", bad == 0, d);
	j->tmpBuffer[0] = s0; j->tmpBuffer[1] = s1;

	/* Whatever goes in, nothing non-finite comes out: with the limiter off
	   at 96 kHz, a run of huge finite samples overflowed the resampler that runs
	   after the output stage's own check. In root mode that NaN goes
	   straight into the audio server's mixer. */
	printf("== huge samples at 96 kHz, limiter off ==\n");
	static JamesDSPLib c;
	JamesDSPInit(&c, BLOCK, 48000);
	JamesDSPSetSampleRate(&c, 96000.0f, 1);
	JLimiterSetMode(&c, 2);
	bad = 0;
	for (int blk = 0; blk < 20; blk++)
	{
		for (int i = 0; i < 2 * BLOCK; i++) in[i] = 3e38f;
		c.processFloatMultiplexd(&c, in, outA, BLOCK);
		for (int i = 0; i < 2 * BLOCK; i++) if (!isfinite(outA[i])) bad++;
	}
	snprintf(d, sizeof(d), "%ld non-finite", bad);
	check("no NaN or Inf leaves the engine", bad == 0, d);
	JamesDSPFree(&c);

	JamesDSPFree(&a);
	JamesDSPFree(&b);
	printf("%s\n", failures ? "FAIL" : "PASS");
	return failures != 0;
}
