// Does an out-of-range compander transform setting stay safe?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/comptfres_harness.c \
//       <engine objects> -lm -llog -o tools/comptfres_x86
//
// The time-frequency transform is 0 to 3 (STFT, CWT, CWT with STFT inverse,
// time domain). Nothing checked the range, and the HAL's id 115 passes
// whatever float an app sends, rounded. FFTCompanderInit sets the transform's
// function pointer only for 0, 1 and 2; any other value fell to the time
// domain lengths but left the pointer alone, and CompressorProcess sends
// anything below 3 down the FFT path. So -1 jumped through a null pointer in
// audioserver (or a stale one, sized for 2048 or 4096). 4 and up ran the time
// domain path on gammatone coefficients nobody had set.
//
// Each value runs in its own process: set it, enable, process 20 blocks of a
// sine. The output must stay finite and in range.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <unistd.h>
#include <sys/wait.h>
#include "jdsp_header.h"

// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }
void setStdOutHandler(void *f, void *u) { (void)f; (void)u; }
int isStdOutHandlerSet(void) { return 0; }

#define NFR 1024
static float in[NFR * 2], out[NFR * 2];

static int child(int tfres)
{
	static double f[7] = { 95, 200, 400, 800, 1600, 3400, 7500 };
	static double gn[7] = { 0.6, -0.4, 0.3, -0.6, 0.5, -0.2, 0.4 };
	JamesDSPLib *dsp = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPGlobalMemoryAllocation();
	JamesDSPInit(dsp, NFR, 48000);
	CompressorSetParam(dsp, 0.22f, 2, tfres, 0);
	CompressorSetGain(dsp, f, gn, 1);
	CompressorEnable(dsp, 1);
	double peak = 0;
	int bad = 0;
	for (int b = 0; b < 20; b++)
	{
		for (int i = 0; i < NFR; i++)
			in[2 * i] = in[2 * i + 1] = 0.2f * sinf(2.0f * 3.14159265f * 440.0f * (b * NFR + i) / 48000.0f);
		dsp->processFloatMultiplexd(dsp, in, out, NFR);
		for (int i = 0; i < NFR * 2; i++)
		{
			if (!isfinite(out[i]))
				bad++;
			else if (fabs(out[i]) > peak)
				peak = fabs(out[i]);
		}
	}
	printf("    tfresolution %d stored as %d, peak %.3f, non-finite %d\n", tfres, dsp->comp.tfresolution, peak, bad);
	return bad || peak > 2.0 || peak < 0.01;
}

static int failures = 0;
static void run(int tfres)
{
	fflush(stdout);
	pid_t pid = fork();
	if (!pid)
	{
		int r = child(tfres);
		fflush(stdout);
		_exit(r);
	}
	int st = 0;
	waitpid(pid, &st, 0);
	const int ok = WIFEXITED(st) && !WEXITSTATUS(st);
	printf("  transform %2d: %s\n", tfres, ok ? "ok" : WIFSIGNALED(st) ? "FAIL (crashed)" : "FAIL (bad output)");
	if (!ok)
		failures++;
}

int main(void)
{
	setvbuf(stdout, 0, _IONBF, 0);
	for (int t = -2; t <= 5; t++)
		run(t);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
