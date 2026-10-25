// Can a processing-order change tear the block being processed?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -Wno-error=implicit-function-declaration \
//       -I $J tools/chainrace_harness.c \
//       tools/chainrace_stubs.c $J/jdspController.c -lm -llog -o tools/chainrace_x86
//
// JamesDSPSetChainOrder and JamesDSPResetChainOrder rewrote chainOrder[] in
// place with no lock while the audio thread walked it once per block. A
// reorder from the chain editor, a V4A mode change or the HAL's 26012 could
// land mid-walk, and the block then ran a mix of the old and new orders: an
// effect skipped (the maximiser, letting a peak through) or run twice (a
// delay line or reverb advanced twice in one block).
//
// Links the real controller, with its real mutex, against stub effects;
// field surround and the maximiser only count their calls. One thread flips
// the order between "both first" and "both last" as fast as it can while the
// other processes blocks: every block must run each exactly once.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdatomic.h>
#include "jdsp_header.h"
void JamesDSPProcess(JamesDSPLib *jdsp, size_t n);

static JamesDSPLib g_lib;
static int fsCalls, mxCalls;
void FieldSurroundProcess(JamesDSPLib *j, size_t n) { (void)j; (void)n; fsCalls++; }
void MaximizerProcess(JamesDSPLib *j, size_t n) { (void)j; (void)n; mxCalls++; }

static atomic_int stop;
static int orderFirst[JDSP_EFX_COUNT], orderLast[JDSP_EFX_COUNT];

static void *control(void *arg)
{
	(void)arg;
	long flips = 0;
	while (!atomic_load(&stop))
	{
		JamesDSPSetChainOrder(&g_lib, (flips & 1) ? orderLast : orderFirst, JDSP_EFX_COUNT);
		flips++;
	}
	return (void *)flips;
}

int main(int argc, char **argv)
{
	long blocks = argc > 1 ? atol(argv[1]) : 2000000;
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = 48000.0f;
	g_lib.postGain = 1.0f;
	g_lib.limiter.mode = 2;
	static float bufL[64], bufR[64];
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
	g_lib.isMutexSuccess = pthread_mutex_init(&g_lib.m_in_processing, NULL) == 0;
	g_lib.fieldSurroundEnabled = 1;
	g_lib.maximizerEnabled = 1;

	// FS and MAX at the front vs at the back; everything else in id order.
	int a = 0, b = 0;
	orderFirst[a++] = JDSP_EFX_FIELDSURROUND;
	orderFirst[a++] = JDSP_EFX_MAXIMIZER;
	for (int id = 0; id < JDSP_EFX_COUNT; id++)
	{
		if (id == JDSP_EFX_FIELDSURROUND || id == JDSP_EFX_MAXIMIZER) continue;
		orderFirst[a++] = id;
		orderLast[b++] = id;
	}
	orderLast[b++] = JDSP_EFX_FIELDSURROUND;
	orderLast[b++] = JDSP_EFX_MAXIMIZER;
	JamesDSPSetChainOrder(&g_lib, orderFirst, JDSP_EFX_COUNT);

	// Sanity: single-threaded, exactly one call per block.
	long sanityBad = 0;
	for (int i = 0; i < 1000; i++)
	{
		fsCalls = mxCalls = 0;
		JamesDSPSetChainOrder(&g_lib, (i & 1) ? orderLast : orderFirst, JDSP_EFX_COUNT);
		JamesDSPProcess(&g_lib, 64);
		if (fsCalls != 1 || mxCalls != 1) sanityBad++;
	}
	printf("single-threaded sanity: %ld bad blocks of 1000 (expect 0)\n", sanityBad);

	pthread_t t;
	pthread_create(&t, NULL, control, NULL);
	long fs0 = 0, fs2 = 0, mx0 = 0, mx2 = 0;
	for (long i = 0; i < blocks; i++)
	{
		fsCalls = mxCalls = 0;
		JamesDSPProcess(&g_lib, 64);
		if (fsCalls == 0) fs0++; else if (fsCalls >= 2) fs2++;
		if (mxCalls == 0) mx0++; else if (mxCalls >= 2) mx2++;
	}
	atomic_store(&stop, 1);
	void *flips;
	pthread_join(t, &flips);
	printf("concurrent: %ld blocks, %ld order flips\n", blocks, (long)flips);
	printf("  field surround: skipped in %ld blocks, run twice in %ld blocks\n", fs0, fs2);
	printf("  maximiser:      skipped in %ld blocks, run twice in %ld blocks\n", mx0, mx2);
	int fail = (fs0 + fs2 + mx0 + mx2) > 0;
	printf("%s\n", fail ? "FAIL: chain order torn during processing" : "PASS: no torn blocks");
	return fail;
}
