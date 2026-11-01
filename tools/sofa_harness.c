// Does the SOFA reader take the right nine pairs out of a real set, and does a
// broken file only ever produce an error?
//
// Built for x86_64 and run on the emulator, with AddressSanitizer:
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   S=third_party/libmysofa/src
//   $NDK/x86_64-linux-android29-clang -O1 -g -w -fsanitize=address \
//       -I $S -I $S/hrtf -I app/src/main/cpp/libsofareader \
//       tools/sofa_harness.c app/src/main/cpp/libsofareader/sofa_extract.c \
//       $S/hdf/*.c $S/hrtf/check.c $S/hrtf/reader.c $S/hrtf/spherical.c \
//       $S/hrtf/tools.c -lz -lm -o sofa
//   adb -s emulator-5554 push sofa app/src/main/assets/hrtf/mit_kemar_normal_pinna.sofa \
//       <the NDK's libclang_rt.asan-x86_64-android.so> /data/local/tmp/
//   adb -s emulator-5554 shell LD_LIBRARY_PATH=/data/local/tmp \
//       /data/local/tmp/sofa /data/local/tmp/mit_kemar_normal_pinna.sofa
//
// Each fuzzed file is read in a child process, so a crash is counted rather
// than ending the run; under AddressSanitizer any bad read or write is a
// crash. Build with -DBROKEN_NOSIZECHECK (sofa_extract.c skips its file size
// limit) or -DBROKEN_NEAREST (it takes the first measurement for every
// speaker) and the named check fails.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <unistd.h>
#include <sys/wait.h>
#include "sofa_extract.h"

static int failures = 0;

static void check(const char *what, double got, double lo, double hi)
{
	const int ok = got >= lo && got <= hi;
	printf("  %-60s %12.4f  [%9.3f..%9.3f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

static void info(const char *what, double got)
{
	printf("  %-60s %12.4f  (reported)\n", what, got);
}

static char *readFile(const char *path, size_t *n)
{
	FILE *f = fopen(path, "rb");
	if (!f) return NULL;
	fseek(f, 0, SEEK_END);
	const long len = ftell(f);
	fseek(f, 0, SEEK_SET);
	char *b = (char *)malloc((size_t)len);
	if (b && fread(b, 1, (size_t)len, f) != (size_t)len) { free(b); b = NULL; }
	fclose(f);
	*n = (size_t)len;
	return b;
}

static uint64_t g_rng = 88172645463325252ull;
static uint64_t rnd(void)
{
	g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17;
	return g_rng;
}

/* -1 crashed, otherwise the error code. */
static int extractInChild(const char *data, size_t n)
{
	fflush(stdout);
	const pid_t pid = fork();
	if (pid == 0)
	{
		SofaSet s;
		const int e = sofaExtract(data, n, &s);
		if (e == SOFA_OK)
		{
			/* Touch everything handed back, as the app would. */
			double t = 0;
			for (int i = 0; i < SOFA_SPEAKERS * 2 * s.taps; i++) t += s.hrir[i];
			for (int i = 0; i < s.diffBins; i++) t += s.diffuse[i];
			if (!isfinite(t)) _exit(99);
		}
		sofaFree(&s);
		_exit(e);
	}
	int st = 0;
	waitpid(pid, &st, 0);
	if (WIFEXITED(st)) return WEXITSTATUS(st) == 99 ? -1 : WEXITSTATUS(st);
	return -1;
}

int main(int argc, char **argv)
{
	if (argc < 2)
	{
		printf("usage: sofa <mit_kemar_normal_pinna.sofa>\n");
		return 2;
	}
	size_t n = 0;
	char *data = readFile(argv[1], &n);
	if (!data) { printf("cannot read %s\n", argv[1]); return 2; }

	printf("\n[1] the bundled MIT KEMAR set\n");
	SofaSet s;
	const int e = sofaExtract(data, n, &s);
	check("extract error code", e, 0, 0);
	if (e == SOFA_OK)
	{
		check("measurements", s.measurements, 710, 710);
		check("sample rate, Hz", s.fs, 44100, 44100);
		check("taps per response", s.taps, 512, 512);
		check("ears swapped in the file", s.swapped, 0, 0);
		double worst = 0;
		for (int k = 0; k < SOFA_SPEAKERS; k++)
		{
			double az = s.dirs[k * 2], el = s.dirs[k * 2 + 1];
			double want = sofaSpeakerAz[k];
			double d = fmod(az - want + 720.0, 360.0);
			if (d > 180) d -= 360;
			printf("    speaker %d: wanted %6.1f, %5.1f   got %8.3f, %7.3f\n", k,
				sofaSpeakerAz[k], sofaSpeakerEl[k], az, el);
			if (fabs(d) > worst) worst = fabs(d);
			if (fabs(el - sofaSpeakerEl[k]) > worst) worst = fabs(el - sofaSpeakerEl[k]);
		}
		check("worst direction error, degrees (BROKEN_NEAREST)", worst, 0, 0.05);
		/* The centre pair: the normal pinna set is one ear mirrored, so the
		   ears should match closely. */
		const float *cl = s.hrir, *cr = s.hrir + s.taps;
		double el = 0, er = 0, x = 0;
		for (int i = 0; i < s.taps; i++) { el += cl[i] * cl[i]; er += cr[i] * cr[i]; x += cl[i] * cr[i]; }
		check("centre pair: correlation of the ears", x / sqrt(el * er), 0.99, 1.0001);
		check("centre pair: level difference, dB", 10 * log10(el / er), -0.5, 0.5);
		/* Left speakers louder in the left ear. */
		int sideOk = 0;
		const int lefts[4] = { 1, 3, 5, 7 };
		for (int i = 0; i < 4; i++)
		{
			const float *a = s.hrir + (size_t)lefts[i] * 2 * s.taps, *b = a + s.taps;
			double ea = 0, eb = 0;
			for (int t = 0; t < s.taps; t++) { ea += a[t] * a[t]; eb += b[t] * b[t]; }
			sideOk += ea > eb * 2;
		}
		check("left speakers louder in the left ear, of 4", sideOk, 4, 4);
		int finite = 1;
		double lo = 1e300, hi = 0;
		for (int k = 0; k < s.diffBins; k++)
		{
			if (!isfinite(s.diffuse[k]) || s.diffuse[k] <= 0) finite = 0;
			if (k > 2 && k < s.diffBins - 2)
			{
				if (s.diffuse[k] < lo) lo = s.diffuse[k];
				if (s.diffuse[k] > hi) hi = s.diffuse[k];
			}
		}
		check("diffuse average: finite and positive", finite, 1, 1);
		info("diffuse average: bins", s.diffBins);
		info("diffuse average: range over the band, dB", 10 * log10(hi / lo));
		printf("    title:    %s\n    licence:  %.200s\n    database: %s\n", s.title, s.license, s.database);
		check("licence attribute present", strlen(s.license) > 20, 1, 1);
		sofaFree(&s);
	}

	printf("\n[2] files that are not sets\n");
	check("empty file is refused", sofaExtract(data, 0, &s), SOFA_ERR_SIZE, SOFA_ERR_SIZE);
	{
		const size_t big = SOFA_MAX_BYTES + 1;
		char *b = (char *)calloc(1, big);
		memcpy(b, data, n);
		const int r = extractInChild(b, big);
		check("64 MB plus one refused unread (BROKEN_NOSIZECHECK)", r, SOFA_ERR_SIZE, SOFA_ERR_SIZE);
		free(b);
	}
	{
		char junk[4096];
		for (size_t i = 0; i < sizeof junk; i++) junk[i] = (char)rnd();
		check("random bytes refused as unreadable", extractInChild(junk, sizeof junk), SOFA_ERR_PARSE, SOFA_ERR_PARSE);
	}

	printf("\n[3] fuzz: truncated and damaged copies never crash\n");
	int crashes = 0, ok = 0, refused = 0, runs = 0;
	for (int k = 1; k < 64; k++)
	{
		const int r = extractInChild(data, n * (size_t)k / 64);
		runs++;
		if (r < 0) crashes++; else if (r == 0) ok++; else refused++;
	}
	char *m = (char *)malloc(n);
	const int mutations = argc > 2 ? atoi(argv[2]) : 1500;
	for (int k = 0; k < mutations; k++)
	{
		memcpy(m, data, n);
		const int flips = 1 + (int)(rnd() % 8);
		for (int f = 0; f < flips; f++)
		{
			/* Mostly the first 64 KB, where the HDF5 structure is. */
			const size_t at = (rnd() % 4) ? rnd() % (n < 65536 ? n : 65536) : rnd() % n;
			m[at] = (rnd() % 3) ? (char)(m[at] ^ (1 << (rnd() % 8))) : (char)rnd();
		}
		const int r = extractInChild(m, n);
		runs++;
		if (r < 0) { crashes++; printf("    crash on mutation %d\n", k); }
		else if (r == 0) ok++; else refused++;
	}
	free(m);
	info("damaged files read anyway", ok);
	info("damaged files refused", refused);
	check("crashes, of all damaged files", crashes, 0, 0);
	info("damaged files tried", runs);

	free(data);
	printf("\n%s (%d failure%s)\n", failures ? "FAILURES PRESENT" : "all checks passed",
		failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
