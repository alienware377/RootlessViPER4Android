// Does the rooted HAL do what the app asks of it?
//
// Compile every engine source as for tools/param_harness.cpp (x86_64 objects),
// plus app/src/main/cpp/EELStdOutExtension.c, then:
//   $NDK/x86_64-linux-android29-clang++ -O1 -w -std=c++17 -static-libstdc++ \
//       -I $J -I app/src/main/cpp/libjamesdsp/VeryOldOpen_source_edition/Audio_Engine/eclipse_libjamesdsp_free_bp/jni \
//       -Wl,--wrap=malloc -Wl,--wrap=calloc -Wl,--wrap=realloc -Wl,--wrap=free \
//       tools/hal_harness.cpp <engine objects> -lm -llog -o tools/hal_x86
//
// It includes the HAL's own translation unit and drives rv4a_command the way
// AudioFlinger does, with the exact payload sequences JamesDspRemoteEngine
// sends (char buffers, impulse response partitions, hashes, enables), then
// checks the engine state. Each section is one way root mode used to differ
// from rootless: cards that could not be switched off, a Liveprog script that
// could never be enabled, settings lost on SET_CONFIG, a leaked engine, the
// linear phase switch, the processing order reset, true-stereo impulse
// responses, and the rate impulse responses are resampled to.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include <vector>

// ---- live allocation tracking (only what our objects allocate) ----
extern "C" {
void *__real_malloc(size_t);
void *__real_calloc(size_t, size_t);
void *__real_realloc(void *, size_t);
void __real_free(void *);
}
#define TBL (1u << 21)
static void *g_ptr[TBL];
static size_t g_sz[TBL];
static long long g_live = 0;
static int g_track = 0;
static size_t slot(void *p) { return ((uintptr_t)p >> 4) & (TBL - 1); }
static void addp(void *p, size_t n)
{
	if (!p || !g_track) return;
	size_t i = slot(p);
	while (g_ptr[i] && g_ptr[i] != (void *)1) i = (i + 1) & (TBL - 1);
	g_ptr[i] = p; g_sz[i] = n; g_live += (long long)n;
}
static void delp(void *p)
{
	if (!p) return;
	size_t i = slot(p);
	for (size_t k = 0; k < TBL; k++, i = (i + 1) & (TBL - 1))
	{
		if (!g_ptr[i]) return;
		if (g_ptr[i] == p) { g_live -= (long long)g_sz[i]; g_ptr[i] = (void *)1; return; }
	}
}
extern "C" void *__wrap_malloc(size_t n) { void *p = __real_malloc(n); addp(p, n); return p; }
extern "C" void *__wrap_calloc(size_t a, size_t b) { void *p = __real_calloc(a, b); addp(p, a * b); return p; }
extern "C" void *__wrap_realloc(void *o, size_t n) { delp(o); void *p = __real_realloc(o, n); addp(p, n); return p; }
extern "C" void __wrap_free(void *p) { delp(p); __real_free(p); }

#include "../app/src/main/cpp/hal/JamesDspHalEffect.cpp"
#include "hal_vdc_blob.h"

// pitch shifting is not under test; keep the link free of Rubber Band
extern "C" {
int RubberBandPrepare(PitchShift *p, float fs, int formant) { return 0; }
void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n) { }
void RubberBandRelease(PitchShift *p) { }
}

static int failures = 0;
static void check(const char *what, bool ok, const char *detail = "")
{
	printf("  %-60s %-4s %s\n", what, ok ? "ok" : "FAIL", detail);
	if (!ok) failures++;
}

static effect_handle_t h;
static rv4a_context *C() { return (rv4a_context *)h; }

static int cmd(uint32_t code, void *data, uint32_t size)
{
	int reply = 0; uint32_t rs = sizeof(int);
	return (*h)->command(h, code, size, data, &rs, &reply);
}
static int setParamRaw(int32_t id, const void *val, uint32_t vsize)
{
	std::vector<uint8_t> b(sizeof(effect_param_t) + 4 + ((vsize + 3) & ~3u));
	effect_param_t *p = (effect_param_t *)b.data();
	p->status = 0; p->psize = 4; p->vsize = vsize;
	memcpy(p->data, &id, 4);
	memcpy(p->data + 4, val, vsize);
	return cmd(EFFECT_CMD_SET_PARAM, b.data(), (uint32_t)b.size());
}
static void setShort(int32_t id, int16_t v) { setParamRaw(id, &v, 2); }
static void setInt(int32_t id, int32_t v) { setParamRaw(id, &v, 4); }
static void setFloats(int32_t id, const std::vector<float> &v) { setParamRaw(id, v.data(), (uint32_t)(v.size() * 4)); }
static int32_t getInt(int32_t id)
{
	uint8_t q[sizeof(effect_param_t) + 8] = {0};
	effect_param_t *p = (effect_param_t *)q; p->psize = 4; p->vsize = 4; memcpy(p->data, &id, 4);
	rv4a_reply_1x4_1x4 r; uint32_t rs = sizeof(r);
	if ((*h)->command(h, EFFECT_CMD_GET_PARAM, sizeof(q), q, &rs, &r) != 0) return -1;
	return r.data;
}
// setParameterCharBuffer from AudioEffectExtensions.kt
static void charBuffer(int32_t send, int32_t commit, const char *s)
{
	size_t len = strlen(s);
	int parts = (int)((len + 255) / 256);
	int32_t info[2] = { parts, 256 };
	setParamRaw(8888, info, 8);
	for (int i = 0; i < parts; i++)
	{
		char chunk[256] = {0};
		size_t n = len - (size_t)i * 256; if (n > 256) n = 256;
		memcpy(chunk, s + (size_t)i * 256, n);
		setParamRaw(send, chunk, 256);
	}
	setShort(commit, 1);
}
static void config(uint32_t rate)
{
	effect_config_t cfg; memset(&cfg, 0, sizeof(cfg));
	cfg.inputCfg.samplingRate = cfg.outputCfg.samplingRate = rate;
	cfg.inputCfg.format = cfg.outputCfg.format = AUDIO_FORMAT_PCM_FLOAT;
	cfg.inputCfg.channels = cfg.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
	cfg.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
	cmd(EFFECT_CMD_SET_CONFIG, &cfg, sizeof(cfg));
}
static effect_uuid_t U() { return rv4a_descriptor.uuid; }
static void create() { effect_uuid_t u = U(); EffectCreate(&u, 0, 0, &h); }

int main()
{
	printf("== HAL glue harness ==\n");
	char d[160];

	// ---- 1: switching effects OFF in root mode --------------------------
	printf("\n[1] enable ids switched off (what the app sends when the card is turned off)\n");
	create(); config(48000); cmd(EFFECT_CMD_ENABLE, 0, 0);
	{
		// compander: 115 payload then 1200 (setCompanderInternal)
		std::vector<float> v = { 0.22f, 2, 0, 95, 200, 400, 800, 1600, 3400, 7500, .4f, .4f, .4f, .4f, .4f, .4f, .4f };
		setFloats(115, v); setShort(1200, 1);
		int on = C()->dsp.compEnabled;
		setFloats(115, v); setShort(1200, 0);
		snprintf(d, sizeof d, "on=%d after-off=%d", on, C()->dsp.compEnabled);
		check("1200 off clears compEnabled", on == 1 && C()->dsp.compEnabled == 0, d);
	}
	{
		std::vector<float> v = { 0, -1 };
		const float f[15] = { 25,40,63,100,160,250,400,630,1000,1600,2500,4000,6300,10000,16000 };
		for (int i = 0; i < 15; i++) v.push_back(f[i]);
		for (int i = 0; i < 15; i++) v.push_back(3.0f);
		setFloats(116, v); setShort(1202, 1);
		int on = C()->dsp.equalizerEnabled;
		setShort(1202, 0);   // setMultiEqualizerInternal(false): no 116, just 1202=0
		snprintf(d, sizeof d, "on=%d after-off=%d", on, C()->dsp.equalizerEnabled);
		check("1202 off clears equalizerEnabled", on == 1 && C()->dsp.equalizerEnabled == 0, d);
	}
	{
		setShort(188, 2); setShort(1208, 1);
		int on = C()->dsp.crossfeedEnabled;
		setShort(1208, 0);   // setCrossfeed(false): only 1208=0
		snprintf(d, sizeof d, "on=%d after-off=%d", on, C()->dsp.crossfeedEnabled);
		check("1208 off clears crossfeedEnabled", on == 1 && C()->dsp.crossfeedEnabled == 0, d);
	}
	{
		charBuffer(12001, 10009, kVdc); setInt(25001, 1234); setShort(1212, 1);
		int on = C()->dsp.ddcEnabled;
		setShort(1212, 0);   // setVdcInternal(false): only 1212=0
		snprintf(d, sizeof d, "on=%d after-off=%d", on, C()->dsp.ddcEnabled);
		check("1212 off clears ddcEnabled", on == 1 && C()->dsp.ddcEnabled == 0, d);
	}
	{   // controls that do work
		setShort(112, 6); setShort(1201, 1); int on = C()->dsp.bassBoostEnabled; setShort(1201, 0);
		snprintf(d, sizeof d, "on=%d after-off=%d", on, C()->dsp.bassBoostEnabled);
		check("(control) 1201 off clears bassBoostEnabled", on == 1 && C()->dsp.bassBoostEnabled == 0, d);
	}

	// ---- 2: liveprog with a valid script --------------------------------
	printf("\n[2] liveprog: valid script committed, then 1213=1\n");
	{
		const char *script = "desc: half gain\n@init\ng=0.5;\n@sample\nspl0=spl0*g;\nspl1=spl1*g;\n";
		charBuffer(12001, 10010, script); setInt(25002, 4321); setShort(1213, 1);
		snprintf(d, sizeof d, "compiled=%d haveLiveprog=%d liveprogEnabled=%d",
				 C()->dsp.eel.compileSucessfully, (int)C()->haveLiveprog, C()->dsp.liveprogEnabled);
		check("valid script can be switched on", C()->dsp.liveprogEnabled == 1, d);
		// a script the parser refuses must not have its hash kept, or the app
		// sees a match and never sends a corrected one
		charBuffer(12001, 10010, "@init\ng=0.5;\n"); setInt(25002, 999);
		snprintf(d, sizeof d, "haveLiveprog=%d hash read back=%d", (int)C()->haveLiveprog, getInt(30002));
		check("rejected script is not marked loaded or hashed", !C()->haveLiveprog && getInt(30002) != 999, d);
	}
	EffectRelease(h);

	// ---- 3: a second SET_CONFIG (output thread reconfigured / chain moved) ----
	printf("\n[3] second SET_CONFIG after settings were applied\n");
	create(); config(48000); cmd(EFFECT_CMD_ENABLE, 0, 0);
	setShort(112, 9); setShort(1201, 1);
	setFloats(26018, { 25, 30, 35, -20, 3, 100 }); setShort(26118, 1);
	int bb = C()->dsp.bassBoostEnabled, tp = C()->dsp.tapeEnabled;
	config(44100);
	snprintf(d, sizeof d, "before: bass=%d tape=%d  after: bass=%d tape=%d  active=%d",
			 bb, tp, C()->dsp.bassBoostEnabled, C()->dsp.tapeEnabled, (int)C()->active);
	check("settings survive a re-config", C()->dsp.bassBoostEnabled == 1 && C()->dsp.tapeEnabled == 1, d);
	snprintf(d, sizeof d, "graphicEq hash slot after re-config = %d", getInt(30000));
	printf("  %s\n", d);
	EffectRelease(h);

	// ---- 4: first SET_CONFIG leaks EffectCreate's engine ----------------
	printf("\n[4] memory: create+release vs create+SET_CONFIG+release\n");
	{
		create(); EffectRelease(h);            // warm up process-wide tables
		g_track = 1; long long base = g_live;
		create(); EffectRelease(h);
		long long a = g_live - base;
		base = g_live;
		create(); config(48000); EffectRelease(h);
		long long b = g_live - base;
		base = g_live; create(); long long one = g_live - base; EffectRelease(h);
		printf("  one EffectCreate engine holds %lld B\n", one);
		g_track = 0;
		snprintf(d, sizeof d, "left live: create/release=%lld B, with SET_CONFIG=%lld B", a, b);
		check("first SET_CONFIG frees EffectCreate's engine", b <= a, d);
	}

	// ---- 5: linear-phase toggle in root mode ----------------------------
	printf("\n[5] GraphicEQ linear phase toggled (curve unchanged)\n");
	{
		create(); config(48000); cmd(EFFECT_CMD_ENABLE, 0, 0);
		const char *geq = "GraphicEQ: 20 0; 100 6; 1000 -6; 4000 6; 16000 0";
		// first sync: hash differs, payload + hash + enable
		setShort(26011, 0);
		if (getInt(30000) != 777) { charBuffer(12001, 10006, geq); setInt(25000, 777); }
		setShort(1210, 1);
		unsigned len0 = C()->dsp.arbMag.keptLen;
		std::vector<float> before(C()->dsp.arbMag.filterL, C()->dsp.arbMag.filterL + len0);
		// user flips linear phase: app sends 26011=1, then setGraphicEqInternal
		// reads hash 30000, finds it equal and skips the payload
		setShort(26011, 1);
		bool resent = false;
		if (getInt(30000) != 777) { charBuffer(12001, 10006, geq); setInt(25000, 777); resent = true; }
		setShort(1210, 1);
		unsigned len1 = C()->dsp.arbMag.keptLen;
		bool same = len1 == len0 && memcmp(before.data(), C()->dsp.arbMag.filterL, len0 * 4) == 0;
		snprintf(d, sizeof d, "linearPhase=%d resent=%d keptLen %u->%u filter unchanged=%d genLen=%u",
				 C()->dsp.arbMag.linearPhase, (int)resent, len0, len1, (int)same, C()->dsp.arbMag.instance.filterLen);
		check("loaded filter follows the phase toggle", !same, d);
		// what rootless does: re-send the string after the toggle
		ArbitraryResponseEqualizerStringParser(&C()->dsp, (char *)geq);
		bool same2 = C()->dsp.arbMag.keptLen == len0 && memcmp(before.data(), C()->dsp.arbMag.filterL, len0 * 4) == 0;
		snprintf(d, sizeof d, "after re-send: keptLen=%u unchanged=%d", C()->dsp.arbMag.keptLen, (int)same2);
		printf("  (reference) %s\n", d);
		EffectRelease(h);
	}

	// ---- 6: chain order cannot be reset ---------------------------------
	printf("\n[6] chain order: custom, then an empty 26012 (the only reset the wire could carry)\n");
	{
		create(); config(48000);
		int def0 = C()->dsp.chainOrder[0];
		int32_t order[3] = { JDSP_EFX_MAXIMIZER, JDSP_EFX_BALANCE, JDSP_EFX_TAPE };
		setParamRaw(26012, order, sizeof order);
		int cust0 = C()->dsp.chainOrder[0];
		setParamRaw(26012, order, 0);
		snprintf(d, sizeof d, "default[0]=%d custom[0]=%d after empty=%d", def0, cust0, C()->dsp.chainOrder[0]);
		check("an empty order restores the default", C()->dsp.chainOrder[0] == def0, d);
		// what JamesDspRemoteEngine.setChainOrder(null) sends now
		setParamRaw(26012, order, sizeof order);
		int32_t reset = -1;
		setParamRaw(26012, &reset, sizeof reset);
		bool same = C()->dsp.chainCount > 0;
		JamesDSPLib ref;
		memset(&ref, 0, sizeof(ref));
		JamesDSPResetChainOrder(&ref);
		same = same && C()->dsp.chainCount == ref.chainCount &&
			memcmp(C()->dsp.chainOrder, ref.chainOrder, ref.chainCount * sizeof(int)) == 0;
		snprintf(d, sizeof d, "after -1: count %d, matches the default chain %d", C()->dsp.chainCount, (int)same);
		check("the app's reset marker restores the default", same, d);
		EffectRelease(h);
	}

	// ---- 7: a true-stereo (4 channel) impulse response -------------------
	printf("\n[7] convolver: 4-channel IR as JdspImpResToolbox emits for true-stereo files\n");
	{
		create(); config(48000); cmd(EFFECT_CMD_ENABLE, 0, 0);
		const int frames = 2048, ch = 4;
		std::vector<float> ir(frames * ch, 0.0f);
		for (int c = 0; c < ch; c++) ir[c] = (c == 0 || c == 3) ? 1.0f : 0.0f;
		// setParameterImpulseResponseBuffer
		int total = (int)ir.size(), parts = (total + 4095) / 4096;
		int32_t info[4] = { total, ch, 0, parts };
		setParamRaw(9999, info, 16);
		std::vector<float> fin(parts * 4096, 0.0f); memcpy(fin.data(), ir.data(), total * 4);
		for (int i = 0; i < parts; i++) setParamRaw(12000, fin.data() + i * 4096, 4096 * 4);
		setShort(10004, 1); setInt(25003, 99); setShort(1205, 1);
		snprintf(d, sizeof d, "haveIr=%d convolverEnabled=%d", (int)C()->haveIr, C()->dsp.convolverEnabled);
		check("4-channel IR loads and enables", C()->dsp.convolverEnabled == 1, d);
		// reference: the engine itself takes it
		int rc = Convolver1DLoadImpulseResponse(&C()->dsp, ir.data(), ch, frames, 1);
		printf("  (reference) Convolver1DLoadImpulseResponse(4 ch) direct = %d\n", rc);
		EffectRelease(h);
	}

	// ---- 8: the rate the app resamples IRs to vs the rate the engine runs ----
	printf("\n[8] 20001 (read by setConvolver as targetSampleRate) vs engine fs\n");
	{
		const uint32_t rates[] = { 44100, 48000, 88200, 96000, 192000 };
		for (uint32_t r : rates)
		{
			create(); config(r);
			snprintf(d, sizeof d, "stream %u: 20001=%d engine fs=%.0f asrc=%d", r, getInt(20001), C()->dsp.fs, C()->dsp.enableASRC);
			check("IR target rate equals engine rate", getInt(20001) == (int)C()->dsp.fs, d);
			EffectRelease(h);
		}
	}

	// ---- 9: bass boost gain finer than a whole dB ------------------------
	printf("\n[9] bass boost at 7.4 dB, as JamesDspRemoteEngine.setBassBoost sends it\n");
	{
		create(); config(48000);
		setShort(112, 7); setShort(26200, 740); setShort(1201, 1);
		snprintf(d, sizeof d, "maxGain %.2f dB", C()->dsp.dbb.maxGain);
		check("gain arrives to the hundredth of a dB", fabsf(C()->dsp.dbb.maxGain - 7.4f) < 1e-3f, d);
		EffectRelease(h);
	}

	printf("\n%d failure(s)\n", failures);
	return failures ? 1 : 0;
}
