// Does the ViPER-DDC text parser stay inside its arrays and its string?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       -Wl,--wrap=malloc -Wl,--wrap=calloc tools/vdcparse_harness.c \
//       $J/Effects/vdc.c tools/lockstub.c -lm -o tools/vdcparse_x86
//
// DDCParser sized both coefficient arrays from the comma count of the
// SR_48000 section (numberCount / 5, rounded down), then parsed numberCount
// numbers into each. A count that is not a multiple of 5 (a trailing comma,
// or a sixth number on a line) wrote one DirectForm2 past the end of each
// array. With fewer real numbers than that count, the 44.1 kHz loop took the
// digits out of the "SR_48000" label and the 48 kHz loop walked past the
// string terminator into the heap. A file with SR_48000 first counted both
// sections. The input is a user-imported .vdc file, or in root mode any app's
// payload to the HAL, so this runs inside audioserver.
//
// Every allocation gets 128 guard bytes of 0xA5 behind it; any change is a
// write past the end. The input string is followed by ",9,9,9,9,9" after its
// terminator, so a parse that reads past the end shows up as a 9.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "jdsp_header.h"

#define GUARD 128
#define MAXBLK 64
static struct { unsigned char *p; size_t n; } blk[MAXBLK];
static int failures = 0;

static void record(unsigned char *p, size_t n)
{
	memset(p + n, 0xA5, GUARD);
	for (int i = 0; i < MAXBLK; i++)
		if (!blk[i].p) { blk[i].p = p; blk[i].n = n; break; }
}
void *__real_malloc(size_t n);
void *__wrap_malloc(size_t n)
{
	unsigned char *p = (unsigned char *)__real_malloc(n + GUARD);
	if (p) record(p, n);
	return p;
}
// clang at -O2 fuses vdc.c's malloc + memset(0) into calloc, so wrap that too
void *__real_calloc(size_t a, size_t b);
void *__wrap_calloc(size_t a, size_t b)
{
	unsigned char *p = (unsigned char *)__real_calloc(1, a * b + GUARD);
	if (p) record(p, a * b);
	return p;
}

int DDCParser(char *DDCString, DirectForm2 **ptrdf441, DirectForm2 **ptrdf48);

static void run(const char *label, const char *s, int wantSos, double want441, double want48)
{
	memset(blk, 0, sizeof(blk));
	static const char tail[] = ",9,9,9,9,9";
	size_t len = strlen(s);
	char *buf = (char *)__real_malloc(len + 1 + sizeof(tail));
	memcpy(buf, s, len + 1);
	memcpy(buf + len + 1, tail, sizeof(tail));
	DirectForm2 *a = 0, *b = 0;
	int sos = DDCParser(buf, &a, &b);
	int guard = 0, nines = 0;
	for (int i = 0; i < MAXBLK; i++)
		if (blk[i].p)
			for (int k = 0; k < GUARD; k++)
				guard += blk[i].p[blk[i].n + k] != 0xA5;
	for (int i = 0; i < sos; i++)
	{
		const DirectForm2 *d[2] = { a + i, b + i };
		for (int k = 0; k < 2; k++)
			nines += d[k]->b0 == 9 || d[k]->b1 == 9 || d[k]->b2 == 9 || d[k]->a1 == -9 || d[k]->a2 == -9;
	}
	int ok = guard == 0 && nines == 0 && sos == wantSos;
	if (ok && sos)
		ok = a[0].b0 == want441 && b[0].b0 == want48;
	printf("  %-34s sos=%d b0 %g/%g  guard bytes changed=%d  read-past-end values=%d  %s\n",
		label, sos, sos ? a[0].b0 : 0.0, sos ? b[0].b0 : 0.0, guard, nines, ok ? "ok" : "FAIL");
	if (!ok) failures++;
	free(buf);
}

int main(void)
{
	run("well formed", "SR_44100:1,0,0,0,0\nSR_48000:2,0,0,0,0", 1, 1, 2);
	run("two sections each", "SR_44100:1,0,0,0,0,1,0,0,0,0\nSR_48000:2,0,0,0,0,2,0,0,0,0", 2, 1, 2);
	run("sixth number on each line", "SR_44100:1,0,0,0,0,7\nSR_48000:2,0,0,0,0,8", 1, 1, 2);
	run("trailing comma", "SR_44100:1,0,0,0,0\nSR_48000:2,0,0,0,0,", 1, 1, 2);
	run("SR_48000 first", "SR_48000:2,0,0,0,0\nSR_44100:1,0,0,0,0", 1, 1, 2);
	run("44.1 kHz section short", "SR_44100:1,0\nSR_48000:2,0,0,0,0", 0, 0, 0);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
