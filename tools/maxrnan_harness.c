// Does one bad sample switch off the maximiser's true-peak detection?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/maxrnan_harness.c $J/Effects/maximizer.c $J/generalDSP/digitalFilters.c \
//       tools/lockstub.c -lm -o tools/maxrnan_x86
//
// True-peak detection measures on a copy upsampled through a recursive
// filter. One NaN in left that filter's state NaN for good: every upsampled
// value NaN, no comparison with it ever true, and the detection quietly fell
// back to sample peaks, so inter-sample overs above the ceiling went through.
// The output guard only reset the gain. Only switching the card off and on
// recovered.
//
// A tone at a quarter of the rate with a 45 degree phase puts every sample at
// 0.92 while the waveform between them peaks at 1.3, over a 0 dB ceiling. The
// left channel gets one NaN, the right is the clean control: by the end the
// left must be limited exactly as the right is, with its filter state finite.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

#define BLK 256
#define BLOCKS 64

static JamesDSPLib g_lib;
static float bufL[BLK], bufR[BLK];

static int stateFinite(samplerateTool *s)
{
	iirSOS *u = &s->lpfU;
	if (!isfinite(u->_xn1) || !isfinite(u->_xn2)) return 0;
	for (int i = 0; i < STAGE; i++)
		if (!isfinite(u->_yn[i]) || !isfinite(u->_yn1[i]) || !isfinite(u->_yn2[i])) return 0;
	return 1;
}

int main(void)
{
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = 48000;
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
	// mode transparent, gain 0 dB, ceiling 0 dB, release 50 ms, character 0,
	// transient 0, truePeak 1, stereo link 0 (independent channels), oversample 0
	MaximizerSetParam(&g_lib, 0, 0.0f, 0.0f, 50.0f, 0.0f, 0.0f, 1, 0.0f, 0, 0);
	MaximizerEnable(&g_lib);
	Maximizer *m = &g_lib.maximizer;
	printf("osFactor=%d truePeak=%d lookahead=%d\n", m->osFactor, m->truePeak, m->lookahead);

	// fs/4 tone, 45 degree phase: samples are +-A/sqrt2, real peak A sits between them.
	const float A = 1.3f;
	long n = 0;
	int failed = 0;
	for (int b = 0; b < BLOCKS; b++)
	{
		for (int i = 0; i < BLK; i++, n++)
		{
			float v = A * (float)sin(M_PI * 0.5 * (double)n + M_PI * 0.25);
			bufL[i] = v; bufR[i] = v;
		}
		if (b == 16) bufL[10] = NAN; // one NaN, left only
		MaximizerProcess(&g_lib, BLK);
		float maxL = 0, maxR = 0; int nanOut = 0;
		for (int i = 0; i < BLK; i++)
		{
			if (!isfinite(bufL[i]) || !isfinite(bufR[i])) nanOut++;
			if (fabsf(bufL[i]) > maxL) maxL = fabsf(bufL[i]);
			if (fabsf(bufR[i]) > maxR) maxR = fabsf(bufR[i]);
		}
		if (b == BLOCKS - 1)
		{
			failed = !stateFinite(&m->smp[0]) || fabsf(maxL - maxR) > 1e-3f || nanOut;
			printf("  last block: left filter state finite %d, left peak %.4f, right peak %.4f, non-finite %d  %s\n",
				stateFinite(&m->smp[0]), maxL, maxR, nanOut, failed ? "FAIL" : "ok");
		}
	}
	printf(failed ? "FAIL\n" : "PASS\n");
	return failed;
}
