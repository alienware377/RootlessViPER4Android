// Does the Liveprog parser leave the engine lock free on every return path?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) and
//   app/src/main/cpp/EELStdOutExtension.c with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/lpunlock_harness.c \
//       <engine objects> -lm -llog -o tools/lpunlock_x86
//
// setLiveprog and setLiveprogSlot in the JNI wrapper called jdsp_unlock once
// more after the parser returned, a "workaround" for an upstream parser that
// returned with the lock held. This tree's parser unlocks on every path, so
// that was a second unlock of a lock the thread no longer held. The mutex is
// a plain one with no owner check: the unlock released whichever thread had
// taken the lock next, usually the audio thread inside a locked effect, on
// every script load. The wrapper is JNI and cannot be linked here, so this
// checks what removing the extra unlock relies on: every outcome of the
// parser (success, no @init, no @sample, a syntax error in either) leaves the
// lock free, and another thread that then takes it really holds it.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <unistd.h>
#include "jdsp_header.h"

// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }

static JamesDSPLib *dsp;
static volatile int held = 0, release = 0;
static void *other(void *a)
{
	(void)a;
	jdsp_lock(dsp);
	held = 1;
	while (!release)
		usleep(1000);
	jdsp_unlock(dsp);
	return 0;
}

static int failures = 0;
static void run(const char *label, const char *script)
{
	char *s = strdup(script);
	int rc = LiveProgStringParser(dsp, s);
	free(s);
	// free after the parser?
	int free1 = pthread_mutex_trylock(&dsp->m_in_processing) == 0;
	if (free1)
		pthread_mutex_unlock(&dsp->m_in_processing);
	// and once another thread has it, is it really held?
	held = release = 0;
	pthread_t t;
	pthread_create(&t, 0, other, 0);
	while (!held)
		usleep(1000);
	int busy = pthread_mutex_trylock(&dsp->m_in_processing) == EBUSY;
	release = 1;
	pthread_join(t, 0);
	const int ok = free1 && busy;
	printf("  %-24s parser %2d  lock free after %s  held by the other thread %s  %s\n",
		label, rc, free1 ? "yes" : "NO ", busy ? "yes" : "NO ", ok ? "ok" : "FAIL");
	if (!ok)
		failures++;
}

int main(void)
{
	JamesDSPGlobalMemoryAllocation();
	dsp = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPInit(dsp, 128, 48000);
	run("valid", "@init\nx = 1;\n@sample\nspl0 = spl0 * x;\n");
	run("no @init", "@sample\nspl0 = spl0;\n");
	run("no @sample", "@init\nx = 1;\n");
	run("error in @init", "@init\nx = (;\n@sample\nspl0 = spl0;\n");
	run("error in @sample", "@init\nx = 1;\n@sample\nspl0 = (;\n");
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
