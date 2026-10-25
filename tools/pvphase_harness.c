// Does the smooth pitch mode stay in tune on a long held note?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/pvphase_harness.c $J/Effects/pitchshift.c $J/Effects/rubberband.c \
//       tools/lockstub.c -lm -ldl -o tools/pvphase_x86
//
// The phase vocoder adds each bin's phase advance to a float accumulator
// every hop, about k * pi / 2 for bin k, 187.5 hops a second. Only a
// detected transient re-anchors it, and the transient test is built not to
// fire on held material. Nothing wrapped it, so on a drone, a pad or a held
// vocal it grew without bound: 1.8e7 rad after two minutes, where one float
// step is already a whole radian. Every hop's advance then rounds the same
// wrong way, each bin drifts off frequency on its own, and after about 15
// minutes the high partials are noise.
//
// A 6 kHz sine is shifted up 2 semitones for 120 s. The accumulator must stay
// within one turn, and the last 0.68 s must still be a tone at the target:
// at least half the energy within 30 Hz of 6735 Hz, peaking within 2 Hz of it.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

static JamesDSPLib g;
#define BLK 960
static float L[BLK], R[BLK];
#define WIN 32768
static float cap[WIN];

static double gpow(double f, double fs)
{
	double s1 = 0, s2 = 0, w = 2 * M_PI * f / fs, c = 2 * cos(w);
	for (int i = 0; i < WIN; i++)
	{
		double h = 0.5 - 0.5 * cos(2 * M_PI * i / WIN);
		double s0 = c * s1 - s2 + cap[i] * h;
		s2 = s1;
		s1 = s0;
	}
	double re = s1 - s2 * cos(w), im = s2 * sin(w);
	return re * re + im * im;
}

int main(void)
{
	const double fin = 6000, fs = 48000, fout = fin * pow(2.0, 2.0 / 12.0);
	memset(&g, 0, sizeof g);
	g.fs = 48000.0f;
	g.tmpBuffer[0] = L;
	g.tmpBuffer[1] = R;
	PitchShiftSetParam(&g, 2.0f, 100.0f, PITCH_MODE_SMOOTH);
	PitchShiftEnable(&g);
	const long start = 120L * 48000;
	long t = 0;
	double ph = 0, dph = 2 * M_PI * fin / fs;
	while (t < start + WIN)
	{
		for (int i = 0; i < BLK; i++)
		{
			L[i] = R[i] = 0.3f * (float)sin(ph);
			ph += dph;
			if (ph > 2 * M_PI) ph -= 2 * M_PI;
		}
		PitchShiftProcess(&g, BLK);
		for (int i = 0; i < BLK; i++, t++)
			if (t >= start && t < start + WIN)
				cap[t - start] = L[i];
	}
	double tot = 0, best = 0, bf = 0;
	for (int i = 0; i < WIN; i++)
	{
		double h = 0.5 - 0.5 * cos(2 * M_PI * i / WIN);
		tot += cap[i] * h * cap[i] * h;
	}
	for (double f = fout - 30; f <= fout + 30; f += 1)
	{
		double p = gpow(f, fs);
		if (p > best) { best = p; bf = f; }
	}
	const double share = 2.0 * best / WIN / (tot + 1e-30);
	float mx = 0;
	for (int k = 0; k < PV_BINS; k++)
	{
		float a = fabsf(g.pitchShift.pvSumPhase[0][k]);
		if (a > mx) mx = a;
	}
	const int ok = mx <= 6.3f && share > 0.5 && fabs(bf - fout) <= 2.0;
	printf("  after 120 s: largest phase accumulator %.3g rad, tone at %.0f Hz (target %.0f), share %.1f dB  %s\n",
		mx, bf, fout, 10 * log10(share), ok ? "ok" : "FAIL");
	printf(ok ? "PASS\n" : "FAIL\n");
	return !ok;
}
