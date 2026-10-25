// Does the GraphicEQ convolver follow a linear / minimum phase switch?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   N=$J/Effects/eel2/numericSys
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       -Wl,--wrap=malloc -Wl,--wrap=free tools/arbphase_harness.c \
//       $J/Effects/arbEqConv.c $J/generalDSP/ArbFIRGen.c $N/FFTConvolver.c \
//       $N/codelet.c $N/FilterDesign/generalFdesign.c $J/generalDSP/generalProg.c \
//       tools/lockstub.c -lm -llog -o tools/arbphase_x86
//
// Minimum phase filters are 8192 taps and linear phase ones 16383. The phase
// switch only rebuilt the coefficient generator; the convolvers kept the
// segment count of whatever length they were last loaded with, and the node
// string that follows only refreshed the segments in place. Loaded linear
// (16 segments of 1024), then switched to minimum phase, the refresh walked
// all 16 segments over an 8192-tap filter: its unsigned "remaining" wrapped,
// and it read 8 KB to 32 KB past the per-ear buffer, or played a stale tail
// of the old linear kernel. The other way round, it refreshed 8 of the 16
// segments a linear kernel needs and cut it in half.
//
// Every 8192-float allocation is put flush against 64 KB of PROT_NONE, so a
// read past it faults; each case runs in its own process. The response check
// pushes an impulse through the switched convolver and through one built at
// the new phase from the start, and they must match.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include "jdsp_header.h"

// the generator links these but the curves here never reach them
double getValueAt(cubic_hermite *a, double b) { (void)a; (void)b; return 0.0; }
int cpoly() { return 0; }

#define GUARDED_BYTES (8192 * sizeof(float))
static void *guarded[256];
static size_t nguarded = 0;
void *__real_malloc(size_t n);
void __real_free(void *p);
void *__wrap_malloc(size_t n)
{
	if (n != GUARDED_BYTES || nguarded >= 256)
		return __real_malloc(n);
	size_t pg = (size_t)sysconf(_SC_PAGESIZE);
	size_t data = (n + pg - 1) / pg * pg;
	unsigned char *base = mmap(0, data + 65536, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (base == MAP_FAILED)
		return 0;
	mprotect(base + data, 65536, PROT_NONE);
	void *p = base + data - n;
	guarded[nguarded++] = p;
	return p;
}
void __wrap_free(void *p)
{
	for (size_t i = 0; i < nguarded; i++)
		if (guarded[i] == p)
			return; // leaked on purpose, so the guard stays
	__real_free(p);
}

#define BS 1024
#define CURVE_L "GraphicEQ: 100 3; 1000 0; 8000 -4"
#define CURVE_R "GraphicEQ: 100 -3; 1000 2; 8000 0"

static void setup(JamesDSPLib *j, int phase)
{
	memset(j, 0, sizeof(*j));
	j->blockSize = BS;
	j->fs = 48000.0f;
	ArbitraryResponseEqualizerConstructor(j);
	ArbitraryResponseEqualizerSetPhaseMode(j, phase);
	ArbitraryResponseEqualizerStringParserStereo(j, CURVE_L, CURVE_R);
	j->arbMagForceRefresh = 1; // the block-size change path loads at the current length
	ArbitraryResponseEqualizerEnable(j, 1);
}

// largest difference between two convolvers' impulse responses
static double compare(FFTConvolver2x2 *a, FFTConvolver2x2 *b)
{
	static float in[BS], z[BS], al[BS], ar[BS], bl[BS], br[BS];
	double worst = 0;
	for (int blk = 0; blk < 24; blk++)
	{
		memset(in, 0, sizeof(in));
		if (!blk)
			in[0] = 1.0f;
		FFTConvolver2x2Process(a, in, in, al, ar, BS);
		FFTConvolver2x2Process(b, in, in, bl, br, BS);
		for (int i = 0; i < BS; i++)
		{
			double d = fmax(fabs(al[i] - bl[i]), fabs(ar[i] - br[i]));
			if (d > worst)
				worst = d;
		}
	}
	(void)z;
	return worst;
}

static int child(int from, int to)
{
	static JamesDSPLib j, ref;
	setup(&j, from);
	ArbitraryResponseEqualizerSetPhaseMode(&j, to);
	ArbitraryResponseEqualizerStringParserStereo(&j, CURVE_L, CURVE_R);
	setup(&ref, to);
	unsigned int want = (j.arbMag.instance.filterLen + BS - 1) / BS;
	double d = compare(&j.arbMag.conv, &ref.arbMag.conv);
	int ok = j.arbMag.conv._segCount == want && d < 1e-6;
	printf("  %s -> %s: filterLen %u, segments %u (want %u), response vs fresh %.3g  %s\n",
		from ? "linear " : "minimum", to ? "linear " : "minimum", j.arbMag.instance.filterLen,
		j.arbMag.conv._segCount, want, d, ok ? "ok" : "FAIL");
	return ok ? 0 : 1;
}

static int failures = 0;
static void run(int from, int to)
{
	fflush(stdout);
	pid_t pid = fork();
	if (!pid)
	{
		int r = child(from, to);
		fflush(stdout);
		_exit(r);
	}
	int st = 0;
	waitpid(pid, &st, 0);
	if (WIFSIGNALED(st))
		printf("  %s -> %s: killed by signal %d (read past the filter buffer)  FAIL\n",
			from ? "linear " : "minimum", to ? "linear " : "minimum", WTERMSIG(st));
	if (!WIFEXITED(st) || WEXITSTATUS(st))
		failures++;
}

int main(void)
{
	run(0, 0); // controls: no switch
	run(1, 1);
	run(1, 0);
	run(0, 1);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
