// Can the compander's settings change while audio runs through it?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   with the real jdspController.c, so the real mutex (no lockstub), then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/comprace_harness.c \
//       <engine objects> -lm -llog -o tools/comprace_x86
//
// A change of time constant, granularity or transform re-runs
// FFTCompanderInit, which rebuilds the compander's lengths, windows, tables
// and its transform function pointer. The settings thread did that with no
// lock while the audio thread ran the compander, also with no lock. Switching
// to the time domain transform while it played reliably crashed: the audio
// thread was still in the CWT inversion with Init's new 32-sample lengths,
// and an unsigned index wrapped to a read 16 GB away.
//
// The audio thread here goes through the real chain dispatch. The settings
// thread does exactly what setCompander does now: under the lock, SetParam
// (cycling all four transforms and five granularities), SetGain and Enable.
// Each run is 4 s in a child process with an alarm, and the output must stay
// finite and sane.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>
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
static JamesDSPLib *dsp;
static volatile int stop = 0;
static volatile long blocks = 0, changes = 0, bad = 0;
static double freqs[7] = { 95, 200, 400, 800, 1600, 3400, 7500 };
static double gains[7] = { 0.6, -0.4, 0.3, -0.6, 0.5, -0.2, 0.4 };
static int pace;

static void *audio(void *a)
{
	(void)a;
	double ph = 0;
	while (!stop)
	{
		for (int i = 0; i < NFR; i++)
		{
			float s = 0.2f * (float)sin(ph);
			ph += 2 * M_PI * 440.0 / 48000.0;
			in[2 * i] = s;
			in[2 * i + 1] = -s;
		}
		dsp->processFloatMultiplexd(dsp, in, out, NFR);
		for (int i = 0; i < NFR * 2; i++)
			if (!(fabsf(out[i]) < 4.0f))
			{
				bad++;
				break;
			}
		blocks++;
	}
	return 0;
}
static void *settings(void *a)
{
	(void)a;
	for (int k = 0; !stop; k++)
	{
		jdsp_lock(dsp); // as setCompander does now
		CompressorSetParam(dsp, (k & 1) ? 0.22f : 0.3f, (k / 4) % 5, k % 4, 0);
		CompressorSetGain(dsp, freqs, gains, 1);
		CompressorEnable(dsp, 1);
		jdsp_unlock(dsp);
		changes++;
		usleep(pace + (k % 7) * 300);
	}
	return 0;
}

static int child(void)
{
	alarm(10);
	dsp = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPGlobalMemoryAllocation();
	JamesDSPInit(dsp, 128, 48000);
	CompressorSetParam(dsp, 0.22f, 2, 0, 0);
	CompressorSetGain(dsp, freqs, gains, 1);
	CompressorEnable(dsp, 1);
	dsp->processFloatMultiplexd(dsp, in, out, NFR); // block-size refresh first
	pthread_t ta, tb;
	pthread_create(&ta, 0, audio, 0);
	pthread_create(&tb, 0, settings, 0);
	sleep(4);
	stop = 1;
	pthread_join(ta, 0);
	pthread_join(tb, 0);
	printf("    %ld blocks, %ld changes, %ld blocks out of range\n", blocks, changes, bad);
	return bad != 0;
}

static int failures = 0;
static void run(int p)
{
	pace = p;
	fflush(stdout);
	pid_t pid = fork();
	if (!pid)
	{
		int r = child();
		fflush(stdout);
		_exit(r);
	}
	int st = 0;
	waitpid(pid, &st, 0);
	const int ok = WIFEXITED(st) && !WEXITSTATUS(st);
	printf("  changes every %.1f ms or so: %s\n", p / 1000.0, ok ? "ok" :
		WIFSIGNALED(st) ? (WTERMSIG(st) == SIGALRM ? "FAIL (froze)" : "FAIL (crashed)") : "FAIL (bad output)");
	if (!ok)
		failures++;
}

int main(void)
{
	setvbuf(stdout, 0, _IONBF, 0);
	run(500);
	run(1500);
	run(3000);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
