// Headphone Surround+ (lite): binaural crossfeed with delayed, lowpassed
// opposite-ear feed plus early room reflections, inspired by ViPER VHS.
#include <math.h>
#include <string.h>
#include "../jdsp_header.h"

#define HPS_BUFLEN 4096

void HpSurroundSetParam(JamesDSPLib *jdsp, float strengthPct, float roomPct)
{
	HpSurround *h = &jdsp->hpSurround;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f)
		fs = 48000.0f;
	h->cross = strengthPct * 0.007f;
	h->room = roomPct * 0.004f;
	h->dCross = (int)(0.00035f * fs);
	h->dR1 = (int)(0.011f * fs);
	h->dR2 = (int)(0.023f * fs);
	if (h->dR2 > HPS_BUFLEN - 4) h->dR2 = HPS_BUFLEN - 4;
	if (h->dR1 > HPS_BUFLEN - 4) h->dR1 = HPS_BUFLEN - 4;
	// one-pole lowpass ~750 Hz for the crossfeed path
	h->lpCoef = 1.0f - expf(-2.0f * 3.14159265f * 750.0f / fs);
}

void HpSurroundProcess(JamesDSPLib *jdsp, size_t n)
{
	HpSurround *h = &jdsp->hpSurround;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		int w = h->widx;
		float l = jdsp->tmpBuffer[0][i];
		float r = jdsp->tmpBuffer[1][i];
		h->bufL[w] = l;
		h->bufR[w] = r;

		int pc = w - h->dCross; if (pc < 0) pc += HPS_BUFLEN;
		int p1 = w - h->dR1; if (p1 < 0) p1 += HPS_BUFLEN;
		int p2 = w - h->dR2; if (p2 < 0) p2 += HPS_BUFLEN;

		// lowpassed delayed opposite ear
		h->lpzL += h->lpCoef * (h->bufR[pc] - h->lpzL);
		h->lpzR += h->lpCoef * (h->bufL[pc] - h->lpzR);

		float roomL = h->bufL[p1] * 0.6f + h->bufR[p2] * 0.4f;
		float roomR = h->bufR[p1] * 0.6f + h->bufL[p2] * 0.4f;

		jdsp->tmpBuffer[0][i] = l + h->cross * h->lpzL + h->room * roomL;
		jdsp->tmpBuffer[1][i] = r + h->cross * h->lpzR + h->room * roomR;

		h->widx = (w + 1) & (HPS_BUFLEN - 1);
	}
}

void HpSurroundEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->hpSurroundEnabled)
	{
		memset(jdsp->hpSurround.bufL, 0, sizeof(jdsp->hpSurround.bufL));
		memset(jdsp->hpSurround.bufR, 0, sizeof(jdsp->hpSurround.bufR));
		jdsp->hpSurround.lpzL = jdsp->hpSurround.lpzR = 0.0f;
		jdsp->hpSurround.widx = 0;
	}
	jdsp->hpSurroundEnabled = 1;
}

void HpSurroundDisable(JamesDSPLib *jdsp)
{
	jdsp->hpSurroundEnabled = 0;
}
