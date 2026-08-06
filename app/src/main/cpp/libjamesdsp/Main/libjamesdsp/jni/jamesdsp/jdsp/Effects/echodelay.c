// Feature-rich stereo echo/delay, modelled on the classic "fruity delay"
// topology: delay line with mono / stereo / ping-pong routing, feedback with a
// state-variable filter in the loop, modulated (chorus-like) delay time,
// diffusion, and soft saturation inside the feedback path.
#include <math.h>
#include <string.h>
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

	// State-variable filter coefficients for the feedback loop
	if (cutoffHz < 40.0f) cutoffHz = 40.0f;
	if (cutoffHz > fs * 0.45f) cutoffHz = fs * 0.45f;
	e->svfF = 2.0f * sinf(3.14159265358979f * cutoffHz / fs);
	float q = 0.5f + resonance * 0.045f;       // resonance 0..100 -> Q 0.5..5
	if (q < 0.5f) q = 0.5f;
	e->svfQ = 1.0f / q;
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
	float lp = e->svfLp[ch] + e->svfF * e->svfBp[ch];
	float hp = x - lp - e->svfQ * e->svfBp[ch];
	float bp = e->svfF * hp + e->svfBp[ch];
	e->svfLp[ch] = lp;
	e->svfBp[ch] = bp;
	if (e->filterType == 0) return lp;
	if (e->filterType == 1) return hp;
	return bp;
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

		jdsp->tmpBuffer[0][i] = inL * e->dry + echoL * e->wet;
		jdsp->tmpBuffer[1][i] = inR * e->dry + echoR * e->wet;

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
		memset(e->bufL, 0, sizeof(e->bufL));
		memset(e->bufR, 0, sizeof(e->bufR));
		memset(e->apL, 0, sizeof(e->apL));
		memset(e->apR, 0, sizeof(e->apR));
		e->widx = 0;
		e->modPhase = 0.0f;
		e->svfLp[0] = e->svfLp[1] = 0.0f;
		e->svfBp[0] = e->svfBp[1] = 0.0f;
		e->diffDelay = (int)(0.0071f * e->fs);
		if (e->diffDelay < 8 || e->diffDelay >= ECHO_APLEN) e->diffDelay = 331;
	}
	jdsp->echoDelayEnabled = 1;
}

void EchoDelayDisable(JamesDSPLib *jdsp)
{
	jdsp->echoDelayEnabled = 0;
}
