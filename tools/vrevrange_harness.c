// Do out-of-range reverb settings stay inside the reverb's buffers?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       -Wl,--wrap=calloc -Wl,--wrap=free tools/vrevrange_harness.c \
//       $J/Effects/viperextras2.c tools/lockstub.c -lm -o tools/vrevrange_x86
//
// The card's sliders keep every setting in 0..100 (pre-delay 0..200), and the
// buffers are sized for that. Nothing in the engine held them there, and in
// root mode any app can send SET_PARAM 26007 with whatever it likes. A
// modulation of 1e5 % swung the smooth hall and room's fractional read more
// than a whole line away, which one wrap cannot bring back; a room size below
// -50 % made the smooth room's early reflection taps negative, which skipped
// their clamp and read past the end of erMem. Both fault in audioserver.
//
// Every reverb calloc has its end flush against a no access page, so any read
// past a buffer faults. Each case runs in its own process for 1 s of noise.
#include <sys/wait.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <unistd.h>
#include <sys/mman.h>
#include "jdsp_header.h"

#define BLK 1024

static JamesDSPLib g_lib;
static float bufL[BLK], bufR[BLK];

/* ---- page-guard allocator: buffer end flush against a PROT_NONE page ---- */
void *__real_calloc(size_t a, size_t b);
void __real_free(void *p);

#define MAXBLK 256
static struct { void *user; void *base; size_t total; } g_map[MAXBLK];

void *__wrap_calloc(size_t a, size_t b)
{
	size_t n = a * b;
	if (n == 0) n = 1;
	long pg = sysconf(_SC_PAGESIZE);
	size_t usable = (n + pg - 1) / pg * pg;
	size_t total = usable + pg;
	unsigned char *base = (unsigned char*)mmap(NULL, total,
		PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (base == MAP_FAILED) return NULL;
	mprotect(base + usable, pg, PROT_NONE);          /* trailing guard page */
	unsigned char *user = base + (usable - n);       /* end flush to guard */
	memset(user, 0, n);                              /* calloc semantics */
	for (int i = 0; i < MAXBLK; i++)
		if (!g_map[i].base) { g_map[i].user = user; g_map[i].base = base; g_map[i].total = total; break; }
	return user;
}

void __wrap_free(void *q)
{
	if (!q) return;
	for (int i = 0; i < MAXBLK; i++)
		if (g_map[i].user == q) { munmap(g_map[i].base, g_map[i].total); g_map[i].base = 0; g_map[i].user = 0; return; }
	__real_free(q);
}

/* ---- driver ---- */
static void prepare(float fs)
{
	memset(&g_lib, 0, sizeof(g_lib));
	g_lib.fs = fs;
	g_lib.tmpBuffer[0] = bufL;
	g_lib.tmpBuffer[1] = bufR;
}

static unsigned s = 12345;
static float noise(void) { s = s * 1103515245u + 12345u; return ((int)(s >> 9) - 4194304) / 4194304.0f; }

/* room,damp,width,pre,decay,diff,mod,bass,er,wet,dry */
static void run(int model, float room, float mod)
{
	prepare(48000.0f);
	VReverbSetParam(&g_lib, model, room, 50.0f, 100.0f, 0.0f, 60.0f, 70.0f,
		mod, 50.0f, 60.0f, 100.0f, 0.0f);
	VReverbEnable(&g_lib);
	/* process ~1 s; smOscS sweeps its full range, so the extreme of the read
	   is reached within the first second */
	for (int done = 0; done < 48000; done += BLK)
	{
		for (int i = 0; i < BLK; i++) { bufL[i] = noise(); bufR[i] = noise(); }
		VReverbProcess(&g_lib, BLK);
	}
	/* read back the output so nothing is optimised away */
	double acc = 0; for (int i = 0; i < BLK; i++) acc += bufL[i] + bufR[i];
	if (!(acc == acc)) printf("    (output went non-finite)\n");
}

static int failures = 0;
static void one(const char *label, int model, float room, float mod)
{
	fflush(stdout);
	pid_t pid = fork();
	if (!pid)
	{
		run(model, room, mod);
		fflush(stdout);
		_exit(0);
	}
	int st = 0;
	waitpid(pid, &st, 0);
	const int ok = WIFEXITED(st) && !WEXITSTATUS(st);
	printf("  %-36s %s\n", label, ok ? "ok" : "FAIL (read past a buffer)");
	if (!ok)
		failures++;
}

int main(void)
{
	one("smooth room, room 50, mod 100", 5, 50.0f, 100.0f);
	one("smooth hall, room 100, mod 100", 4, 100.0f, 100.0f);
	one("smooth hall, mod 1e5", 4, 50.0f, 100000.0f);
	one("smooth room, mod 1e5", 5, 50.0f, 100000.0f);
	one("smooth room, mod -1e5", 5, 50.0f, -100000.0f);
	one("smooth room, room -200", 5, -200.0f, 50.0f);
	one("smooth room, room -1e30", 5, -1e30f, 50.0f);
	one("smooth room, mod NaN", 5, 50.0f, NAN);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
