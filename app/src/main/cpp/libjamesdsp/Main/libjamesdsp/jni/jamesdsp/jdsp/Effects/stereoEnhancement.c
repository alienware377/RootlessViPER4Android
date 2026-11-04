#include <string.h>
#include <stdlib.h>
#include <math.h>
#include "../jdsp_header.h"
/* The design on its own, for callers that already hold the lock, such as
   JamesDSPSetSampleRate: the lock is not recursive. */
void StereoEnhancementDesign(JamesDSPLib *jdsp)
{
	WarpedPFB *subband0 = (WarpedPFB *)jdsp->sterEnh.subband[0];
	WarpedPFB *subband1 = (WarpedPFB *)jdsp->sterEnh.subband[1];
	initWarpedPFB(subband0, jdsp->fs, 5, 2);
	assignPtrWarpedPFB(subband1, 5, 2);
	float ms = 1.2f; // 1.2 ms
	for (unsigned int i = 0; i < 5; i++)
		jdsp->sterEnh.emaAlpha[i] = 1.0f - powf(10.0f, (log10f(0.5f) / (ms / 1000.0f) / (jdsp->fs / (float)subband0->Sk[i])));
}
void StereoEnhancementRefresh(JamesDSPLib *jdsp)
{
  jdsp_lock(jdsp);
	StereoEnhancementDesign(jdsp);
  jdsp_unlock(jdsp);
}
void StereoEnhancementSetParam(JamesDSPLib *jdsp, float mix)
{
  jdsp_lock(jdsp);
	/* 0 to 1; the card sends 0.3 to 0.75. In root mode any app can send up
	   to 327, and the gain that follows grew without limit. */
	jdsp->sterEnh.mix = JDSP_HOLD(mix, 0.0f, 1.0f);
	jdsp->sterEnh.minusMix = 1.0f - jdsp->sterEnh.mix;
	if (jdsp->sterEnh.mix > 0.5f)
		jdsp->sterEnh.gain = 3.0f - jdsp->sterEnh.mix * 2.0f;
	else
		jdsp->sterEnh.gain = jdsp->sterEnh.mix * 2.0f + 1.0f;
  jdsp_unlock(jdsp);
}
void StereoEnhancementConstructor(JamesDSPLib *jdsp)
{
	StereoEnhancementRefresh(jdsp);
}
void StereoEnhancementDestructor(JamesDSPLib *jdsp)
{
}
void StereoEnhancementEnable(JamesDSPLib *jdsp)
{
	jdsp->sterEnhEnabled = 1;
}
void StereoEnhancementDisable(JamesDSPLib *jdsp)
{
	jdsp->sterEnhEnabled = 0;
}
void StereoEnhancementProcess(JamesDSPLib *jdsp, size_t n)
{
	stereoEnhancement *snh = &jdsp->sterEnh;
	WarpedPFB *subband0 = (WarpedPFB *)snh->subband[0];
	WarpedPFB *subband1 = (WarpedPFB *)snh->subband[1];
	unsigned int *samplingPeriod = subband0->decimationCounter;
	unsigned int *Sk = subband0->Sk;
	float *bandLeft = subband0->subbandData;
	float *bandRight = subband1->subbandData;
	float y1, y2;
	for (size_t i = 0; i < n; i++)
	{
		/* A NaN or Inf in stays in the filterbank and the band envelopes for
		   good, and switching the card off and on does not clear them, so
		   every sample out was NaN for the life of the engine. Keep it out. */
		if (!isfinite(jdsp->tmpBuffer[0][i])) jdsp->tmpBuffer[0][i] = 0.0f;
		if (!isfinite(jdsp->tmpBuffer[1][i])) jdsp->tmpBuffer[1][i] = 0.0f;
		analysisWarpedPFBStereo(subband0, subband1, &jdsp->tmpBuffer[0][i], &jdsp->tmpBuffer[1][i]);
		for (int j = 0; j < 5; j++)
		{
			if (samplingPeriod[j] == Sk[j])
			{
				float sum = bandLeft[j] + bandRight[j];
				float diff = bandLeft[j] - bandRight[j];
				float sumSq = sum * sum;
				float diffSq = diff * diff;
				snh->sumStates[j] = snh->sumStates[j] * (1.0f - snh->emaAlpha[j]) + sumSq * snh->emaAlpha[j];
				snh->diffStates[j] = snh->diffStates[j] * (1.0f - snh->emaAlpha[j]) + diffSq * snh->emaAlpha[j];
				/* A huge but finite sample squares to Inf, and an Inf level
				   never decays: Inf over Inf made every sample after it NaN.
				   Found by tools/paramfuzz_harness.cpp. Start the levels again. */
				int restarted = 0;
				if (!isfinite(snh->sumStates[j]) || !isfinite(snh->diffStates[j]))
				{
					snh->sumStates[j] = snh->diffStates[j] = 0.0f;
					restarted = 1;
				}
				float centre = 0.0f;
				/* No ratio on the sample of the restart: 0 over 0 is NaN too.
				   From the next one the level is above zero again. */
				if (sumSq > FLT_EPSILON && !restarted)
					centre = (0.5f - sqrtf(snh->diffStates[j] / snh->sumStates[j]) * 0.5f) * sum;
				bandLeft[j] = (bandLeft[j] - centre) * snh->mix + centre * snh->minusMix;
				bandRight[j] = (bandRight[j] - centre) * snh->mix + centre * snh->minusMix;
			}
		}
		synthesisWarpedPFBStereo(subband0, subband1, &y1, &y2);
		jdsp->tmpBuffer[0][i] = y1 * snh->gain;
		jdsp->tmpBuffer[1][i] = y2 * snh->gain;
	}
}
