// Does the vinyl's event density follow a rate change?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/vinylrate_harness.c $J/Effects/vinyl.c tools/lockstub.c -lm -o tools/vinylrate_x86
//
// The crackle, pop, click, sizzle and prickle probabilities are per sample,
// worked out from the rate in VinylSetParam only. VinylRefresh, which
// JamesDSPSetSampleRate calls, redid the filters and decays but not these, so
// after a 48 to 44.1 kHz move every kind of event came about 8 % less often
// (827 crackles a second where 900 were set) until the card was touched.
//
// Set at 48 kHz, refresh at 44.1 kHz: the crackle rate must be 900 a second.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"
static JamesDSPLib g;
#define N 1024
static float L[N], R[N];
int main(void)
{
	memset(&g, 0, sizeof(g));
	g.tmpBuffer[0] = L; g.tmpBuffer[1] = R;
	g.fs = 48000.0f;
	/* crackle 100%, pops 100, clicks 100, sizzle 100, prickle 100 */
	VinylSetParam(&g, 0, 100, 50, 100, 100, 100, 0, 100, 0, 0, 0, 100);
	printf("at 48k : crackle/s=%.2f pop/s=%.3f click/s=%.2f sizzle/s=%.1f prickle/s=%.2f\n",
		g.vinyl.crackleRate*48000, g.vinyl.popRate*48000, g.vinyl.clickRate*48000,
		g.vinyl.sizzleRate*48000, g.vinyl.prickleRate*48000);
	g.fs = 44100.0f;
	VinylRefresh(&g);
	double c = g.vinyl.crackleRate*44100;
	printf("after 44.1k refresh: fs(vinyl)=%.0f crackle/s=%.2f pop/s=%.3f click/s=%.2f sizzle/s=%.1f prickle/s=%.2f\n",
		g.vinyl.fs, c, g.vinyl.popRate*44100, g.vinyl.clickRate*44100,
		g.vinyl.sizzleRate*44100, g.vinyl.prickleRate*44100);
	/* Empirical: count crackle triggers over 10 s of 44.1k audio by running the
	   same RNG/decision sequence: simplest is measuring expected value; also
	   compare to fresh SetParam at 44.1k. */
	JamesDSPLib h; memset(&h, 0, sizeof(h)); h.fs = 44100.0f;
	VinylSetParam(&h, 0, 100, 50, 100, 100, 100, 0, 100, 0, 0, 0, 100);
	printf("fresh SetParam at 44.1k: crackle/s=%.2f\n", h.vinyl.crackleRate*44100);
	int fail = fabs(c - 900.0) > 1.0;
	printf("%s: crackle density after rate change %.2f/s vs 900/s (%.1f%% off)\n",
		fail ? "FAIL" : "PASS", c, (c/900.0-1.0)*100.0);
	return fail;
}
