// Does the convolver's partition choice stay inside its benchmark tables?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) and
//   app/src/main/cpp/EELStdOutExtension.c with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/convbench_harness.c \
//       <engine objects> -lm -llog -o tools/convbench_x86
//
// selectConvPartitions indexes two 10-entry benchmark tables with
// log2(block) - 6, which is 10 for a 65536-frame block, and the HAL accepts
// exactly 65536. That read one past both tables: the first lands on the
// second table's first entry, the second on whatever global follows. With the
// two-segment loop skipped as well, the type it returned was never assigned.
//
// The answer for a 65536 block must not depend on memory outside the tables:
// changing the entries it aliased (the first of the second table, and the
// first of each) must not change it.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "jdsp_header.h"

// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }

extern double convbench_c0[MAX_BENCHMARK];
extern double convbench_c1[MAX_BENCHMARK];
static JamesDSPLib g;

static int pick(size_t block, unsigned int *seg2)
{
	g.blockSize = block;
	*seg2 = 0;
	return selectConvPartitions(&g, 48000, seg2);
}

int main(void)
{
	memset(&g, 0, sizeof(g));
	g.fs = 48000.0f;
	unsigned int sa, sb;
	const int a = pick(65536, &sa);
	const double c0 = convbench_c0[0], c1 = convbench_c1[0];
	convbench_c0[0] = convbench_c1[0] = 1e300;
	const int b = pick(65536, &sb);
	convbench_c0[0] = c0;
	convbench_c1[0] = c1;
	const int ok = a == b && sa == sb;
	printf("  65536-frame block: type %d seg2 %u, with out-of-range entries changed type %d seg2 %u  %s\n",
		a, sa, b, sb, ok ? "ok" : "FAIL");
	printf(ok ? "PASS\n" : "FAIL\n");
	return !ok;
}
