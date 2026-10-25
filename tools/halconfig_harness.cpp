// Does the rooted effect keep its engine, and the user's settings, across
// SET_CONFIG?
//
// Compile every engine source as for tools/param_harness.cpp (x86_64 objects),
// plus app/src/main/cpp/EELStdOutExtension.c, then:
//   $NDK/x86_64-linux-android29-clang++ -O1 -w -std=c++17 -static-libstdc++ \
//       -I $J -I app/src/main/cpp/libjamesdsp/VeryOldOpen_source_edition/Audio_Engine/eclipse_libjamesdsp_free_bp/jni \
//       -Wl,--wrap=malloc -Wl,--wrap=calloc -Wl,--wrap=realloc -Wl,--wrap=free \
//       tools/halconfig_harness.cpp <engine objects> -lm -llog -o tools/halconfig_x86
// It includes the HAL's own translation unit, so it drives the real command
// handler and process call.
//
// Two faults in one place. EffectCreate builds a whole engine at 48 kHz, and
// the first SET_CONFIG built another over it without freeing the first, about
// 271 MB of allocations leaked in audioserver per effect instance. Every
// SET_CONFIG after that freed the engine and built a fresh one, which put
// every effect back to off and every setting to its default, and nothing on
// the app side ever sends them again. SET_CONFIG comes again on every route
// change (headphones in, Bluetooth on), so root mode silently lost all its
// processing the first time the output changed.
//
// The leak check counts live heap bytes across create, SET_CONFIG and
// release. The settings check pushes tape, bass boost and a limiter post gain
// the way the app does, then sends SET_CONFIG again at the same rate and at
// 44.1 kHz, and they must all still be there, with the engine at the new rate.
#include "../app/src/main/cpp/hal/JamesDspHalEffect.cpp"
#include <stdio.h>
#include <math.h>
#include <malloc.h>

extern "C" {
void *__real_malloc(size_t);
void *__real_calloc(size_t, size_t);
void *__real_realloc(void *, size_t);
void __real_free(void *);
static long long live = 0;
void *__wrap_malloc(size_t n)
{
	void *p = __real_malloc(n);
	if (p) live += malloc_usable_size(p);
	return p;
}
void *__wrap_calloc(size_t a, size_t b)
{
	void *p = __real_calloc(a, b);
	if (p) live += malloc_usable_size(p);
	return p;
}
void *__wrap_realloc(void *q, size_t n)
{
	long long before = q ? (long long)malloc_usable_size(q) : 0;
	void *p = __real_realloc(q, n);
	if (p) live += (long long)malloc_usable_size(p) - before;
	else if (!n) live -= before;
	return p;
}
void __wrap_free(void *p)
{
	if (p) live -= malloc_usable_size(p);
	__real_free(p);
}
// pitch shifting is not under test; keep the link free of Rubber Band
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }
}

static int failures = 0;
static void check(const char *what, bool ok, const char *detail)
{
	printf("  %-48s %-4s %s\n", what, ok ? "ok" : "FAIL", detail);
	if (!ok) failures++;
}

static int32_t sendCmd(effect_handle_t h, uint32_t code, uint32_t size, void *data)
{
	int reply = -1;
	uint32_t rs = sizeof(int);
	int32_t r = (*h)->command(h, code, size, data, &rs, &reply);
	return r ? r : reply;
}
static void setConfig(effect_handle_t h, uint32_t rate)
{
	effect_config_t cfg;
	memset(&cfg, 0, sizeof(cfg));
	cfg.inputCfg.samplingRate = cfg.outputCfg.samplingRate = rate;
	cfg.inputCfg.format = cfg.outputCfg.format = AUDIO_FORMAT_PCM_FLOAT;
	cfg.inputCfg.channels = cfg.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
	cfg.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
	sendCmd(h, EFFECT_CMD_SET_CONFIG, sizeof(cfg), &cfg);
}
static void setShort(effect_handle_t h, int32_t id, int16_t v)
{
	uint8_t buf[sizeof(effect_param_t) + 8];
	memset(buf, 0, sizeof(buf));
	effect_param_t *p = (effect_param_t *)buf;
	p->psize = 4;
	p->vsize = 2;
	memcpy(p->data, &id, 4);
	memcpy(p->data + 4, &v, 2);
	sendCmd(h, EFFECT_CMD_SET_PARAM, sizeof(buf), buf);
}
static void setFloats(effect_handle_t h, int32_t id, const float *v, uint32_t n)
{
	size_t sz = sizeof(effect_param_t) + 4 + n * 4;
	uint8_t *buf = (uint8_t *)calloc(1, sz);
	effect_param_t *p = (effect_param_t *)buf;
	p->psize = 4;
	p->vsize = n * 4;
	memcpy(p->data, &id, 4);
	memcpy(p->data + 4, v, n * 4);
	sendCmd(h, EFFECT_CMD_SET_PARAM, (uint32_t)sz, buf);
	free(buf);
}
static double rms(effect_handle_t h, float rate)
{
	const int N = 1024;
	static float in[N * 2], out[N * 2];
	double acc = 0;
	int cnt = 0;
	for (int blk = 0; blk < 40; blk++)
	{
		for (int i = 0; i < N; i++)
			in[2 * i] = in[2 * i + 1] = 0.1f * sinf(2.0f * 3.14159265f * 60.0f * (blk * N + i) / rate);
		audio_buffer_t ib, ob;
		ib.frameCount = ob.frameCount = N;
		ib.f32 = in;
		ob.f32 = out;
		(*h)->process(h, &ib, &ob);
		if (blk >= 20)
			for (int i = 0; i < N * 2; i++) { acc += (double)out[i] * out[i]; cnt++; }
	}
	return sqrt(acc / cnt);
}

int main()
{
	char d[160];
	setvbuf(stdout, NULL, _IONBF, 0);
	printf("leak across create, SET_CONFIG, release\n");
	effect_handle_t h = NULL;
	// one round first, so once-only global tables are not counted
	EffectCreate(&rv4a_descriptor.uuid, 0, 0, &h);
	EffectRelease(h);
	for (int round = 0; round < 2; round++)
	{
		const int configs = round ? 3 : 1;
		long long before = live;
		for (int k = 0; k < 3; k++)
		{
			EffectCreate(&rv4a_descriptor.uuid, 0, 0, &h);
			for (int j = 0; j < configs; j++)
				setConfig(h, j == 1 ? 44100 : 48000);
			EffectRelease(h);
		}
		snprintf(d, sizeof(d), "%lld bytes", live - before);
		check(round ? "3 instances, 3 SET_CONFIGs each" : "3 instances, one SET_CONFIG each",
			live - before < 65536, d);
	}

	printf("settings across a second SET_CONFIG\n");
	EffectCreate(&rv4a_descriptor.uuid, 0, 0, &h);
	rv4a_context *c = (rv4a_context *)h;
	sendCmd(h, EFFECT_CMD_ENABLE, 0, NULL);
	setConfig(h, 48000);
	const double fresh = rms(h, 48000.f);
	setShort(h, 112, 12); // bass boost +12 dB
	setShort(h, 1201, 1);
	setShort(h, 26118, 1); // tape
	const float lim[3] = { -0.5f, 60.0f, 6.0f };
	setFloats(h, 1500, lim, 3);
	const double set = rms(h, 48000.f);
	snprintf(d, sizeof(d), "RMS %.4f, untouched engine %.4f", set, fresh);
	check("settings change the sound at all (control)", fabs(set - fresh) > 0.05, d);
	setConfig(h, 48000);
	const double again = rms(h, 48000.f);
	snprintf(d, sizeof(d), "tape %d bass %d post gain %.3f, RMS %.4f",
		c->dsp.tapeEnabled, c->dsp.bassBoostEnabled, c->dsp.postGain, again);
	check("same rate again (a route change)", c->dsp.tapeEnabled && c->dsp.bassBoostEnabled &&
		fabsf(c->dsp.postGain - 1.9953f) < 1e-3f && fabs(again - set) < 0.1, d);
	setConfig(h, 44100);
	const double moved = rms(h, 44100.f);
	snprintf(d, sizeof(d), "fs %.0f, tape %d bass %d post gain %.3f, RMS %.4f",
		c->dsp.fs, c->dsp.tapeEnabled, c->dsp.bassBoostEnabled, c->dsp.postGain, moved);
	check("route to a 44.1 kHz output", c->dsp.fs == 44100.0f && c->dsp.tapeEnabled &&
		c->dsp.bassBoostEnabled && fabsf(c->dsp.postGain - 1.9953f) < 1e-3f && fabs(moved - set) < 0.1, d);
	setConfig(h, 96000);
	const double hi = rms(h, 96000.f);
	snprintf(d, sizeof(d), "fs %.0f, resampler %d, tape %d, RMS %.4f",
		c->dsp.fs, c->dsp.enableASRC, c->dsp.tapeEnabled, hi);
	check("route to a 96 kHz output", c->dsp.fs == 48000.0f && c->dsp.enableASRC &&
		c->dsp.tapeEnabled && hi > 0.05 && hi == hi, d);
	EffectRelease(h);
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
