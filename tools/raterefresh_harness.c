// Do the DDC and the compander follow a 44.1 / 48 kHz move?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   Compile every engine source (the tools/cc86.rsp list) with
//       $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -fPIC -c
//   then
//   $NDK/x86_64-linux-android29-clang -O2 -w -I $J tools/raterefresh_harness.c \
//       <engine objects> -lm -llog -o tools/raterefresh_x86
//
// JamesDSPSetSampleRate redesigns these two only under forceRefresh, which
// this app never passes (and which deadlocks if it does). A ViPER-DDC file
// carries one coefficient set per rate, and the DDC kept the one it picked at
// load; sending the same file again returns early because the text matches,
// so every correction peak sat about 9 % off for the rest of the session.
// That includes every 44.1 kHz session, since the settings are first pushed
// at the 48 kHz the engine is created with. The compander's time constants
// are worked out from the rate the same way and kept until its settings
// change.
//
// Set both up at one rate with the real engine, move with the real
// JamesDSPSetSampleRate, and they must match an engine set up at the new rate
// to begin with.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }
void setStdOutHandler(void *f, void *u) { (void)f; (void)u; }
int isStdOutHandlerSet(void) { return 0; }

static char ddc[] =
	"# test\nSR_44100:1.10,0.11,0.12,0.13,0.14,1.20,0.21,0.22,0.23,0.24\n"
	"SR_48000:2.10,0.31,0.32,0.33,0.34,2.20,0.41,0.42,0.43,0.44\n";
static double freqs[7] = { 95, 200, 400, 800, 1600, 3400, 7500 };
static double gains[7] = { 0.6, -0.4, 0.3, -0.6, 0.5, -0.2, 0.4 };
static int failures = 0;

static JamesDSPLib *make(float rate)
{
	JamesDSPLib *d = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPInit(d, 128, rate);
	char *s = strdup(ddc);
	DDCStringParser(d, s);
	DDCEnable(d, 1);
	CompressorSetParam(d, 0.22f, 2, 0, 0);
	CompressorSetGain(d, freqs, gains, 1);
	CompressorEnable(d, 1);
	return d;
}

static void run(float from, float to)
{
	JamesDSPLib *moved = make(from), *ref = make(to);
	JamesDSPSetSampleRate(moved, to, 0);
	// what the app does next: send the same settings again
	char *s = strdup(ddc);
	DDCStringParser(moved, s);
	DDCEnable(moved, 1);
	CompressorSetParam(moved, 0.22f, 2, 0, 0);
	CompressorSetGain(moved, freqs, gains, 1);
	CompressorEnable(moved, 1);
	const double b0 = moved->vdcFl.sosPointer[0].b0, want = ref->vdcFl.sosPointer[0].b0;
	const int ddcOk = b0 == want && moved->ddcEnabled;
	printf("  %5.0f -> %5.0f  DDC b0 %.2f (want %.2f)  %s\n", from, to, b0, want, ddcOk ? "ok" : "FAIL");
	const int compOk = moved->comp.fgt_fac == ref->comp.fgt_fac && moved->comp.spectralRate == ref->comp.spectralRate;
	printf("  %5.0f -> %5.0f  compander smoothing %.6f (want %.6f)  %s\n", from, to,
		moved->comp.fgt_fac, ref->comp.fgt_fac, compOk ? "ok" : "FAIL");
	failures += !ddcOk + !compOk;
}

int main(void)
{
	JamesDSPGlobalMemoryAllocation();
	run(48000.f, 44100.f);
	run(44100.f, 48000.f);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
