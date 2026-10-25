// Can the echo's feedback loop run away?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/echofb_harness.c $J/Effects/echodelay.c tools/lockstub.c -lm -o tools/echofb_x86
//
// The echo's filter sits inside its feedback loop, and a resonant state
// variable filter has a peak gain above 1: about q for the band pass, and
// q / sqrt(1 - 1/(4 q^2)) for the low and high pass, with q up to 5. Feedback
// goes to 0.98, and with distortion at its default of 0 nothing else bounds
// the loop. So the loop gain went past 1 and the echoes grew without limit:
// with the default resonance and feedback at 100 %, a 100 ms burst grew from
// 0.25 to over 100 in 30 s; band pass at full resonance did it at the default
// 40 % feedback. Once the filter overflowed its state went NaN and the echo
// stayed silent until it was switched off and on.
//
// 100 ms of noise at -12 dB, then silence. The loudest echo in the last 5 s
// must not be louder than in the first 5 s, the first 5 s must stay under 2
// (the resonance can lift the burst a little, but not run away with it), and
// nothing non-finite may come out. Then the filter state is poisoned with a
// NaN, as an overflow did, and a new burst must still produce an echo.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

static JamesDSPLib g;
static float L[1024], R[1024];
static int failures = 0;

// argument order as JamesDspBaseEngine sends it
static void setp(float fb, float res, int filt)
{
	EchoDelaySetParam(&g, 100, 350, 20, 0, 0, 1, 50, fb, 12000, res, filt,
		100, 24, 0, 0, 0, 0, 0, 1, 0, 50, 0, 0, 35, 100);
}

static void burst(int b, unsigned *s)
{
	for (int i = 0; i < 1024; i++)
	{
		float v = 0;
		if (b < 5)
		{
			*s = *s * 1664525u + 1013904223u;
			v = 0.25f * ((float)(*s >> 9) / 4194304.0f - 1.0f);
		}
		L[i] = R[i] = v;
	}
}

static void run(const char *tag, float fb, float res, int filt)
{
	memset(&g, 0, sizeof(g));
	g.fs = 48000;
	g.tmpBuffer[0] = L;
	g.tmpBuffer[1] = R;
	setp(fb, res, filt);
	EchoDelayEnable(&g);
	unsigned s = 12345;
	const int blocks = 30 * 48000 / 1024, win = 5 * 48000 / 1024;
	double first = 0, last = 0;
	int nonfin = 0;
	for (int b = 0; b < blocks; b++)
	{
		burst(b, &s);
		EchoDelayProcess(&g, 1024);
		for (int i = 0; i < 1024; i++)
		{
			if (!isfinite(L[i]))
			{
				nonfin++;
				continue;
			}
			if (b < win && fabs(L[i]) > first) first = fabs(L[i]);
			if (b >= blocks - win && fabs(L[i]) > last) last = fabs(L[i]);
		}
	}
	// what an overflow used to leave behind: does the echo come back?
	g.echoDelay.svfIc1[0] = g.echoDelay.svfIc1[1] = NAN;
	double again = 0;
	for (int b = 0; b < 60; b++)
	{
		burst(b, &s);
		EchoDelayProcess(&g, 1024);
		if (b >= 20)
			for (int i = 0; i < 1024; i++)
				if (isfinite(L[i]) && fabs(L[i]) > again) again = fabs(L[i]);
	}
	const int ok = !nonfin && last <= first && first < 2.0 && again > 1e-4;
	printf("  %-30s peak 0-5 s %9.3g  25-30 s %9.3g  non-finite %d  echo after NaN %.3g  %s\n",
		tag, first, last, nonfin, again, ok ? "ok" : "FAIL");
	if (!ok)
		failures++;
}

int main(void)
{
	run("defaults (LP, res 10, fb 40)", 40, 10, 0);
	run("filter off, fb 100", 100, 0, 3);
	run("LP res 10, fb 100", 100, 10, 0);
	run("LP res 50, fb 60", 60, 50, 0);
	run("HP res 100, fb 100", 100, 100, 1);
	run("BP res 100, fb 40", 40, 100, 2);
	run("BP res 100, fb 100", 100, 100, 2);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
