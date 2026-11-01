// Global mode draws the Multiband EQ's curve in Kotlin (globalfx/HermiteCurve.kt)
// instead of in the engine, because in global mode the engine never runs. If
// that port's slopes differ from interpolation.c, the same card sounds
// different in the two modes and nothing else would notice. This prints the
// engine's own PCHIP and makima values for a fixed set of nodes, built exactly
// as MultimodalEqualizerAxisInterpolation builds them (end nodes at 0 Hz and
// 24 kHz with copied gains), so the numbers can be pasted into
// EqTargetBuilderTest and checked there on every build.
//
// It also checks the engine side against itself: the curve must pass through
// every node, and a deliberately broken slope rule must move the curve between
// nodes, so the stored numbers are known to be able to tell the two apart.
//
// Built for x86_64 and run on the emulator:
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/globalfx_interp_harness.c $J/generalDSP/interpolation.c -lm -o gfxinterp
//   adb -s emulator-5554 push gfxinterp /data/local/tmp/ && \
//   adb -s emulator-5554 shell /data/local/tmp/gfxinterp
//
// -DBROKEN_SLOPES zeroes every interior slope after the engine computed them
// (a Hermite curve with flat tangents at each node) and the "between nodes"
// check fails.
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include "jdsp_header.h"

// generalProg.c's upper_bound, the one helper interpolation.c needs. Copied
// rather than linked so the harness does not drag in the rest of that file.
int upper_bound(double *a, int n, double x)
{
	int l = 0;
	int h = n;
	while (l < h)
	{
		int mid = l + (h - l) / 2;
		if (x >= a[mid])
			l = mid + 1;
		else
			h = mid;
	}
	return l;
}

#define NUMPTS 15
static const double nodeFreq[NUMPTS] = { 25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000 };
static const double nodeGain[NUMPTS] = { 6, 5, 3.5, 1, -2, -2.5, 0, 1.5, 0.5, -1, 2, 4, 3, -3, -6 };
static const double probes[] = { 10, 30, 50, 80, 130, 200, 320, 500, 800, 1300, 2000, 3200, 5000, 8000, 12500, 18000, 22000 };
#define NPROBES (int)(sizeof(probes) / sizeof(probes[0]))

static int failures = 0;

static void build(double *f, double *g)
{
	for (int i = 0; i < NUMPTS; i++) { f[i + 1] = nodeFreq[i]; g[i + 1] = nodeGain[i]; }
	f[0] = 0.0; g[0] = g[1];
	f[NUMPTS + 1] = 24000.0; g[NUMPTS + 1] = g[NUMPTS];
}

static void run(const char *name, int useMakima, double *out)
{
	double f[NUMPTS + 2], g[NUMPTS + 2];
	build(f, g);
	ierper ip;
	initIerper(&ip, NUMPTS + 2);
	if (useMakima)
		makima(&ip, f, g, NUMPTS + 2, 1, 1);
	else
		pchip(&ip, f, g, NUMPTS + 2, 1, 1);
#ifdef BROKEN_SLOPES
	for (int i = 1; i < NUMPTS + 1; i++) ip.s[i] = 0.0;
#endif
	for (int i = 0; i < NUMPTS; i++)
	{
		double v = getValueAt(&ip.cb, nodeFreq[i]);
		if (fabs(v - nodeGain[i]) > 1e-9)
		{
			printf("FAIL %s does not pass through node %d: %.9f vs %.9f\n", name, i, v, nodeGain[i]);
			failures++;
		}
	}
	printf("%s:", name);
	for (int i = 0; i < NPROBES; i++)
	{
		out[i] = getValueAt(&ip.cb, probes[i]);
		printf(" %.9f", out[i]);
	}
	printf("\n");
	freeIerper(&ip);
}

int main(void)
{
	double pc[NPROBES], mk[NPROBES];
	run("pchip", 0, pc);
	run("makima", 1, mk);

	// Reference numbers from the unbroken build, as stored in
	// EqTargetBuilderTest. The broken build has to land away from them.
	static const double pchipRef[NPROBES] = {
#include "globalfx_interp_ref.h"
	};
	double worst = 0;
	for (int i = 0; i < NPROBES; i++)
	{
		double d = fabs(pc[i] - pchipRef[i]);
		if (d > worst) worst = d;
	}
	if (worst > 1e-6)
	{
		printf("FAIL between nodes: pchip differs from the stored reference by %.6f dB\n", worst);
		failures++;
	}
	else
		printf("ok   between nodes: pchip matches the stored reference (worst %.2e dB)\n", worst);

	printf(failures ? "FAILED %d\n" : "PASS\n", failures);
	return failures ? 1 : 0;
}
