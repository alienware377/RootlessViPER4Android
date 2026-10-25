// Can the crossfeed be changed while audio runs through it?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   with the real jdspController.c, so the real mutex (no lockstub), then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/xfrace_harness.c \
//       <engine objects> -lm -llog -o tools/xfrace_x86
//
// setCrossfeed runs CrossfeedChangeMode and CrossfeedEnable on the settings
// thread while the audio thread is in CrossfeedProcess. Three things went
// wrong. The dispatch ran CrossfeedProcess without the lock that
// CrossfeedEnable rebuilds the convolvers under, so a block read freed
// convolvers or a null one, or tore down the two-stage convolver's worker
// handshake and froze both threads. The rebuild test asked for both long
// convolvers when only one is ever built, so every enable rebuilt
// everything, even a resend of the same mode. And CrossfeedProcess read the
// mode again on every sample, so a switch from BS2B to an HRTF mode inside a
// block indexed the two-entry bs2b[] with 2 to 5 and wrote filter state over
// the DDC fields after it.
//
// Each pattern runs two real threads for 3 s in a child process with an 8 s
// alarm, so a crash or a freeze both fail it. Afterwards the DDC pointers that
// follow the crossfeed state must still be null.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
static volatile long blocks = 0, changes = 0;
static int ma, mb;

static void *audio(void *a)
{
	(void)a;
	while (!stop)
	{
		dsp->processFloatMultiplexd(dsp, in, out, NFR);
		blocks++;
	}
	return 0;
}
static void *ui(void *a)
{
	(void)a;
	for (int k = 0; !stop; k++)
	{
		// exactly what setCrossfeed(enable = true, mode) does
		CrossfeedChangeMode(dsp, (k & 1) ? mb : ma);
		CrossfeedEnable(dsp, 1);
		changes++;
		usleep(2000);
	}
	return 0;
}

static void setup(void)
{
	dsp = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPGlobalMemoryAllocation();
	JamesDSPInit(dsp, 128, 48000);
	for (int i = 0; i < NFR * 2; i++)
		in[i] = 0.25f * (float)((i * 7919) % 2001 - 1000) / 1000.0f;
	CrossfeedChangeMode(dsp, ma);
	CrossfeedEnable(dsp, 1);
	dsp->processFloatMultiplexd(dsp, in, out, NFR); // block-size refresh first
}

static int child(void)
{
	alarm(8);
	setup();
	pthread_t ta, tb;
	pthread_create(&ta, 0, audio, 0);
	pthread_create(&tb, 0, ui, 0);
	sleep(3);
	stop = 1;
	pthread_join(ta, 0);
	pthread_join(tb, 0);
	const int clean = !dsp->vdcFl.sosPointer && !dsp->vdcFl.oldFile;
	printf("    %ld blocks, %ld changes, DDC fields %s\n", blocks, changes, clean ? "untouched" : "OVERWRITTEN");
	return clean ? 0 : 1;
}

static int failures = 0;
static void run(int a, int b)
{
	ma = a;
	mb = b;
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
	printf("  modes %d and %d: %s\n", a, b, ok ? "ok" :
		WIFSIGNALED(st) ? (WTERMSIG(st) == SIGALRM ? "FAIL (froze)" : "FAIL (crashed)") : "FAIL");
	if (!ok)
		failures++;
}

int main(void)
{
	setvbuf(stdout, 0, _IONBF, 0);
	ma = 5;
	setup();
	void *before = dsp->advXF.conv[0];
	CrossfeedEnable(dsp, 1);
	const int rebuilt = before != (void *)dsp->advXF.conv[0];
	printf("  resending the same mode rebuilds the convolvers: %s\n", rebuilt ? "yes  FAIL" : "no   ok");
	failures += rebuilt;
	run(3, 5); // HRTF mode changes
	run(5, 5); // the same mode sent again and again
	run(1, 2); // BS2B to HRTF and back
	run(0, 4);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
