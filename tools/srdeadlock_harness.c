// Does JamesDSPSetSampleRate with forceRefresh hang on its own lock?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) and
//   app/src/main/cpp/EELStdOutExtension.c with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   with the real jdspController.c, so the real mutex (no lockstub), then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/srdeadlock_harness.c \
//       <engine objects> -lm -llog -o tools/srdeadlock_x86
//
// JamesDSPSetSampleRate holds the engine lock for its whole body, and its
// forceRefresh branch called CrossfeedEnable, ArbitraryResponseEqualizerEnable
// and StereoEnhancementRefresh, which all take the same lock. It is a plain
// mutex, so the first rate change with forceRefresh hung that thread for good,
// and every locked call after it, the audio thread's included. Nothing in the
// app passes forceRefresh today, but the JNI call takes the flag and the code
// invites using it.
//
// Each call runs on a worker thread; it must come back within 10 s and leave
// the lock free.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include <errno.h>
#include <unistd.h>
#include "jdsp_header.h"

typedef struct { JamesDSPLib *d; float rate; int force; volatile int done; } Job;
static void *worker(void *p)
{
	Job *j = (Job *)p;
	JamesDSPSetSampleRate(j->d, j->rate, j->force);
	j->done = 1;
	return 0;
}
static JamesDSPLib *mk(void)
{
	JamesDSPLib *d = (JamesDSPLib *)malloc(sizeof(JamesDSPLib));
	memset(d, 0, sizeof(*d));
	JamesDSPInit(d, 128, 48000);
	return d;
}
// returns 1 if the call came back within 10 s
static int run(JamesDSPLib *d, float rate, int force, const char *label)
{
	Job j = { d, rate, force, 0 };
	pthread_t t;
	pthread_create(&t, 0, worker, &j);
	for (int i = 0; i < 1000 && !j.done; i++) { struct timespec ts = {0, 10000000}; nanosleep(&ts, 0); }
	int rc = pthread_mutex_trylock(&d->m_in_processing);
	if (rc == 0) pthread_mutex_unlock(&d->m_in_processing);
	printf("%-44s returned=%d  mutexStatus=%d  trylock=%s\n", label, j.done,
		JamesDSPGetMutexStatus(d), rc == 0 ? "free" : (rc == EBUSY ? "EBUSY (held)" : "other"));
	if (j.done) pthread_join(t, 0); else pthread_detach(t);
	return j.done;
}
int main(void)
{
	JamesDSPGlobalMemoryAllocation();
	JamesDSPLib *a = mk();
	int c1 = run(a, 44100, 0, "control: 48k->44.1k forceRefresh=0");
	int c2 = run(a, 48000, 0, "control: 44.1k->48k forceRefresh=0");
	JamesDSPLib *b = mk();
	int c3 = run(b, 48000, 1, "control: same rate (48k) forceRefresh=1");
	int t1 = run(b, 44100, 1, "TEST: 48k->44.1k forceRefresh=1");
	if (t1) t1 = run(b, 48000, 1, "TEST: 44.1k->48k forceRefresh=1");
	int ok = c1 && c2 && c3;
	printf("controls %s; test %s\n", ok ? "pass" : "FAIL", t1 ? "returned (no deadlock)" : "HUNG (deadlock)");
	printf(ok && t1 ? "PASS\n" : "FAIL\n");
	fflush(stdout);
	_exit(t1 ? 0 : (ok ? 3 : 2));
}

// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }
