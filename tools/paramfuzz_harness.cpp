// Can anything an app sends the rooted effect crash it or poison its audio?
//
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang++ -O1 -w -std=c++17 -static-libstdc++ \
//       -I $J -I app/src/main/cpp/libjamesdsp/VeryOldOpen_source_edition/Audio_Engine/eclipse_libjamesdsp_free_bp/jni \
//       tools/paramfuzz_harness.cpp <engine objects> -lm -llog -o tools/paramfuzz_x86
//
// In root mode the effect lives in the audio server and any app on the device
// can address it, so every byte of every command is untrusted. This drives the
// legacy HAL's own command() and process() with a fixed-seed stream of
// commands: real parameter ids with values in and far out of range, NaN and
// infinity, wrong lengths, effect_param_t headers that lie about their sizes,
// multi-part payloads (text, impulse responses) started, interrupted, sent out
// of order and committed early, random ids, reads, rate changes, releases, and
// audio in between. Run it under AddressSanitizer (tools/run_harnesses.sh with
// SANITIZE=1) for the memory side; on its own it checks that the effect keeps
// answering and that its output stays finite.
//
// Arguments: [iterations] [seed]. Defaults: 60000, 1. FUZZ_TRACE=1 prints the
// commands before the first bad block; FUZZ_SCAN=1 tries each id on its own
// (slow: it builds a fresh engine for every try).
//
// Open finding: with seed 1, about 2250 of 8000 blocks of clean input come out
// with NaN or infinity, so some parameter values leave the engine poisoned.
// Which ids is not narrowed down yet. Listed as a known failure in CI until
// it is.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include <vector>

#include "../app/src/main/cpp/hal/JamesDspHalEffect.cpp"

static uint64_t g_rng = 1;
static uint32_t rnd() { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return (uint32_t)(g_rng >> 16); }
static uint32_t below(uint32_t n) { return n ? rnd() % n : 0; }

/* Every id the HAL and EffectParams.h handle, with the fork enable ids. */
static const int32_t kIds[] = {
	112, 115, 116, 128, 137, 150, 188, 1200, 1201, 1202, 1203, 1204, 1205, 1206, 1208,
	1210, 1212, 1213, 1500, 8888, 9997, 9999, 10004, 10006, 10009, 10010, 10011, 12000,
	12001, 12002, 19998, 19999, 20000, 20001, 20002, 25000, 25001, 25002, 25003, 25004,
	26000, 26001, 26002, 26003, 26004, 26005, 26006, 26007, 26008, 26009, 26010, 26011,
	26012, 26013, 26014, 26015, 26016, 26017, 26018, 26019, 26020, 26021, 26022, 26023,
	26024, 26100, 26101, 26102, 26103, 26104, 26105, 26106, 26107, 26108, 26109, 26110,
	26113, 26114, 26115, 26116, 26117, 26118, 26119, 26120, 26121, 26122, 26123, 26124,
	26200, 30000, 30001, 30002, 30003, 30004 };
static const int kNumIds = sizeof(kIds) / sizeof(kIds[0]);

static float nastyFloat()
{
	switch (below(12))
	{
	case 0: return NAN;
	case 1: return INFINITY;
	case 2: return -INFINITY;
	case 3: return 1e30f;
	case 4: return -1e30f;
	case 5: return 0.0f;
	case 6: return -1.0f;
	case 7: return (float)(int32_t)rnd();
	case 8: return 1e-40f;
	default: return (float)((int)below(40000) - 20000) * 0.05f;   /* plausible */
	}
}

static effect_handle_t h;
static std::vector<uint8_t> g_buf;

/* The last commands, to name the one that broke something (FUZZ_TRACE=1
   prints them at the first bad block and stops). */
static char g_trace[64][160];
static unsigned g_traceAt = 0;
static void trace(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
#include <stdarg.h>
static void trace(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(g_trace[g_traceAt++ & 63], sizeof(g_trace[0]), fmt, ap);
	va_end(ap);
}

static int command(uint32_t code, uint32_t size, void *data, void *reply, uint32_t *rs)
{
	uint32_t dummySize = sizeof(int32_t);
	int32_t dummy = 0;
	if (!reply) { reply = &dummy; rs = &dummySize; }
	return (*h)->command(h, code, size, data, rs, reply);
}

/* A well-formed SET_PARAM for id with vsize bytes of value. */
static void setParam(int32_t id, uint32_t vsize, bool nasty)
{
	g_buf.assign(sizeof(effect_param_t) + 4 + ((vsize + 3) & ~3u), 0);
	effect_param_t *p = (effect_param_t *)g_buf.data();
	p->psize = 4;
	p->vsize = vsize;
	memcpy(p->data, &id, 4);
	uint8_t *v = (uint8_t *)p->data + 4;
	if (nasty || below(2))
	{
		for (uint32_t i = 0; i + 4 <= vsize; i += 4) { float f = nastyFloat(); memcpy(v + i, &f, 4); }
	}
	else
	{
		for (uint32_t i = 0; i < vsize; i++) v[i] = (uint8_t)rnd();
	}
	{
		char vals[96] = "";
		int at = 0;
		for (uint32_t i = 0; i + 4 <= vsize && i < 32 && at < 80; i += 4)
		{
			float f; memcpy(&f, v + i, 4);
			at += snprintf(vals + at, sizeof(vals) - at, " %g", f);
		}
		trace("SET %d vsize %u:%s", id, vsize, vals);
	}
	command(EFFECT_CMD_SET_PARAM, (uint32_t)g_buf.size(), g_buf.data(), NULL, NULL);
}

/* A SET_PARAM whose header lies: psize/vsize larger than the buffer, a short
   buffer, psize not 4. Sizes picked to cross every check. */
static void lyingParam()
{
	uint32_t size = below(64);
	g_buf.assign(size + 8, 0);
	for (auto &b : g_buf) b = (uint8_t)rnd();
	if (size >= sizeof(effect_param_t))
	{
		effect_param_t *p = (effect_param_t *)g_buf.data();
		const uint32_t lies[] = { 4, 0, 2, 3, 8, 0xFFFFFFFFu, 0x7FFFFFFFu, 0x80000000u, 1u << 20 };
		p->psize = below(3) ? 4 : lies[below(9)];
		p->vsize = lies[below(9)];
		if (size >= sizeof(effect_param_t) + 4)
		{
			int32_t id = kIds[below(kNumIds)];
			memcpy(p->data, &id, 4);
		}
	}
	command(EFFECT_CMD_SET_PARAM, size, g_buf.data(), NULL, NULL);
}

static void getParam()
{
	uint8_t q[sizeof(effect_param_t) + 8] = { 0 };
	effect_param_t *p = (effect_param_t *)q;
	p->psize = below(4) ? 4 : below(16);
	p->vsize = below(4) ? 4 : below(16);
	int32_t id = below(3) ? kIds[below(kNumIds)] : (int32_t)rnd();
	memcpy(p->data, &id, 4);
	uint8_t reply[64];
	uint32_t rs = below(4) ? 20 : below(40);
	uint32_t cs = below(4) ? (uint32_t)sizeof(q) : below((uint32_t)sizeof(q) + 1);
	command(EFFECT_CMD_GET_PARAM, cs, q, reply, &rs);
}

/* A multi-part payload: header, some slices, a commit; sometimes cut short,
   sometimes with a lying header or slices for the other protocol. */
static void payload()
{
	const bool text = below(2);
	if (text)
	{
		int32_t info[2] = { (int32_t)below(6), below(4) ? 256 : (int32_t)rnd() };
		if (below(8) == 0) info[0] = (int32_t)rnd();
		g_buf.assign(sizeof(effect_param_t) + 12, 0);
		effect_param_t *p = (effect_param_t *)g_buf.data();
		p->psize = 4; p->vsize = 8;
		int32_t id = 8888; memcpy(p->data, &id, 4); memcpy(p->data + 4, info, 8);
		command(EFFECT_CMD_SET_PARAM, (uint32_t)g_buf.size(), g_buf.data(), NULL, NULL);
		const int slices = (int)below(7);
		for (int i = 0; i < slices; i++)
		{
			g_buf.assign(sizeof(effect_param_t) + 4 + 256, 0);
			p = (effect_param_t *)g_buf.data();
			p->psize = 4; p->vsize = below(6) ? 256 : below(300);
			id = 12001; memcpy(p->data, &id, 4);
			for (int k = 0; k < 256; k++) p->data[4 + k] = (char)(below(4) ? ' ' + below(95) : rnd());
			command(EFFECT_CMD_SET_PARAM, (uint32_t)g_buf.size(), g_buf.data(), NULL, NULL);
		}
	}
	else
	{
		/* 9999 header: the HAL treats its values as channels/frames/parts. */
		g_buf.assign(sizeof(effect_param_t) + 4 + 16, 0);
		effect_param_t *p = (effect_param_t *)g_buf.data();
		p->psize = 4; p->vsize = 16;
		int32_t id = 9999; memcpy(p->data, &id, 4);
		int32_t hdr[4] = { (int32_t)below(5), (int32_t)below(20000), (int32_t)below(8), (int32_t)below(4) };
		if (below(8) == 0) for (auto &x : hdr) x = (int32_t)rnd();
		memcpy(p->data + 4, hdr, 16);
		command(EFFECT_CMD_SET_PARAM, (uint32_t)g_buf.size(), g_buf.data(), NULL, NULL);
		const int slices = (int)below(6);
		for (int i = 0; i < slices; i++)
			setParam(below(2) ? 12000 : 12002, below(4) ? 16384 : below(20000), false);
	}
	if (below(4))
	{
		const int32_t commits[] = { 10004, 10006, 10009, 10010, 10011, 9997 };
		int16_t one = 1;
		g_buf.assign(sizeof(effect_param_t) + 8, 0);
		effect_param_t *p = (effect_param_t *)g_buf.data();
		p->psize = 4; p->vsize = 2;
		memcpy(p->data, &commits[below(6)], 4); memcpy(p->data + 4, &one, 2);
		command(EFFECT_CMD_SET_PARAM, (uint32_t)g_buf.size(), g_buf.data(), NULL, NULL);
	}
}

static uint32_t g_rate = 48000;
static void configure()
{
	const uint32_t rates[] = { 44100, 48000, 96000, 192000, 8000, 22050, 384000, 0, 1, 0xFFFFFFFFu };
	effect_config_t cfg;
	memset(&cfg, 0, sizeof(cfg));
	cfg.inputCfg.samplingRate = cfg.outputCfg.samplingRate = rates[below(4) ? below(4) : below(10)];
	cfg.inputCfg.format = cfg.outputCfg.format = below(8) ? AUDIO_FORMAT_PCM_FLOAT : (audio_format_t)below(8);
	cfg.inputCfg.channels = cfg.outputCfg.channels = below(8) ? AUDIO_CHANNEL_OUT_STEREO : below(64);
	cfg.outputCfg.accessMode = below(4) ? EFFECT_BUFFER_ACCESS_WRITE : EFFECT_BUFFER_ACCESS_ACCUMULATE;
	int32_t status = 0;
	uint32_t rs = sizeof(status);
	uint32_t size = below(8) ? (uint32_t)sizeof(cfg) : below((uint32_t)sizeof(cfg));
	command(EFFECT_CMD_SET_CONFIG, size, &cfg, &status, &rs);
	if (cfg.inputCfg.samplingRate >= 8000 && cfg.inputCfg.samplingRate <= 384000)
		g_rate = cfg.inputCfg.samplingRate;
}

static long g_blocks = 0, g_badBlocks = 0, g_badSamples = 0;
static void audio()
{
	const uint32_t frames = below(4) ? 1 + below(1024) : 1 + below(8192);
	static float in[8192 * 2], out[8192 * 2];
	const bool nastyIn = below(16) == 0;
	for (uint32_t i = 0; i < frames * 2; i++)
		in[i] = nastyIn && below(64) == 0 ? nastyFloat() : (float)((int)below(2000) - 1000) * 0.0005f;
	memset(out, 0, sizeof(float) * frames * 2);
	audio_buffer_t ib, ob;
	ib.frameCount = ob.frameCount = frames;
	ib.f32 = in;
	ob.f32 = out;
	int rc = (*h)->process(h, &ib, &ob);
	if (rc != 0)
		return;
	g_blocks++;
	long bad = 0;
	for (uint32_t i = 0; i < frames * 2; i++)
		if (!isfinite(out[i])) bad++;
	/* A non-finite sample in is allowed to come out as such once, nothing
	   more; anything else means a setting or a state went bad. */
	if (bad && !nastyIn)
	{
		g_badBlocks++; g_badSamples += bad;
		if (getenv("FUZZ_TRACE"))
		{
			printf("first bad block (%ld of %u samples); the commands before it:\n", bad, frames * 2);
			for (unsigned k = 0; k < 64; k++)
				if (g_trace[(g_traceAt + k) & 63][0]) printf("  %s\n", g_trace[(g_traceAt + k) & 63]);
			exit(3);
		}
	}
	trace("AUDIO %u frames%s", frames, nastyIn ? " (nasty input)" : "");
}

static void create()
{
	effect_uuid_t u = rv4a_descriptor.uuid;
	if (EffectCreate(&u, 0, 0, &h) != 0) { printf("EffectCreate failed\n"); exit(2); }
	configure();
	command(EFFECT_CMD_ENABLE, 0, NULL, NULL, NULL);
}

/* FUZZ_SCAN=1: each id on its own, on a fresh effect with that id's enable
   (id + 100 for the fork effects) switched on, 200 nasty payloads, clean audio
   after each. Names the ids whose values can poison the output. */
static int scanIds(void)
{
	int bad = 0;
	for (int k = 0; k < kNumIds; k++)
	{
		const int32_t id = kIds[k];
		long badHere = 0;
		for (int t = 0; t < 200 && !badHere; t++)
		{
			create();
			int16_t one = 1;
			const int32_t en[] = { id + 100, 1200 + (id % 100) };
			for (int e = 0; e < 2; e++)
			{
				g_buf.assign(sizeof(effect_param_t) + 8, 0);
				effect_param_t *p = (effect_param_t *)g_buf.data();
				p->psize = 4; p->vsize = 2;
				memcpy(p->data, &en[e], 4); memcpy(p->data + 4, &one, 2);
				command(EFFECT_CMD_SET_PARAM, (uint32_t)g_buf.size(), g_buf.data(), NULL, NULL);
			}
			setParam(id, 4 * (1 + below(24)), true);
			const long before = g_badBlocks;
			for (int a = 0; a < 6; a++) audio();
			badHere = g_badBlocks - before;
			if (badHere)
			{
				printf("  id %d poisons the output; the payload was:\n    %s\n", id,
					g_trace[(g_traceAt - 7) & 63]);
				bad++;
			}
			EffectRelease(h);
		}
	}
	printf("%d ids can poison the output\n", bad);
	return bad ? 1 : 0;
}

int main(int argc, char **argv)
{
	if (getenv("FUZZ_SCAN")) { g_rng = 7; return scanIds(); }
	const long iterations = argc > 1 ? atol(argv[1]) : 60000;
	g_rng = argc > 2 ? strtoull(argv[2], NULL, 10) : 1;
	if (!g_rng) g_rng = 1;
	printf("== %ld commands, seed %llu ==\n", iterations, (unsigned long long)g_rng);
	create();
	long releases = 0, answered = 0;
	for (long it = 0; it < iterations; it++)
	{
		switch (below(100))
		{
		case 0 ... 39: setParam(kIds[below(kNumIds)], below(5) ? 4 * below(20) : below(600), below(3) == 0); break;
		case 40 ... 49: setParam((int32_t)rnd(), below(64), true); break;
		case 50 ... 57: lyingParam(); break;
		case 58 ... 63: getParam(); break;
		case 64 ... 69: payload(); break;
		case 70 ... 71: configure(); break;
		case 72: command(below(2) ? EFFECT_CMD_ENABLE : EFFECT_CMD_DISABLE, 0, NULL, NULL, NULL); break;
		case 73: command(EFFECT_CMD_RESET, 0, NULL, NULL, NULL); break;
		case 74:
			if (below(20) == 0) { EffectRelease(h); releases++; create(); }
			break;
		default: audio(); break;
		}
		/* The liveness read the app makes on every sync must keep answering. */
		if (it % 997 == 0)
		{
			uint8_t q[sizeof(effect_param_t) + 8] = { 0 };
			effect_param_t *p = (effect_param_t *)q;
			p->psize = 4; p->vsize = 4;
			int32_t id = 20002; memcpy(p->data, &id, 4);
			uint8_t reply[32]; uint32_t rs = 20;
			if (command(EFFECT_CMD_GET_PARAM, sizeof(q), q, reply, &rs) == 0) answered++;
		}
	}
	EffectRelease(h);
	const long reads = (iterations + 996) / 997;
	printf("  blocks processed %ld, effect released and re-created %ld times\n", g_blocks, releases);
	printf("  liveness reads answered            %ld of %ld  %s\n", answered, reads, answered == reads ? "ok" : "FAIL");
	printf("  blocks with non-finite output      %ld (%ld samples)  %s\n", g_badBlocks, g_badSamples,
		g_badBlocks == 0 ? "ok" : "FAIL");
	const bool ok = answered == reads && g_badBlocks == 0;
	printf("%s\n", ok ? "PASS" : "FAIL");
	return ok ? 0 : 1;
}
