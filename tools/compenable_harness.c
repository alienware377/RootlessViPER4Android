// Does switching the compander on before its settings hang the audio thread?
//
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/compenable_harness.c \
//       <engine objects> -lm -llog -o tools/compenable_x86
//
// CompressorConstructor builds no transform; only CompressorSetParam does.
// Enabled straight after JamesDSPInit, the compander ran with every length at
// 0, and CompressorProcess's loop advanced by min(n - offset, 0) forever. In
// root mode one "on" (id 1200) from any app was enough to hang the audio
// server's mixer thread. Found by tools/paramfuzz_harness.cpp.
//
// A fresh engine, the compander on with no settings sent, then a second of
// audio for each transform. An alarm turns a hang into a failure.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <signal.h>
#include <unistd.h>
#include "jdsp_header.h"

static JamesDSPLib g;
static float in[2 * 1024], out[2 * 1024];

static void hung(int sig)
{
	(void)sig;
	static const char msg[] = "  audio thread still busy after 10 s: HUNG\nFAIL\n";
	write(1, msg, sizeof(msg) - 1);
	_exit(1);
}

static long second(void)
{
	long bad = 0;
	unsigned r = 3;
	for (int b = 0; b < 47; b++)
	{
		for (int i = 0; i < 2048; i++)
		{
			r = r * 1664525u + 1013904223u;
			in[i] = ((int)(r >> 9) - (1 << 22)) / (float)(1 << 23) * 0.3f;
		}
		g.processFloatMultiplexd(&g, in, out, 1024);
		for (int i = 0; i < 2048; i++)
			if (!isfinite(out[i])) bad++;
	}
	return bad;
}

int main(void)
{
	signal(SIGALRM, hung);
	alarm(10);
	JamesDSPGlobalMemoryAllocation();
	int fails = 0;

	JamesDSPInit(&g, 4096, 48000);
	CompressorEnable(&g, 1);
	long bad = second();
	printf("  on straight after init, 1 s of audio: done, %ld non-finite  %s\n", bad, bad ? "FAIL" : "ok");
	fails += bad != 0;

	/* And each transform chosen afterwards still runs. */
	for (int tf = 0; tf < 4; tf++)
	{
		CompressorSetParam(&g, 0.22f, 2, tf, 1);
		bad = second();
		printf("  transform %d, 1 s of audio: done, %ld non-finite  %s\n", tf, bad, bad ? "FAIL" : "ok");
		fails += bad != 0;
	}
	JamesDSPFree(&g);
	printf("%s\n", fails ? "FAIL" : "PASS");
	return fails != 0;
}
