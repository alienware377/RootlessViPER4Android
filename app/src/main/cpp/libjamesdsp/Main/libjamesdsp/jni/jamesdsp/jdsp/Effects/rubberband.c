// Optional Rubber Band back-end for the pitch shifter.
//
// Rubber Band is a far better pitch shifter than anything reasonable to write
// here, but it is most of a megabyte and most people never touch this card, so
// it is not in the app. The user downloads it if they want it, and this file is
// the only thing that knows how to talk to it: the library is opened by path at
// runtime and every entry point is looked up by name. If it was never
// downloaded, or the download was removed, or the file is for the wrong
// architecture, nothing here is reachable and the card falls back to a built-in
// mode. Nothing else in the engine has to know it might be missing.
//
// The library is never dlclose()d. Removing the download deletes the file so it
// is gone from the next launch onwards, but a mapping the audio thread may be
// inside cannot be pulled out from under it, and unmapping to save a megabyte
// of address space in a process that is about to be told to stop anyway buys
// nothing worth that risk.
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "../jdsp_header.h"

// Mirrors rubberband-c.h. Duplicated rather than included because the header
// belongs to a library that may not be present, and this file has to compile
// and run whether or not it ever is.
typedef void *RbLiveState;
#define RB_OPTION_FORMANT_PRESERVED 0x01000000

static struct
{
	void *handle;
	RbLiveState (*live_new)(unsigned int sampleRate, unsigned int channels, int options);
	void (*live_delete)(RbLiveState);
	void (*live_reset)(RbLiveState);
	void (*live_set_pitch_scale)(RbLiveState, double scale);
	unsigned int (*live_get_block_size)(RbLiveState);
	void (*live_shift)(RbLiveState, const float *const *input, float *const *output);
} rb;

static char rbError[256];

const char *RubberBandLastError(void)
{
	return rbError;
}

int RubberBandAvailable(void)
{
	return rb.handle != 0;
}

// Every symbol is required. A partial load - right file name, wrong or older
// library - would otherwise leave a null pointer to be called on the audio
// thread later, which is a crash a long way from its cause.
#define RB_SYM(name) \
	do { \
		*(void **)(&rb.name) = dlsym(h, "rubberband_" #name); \
		if (!rb.name) { missing = "rubberband_" #name; goto fail; } \
	} while (0)

int RubberBandLoad(JamesDSPLib *jdsp, const char *path)
{
	const char *missing = 0;
	void *h;
	if (!path || !*path)
		return 0;
	if (rb.handle)
		return 1;
	dlerror();
	h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
	if (!h)
	{
		const char *e = dlerror();
		snprintf(rbError, sizeof(rbError), "%s", e ? e : "dlopen failed");
		return 0;
	}
	RB_SYM(live_new);
	RB_SYM(live_delete);
	RB_SYM(live_reset);
	RB_SYM(live_set_pitch_scale);
	RB_SYM(live_get_block_size);
	RB_SYM(live_shift);

	// Published under the lock: the audio thread reads rb.handle through
	// RubberBandPrepare, and must never see the table half filled in.
	jdsp_lock(jdsp);
	rb.handle = h;
	jdsp_unlock(jdsp);
	rbError[0] = 0;
	return 1;

fail:
	snprintf(rbError, sizeof(rbError), "%s missing - wrong or damaged library", missing);
	memset(&rb, 0, sizeof(rb));
	dlclose(h);
	return 0;
}
#undef RB_SYM

void RubberBandRelease(PitchShift *p)
{
	if (p->rbState && rb.live_delete)
		rb.live_delete((RbLiveState)p->rbState);
	p->rbState = 0;
	for (int c = 0; c < 2; c++)
	{
		if (p->rbIn[c]) { free(p->rbIn[c]); p->rbIn[c] = 0; }
		if (p->rbOut[c]) { free(p->rbOut[c]); p->rbOut[c] = 0; }
	}
	p->rbBlock = p->rbFill = p->rbReady = 0;
}

// Called only from SetParam, Enable and Refresh, all of which hold the lock and
// none of which run on the audio thread. rubberband_live_new allocates and is
// not realtime safe.
int RubberBandPrepare(PitchShift *p, float fs, int formant)
{
	unsigned int block;
	RbLiveState st;
	if (!rb.handle)
		return 0;
	if (p->rbReady && p->rbFormant == formant && p->rbFs == fs)
	{
		// Same library, same rate, same formant setting: only the amount of
		// shift changed, and that is a realtime-safe call.
		rb.live_set_pitch_scale((RbLiveState)p->rbState, (double)p->rate);
		return 1;
	}
	RubberBandRelease(p);
	if (fs < 8000.0f) fs = 48000.0f;
	st = rb.live_new((unsigned int)fs, 2, formant ? RB_OPTION_FORMANT_PRESERVED : 0);
	if (!st)
	{
		snprintf(rbError, sizeof(rbError), "the library would not start at %d Hz", (int)fs);
		return 0;
	}
	rb.live_set_pitch_scale(st, (double)p->rate);
	block = rb.live_get_block_size(st);
	// A block size of zero, or one large enough to look like a corrupted read,
	// means the library is not what it claims to be. Refuse rather than
	// allocate on it.
	if (block == 0 || block > 65536)
	{
		rb.live_delete(st);
		snprintf(rbError, sizeof(rbError), "implausible block size %u", block);
		return 0;
	}
	for (int c = 0; c < 2; c++)
	{
		p->rbIn[c] = (float *)calloc(block, sizeof(float));
		p->rbOut[c] = (float *)calloc(block, sizeof(float));
	}
	if (!p->rbIn[0] || !p->rbIn[1] || !p->rbOut[0] || !p->rbOut[1])
	{
		rb.live_delete(st);
		RubberBandRelease(p);
		snprintf(rbError, sizeof(rbError), "out of memory for a %u sample block", block);
		return 0;
	}
	p->rbState = st;
	p->rbBlock = (int)block;
	p->rbFill = 0;
	p->rbFormant = formant;
	p->rbFs = fs;
	p->rbReady = 1;
	// The first block out is silence, because a block of input has to go in
	// before anything comes back. Ramping the wet path up from nothing means
	// that shows up as the effect arriving rather than as the audio dropping
	// out, which at a full wet mix is what it would otherwise be.
	p->wetGain = 0.0f;
	p->fadeLen = (float)block + fs * 0.02f;
	return 1;
}

void RubberBandProcess(JamesDSPLib *jdsp, PitchShift *p, size_t n)
{
	const int block = p->rbBlock;
	const float step = p->fadeLen > 0.0f ? 1.0f / p->fadeLen : 1.0f;
	int idx = p->rbFill;
	float g = p->wetGain;
	for (size_t i = 0; i < n; i++)
	{
		const float dryL = jdsp->tmpBuffer[0][i];
		const float dryR = jdsp->tmpBuffer[1][i];
		const float wetL = p->rbOut[0][idx];
		const float wetR = p->rbOut[1][idx];
		p->rbIn[0][idx] = dryL;
		p->rbIn[1][idx] = dryR;
		jdsp->tmpBuffer[0][i] = dryL + p->mix * g * (wetL - dryL);
		jdsp->tmpBuffer[1][i] = dryR + p->mix * g * (wetR - dryR);
		if (g < 1.0f) { g += step; if (g > 1.0f) g = 1.0f; }
		if (++idx >= block)
		{
			// Reads rbIn, writes rbOut, so the block just collected becomes the
			// block handed out over the next pass - one block of latency, and
			// the reason a single index serves for both.
			const float *const in[2] = { p->rbIn[0], p->rbIn[1] };
			float *const out[2] = { p->rbOut[0], p->rbOut[1] };
			rb.live_shift((RbLiveState)p->rbState, in, out);
			idx = 0;
		}
	}
	p->rbFill = idx;
	p->wetGain = g;
}
