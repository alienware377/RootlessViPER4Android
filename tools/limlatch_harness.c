// Does one non-finite sample kill the master limiter for good?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) and
//   app/src/main/cpp/EELStdOutExtension.c with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/limlatch_harness.c \
//       <engine objects> -lm -llog -o tools/limlatch_x86
//
// The default output limiter (mode 0) follows an envelope that only rises
// to a new peak or decays toward the signal. After one +Inf or -Inf sample
// the envelope is Inf forever, so the gain is 0 and the whole output is
// silent; after one NaN the comparison is always false, the envelope stays
// NaN and every sample out is NaN. Nothing but a new engine reset it: not a
// new threshold or release, not changing mode and back, not a rate change.
// Nothing upstream filters non-finite samples, and several effects emit them
// once they blow up.
//
// Real engine, real entry point: 1 s of a -6 dB tone, one block with the bad
// sample, then 10 s of tone, which must come through.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

#define N 1024
static JamesDSPLib g;
static float in[2 * N], out[2 * N];
static long t;

static void fresh(int mode)
{
	static int inited = 0;
	if (inited) JamesDSPFree(&g);
	memset(&g, 0, sizeof(g));
	JamesDSPInit(&g, N, 48000);
	inited = 1;
	JLimiterSetCoefficients(&g, 0.0, 60.0); /* 0 dB threshold, 60 ms release */
	JLimiterSetMode(&g, mode);
	t = 0;
}
/* one block where frame 0 carries the spike in the chosen channels, rest silence */
static void spike(float v, int chL, int chR)
{
	memset(in, 0, sizeof(in));
	if (chL) in[0] = v;
	if (chR) in[1] = v;
	g.processFloatMultiplexd(&g, in, out, N);
}
/* seconds of clean -6 dBFS 1 kHz tone; report peak and non-finite count */
static void tone(double sec, double *peak, long *nf, long *tot)
{
	*peak = 0; *nf = 0; *tot = 0;
	int blocks = (int)(sec * 48000 / N);
	for (int b = 0; b < blocks; b++)
	{
		for (int i = 0; i < N; i++, t++)
			in[2 * i] = in[2 * i + 1] = 0.5f * (float)sin(2 * M_PI * 1000.0 * t / 48000.0);
		g.processFloatMultiplexd(&g, in, out, N);
		for (int i = 0; i < 2 * N; i++)
		{
			(*tot)++;
			if (!isfinite(out[i])) (*nf)++;
			else if (fabsf(out[i]) > *peak) *peak = fabsf(out[i]);
		}
	}
}
static int dead(double pk, long nf) { return !(pk > 0.3 && nf == 0); }

int main(void)
{
	JamesDSPGlobalMemoryAllocation();
	int fails = 0;
	double pk; long nf, tot;

	printf("defaults after JamesDSPInit: limiter.mode=%d\n", (fresh(0), g.limiter.mode));

	struct { const char *name; int mode; float v; int l, r; } cs[] = {
		{ "mode0 control: finite 1e30 spike", 0, 1e30f, 1, 1 },
		{ "mode0 one +Inf sample (L+R)", 0, INFINITY, 1, 1 },
		{ "mode0 one -Inf sample (R only)", 0, -INFINITY, 0, 1 },
		{ "mode0 one NaN sample (L+R)", 0, NAN, 1, 1 },
		{ "mode0 one NaN sample (L only)", 0, NAN, 1, 0 },
		{ "mode0 one NaN sample (R only)", 0, NAN, 0, 1 },
		{ "mode1 (tanh) one +Inf", 1, INFINITY, 1, 1 },
		{ "mode1 (tanh) one NaN", 1, NAN, 1, 1 },
		{ "mode3 (classic) one +Inf", 3, INFINITY, 1, 1 },
		{ "mode3 (classic) one NaN", 3, NAN, 1, 1 },
	};
	for (unsigned k = 0; k < sizeof(cs) / sizeof(cs[0]); k++)
	{
		fresh(cs[k].mode);
		double p0; long n0, t0;
		tone(1.0, &p0, &n0, &t0); /* pre-roll: proves the chain passes the tone */
		spike(cs[k].v, cs[k].l, cs[k].r);
		tone(10.0, &pk, &nf, &tot);
		int d = dead(pk, nf);
		printf("%-36s pre peak %.4f | env=%g | 10 s later peak %.4f nonfinite %ld/%ld -> %s\n",
		       cs[k].name, p0, g.limiter.envOverThreshold, pk, nf, tot, d ? "DEAD (latched)" : "recovered");
		if (k == 0 && d) { printf("  control broke: harness invalid\n"); return 9; }
		if (d) fails++;
	}

	printf(fails ? "FAIL (%d)\n" : "PASS\n", fails);
	return fails != 0;
}

// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }
