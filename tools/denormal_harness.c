// Does silence cost what audio costs, and does the caller get its float mode back?
//
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/denormal_harness.c \
//       <engine objects> -lm -llog -o tools/denormal_x86
//
// Once the input goes silent every filter decays towards zero, and its state
// crosses the subnormal range on the way; some never leave it. x86 does
// subnormal arithmetic in microcode, and with nothing flushing them every
// effect in tools/perf_harness.c cost 15 to 60 times more per sample in
// silence than with loud audio. That harness calls each effect directly, so
// this one goes through processFloatMultiplexd, the entry the app and the
// root-mode HAL use, with the same six effects on at their shipping settings.
//
// The engine now sets flush-to-zero for the length of one block. In root mode
// that block runs on the audio server's mixer thread, so the second half
// checks the caller's own mode is handed back exactly as it was.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "jdsp_header.h"

#define BLOCK 1024
#define LOUD 200
#define QUIET 400

static JamesDSPLib g;
static float in[2 * BLOCK], out[2 * BLOCK], noise[2 * BLOCK];
static double t[QUIET];
static int failures = 0;

static double now_ns(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
}

static int cmp(const void *a, const void *b)
{
	const double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : (x > y ? 1 : 0);
}

/* Median ns per sample over `blocks` blocks of noise or silence. */
static double run(int silent, int blocks)
{
	for (int b = 0; b < blocks; b++)
	{
		if (silent) memset(in, 0, sizeof(in));
		else memcpy(in, noise, sizeof(in));
		const double t0 = now_ns();
		g.processFloatMultiplexd(&g, in, out, BLOCK);
		t[b] = (now_ns() - t0) / BLOCK;
	}
	qsort(t, blocks, sizeof(double), cmp);
	return t[blocks / 2];
}

static void check(const char *what, int ok, const char *detail)
{
	printf("  %-52s %s  %s\n", what, detail, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

#if defined(__SSE__) || defined(__x86_64__)
#define HAVE_MODE 1
static unsigned getMode(void) { return __builtin_ia32_stmxcsr(); }
static void setMode(unsigned m) { __builtin_ia32_ldmxcsr(m); }
#define FLUSH_BITS 0x8040u
#elif defined(__aarch64__)
#define HAVE_MODE 1
static unsigned getMode(void) { unsigned long v; __asm__ __volatile__("mrs %0, fpcr" : "=r"(v)); return (unsigned)v; }
static void setMode(unsigned m) { unsigned long v = m; __asm__ __volatile__("msr fpcr, %0" : : "r"(v)); }
#define FLUSH_BITS (1u << 24)
#endif

int main(void)
{
	JamesDSPGlobalMemoryAllocation();
	JamesDSPInit(&g, BLOCK, 48000);

	unsigned s = 4242u;
	for (int i = 0; i < 2 * BLOCK; i++)
	{
		s = s * 1664525u + 1013904223u;
		noise[i] = 0.6f * ((float)((s >> 9) & 0xFFFF) / 32768.0f - 1.0f);
	}

	/* The six from tools/perf_harness.c, at the same settings. */
	TapeSetParam(&g, 25.0f, 30.0f, 35.0f, -20.0f, 3.0f, 100.0f);
	TapeEnable(&g);
	ExciterSetParam(&g, 150.0f, 900.0f, 4500.0f, 30.0f, 12.0f, 18.0f, 35.0f,
	                EXCITER_TUBE, 6.0f, 100.0f);
	ExciterEnable(&g);
	LowEndSetParam(&g, 30.0f, 90.0f, 3.0f, 300.0f, -3.0f, 100.0f);
	LowEndEnable(&g);
	TransientSetParam(&g, 200.0f, 3000.0f, 45.0f, -20.0f, 30.0f, -15.0f,
	                  0.0f, 0.0f, 6.0f, 100.0f);
	TransientEnable(&g);
	ImagingSetParam(&g, 120.0f, 250.0f, 1500.0f, 6000.0f, 1.0f, 1.15f, 1.6f, 100.0f);
	ImagingEnable(&g);
	const float bands[3 * DYNEQ_VALUES_PER_BAND] = {
		180.0f, 1.0f, -22.0f, 3.0f, 15.0f, 150.0f, -6.0f, (float)DYNEQ_MODE_COMPRESS,
		3200.0f, 1.4f, -26.0f, 3.0f,  3.0f,  80.0f, -5.0f, (float)DYNEQ_MODE_COMPRESS,
		6800.0f, 3.0f, -30.0f, 4.0f,  1.0f,  40.0f, -8.0f, (float)DYNEQ_MODE_COMPRESS
	};
	DynamicEqSetBands(&g, bands, 3);
	DynamicEqSetParam(&g, 100.0f, MS_MODE_STEREO);
	DynamicEqEnable(&g);

	char d[96];
	printf("== silence through the public entry, six effects on ==\n");
	run(0, 32); /* warm up */
	const double loud = run(0, LOUD);
	/* Silence after loud audio is the case that matters: the state is on its
	   way down through the subnormals now. Several seconds of it, so a state
	   stuck there would show in the median, not just a few blocks. */
	const double quiet = run(1, QUIET);
	snprintf(d, sizeof(d), "%7.1f vs %7.1f ns/sample", quiet, loud);
	/* Measured 1.0x with the flush and 9x without on an x86 host. */
	check("silence costs at most 3x what audio does", quiet <= loud * 3.0, d);

#ifdef HAVE_MODE
	printf("== the caller's float mode ==\n");
	const unsigned orig = getMode();
	setMode(orig & ~FLUSH_BITS);
	const unsigned before = getMode();
	g.processFloatMultiplexd(&g, noise, out, BLOCK);
	const unsigned after = getMode();
	snprintf(d, sizeof(d), "%08x -> %08x", before, after);
	check("flush off before a block, still off after it", after == before, d);

	setMode(orig | FLUSH_BITS);
	const unsigned before2 = getMode();
	g.processFloatMultiplexd(&g, noise, out, BLOCK);
	const unsigned after2 = getMode();
	snprintf(d, sizeof(d), "%08x -> %08x", before2, after2);
	check("flush on before a block, still on after it", after2 == before2, d);
	setMode(orig);
#endif

	JamesDSPFree(&g);
	printf("%s\n", failures ? "FAIL" : "PASS");
	return failures != 0;
}
