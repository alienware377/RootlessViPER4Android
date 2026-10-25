// Do the vinyl's event envelopes come to rest at zero?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/vinylsub_harness.c $J/Effects/vinyl.c tools/lockstub.c -lm -o tools/vinylsub_x86
//
// The pop, crackle, click, sizzle and prickle envelopes decay by repeated
// multiplication, which under round to nearest never reaches 0: each one
// parks on a subnormal (popEnv on 1.3e-43, say) until the next event. On x86
// every operation on a subnormal costs 90 to 250 times a normal one, and the
// envelopes feed two filters as well, so a quiet passage with pops and clicks
// on cost 4 to 27 times the CPU on the x86 builds.
//
// Pops, clicks and prickle at 100 over 60 s of silence: no envelope may end a
// block as a subnormal.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

static JamesDSPLib g;
static float L[1024], R[1024];

static int sub(float x) { return fpclassify(x) == FP_SUBNORMAL; }

int main(void)
{
	memset(&g, 0, sizeof(g));
	g.tmpBuffer[0] = L;
	g.tmpBuffer[1] = R;
	g.fs = 48000.0f;
	// surface, crackle, size, pops, clicks, sizzle, hiss, prickle, rumble, wear, follow, mix
	VinylSetParam(&g, 0, 0, 30, 100, 100, 0, 0, 100, 0, 0, 0, 100);
	VinylEnable(&g);
	int blocks = 60 * 48000 / 1024, parked = 0;
	for (int b = 0; b < blocks; b++)
	{
		memset(L, 0, sizeof(L));
		memset(R, 0, sizeof(R));
		VinylProcess(&g, 1024);
		Vinyl *v = &g.vinyl;
		parked += sub(v->popEnv) || sub(v->clickEnv) || sub(v->prickleEnv) || sub(v->crackleEnv) || sub(v->sizzleEnv);
	}
	printf("  blocks ending with an envelope on a subnormal: %d of %d  %s\n", parked, blocks, parked ? "FAIL" : "ok");
	printf(parked ? "FAIL\n" : "PASS\n");
	return parked != 0;
}
