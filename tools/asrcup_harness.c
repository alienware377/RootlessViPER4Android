// Does the engine survive stream rates below 44.1 kHz?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   Compile every engine source (jdsp/*.c, Effects/**/*.c, generalDSP/*.c,
//   the same list as tools/cc86.rsp) with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J -Wl,--wrap=malloc \
//       tools/asrcup_harness.c <engine objects> -lm -llog -o tools/asrcup_x86
//
// The engine only runs at 44.1 or 48 kHz; any other stream rate goes through
// a resampler (the ASRC) on the way in and out. Its scratch layout assumed
// the resampler always shrinks the block, which holds above 48 kHz. Below
// 44.1 kHz it grows it: 4096 frames at 16 kHz become 12288 at 48 kHz, at
// 8 kHz 24576. The upsampled block was still written into areas sized for
// 4096, so the left and right channels overlapped each other (the right
// channel came out carrying the left's audio) and at 12 kHz and below it ran
// up to 48 KB off the end of the allocation. The rooted HAL hands any rate
// AudioFlinger configures straight to JamesDSPInit, so that is audioserver.
//
// Every allocation of 32 KB or more gets a 64 KB guard of 0xA5 behind it.
// Each rate is fed 12 blocks of 4096 frames, left a 300 Hz sine at 0.5, right
// silence, all effects off. The scratch block's guard must be intact, the
// right channel must stay silent and the left must still carry the sine.
// Each rate is set up both by JamesDSPInit (the HAL's way) and by a move from
// 48 kHz with JamesDSPSetSampleRate (the app engine's way).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

#define N 4096
#define BIG 32768
#define GUARD 65536
#define MAXREC 8192
static struct { unsigned char *p; size_t n; } rec[MAXREC];
static int nrec = 0;
static int failures = 0;

void *__real_malloc(size_t);
void *__wrap_malloc(size_t n)
{
	if (n < BIG || nrec >= MAXREC)
		return __real_malloc(n);
	unsigned char *p = (unsigned char *)__real_malloc(n + GUARD);
	if (!p)
		return p;
	memset(p + n, 0xA5, GUARD);
	rec[nrec].p = p;
	rec[nrec].n = n;
	nrec++;
	return p;
}

// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }

static void run(float rate, int viaSetRate)
{
	static float x[N * 2], y[N * 2];
	JamesDSPLib *lib = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	if (viaSetRate)
	{
		// the app engine's way: made at 48 kHz with a small block, then moved,
		// so the layout comes from JamesDSPSetSampleRate and then
		// JamesDSPReallocateBlock on the first full-size block
		JamesDSPInit(lib, 128, 48000.f);
		JamesDSPSetSampleRate(lib, rate, 0);
	}
	else
		JamesDSPInit(lib, N, rate);
	double rmsL = 0, rmsR = 0, ph = 0, w = 2.0 * M_PI * 300.0 / rate;
	int cnt = 0;
	for (int b = 0; b < 12; b++)
	{
		for (int i = 0; i < N; i++)
		{
			x[2 * i] = 0.5f * (float)sin(ph);
			x[2 * i + 1] = 0.0f;
			ph += w;
		}
		lib->processFloatMultiplexd(lib, x, y, N);
		if (b >= 4)
			for (int i = 0; i < N; i++)
			{
				rmsL += y[2 * i] * (double)y[2 * i];
				rmsR += y[2 * i + 1] * (double)y[2 * i + 1];
				cnt++;
			}
	}
	rmsL = sqrt(rmsL / cnt);
	rmsR = sqrt(rmsR / cnt);
	long changed = -1;
	for (int i = 0; i < nrec; i++)
		if (rec[i].p == (unsigned char *)lib->tmpBuffer[0])
		{
			changed = 0;
			for (long k = 0; k < GUARD; k++)
				changed += rec[i].p[rec[i].n + k] != 0xA5;
		}
	// an unguarded scratch block (under 32 KB) is only possible without the ASRC
	if (changed < 0 && lib->enableASRC)
		changed = 1 << 30;
	if (changed < 0)
		changed = 0;
	int ok = changed == 0 && rmsR < 1e-3 && rmsL > 0.3 && rmsL < 0.4;
	printf("  %s %6.0f Hz  fs %5.0f  ASRC %d  guard bytes changed %6ld  RMS L %.4f R %.5f  %s\n",
		viaSetRate ? "set rate" : "init    ", rate, lib->fs, lib->enableASRC, changed, rmsL, rmsR, ok ? "ok" : "FAIL");
	if (!ok)
		failures++;
}

int main(void)
{
	setvbuf(stdout, NULL, _IONBF, 0);
	JamesDSPGlobalMemoryAllocation();
	const float rates[] = { 96000.f, 48000.f, 44100.f, 32000.f, 24000.f, 22050.f, 16000.f, 12000.f, 11025.f, 8000.f };
	for (int i = 0; i < (int)(sizeof(rates) / sizeof(rates[0])); i++)
		run(rates[i], 0);
	for (int i = 0; i < (int)(sizeof(rates) / sizeof(rates[0])); i++)
		run(rates[i], 1);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
