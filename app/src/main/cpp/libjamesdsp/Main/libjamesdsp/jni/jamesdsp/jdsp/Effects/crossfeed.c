#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"
void CrossfeedConstructor(JamesDSPLib *jdsp)
{
	memset(&jdsp->advXF, 0, sizeof(jdsp->advXF));
}
void CrossfeedDestructor(JamesDSPLib *jdsp)
{
	if (jdsp->advXF.conv[0])
	{
		FFTConvolver2x4x2Free(jdsp->advXF.conv[0]);
		FFTConvolver2x4x2Free(jdsp->advXF.conv[1]);
		FFTConvolver2x4x2Free(jdsp->advXF.conv[2]);
		free(jdsp->advXF.conv[0]);
		free(jdsp->advXF.conv[1]);
		free(jdsp->advXF.conv[2]);
		/* All three, not just the first: the rebuild test looks at each one,
		   and a stale pointer to freed memory passed it. */
		jdsp->advXF.conv[0] = 0;
		jdsp->advXF.conv[1] = 0;
		jdsp->advXF.conv[2] = 0;
	}
	if (jdsp->advXF.convLong_T_S)
	{
		TwoStageFFTConvolver2x4x2Free(jdsp->advXF.convLong_T_S);
		free(jdsp->advXF.convLong_T_S);
		jdsp->advXF.convLong_T_S = 0;
	}
	if (jdsp->advXF.convLong_S_S)
	{
		FFTConvolver2x4x2Free(jdsp->advXF.convLong_S_S);
		free(jdsp->advXF.convLong_S_S);
		jdsp->advXF.convLong_S_S = 0;
	}
}
void CrossfeedProcessFFTConvolver2x4x2(JamesDSPLib *jdsp, size_t n)
{
	FFTConvolver2x4x2Process(jdsp->advXF.convLong_S_S, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}
void CrossfeedProcessTwoStageFFTConvolver2x4x2(JamesDSPLib *jdsp, size_t n)
{
	TwoStageFFTConvolver2x4x2Process(jdsp->advXF.convLong_T_S, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}
void CrossfeedEnable(JamesDSPLib *jdsp, char enable)
{
    jdsp_lock(jdsp);
	/* Only one of the two long convolvers is ever built, so asking for both
	   was always true: every enable, including every mode change and every
	   resend of the same settings, tore all four convolvers down and built
	   them again, several ms of work with audio waiting on the lock. */
	if (jdsp->crossfeedForceRefresh || !jdsp->advXF.conv[0] || !jdsp->advXF.conv[1] || !jdsp->advXF.conv[2] || (!jdsp->advXF.convLong_S_S && !jdsp->advXF.convLong_T_S))
	{
		CrossfeedDestructor(jdsp);
		jdsp->advXF.conv[0] = (FFTConvolver2x4x2 *)malloc(sizeof(FFTConvolver2x4x2));
		FFTConvolver2x4x2Init(jdsp->advXF.conv[0]);
		FFTConvolver2x4x2LoadImpulseResponse(jdsp->advXF.conv[0], (unsigned int)jdsp->blockSize, jdsp->blobsCh1[0], jdsp->blobsCh2[0], jdsp->blobsCh3[0], jdsp->blobsCh4[0], jdsp->blobsResampledLen);
		jdsp->advXF.conv[1] = (FFTConvolver2x4x2 *)malloc(sizeof(FFTConvolver2x4x2));
		FFTConvolver2x4x2Init(jdsp->advXF.conv[1]);
		FFTConvolver2x4x2LoadImpulseResponse(jdsp->advXF.conv[1], (unsigned int)jdsp->blockSize, jdsp->blobsCh1[1], jdsp->blobsCh2[1], jdsp->blobsCh3[1], jdsp->blobsCh4[1], jdsp->blobsResampledLen);
		jdsp->advXF.conv[2] = (FFTConvolver2x4x2 *)malloc(sizeof(FFTConvolver2x4x2));
		FFTConvolver2x4x2Init(jdsp->advXF.conv[2]);
		FFTConvolver2x4x2LoadImpulseResponse(jdsp->advXF.conv[2], (unsigned int)jdsp->blockSize, jdsp->blobsCh1[2], jdsp->blobsCh2[2], jdsp->blobsCh3[2], jdsp->blobsCh4[2], jdsp->blobsResampledLen);
		unsigned int seg2Len = 0;
		if (selectConvPartitions(jdsp, jdsp->frameLenSVirResampled, &seg2Len) == 1)
		{
			jdsp->advXF.convLong_S_S = (FFTConvolver2x4x2 *)malloc(sizeof(FFTConvolver2x4x2));
			FFTConvolver2x4x2Init(jdsp->advXF.convLong_S_S);
			FFTConvolver2x4x2LoadImpulseResponse(jdsp->advXF.convLong_S_S, (unsigned int)jdsp->blockSize, jdsp->hrtfblobsResampled[0], jdsp->hrtfblobsResampled[1], jdsp->hrtfblobsResampled[2], jdsp->hrtfblobsResampled[3], jdsp->frameLenSVirResampled);
			jdsp->advXF.process = CrossfeedProcessFFTConvolver2x4x2;
		}
		else
		{
			//seg2Len = (jdsp->frameLenSVirResampled - jdsp->blockSize) / 4;
			jdsp->advXF.convLong_T_S = (TwoStageFFTConvolver2x4x2 *)malloc(sizeof(TwoStageFFTConvolver2x4x2));
			TwoStageFFTConvolver2x4x2Init(jdsp->advXF.convLong_T_S);
			TwoStageFFTConvolver2x4x2LoadImpulseResponse(jdsp->advXF.convLong_T_S, (unsigned int)jdsp->blockSize, seg2Len, jdsp->hrtfblobsResampled[0], jdsp->hrtfblobsResampled[1], jdsp->hrtfblobsResampled[2], jdsp->hrtfblobsResampled[3], jdsp->frameLenSVirResampled);
			jdsp->advXF.process = CrossfeedProcessTwoStageFFTConvolver2x4x2;
		}
		jdsp->crossfeedForceRefresh = 0;
	}
	if (jdsp->advXF.mode < 2)
	{
		memset(&jdsp->advXF.bs2b, 0, sizeof(jdsp->advXF.bs2b));
		BS2BInit(&jdsp->advXF.bs2b[0], (unsigned int)jdsp->fs, BS2B_DEFAULT_CLEVEL);
		BS2BInit(&jdsp->advXF.bs2b[1], (unsigned int)jdsp->fs, BS2B_JMEIER_CLEVEL);
	}
	if (enable)
		jdsp->crossfeedEnabled = 1;
    jdsp_unlock(jdsp);
}
void CrossfeedDisable(JamesDSPLib *jdsp)
{
	jdsp->crossfeedEnabled = 0;
}
void CrossfeedChangeMode(JamesDSPLib *jdsp, int nMode)
{
	if (nMode < 0)
		nMode = 0;
	if (nMode > 5)
		nMode = 5;
	/* Under the lock: CrossfeedProcess reads the mode and the BS2B state on
	   the audio thread. Not to be called with the lock held. */
	jdsp_lock(jdsp);
	if (nMode < 2)
	{
		memset(&jdsp->advXF.bs2b, 0, sizeof(jdsp->advXF.bs2b));
		if (!nMode)
			BS2BInit(&jdsp->advXF.bs2b[1], (unsigned int)jdsp->fs, BS2B_CMOY_CLEVEL);
		else
			BS2BInit(&jdsp->advXF.bs2b[1], (unsigned int)jdsp->fs, BS2B_JMEIER_CLEVEL);
	}
	jdsp->advXF.mode = nMode;
	jdsp_unlock(jdsp);
}
void CrossfeedProcess(JamesDSPLib *jdsp, size_t n)
{
	/* Read the mode once. It used to be read again for every sample, so a
	   change from BS2B to an HRTF mode in the middle of a block went on
	   indexing bs2b[] (two entries) with 2 to 5, writing filter state
	   over the DDC and convolver fields that follow it. */
	const int mode = jdsp->advXF.mode;
	if (mode < 2)
	{
		double tmpL, tmpR;
		for (size_t i = 0; i < n; i++)
		{
			tmpL = (double)jdsp->tmpBuffer[0][i];
			tmpR = (double)jdsp->tmpBuffer[1][i];
			BS2BProcess(&jdsp->advXF.bs2b[mode], &tmpL, &tmpR);
			jdsp->tmpBuffer[0][i] = (float)tmpL;
			jdsp->tmpBuffer[1][i] = (float)tmpR;
		}
	}
	else if (mode < 5)
		FFTConvolver2x4x2Process(jdsp->advXF.conv[mode - 2], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
	else
		jdsp->advXF.process(jdsp, (unsigned int)n);
}
