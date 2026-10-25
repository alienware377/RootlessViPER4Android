// Does one bad sample latch the dynamic bass boost?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/dbbnan_harness.c $J/Effects/dbb.c $J/generalDSP/digitalFilters.c \
//       $J/generalDSP/generalProg.c tools/lockstub.c -lm -o tools/dbbnan_x86
//
// The bass boost measures the bass with a 16-point transform on a decimated
// copy of the input, smooths the boost it wants with a one-pole, and feeds it
// to two state variable filters. One NaN sample in made the transform NaN,
// every comparison on it false, the smoothed boost NaN and then the filter
// states NaN, and none of that is ever reset: Enable only sets a flag and the
// slider does not touch them. So the bass boost put out NaN for the life of
// the engine, card off and on and slider moves included. An Inf did the same.
//
// A 60 Hz tone through the bass boost as the wrapper sets it up, one block
// with a bad sample, then 1 s more, which must be finite and boosted.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

static JamesDSPLib g;
static float L[1024], R[1024];
static long t = 0;
static int failures = 0;

static int run(int blocks, double *pk)
{
	int nf = 0;
	*pk = 0;
	for (int b = 0; b < blocks; b++)
	{
		for (int i = 0; i < 1024; i++, t++)
			L[i] = R[i] = 0.2f * (float)sin(2 * M_PI * 60.0 * t / g.fs);
		BassBoostProcess(&g, 1024);
		for (int i = 0; i < 1024; i++)
		{
			if (!isfinite(L[i]) || !isfinite(R[i])) nf++;
			else if (fabs(L[i]) > *pk) *pk = fabs(L[i]);
		}
	}
	return nf;
}

static void one(const char *label, float bad)
{
	memset(&g, 0, sizeof(g));
	g.fs = 48000.0f;
	g.tmpBuffer[0] = L;
	g.tmpBuffer[1] = R;
	BassBoostConstructor(&g); // as JamesDSPInit
	BassBoostSetParam(&g, 5.0f);
	BassBoostSetParam(&g, 10.0f); // as setBassBoost
	BassBoostEnable(&g);
	double before, after;
	run(47, &before);
	memset(L, 0, sizeof(L));
	memset(R, 0, sizeof(R));
	L[100] = bad;
	BassBoostProcess(&g, 1024);
	int nf = run(47, &after);
	const int ok = nf == 0 && after > 0.3;
	printf("  %-24s peak before %.4f, 1 s after %.4f, non-finite %5d  %s\n", label, before, after, nf, ok ? "ok" : "FAIL");
	if (!ok)
		failures++;
}

int main(void)
{
	one("one NaN sample", NAN);
	one("one +Inf sample", INFINITY);
	one("one -Inf sample", -INFINITY);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
