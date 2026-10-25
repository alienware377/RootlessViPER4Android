// Can the EQ curves change while a block size change rebuilds their convolvers?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   with the real jdspController.c, so the real mutex (no lockstub), then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/eqrace_harness.c \
//       <engine objects> -lm -llog -o tools/eqrace_x86
//   Add -fsanitize=address to every compile and the link to see use after free
//   reported directly rather than only when it happens to crash.
//
// With cached benchmarks, every change of callback block size makes the audio
// thread run JamesDSPRefreshConvolutions, which frees and reloads the GraphicEQ
// and multimodal EQ convolvers (the multimodal one with no lock at all). The
// settings thread meanwhile wrote new curves into those same convolvers'
// segments, and freed and replaced the kept GraphicEQ filters Enable was
// reading, with no lock either, and the chain ran both EQs unlocked. Any
// overlap was a heap write after free.
//
// The audio thread alternates 512 and 1024 frame blocks so every block takes
// the rebuild; the settings thread sends per-ear GraphicEQ curves and
// multimodal EQ bands as fast as it can. 4 s in a child with an alarm: it
// must neither crash nor freeze, and the output must stay finite.
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
extern char benchmarkEnable;

static float in[2048 * 2], out[2048 * 2];
static JamesDSPLib *dsp;
static volatile int stop = 0;
static volatile long blocks = 0, changes = 0, bad = 0;

static void *audio(void *a)
{
	(void)a;
	for (long k = 0; !stop; k++)
	{
		const size_t n = (k & 1) ? 1024 : 512;
		dsp->processFloatMultiplexd(dsp, in, out, n);
		for (size_t i = 0; i < n * 2; i++)
			if (!isfinite(out[i])) { bad++; break; }
		blocks++;
	}
	return 0;
}
static void *settings(void *a)
{
	(void)a;
	double f[15] = { 25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000 };
	double g[15];
	for (int k = 0; !stop; k++)
	{
		ArbitraryResponseEqualizerStringParserStereo(dsp,
			(k & 1) ? "GraphicEQ: 100 6; 1000 -3; 8000 2" : "GraphicEQ: 100 -2; 1000 4; 8000 -6",
			(k & 1) ? "GraphicEQ: 100 -6; 1000 3" : "GraphicEQ: 100 2; 1000 -4");
		for (int i = 0; i < 15; i++) g[i] = ((k + i) % 7) - 3.0;
		MultimodalEqualizerAxisInterpolation(dsp, k & 1, 0, f, g);
		changes++;
	}
	return 0;
}

static int child(void)
{
	alarm(15);
	JamesDSPGlobalMemoryAllocation();
	benchmarkEnable = 1; // as JamesDSP_Load_benchmark does with cached results
	dsp = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPInit(dsp, 128, 48000);
	for (int i = 0; i < 2048 * 2; i++) in[i] = 0.25f * (float)((i * 7919) % 2001 - 1000) / 1000.0f;
	ArbitraryResponseEqualizerStringParser(dsp, "GraphicEQ: 100 3; 1000 0");
	ArbitraryResponseEqualizerEnable(dsp, 1);
	MultimodalEqualizerEnable(dsp, 1);
	pthread_t ta, tb;
	pthread_create(&ta, 0, audio, 0);
	pthread_create(&tb, 0, settings, 0);
	sleep(4);
	stop = 1;
	pthread_join(ta, 0);
	pthread_join(tb, 0);
	printf("    %ld blocks, %ld curve changes, %ld blocks with non-finite output\n", blocks, changes, bad);
	return bad != 0;
}

int main(void)
{
	setvbuf(stdout, 0, _IONBF, 0);
	int failures = 0;
	for (int run = 0; run < 3; run++)
	{
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
		printf("  run %d: %s\n", run + 1, ok ? "ok" :
			WIFSIGNALED(st) ? (WTERMSIG(st) == SIGALRM ? "FAIL (froze)" : "FAIL (crashed)") : "FAIL (bad output)");
		failures += !ok;
	}
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
