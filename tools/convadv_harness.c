// What do the six "Advanced waveform editing" numbers of the Convolver really
// do? Runs the shipped impulse response loader (libjdspimprestoolbox.so, the
// one the app calls through JdspImpResToolbox.ReadImpulseResponseToFloat) on
// small hand-made IR files and reports where the samples end up.
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   $NDK/x86_64-linux-android29-clang -O2 -w tools/convadv_harness.c -ldl -o convadv
//   adb push convadv <the built libjdspimprestoolbox.so and its libs> /data/local/tmp/convadv/
//   adb shell "cd /data/local/tmp/convadv && LD_LIBRARY_PATH=. ./convadv"
//
// The loader is called with a stand-in JNIEnv that only implements the calls
// the loader makes, so the library itself runs unchanged.
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

typedef struct { int len; void *data; } Arr;

static const char *getUtf(JNIEnv *e, jstring s, jboolean *c) { return (const char *)s; }
static jsize getLen(JNIEnv *e, jarray a) { return ((Arr *)a)->len; }
static jint *getInts(JNIEnv *e, jintArray a, jboolean *c) { return (jint *)((Arr *)a)->data; }
static void relInts(JNIEnv *e, jintArray a, jint *p, jint m) {}
static void setInts(JNIEnv *e, jintArray a, jsize s, jsize n, const jint *b) { memmove((jint *)((Arr *)a)->data + s, b, n * sizeof(jint)); }
static jfloatArray newFloats(JNIEnv *e, jsize n) { Arr *a = malloc(sizeof(Arr)); a->len = n; a->data = calloc(n ? n : 1, sizeof(float)); return (jfloatArray)a; }
static void setFloats(JNIEnv *e, jfloatArray a, jsize s, jsize n, const jfloat *b) { memcpy((float *)((Arr *)a)->data + s, b, n * sizeof(float)); }

typedef jfloatArray (*ReadFn)(JNIEnv *, jobject, jstring, jint, jintArray, jint, jintArray);
static ReadFn readIr;
static JNIEnv env;

static void writeWav(const char *path, const float *x, int frames, int ch, int fs)
{
	FILE *f = fopen(path, "wb");
	int data = frames * ch * 4, v;
	short s;
	fwrite("RIFF", 1, 4, f); v = 36 + data; fwrite(&v, 4, 1, f);
	fwrite("WAVEfmt ", 1, 8, f); v = 16; fwrite(&v, 4, 1, f);
	s = 3; fwrite(&s, 2, 1, f); s = ch; fwrite(&s, 2, 1, f);
	fwrite(&fs, 4, 1, f); v = fs * ch * 4; fwrite(&v, 4, 1, f);
	s = ch * 4; fwrite(&s, 2, 1, f); s = 32; fwrite(&s, 2, 1, f);
	fwrite("data", 1, 4, f); fwrite(&data, 4, 1, f);
	fwrite(x, 4, frames * ch, f);
	fclose(f);
}

// Runs the loader; returns the interleaved result, sets frames/channels/valid
// and leaves the (possibly reset) parameters in adv.
static float *run(const char *path, int mode, int adv[6], int *frames, int *ch, int *valid)
{
	int info[4] = {0};
	Arr ai = {4, info}, aa = {6, adv};
	Arr *out = (Arr *)readIr(&env, 0, (jstring)path, 48000, (jintArray)&ai, mode, (jintArray)&aa);
	*ch = info[0]; *frames = info[1]; *valid = info[3];
	return out ? (float *)out->data : 0;
}

static int firstAbove(const float *x, int frames, int ch, int c, float lvl)
{
	for (int i = 0; i < frames; i++) if (fabsf(x[i * ch + c]) > lvl) return i;
	return -1;
}

static int fails = 0;
#define CHECK(cond, ...) do { int ok_ = (cond); printf("%s ", ok_ ? "PASS" : "FAIL"); printf(__VA_ARGS__); printf("\n"); if (!ok_) fails++; } while (0)

int main()
{
	struct JNINativeInterface fn;
	memset(&fn, 0, sizeof(fn));
	fn.GetStringUTFChars = getUtf;
	fn.GetArrayLength = getLen;
	fn.GetIntArrayElements = getInts;
	fn.ReleaseIntArrayElements = relInts;
	fn.SetIntArrayRegion = setInts;
	fn.NewFloatArray = newFloats;
	fn.SetFloatArrayRegion = setFloats;
	env = &fn;

	void *lib = dlopen("libjdspimprestoolbox.so", RTLD_NOW);
	if (!lib) { printf("dlopen: %s\n", dlerror()); return 2; }
	readIr = (ReadFn)dlsym(lib, "Java_me_timschneeberger_rootlessjamesdsp_interop_JdspImpResToolbox_ReadImpulseResponseToFloat");
	if (!readIr) { printf("dlsym failed\n"); return 2; }

	int frames, ch, valid;
	float *y;

	// 1. Delay direction and units, Original mode. Mono click at sample 10.
	{
		float x[64] = {0}; x[10] = 1.0f;
		writeWav("mono.wav", x, 64, 1, 48000);
		int adv[6] = {-80, -100, 5, 0, 0, 0};
		y = run("mono.wav", 0, adv, &frames, &ch, &valid);
		int at = firstAbove(y, frames, ch, 0, 0.5f);
		CHECK(at == 15 && frames == 64 && valid, "Original, 3rd value 5: click moved from 10 to %d (later), length %d", at, frames);
	}
	// 2. Which value belongs to which channel. Stereo clicks at 10 and 10.
	{
		float x[128] = {0}; x[10 * 2] = 1.0f; x[10 * 2 + 1] = 1.0f;
		writeWav("stereo.wav", x, 64, 2, 48000);
		int adv[6] = {-80, -100, 0, 7, 0, 0};
		y = run("stereo.wav", 0, adv, &frames, &ch, &valid);
		int l = firstAbove(y, frames, ch, 0, 0.5f), r = firstAbove(y, frames, ch, 1, 0.5f);
		CHECK(l == 10 && r == 17, "Stereo, 4th value 7: channel 1 at %d, channel 2 at %d", l, r);
	}
	// 3. Four channel IR: values 3 to 6 are channels 1 to 4.
	{
		float x[256] = {0}; for (int c = 0; c < 4; c++) x[10 * 4 + c] = 1.0f;
		writeWav("quad.wav", x, 64, 4, 48000);
		int adv[6] = {-80, -100, 1, 2, 3, 4};
		y = run("quad.wav", 0, adv, &frames, &ch, &valid);
		int p[4]; for (int c = 0; c < 4; c++) p[c] = firstAbove(y, frames, ch, c, 0.5f);
		CHECK(p[0] == 11 && p[1] == 12 && p[2] == 13 && p[3] == 14, "4 channels, 1;2;3;4: clicks at %d %d %d %d", p[0], p[1], p[2], p[3]);
	}
	// 4. Crop thresholds, Shrink mode. 100 silent samples, a -60 dB lead-in at
	// 100..109, peak at 110, a -70 dB tail to 299, then -120 dB to 999.
	float ir[1000] = {0};
	for (int i = 100; i < 110; i++) ir[i] = 1e-3f;
	ir[110] = 1.0f;
	for (int i = 111; i < 300; i++) ir[i] = 3.16e-4f;
	for (int i = 300; i < 1000; i++) ir[i] = 1e-6f;
	writeWav("crop.wav", ir, 1000, 1, 48000);
	{
		int adv[6] = {-80, -100, 0, 0, 0, 0};
		y = run("crop.wav", 1, adv, &frames, &ch, &valid);
		int pk = firstAbove(y, frames, ch, 0, 0.5f);
		CHECK(frames == 200 && pk == 10, "Shrink -80;-100: kept %d samples (100..299 expected 200), peak now at %d", frames, pk);
	}
	{
		int adv[6] = {-50, -65, 0, 0, 0, 0};
		y = run("crop.wav", 1, adv, &frames, &ch, &valid);
		CHECK(frames == 1, "Shrink -50;-65: kept %d sample(s) (only the peak is above both)", frames);
	}
	{
		int adv[6] = {-80, -100, 0, 0, 0, 0};
		y = run("crop.wav", 0, adv, &frames, &ch, &valid);
		CHECK(frames == 1000, "Original -80;-100: no crop, %d samples", frames);
	}
	// 5. Minimum phase mode ignores the start level, uses the end level.
	{
		int a1[6] = {-80, -100, 0, 0, 0, 0}, a2[6] = {-20, -100, 0, 0, 0, 0}, a3[6] = {-80, -40, 0, 0, 0, 0};
		int f1, f2, f3;
		run("crop.wav", 2, a1, &f1, &ch, &valid);
		run("crop.wav", 2, a2, &f2, &ch, &valid);
		run("crop.wav", 2, a3, &f3, &ch, &valid);
		CHECK(f1 == f2 && f3 < f1, "Min phase: start -80 gives %d, start -20 gives %d, end -40 gives %d", f1, f2, f3);
	}
	// 6. Out of range delays reset all six to the default and report invalid.
	{
		float x[64] = {0}; x[10] = 1.0f;
		int adv[6] = {-50, -60, 64, 0, 0, 0};
		y = run("mono.wav", 0, adv, &frames, &ch, &valid);
		CHECK(!valid && adv[0] == -80 && adv[1] == -100 && adv[2] == 0 && firstAbove(y, frames, ch, 0, 0.5f) == 10,
			"Delay 64 on a 64 sample IR: valid=%d, values now %d;%d;%d;%d;%d;%d", valid, adv[0], adv[1], adv[2], adv[3], adv[4], adv[5]);
		int adv2[6] = {-80, -100, -1, 0, 0, 0};
		run("mono.wav", 0, adv2, &frames, &ch, &valid);
		CHECK(!valid, "Delay -1: valid=%d", valid);
		int adv3[6] = {-80, -100, 63, 0, 0, 0};
		run("mono.wav", 0, adv3, &frames, &ch, &valid);
		CHECK(valid, "Delay 63 on a 64 sample IR: valid=%d", valid);
	}
	// 7. Observation only: the loudest point is found with a signed compare,
	// so an IR whose peak is negative is measured against the wrong level.
	{
		float neg[1000]; memcpy(neg, ir, sizeof(neg)); neg[110] = -1.0f;
		writeWav("cropneg.wav", neg, 1000, 1, 48000);
		int adv[6] = {-50, -65, 0, 0, 0, 0};
		run("cropneg.wav", 1, adv, &frames, &ch, &valid);
		printf("INFO same IR with the peak flipped negative, Shrink -50;-65: kept %d samples (1 if the peak were measured by size)\n", frames);
	}
	printf("%s: %d failed\n", fails ? "FAILED" : "ALL PASSED", fails);
	return fails ? 1 : 0;
}
