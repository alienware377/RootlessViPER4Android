// ViPER-style dynamic bass (port of the DynamicBass EEL script / ViperFX DBB)
#include <math.h>
#include <string.h>
#include "../jdsp_header.h"

static void vdbLowpassSet(VDynamicBass *d, float fs, float freq, float q)
{
	float x = (freq * 2.0f * 3.14159265358979f) / fs;
	float sinX = sinf(x);
	float y = sinX / (q * 2.0f);
	float cosX = cosf(x);
	float z = (1.0f - cosX) * 0.5f;
	float a0 = y + 1.0f;
	d->lpB0 = z / a0;
	d->lpB1 = (1.0f - cosX) / a0;
	d->lpB2 = z / a0;
	d->lpA1 = (cosX * -2.0f) / a0;
	d->lpA2 = (1.0f - y) / a0;
	d->lpX1 = d->lpX2 = d->lpY1 = d->lpY2 = 0.0f;
}

static float vdbLowpassProc(VDynamicBass *d, float s)
{
	float out = s * d->lpB0 + d->lpX1 * d->lpB1 + d->lpX2 * d->lpB2
	          - d->lpY1 * d->lpA1 - d->lpY2 * d->lpA2;
	d->lpY2 = d->lpY1; d->lpY1 = out;
	d->lpX2 = d->lpX1; d->lpX1 = s;
	return out;
}

static void vdbPolesSet(VPolesFilter *p, float fs, float lowerFreq, float upperFreq)
{
	memset(p, 0, sizeof(VPolesFilter));
	p->lowerAngle = lowerFreq * 3.14159265358979f / fs;
	p->upperAngle = upperFreq * 3.14159265358979f / fs;
}

static void vdbPolesProc(VPolesFilter *p, float s)
{
	float oldest = p->in2;
	p->in2 = p->in1;
	p->in1 = p->in0;
	p->in0 = s;

	p->x0 += p->lowerAngle * (s - p->x0);
	p->x1 += p->lowerAngle * (p->x0 - p->x1);
	p->x2 += p->lowerAngle * (p->x1 - p->x2);
	p->x3 += p->lowerAngle * (p->x2 - p->x3);

	p->y0 += p->upperAngle * (s - p->y0);
	p->y1 += p->upperAngle * (p->y0 - p->y1);
	p->y2 += p->upperAngle * (p->y1 - p->y2);
	p->y3 += p->upperAngle * (p->y2 - p->y3);

	p->out0 = p->x3;
	p->out1 = oldest - p->y3;
	p->out2 = p->y3 - p->x3;
}

static float vdbClamp(float v, float lo, float hi)
{
	if (!(v == v)) return lo;
	if (v < lo) return lo;
	if (v > hi) return hi;
	return v;
}

static void vdbDesign(VDynamicBass *d, float fs)
{
	if (fs < 8000.0f)
		fs = 48000.0f;
	d->lowFreqX = d->set[0];
	vdbPolesSet(&d->fXL, fs, d->set[0], d->set[1]);
	vdbPolesSet(&d->fXR, fs, d->set[0], d->set[1]);
	vdbPolesSet(&d->fYL, fs, d->set[2], d->set[3]);
	vdbPolesSet(&d->fYR, fs, d->set[2], d->set[3]);
	vdbLowpassSet(d, fs, 55.0f, d->qPeak / 666.0f + 0.5f);
}

/* Every value is clamped to the card's range, NaN included: in root mode any
   app can send them, and a pole angle past pi makes the filters run away. The
   design runs under the lock, since Process may be on the audio thread. */
void VDynBassSetParam(JamesDSPLib *jdsp, float gainPct, float x1, float x2, float y1, float y2, float sgxPct, float sgyPct)
{
	VDynamicBass *d = &jdsp->vdynBass;
	jdsp_lock(jdsp);
	d->bassGain = (vdbClamp(gainPct, 0.0f, 100.0f) * 20.0f + 100.0f) * 0.01f;
	d->qPeak = (d->bassGain - 1.0f) / 20.0f * 1600.0f;
	if (d->qPeak > 1600.0f)
		d->qPeak = 1600.0f;
	d->sideGainX = vdbClamp(sgxPct, 0.0f, 100.0f) * 0.01f;
	d->sideGainY = vdbClamp(sgyPct, 0.0f, 100.0f) * 0.01f;
	d->set[0] = vdbClamp(x1, 20.0f, 2000.0f);
	d->set[1] = vdbClamp(x2, 1000.0f, 8000.0f);
	d->set[2] = vdbClamp(y1, 20.0f, 200.0f);
	d->set[3] = vdbClamp(y2, 40.0f, 400.0f);
	vdbDesign(d, (float)jdsp->fs);
	jdsp_unlock(jdsp);
}

/* For a sample rate change; the caller holds the lock. */
void VDynBassRefresh(JamesDSPLib *jdsp)
{
	vdbDesign(&jdsp->vdynBass, (float)jdsp->fs);
}

void VDynBassProcess(JamesDSPLib *jdsp, size_t n)
{
	VDynamicBass *d = &jdsp->vdynBass;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	if (d->lowFreqX <= 120.0f)
	{
		for (i = 0; i < n; i++)
		{
			float l = jdsp->tmpBuffer[0][i];
			float r = jdsp->tmpBuffer[1][i];
			float avg = vdbLowpassProc(d, l + r);
			jdsp->tmpBuffer[0][i] = l + avg;
			jdsp->tmpBuffer[1][i] = r + avg;
		}
	}
	else
	{
		for (i = 0; i < n; i++)
		{
			float l = jdsp->tmpBuffer[0][i];
			float r = jdsp->tmpBuffer[1][i];
			vdbPolesProc(&d->fXL, l);
			vdbPolesProc(&d->fXR, r);
			vdbPolesProc(&d->fYL, d->bassGain * d->fXL.out0);
			vdbPolesProc(&d->fYR, d->bassGain * d->fXR.out0);
			jdsp->tmpBuffer[0][i] = d->fXL.out1 + d->fYL.out2 + d->sideGainX * d->fYL.out1 + d->sideGainY * d->fYL.out0 + d->fXL.out2;
			jdsp->tmpBuffer[1][i] = d->fXR.out1 + d->fYR.out2 + d->sideGainX * d->fYR.out1 + d->sideGainY * d->fYR.out0 + d->fXR.out2;
		}
	}
}

static void vdbPolesReset(VPolesFilter *p)
{
	p->in0 = p->in1 = p->in2 = 0.0f;
	p->x0 = p->x1 = p->x2 = p->x3 = 0.0f;
	p->y0 = p->y1 = p->y2 = p->y3 = 0.0f;
	p->out0 = p->out1 = p->out2 = 0.0f;
}

void VDynBassEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->vdynBassEnabled)
	{
		VDynamicBass *d = &jdsp->vdynBass;
		vdbPolesReset(&d->fXL);
		vdbPolesReset(&d->fXR);
		vdbPolesReset(&d->fYL);
		vdbPolesReset(&d->fYR);
		d->lpX1 = d->lpX2 = d->lpY1 = d->lpY2 = 0.0f;
	}
	jdsp->vdynBassEnabled = 1;
}

void VDynBassDisable(JamesDSPLib *jdsp)
{
	jdsp->vdynBassEnabled = 0;
}
