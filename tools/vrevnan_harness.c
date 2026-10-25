// Does one bad sample latch the reverb?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/vrevnan_harness.c $J/Effects/viperextras2.c tools/lockstub.c -lm -o tools/vrevnan_x86
//
// One NaN in the input stayed in the recirculating state of four of the six
// models. Classic has no guard on its combs, so its output, dry signal
// included, was NaN from then on. Plate's diffusers and tank, and the smooth
// models' allpass diffuser, hold it too; their output guards only zero the
// sample, so the wet tail went silent. Switching the card off and on did not
// fix classic either: its comb damping state lives outside the buffer Enable
// clears.
//
// Each model gets 2 s of tone, a block with one NaN, then 4 s more, and must
// still be producing finite reverb 2 to 4 s later. Then the classic comb
// damping state is poisoned directly and the card switched off and on, which
// must clear it.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"
static JamesDSPLib g;
static float L[512], R[512];
static const char *nm[] = { "classic", "plate", "hall", "room", "smooth hall", "smooth room" };
static long t;
/* run nblk blocks of tone, return peak |L| among finite, count non-finite */
static void run(int nblk, int nanAtFirst, double *pk, long *nf)
{
	*pk = 0; *nf = 0;
	for (int b = 0; b < nblk; b++)
	{
		for (int i = 0; i < 512; i++, t++)
			L[i] = R[i] = 0.25f * (float)sin(2 * M_PI * 330.0 * t / 48000.0);
		if (nanAtFirst && b == 0) { L[100] = NAN; }
		VReverbProcess(&g, 512);
		for (int i = 0; i < 512; i++)
		{
			if (!isfinite(L[i]) || !isfinite(R[i])) { (*nf)++; continue; }
			double a = fabs(L[i]); if (a > *pk) *pk = a;
		}
	}
}
int main(void)
{
	int latched = 0;
	for (int pass = 0; pass < 2; pass++)
	for (int model = 0; model < 6; model++)
	{
		float dry = pass ? 50.f : 0.f;
		memset(&g, 0, sizeof(g)); t = 0;
		g.fs = 48000; g.tmpBuffer[0] = L; g.tmpBuffer[1] = R;
		VReverbSetParam(&g, model, 60, 40, 100, 10, 40, 60, 20, 0, 40, 100, dry);
		VReverbEnable(&g);
		double p0, p1, p2; long n0, n1, n2;
		run(190, 0, &p0, &n0); // about 2 s
		run(1, 1, &p1, &n1);   // the NaN block
		run(190, 0, &p2, &n2);
		run(190, 0, &p2, &n2); // 2 to 4 s after
		int dead = n2 > 0 || p2 < 0.1 * p0;
		printf("  dry %2.0f %-12s peak before %.4f, 2-4 s after the NaN %.4f, non-finite %6ld  %s\n",
			dry, nm[model], p0, p2, n2, dead ? "FAIL" : "ok");
		latched += dead;
		VReverbDisable(&g);
	}
	// what an earlier bad value left in the classic combs' damping state
	memset(&g, 0, sizeof(g)); t = 0;
	g.fs = 48000; g.tmpBuffer[0] = L; g.tmpBuffer[1] = R;
	VReverbSetParam(&g, 0, 60, 40, 100, 10, 40, 60, 20, 0, 40, 100, 0);
	VReverbEnable(&g);
	g.vreverb.cflt[0][0] = NAN;
	VReverbDisable(&g);
	VReverbEnable(&g);
	double pk; long nf;
	run(190, 0, &pk, &nf);
	printf("  classic, card off and on after a NaN in its comb state: non-finite %ld  %s\n", nf, nf ? "FAIL" : "ok");
	latched += nf != 0;
	printf(latched ? "FAIL (%d)\n" : "PASS\n", latched);
	return latched != 0;
}
