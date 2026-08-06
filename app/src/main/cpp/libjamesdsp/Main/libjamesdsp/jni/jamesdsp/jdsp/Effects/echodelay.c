// Feature-rich stereo echo/delay, modelled on the classic "fruity delay"
// topology: delay line with mono / stereo / ping-pong routing, feedback with a
// state-variable filter in the loop, modulated (chorus-like) delay time,
// diffusion, and soft saturation inside the feedback path.
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include "../jdsp_header.h"

#define ECHO_MASK (ECHO_BUFLEN - 1)
#define ECHO_APMASK (ECHO_APLEN - 1)

static inline float echoRead(const float *buf, float pos)
{
	int i0 = (int)pos;
	float fr = pos - (float)i0;
	int i1 = (i0 + 1) & ECHO_MASK;
	return buf[i0 & ECHO_MASK] * (1.0f - fr) + buf[i1] * fr;
}

void EchoDelaySetParam(JamesDSPLib *jdsp, float timeMs, float feedbackPct,
	int model, float stereoPct, float cutoffHz, float resonance, int filterType,
	float modRateHz, float modDepthPct, float diffusionPct,
	float satPct, float wetPct, float dryPct)
{
	EchoDelay *e = &jdsp->echoDelay;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	e->fs = fs;

	if (timeMs < 1.0f) timeMs = 1.0f;
	float maxMs = (float)(ECHO_BUFLEN - 8) * 1000.0f / fs;
	if (timeMs > maxMs) timeMs = maxMs;
	e->delaySamples = timeMs * 0.001f * fs;

	e->feedback = feedbackPct * 0.0095f;      // keep just under unity at 100%
	if (e->feedback > 0.98f) e->feedback = 0.98f;
	if (e->feedback < 0.0f) e->feedback = 0.0f;

	e->model = model;                          // 0 mono, 1 stereo, 2 ping-pong, 3 off
	e->stereoSpread = stereoPct * 0.01f;
	e->wet = wetPct * 0.01f;
	e->dry = dryPct * 0.01f;

	// Topology-preserving (zero-delay-feedback) state variable filter.
	// The naive Chamberlin form used previously goes unstable above ~fs/6,
	// which blew the feedback loop up to NaN at the default 12 kHz cutoff and
	// silenced the whole chain. This form is stable across the full range.
	if (cutoffHz < 40.0f) cutoffHz = 40.0f;
	if (cutoffHz > fs * 0.49f) cutoffHz = fs * 0.49f;
	float q = 0.5f + resonance * 0.045f;       // resonance 0..100 -> Q 0.5..5
	if (q < 0.5f) q = 0.5f;
	e->svfG = tanf(3.14159265358979f * cutoffHz / fs);
	e->svfK = 1.0f / q;
	e->svfA1 = 1.0f / (1.0f + e->svfG * (e->svfG + e->svfK));
	e->svfA2 = e->svfG * e->svfA1;
	e->svfA3 = e->svfG * e->svfA2;
	e->filterType = filterType;                // 0 LP, 1 HP, 2 BP, 3 off

	e->modRate = modRateHz;
	e->modDepth = modDepthPct * 0.01f * 0.004f * fs;   // up to ~4 ms of sweep
	e->modInc = 2.0f * 3.14159265358979f * modRateHz / fs;

	e->diffusion = diffusionPct * 0.01f * 0.7f;
	e->satDrive = 1.0f + satPct * 0.06f;
}

static inline float echoSvf(EchoDelay *e, int ch, float x)
{
	if (e->filterType == 3)
		return x;
	float v3 = x - e->svfIc2[ch];
	float v1 = e->svfA1 * e->svfIc1[ch] + e->svfA2 * v3;
	float v2 = e->svfIc2[ch] + e->svfA2 * e->svfIc1[ch] + e->svfA3 * v3;
	e->svfIc1[ch] = 2.0f * v1 - e->svfIc1[ch];
	e->svfIc2[ch] = 2.0f * v2 - e->svfIc2[ch];
	if (e->filterType == 0) return v2;                       // low pass
	if (e->filterType == 1) return x - e->svfK * v1 - v2;    // high pass
	return v1;                                               // band pass
}

static inline float echoSat(EchoDelay *e, float x)
{
	float d = x * e->satDrive;
	return tanhf(d) / e->satDrive;
}

void EchoDelayProcess(JamesDSPLib *jdsp, size_t n)
{
	EchoDelay *e = &jdsp->echoDelay;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	if (!e->bufL || !e->bufR)
		return;
	if (e->model == 3)
		return;

	for (i = 0; i < n; i++)
	{
		int w = e->widx;
		float inL = jdsp->tmpBuffer[0][i];
		float inR = jdsp->tmpBuffer[1][i];

		// Modulated delay time (gives the classic warbling tape feel)
		float mod = sinf(e->modPhase) * e->modDepth;
		float dL = e->delaySamples + mod;
		// Stereo spread offsets the right tap so the echoes don't stack
		float dR = e->delaySamples * (1.0f + 0.35f * e->stereoSpread) - mod;
		if (dL < 2.0f) dL = 2.0f;
		if (dR < 2.0f) dR = 2.0f;

		float posL = (float)w - dL; if (posL < 0.0f) posL += ECHO_BUFLEN;
		float posR = (float)w - dR; if (posR < 0.0f) posR += ECHO_BUFLEN;

		float echoL = echoRead(e->bufL, posL);
		float echoR = echoRead(e->bufR, posR);

		// Diffusion: a short allpass smears each repeat
		if (e->diffusion > 0.0f)
		{
			int aw = w & ECHO_APMASK;
			int dp = aw - e->diffDelay; if (dp < 0) dp += ECHO_APLEN;
			float apL = e->apL[dp & ECHO_APMASK];
			float apR = e->apR[dp & ECHO_APMASK];
			float xL = echoL + e->diffusion * apL;
			float xR = echoR + e->diffusion * apR;
			e->apL[aw] = xL;
			e->apR[aw] = xR;
			echoL = apL - e->diffusion * xL;
			echoR = apR - e->diffusion * xR;
		}

		echoL = echoSvf(e, 0, echoL);
		echoR = echoSvf(e, 1, echoR);

		float fbL, fbR;
		if (e->model == 0)
		{
			// Mono: both taps share one signal
			float m = (echoL + echoR) * 0.5f;
			echoL = m; echoR = m;
			fbL = m; fbR = m;
		}
		else if (e->model == 2)
		{
			// Ping-pong: feedback crosses channels
			fbL = echoR;
			fbR = echoL;
		}
		else
		{
			fbL = echoL;
			fbR = echoR;
		}

		e->bufL[w] = echoSat(e, inL + fbL * e->feedback);
		e->bufR[w] = echoSat(e, inR + fbR * e->feedback);

		float outL = inL * e->dry + echoL * e->wet;
		float outR = inR * e->dry + echoR * e->wet;
		// Guard: if anything ever goes non-finite, drop back to dry audio and
		// reset the loop rather than pushing NaN into the rest of the chain.
		if (!isfinite(outL) || !isfinite(outR))
		{
			memset(e->bufL, 0, ECHO_BUFLEN * sizeof(float));
			memset(e->bufR, 0, ECHO_BUFLEN * sizeof(float));
			e->svfIc1[0] = e->svfIc1[1] = 0.0f;
			e->svfIc2[0] = e->svfIc2[1] = 0.0f;
			outL = inL;
			outR = inR;
		}
		jdsp->tmpBuffer[0][i] = outL;
		jdsp->tmpBuffer[1][i] = outR;

		e->modPhase += e->modInc;
		if (e->modPhase > 6.28318530717959f)
			e->modPhase -= 6.28318530717959f;
		e->widx = (w + 1) & ECHO_MASK;
	}
}

void EchoDelayEnable(JamesDSPLib *jdsp)
{
	EchoDelay *e = &jdsp->echoDelay;
	if (!jdsp->echoDelayEnabled)
	{
		// Allocate the (large) delay lines only while the effect is in use.
		// The engine is instantiated per audio session, so keeping megabytes
		// of idle buffers inside the struct multiplies across every session.
		if (!e->bufL)
			e->bufL = (float*)calloc(ECHO_BUFLEN, sizeof(float));
		if (!e->bufR)
			e->bufR = (float*)calloc(ECHO_BUFLEN, sizeof(float));
		if (!e->bufL || !e->bufR)
		{
			jdsp->echoDelayEnabled = 0;
			return;
		}
		memset(e->bufL, 0, ECHO_BUFLEN * sizeof(float));
		memset(e->bufR, 0, ECHO_BUFLEN * sizeof(float));
		memset(e->apL, 0, sizeof(e->apL));
		memset(e->apR, 0, sizeof(e->apR));
		e->widx = 0;
		e->modPhase = 0.0f;
		e->svfIc1[0] = e->svfIc1[1] = 0.0f;
		e->svfIc2[0] = e->svfIc2[1] = 0.0f;
		e->diffDelay = (int)(0.0071f * e->fs);
		if (e->diffDelay < 8 || e->diffDelay >= ECHO_APLEN) e->diffDelay = 331;
	}
	jdsp->echoDelayEnabled = 1;
}

void EchoDelayDisable(JamesDSPLib *jdsp)
{
	EchoDelay *e = &jdsp->echoDelay;
	jdsp->echoDelayEnabled = 0;
	if (e->bufL) { free(e->bufL); e->bufL = 0; }
	if (e->bufR) { free(e->bufR); e->bufR = 0; }
}
