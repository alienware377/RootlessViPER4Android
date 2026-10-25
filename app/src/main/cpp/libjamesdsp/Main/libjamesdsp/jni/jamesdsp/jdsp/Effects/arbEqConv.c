#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"
void ArbitraryResponseEqualizerConstructor(JamesDSPLib *jdsp)
{
	/* 0 = minimum phase (no added latency, non-linear phase),
	   1 = linear phase (symmetric ringing, constant group delay). */
	jdsp->arbMag.linearPhase = 0;
	jdsp->arbMag.instance.filterLen = InitArbitraryEq(&jdsp->arbMag.instance.coeffGen, 0);
	FFTConvolver2x2Init(&jdsp->arbMag.instance.convState);
	FFTConvolver2x2Init(&jdsp->arbMag.conv);
	float *kDelta = (float*)malloc(jdsp->arbMag.instance.filterLen * sizeof(float));
	memset(kDelta, 0, jdsp->arbMag.instance.filterLen * sizeof(float));
	kDelta[0] = 1.0f;
	FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.instance.convState, (unsigned int)jdsp->blockSize, kDelta, kDelta, jdsp->arbMag.instance.filterLen);
	FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.conv, (unsigned int)jdsp->blockSize, kDelta, kDelta, jdsp->arbMag.instance.filterLen);
	free(kDelta);
}
void ArbitraryResponseEqualizerDestructor(JamesDSPLib *jdsp)
{
	EqNodesFree(&jdsp->arbMag.instance.coeffGen);
	FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);
	FFTConvolver2x2Free(&jdsp->arbMag.conv);
	free(jdsp->arbMag.filterL);
	free(jdsp->arbMag.filterR);
	jdsp->arbMag.filterL = 0;
	jdsp->arbMag.filterR = 0;
	jdsp->arbMag.keptLen = 0;
}
/**
 * Rebuilds the coefficient generator in the requested phase mode. The filter
 * has to be regenerated because phase behaviour is baked in at init, so the
 * caller re-sends the node string afterwards.
 */
void ArbitraryResponseEqualizerSetPhaseMode(JamesDSPLib *jdsp, int linearPhase)
{
	if (jdsp->arbMag.linearPhase == linearPhase)
		return;
	jdsp->arbMag.linearPhase = linearPhase;
	EqNodesFree(&jdsp->arbMag.instance.coeffGen);
	jdsp->arbMag.instance.filterLen =
		InitArbitraryEq(&jdsp->arbMag.instance.coeffGen, linearPhase);
}

/* Keeps a copy of whatever filters are currently loaded, so the deferred
   refresh in Enable can put them back without the node strings. */
static void arbKeepFilters(JamesDSPLib *jdsp, const float *left, const float *right)
{
	const unsigned int len = jdsp->arbMag.instance.filterLen;
	if (jdsp->arbMag.keptLen != len)
	{
		free(jdsp->arbMag.filterL);
		free(jdsp->arbMag.filterR);
		jdsp->arbMag.filterL = (float *)malloc(len * sizeof(float));
		jdsp->arbMag.filterR = (float *)malloc(len * sizeof(float));
		jdsp->arbMag.keptLen = (jdsp->arbMag.filterL && jdsp->arbMag.filterR) ? len : 0;
	}
	if (!jdsp->arbMag.keptLen)
		return;
	memcpy(jdsp->arbMag.filterL, left, len * sizeof(float));
	memcpy(jdsp->arbMag.filterR, right, len * sizeof(float));
}

/* Puts new filters into the convolvers. A refresh rewrites the loaded
   segments in place, which is only right while the filter needs the same
   number of segments as the one loaded. The phase switch changes the length
   (8192 taps minimum phase, 16383 linear) without reloading anything, and a
   refresh across it either read far past the end of an 8192-tap filter or
   cut a linear one in half. So when the count differs, load in full, under
   the lock, the same way the deferred refresh in Enable does. */
static void arbLoadFilters(JamesDSPLib *jdsp, const float *left, const float *right, unsigned int len)
{
	const unsigned int bs = jdsp->arbMag.instance.convState._blockSize;
	if (bs && (len + bs - 1) / bs == jdsp->arbMag.instance.convState._segCount)
	{
		FFTConvolver2x2RefreshImpulseResponse(&jdsp->arbMag.instance.convState, &jdsp->arbMag.conv, left, right, len);
		return;
	}
	jdsp_lock(jdsp);
	FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);
	FFTConvolver2x2Free(&jdsp->arbMag.conv);
	FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.instance.convState, (unsigned int)jdsp->blockSize, left, right, len);
	FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.conv, (unsigned int)jdsp->blockSize, left, right, len);
	jdsp_unlock(jdsp);
}

void ArbitraryResponseEqualizerStringParser(JamesDSPLib *jdsp, char *stringEq)
{
	ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, stringEq);
	float *eqFil = jdsp->arbMag.instance.coeffGen.GetFilter(&jdsp->arbMag.instance.coeffGen, (float)jdsp->fs);
	arbKeepFilters(jdsp, eqFil, eqFil);
	arbLoadFilters(jdsp, eqFil, eqFil, jdsp->arbMag.instance.filterLen);
}

/* One curve per ear.
 *
 * The convolver has always been a 2x2 and has always taken a left and a right
 * impulse response; every caller simply passed the same one twice. So this
 * costs one extra filter build and nothing at runtime.
 *
 * The order matters: the generator returns a pointer into its own working
 * buffer, so the left filter has to be copied out before the right one is
 * built over the top of it. */
void ArbitraryResponseEqualizerStringParserStereo(JamesDSPLib *jdsp, char *stringEqLeft, char *stringEqRight)
{
	const unsigned int len = jdsp->arbMag.instance.filterLen;
	float *left = (float *)malloc(len * sizeof(float));
	if (!left)
	{
		/* Better one correct curve on both ears than none at all. */
		ArbitraryResponseEqualizerStringParser(jdsp, stringEqLeft);
		return;
	}
	ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, stringEqLeft);
	memcpy(left, jdsp->arbMag.instance.coeffGen.GetFilter(&jdsp->arbMag.instance.coeffGen, (float)jdsp->fs), len * sizeof(float));

	ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, stringEqRight);
	float *right = jdsp->arbMag.instance.coeffGen.GetFilter(&jdsp->arbMag.instance.coeffGen, (float)jdsp->fs);

	arbKeepFilters(jdsp, left, right);
	arbLoadFilters(jdsp, left, right, len);
	free(left);
}
void ArbitraryResponseEqualizerEnable(JamesDSPLib *jdsp, char enable)
{
	if (jdsp->arbMagForceRefresh)
	{
		/* Reload what was actually set, which may be two different curves.
		   Asking the generator again would only ever return the last one it
		   built, so a per-channel setup used to collapse onto one ear's curve
		   the first time this refresh ran. */
		float *eqFil = jdsp->arbMag.instance.coeffGen.GetFilter(&jdsp->arbMag.instance.coeffGen, (float)jdsp->fs);
		const float *useL = jdsp->arbMag.keptLen == jdsp->arbMag.instance.filterLen ? jdsp->arbMag.filterL : eqFil;
		const float *useR = jdsp->arbMag.keptLen == jdsp->arbMag.instance.filterLen ? jdsp->arbMag.filterR : eqFil;
		jdsp_lock(jdsp);
		FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);
		FFTConvolver2x2Free(&jdsp->arbMag.conv);
		FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.instance.convState, (unsigned int)jdsp->blockSize, (float *)useL, (float *)useR, jdsp->arbMag.instance.filterLen);
		FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.conv, (unsigned int)jdsp->blockSize, (float *)useL, (float *)useR, jdsp->arbMag.instance.filterLen);
		jdsp_unlock(jdsp);
		jdsp->arbMagForceRefresh = 0;
	}
	if (enable)
		jdsp->arbitraryMagEnabled = 1;
}
void ArbitraryResponseEqualizerDisable(JamesDSPLib *jdsp)
{
	jdsp->arbitraryMagEnabled = 0;
}
void ArbitraryResponseEqualizerProcess(JamesDSPLib *jdsp, size_t n)
{
	FFTConvolver2x2Process(&jdsp->arbMag.conv, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}