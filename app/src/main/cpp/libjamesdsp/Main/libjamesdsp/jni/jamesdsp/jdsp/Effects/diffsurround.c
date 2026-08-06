// Differential surround: per-channel fractional delay (Haas widening),
// extracted from the ViperFX differential surround concept.
#include <math.h>
#include <string.h>
#include "../jdsp_header.h"

#define DSUR_BUFLEN 8192

void DiffSurroundSetParam(JamesDSPLib *jdsp, float delayLms, float delayRms)
{
	DiffSurround *ds = &jdsp->diffSurround;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f)
		fs = 48000.0f;
	float maxDelay = (float)(DSUR_BUFLEN - 4);
	float dl = delayLms * 0.001f * fs;
	float dr = delayRms * 0.001f * fs;
	if (dl < 0.0f) dl = 0.0f;
	if (dr < 0.0f) dr = 0.0f;
	if (dl > maxDelay) dl = maxDelay;
	if (dr > maxDelay) dr = maxDelay;
	ds->delayL = dl;
	ds->delayR = dr;
}

void DiffSurroundProcess(JamesDSPLib *jdsp, size_t n)
{
	DiffSurround *ds = &jdsp->diffSurround;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		int w = ds->widx;
		ds->bufL[w] = jdsp->tmpBuffer[0][i];
		ds->bufR[w] = jdsp->tmpBuffer[1][i];

		float rpL = (float)w - ds->delayL;
		float rpR = (float)w - ds->delayR;
		if (rpL < 0.0f) rpL += DSUR_BUFLEN;
		if (rpR < 0.0f) rpR += DSUR_BUFLEN;
		int iL = (int)rpL;
		int iR = (int)rpR;
		float fL = rpL - (float)iL;
		float fR = rpR - (float)iR;
		int iL1 = iL + 1; if (iL1 >= DSUR_BUFLEN) iL1 = 0;
		int iR1 = iR + 1; if (iR1 >= DSUR_BUFLEN) iR1 = 0;

		jdsp->tmpBuffer[0][i] = ds->bufL[iL] + fL * (ds->bufL[iL1] - ds->bufL[iL]);
		jdsp->tmpBuffer[1][i] = ds->bufR[iR] + fR * (ds->bufR[iR1] - ds->bufR[iR]);

		ds->widx = (w + 1) & (DSUR_BUFLEN - 1);
	}
}

void DiffSurroundEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->diffSurroundEnabled)
	{
		memset(jdsp->diffSurround.bufL, 0, sizeof(jdsp->diffSurround.bufL));
		memset(jdsp->diffSurround.bufR, 0, sizeof(jdsp->diffSurround.bufR));
		jdsp->diffSurround.widx = 0;
	}
	jdsp->diffSurroundEnabled = 1;
}

void DiffSurroundDisable(JamesDSPLib *jdsp)
{
	jdsp->diffSurroundEnabled = 0;
}
