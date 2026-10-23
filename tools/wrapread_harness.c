// Can a wrapped fractional read land one past the end of its delay line?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/aarch64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       -Wl,--wrap=calloc tools/wrapread_harness.c $J/Effects/diffsurround.c \
//       tools/lockstub.c -lm -o tools/wrapread
//
// Every modulated or fractional delay here reads at (write index - delay), and
// when that is negative adds the line length to wrap it. In floats that add
// can round UP to exactly the line length: 600 - 600.0002 is -0.0002, and
// -0.0002 + 8192 is nearer 8192.0 than the float below it. The integer part
// is then the line length itself, one past the last element. Reads that mask
// the index with (length - 1) are immune, which is why the echo, the chorus
// and the pitch shifter are fine. Two did not mask.
//
// Differential surround is the bad one, because its delay is fixed: if a
// setting lands it a hair above a whole number of samples, the bad read
// happens on every pass round the ring, and plenty of ordinary settings do
// (10, 20, 40, 80 and 90 ms at 44.1 kHz among them). It reads one float past
// a heap block. Whatever lives there comes out as audio.
//
// Both are caught by putting something unmistakable just past the end, and
// insisting it never reaches the output.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"
#include "Effects/tape.c"

static int failures = 0;

static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-58s %12.4g  [%g..%g]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

/* Every calloc gets 64 bytes after it filled with 0x7F, which as a float is
   3.4e38: a single stray read of it is impossible to miss in the output. */
void *__real_calloc(size_t a, size_t b);
void *__wrap_calloc(size_t a, size_t b)
{
	const size_t n = a * b;
	unsigned char *p = (unsigned char*)__real_calloc(1, n + 64);
	if (p) memset(p + n, 0x7F, 64);
	return p;
}

static JamesDSPLib g_lib;
#define BLK 1024
static float bufL[BLK], bufR[BLK];

int main(void)
{
	printf("== wrapped fractional reads ==\n\n");

	printf("tape: a read that wraps to exactly the end of the line\n");
	{
		static float line[TAPE_LINE + 1];
		for (int i = 0; i < TAPE_LINE; i++) line[i] = 0.25f;
		line[TAPE_LINE] = 1e30f;   /* one past the end */
		/* The exact case worked through above */
		check("write head 600, delay 600.0002 samples", tapeRead(line, 600, 600.0002f), 0.2, 0.3);
		/* And every write position with the delay a hair beyond it */
		int bad = 0;
		for (int w = 0; w < 4096; w++)
			for (int k = 1; k <= 4; k++)
			{
				const float y = tapeRead(line, w, (float)w + (float)k * 1e-4f);
				if (!(fabsf(y) < 1.0f)) bad++;
			}
		check("reads that reached past the end, of 16384 tried", bad, 0, 0);
	}

	printf("\ndifferential surround at settings that land on the edge\n");
	{
		const float settings[][3] = {
			{ 44100.0f, 10.0f, 20.0f },
			{ 44100.0f, 40.0f, 80.0f },
			{ 44100.0f, 90.0f, 0.0f },
			{ 48000.0f, 12.5f, 25.0f },
		};
		for (unsigned s = 0; s < sizeof(settings) / sizeof(settings[0]); s++)
		{
			memset(&g_lib, 0, sizeof(g_lib));
			g_lib.fs = settings[s][0];
			g_lib.tmpBuffer[0] = bufL;
			g_lib.tmpBuffer[1] = bufR;
			DiffSurroundEnable(&g_lib);
			DiffSurroundSetParam(&g_lib, settings[s][1], settings[s][2]);
			double worst = 0.0;
			long phase = 0;
			/* Four seconds: about ten passes round the ring at 44.1 kHz */
			for (int b = 0; b < (int)(4.0f * settings[s][0]) / BLK; b++)
			{
				for (int i = 0; i < BLK; i++, phase++)
					bufL[i] = bufR[i] = 0.5f * sinf(0.03f * (float)phase);
				DiffSurroundProcess(&g_lib, BLK);
				for (int i = 0; i < BLK; i++)
				{
					const double m = fmax(fabs(bufL[i]), fabs(bufR[i]));
					if (!(m <= worst)) worst = isfinite(m) ? m : 1e38;
				}
			}
			DiffSurroundDisable(&g_lib);
			char what[96];
			snprintf(what, sizeof(what), "%.0f Hz, %.1f / %.1f ms: loudest sample",
				settings[s][0], settings[s][1], settings[s][2]);
			check(what, worst, 0.0, 1.0);
		}
	}

	printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "PASSED", failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
