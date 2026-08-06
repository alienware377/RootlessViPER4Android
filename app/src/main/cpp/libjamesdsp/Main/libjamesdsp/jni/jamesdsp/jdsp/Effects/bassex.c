// Psychoacoustic bass exciter (dual band)
#include <math.h>
#include <string.h>
#include "../jdsp_header.h"

static void bassExBiquad(float *c, float fs, float f0, float q, int isBandpass)
{
	float w0 = 2.0f * 3.14159265358979f * f0 / fs;
	float cw = cosf(w0);
	float sw = sinf(w0);
	float alpha = sw / (2.0f * q);
	float a0 = 1.0f + alpha;
	if (isBandpass)
	{
		c[0] = alpha / a0;
		c[1] = 0.0f;
		c[2] = -alpha / a0;
	}
	else
	{
		c[0] = ((1.0f - cw) * 0.5f) / a0;
		c[1] = (1.0f - cw) / a0;
		c[2] = c[0];
	}
	c[3] = (-2.0f * cw) / a0;
	c[4] = (1.0f - alpha) / a0;
}

static float bassExBqProc(const float *c, float *z, float x)
{
	float y = c[0] * x + c[1] * z[0] + c[2] * z[1] - c[3] * z[2] - c[4] * z[3];
	z[1] = z[0]; z[0] = x;
	z[3] = z[2]; z[2] = y;
	return y;
}

static float bassExClampF(float v, float lo, float hi)
{
	if (v < lo) return lo;
	if (v > hi) return hi;
	return v;
}

void BassExciterSetParam(JamesDSPLib *jdsp, float cutoff, float intensity, float mixPct)
{
	BassExciter *b = &jdsp->bassEx;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f)
		fs = 48000.0f;
	cutoff = bassExClampF(cutoff, 40.0f, 200.0f);
	bassExBiquad(b->lp, fs, cutoff, 0.7071f, 0);
	bassExBiquad(b->bp, fs, cutoff * 2.5f, 0.8f, 1);
	b->drive = 1.0f + intensity * 0.07f;
	b->mix = mixPct * 0.01f;
}

void BassExciterSetParam2(JamesDSPLib *jdsp, int band2On, float cutoff2, float intensity2, float mixPct2)
{
	BassExciter *b = &jdsp->bassEx;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f)
		fs = 48000.0f;
	cutoff2 = bassExClampF(cutoff2, 30.0f, 200.0f);
	bassExBiquad(b->lp2, fs, cutoff2, 0.7071f, 0);
	bassExBiquad(b->bp2, fs, cutoff2 * 2.5f, 0.8f, 1);
	b->drive2 = 1.0f + intensity2 * 0.07f;
	b->mix2 = mixPct2 * 0.01f;
	b->band2 = band2On;
}

void BassExciterProcess(JamesDSPLib *jdsp, size_t n)
{
	BassExciter *b = &jdsp->bassEx;
	size_t i;
	int c;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		for (c = 0; c < 2; c++)
		{
			float x = jdsp->tmpBuffer[c][i];
			float sub = bassExBqProc(b->lp, b->lpz[c], x);
			float h = fabsf(sub) * b->drive;
			h = h - b->dcState[c];
			b->dcState[c] += h * 0.0005f;
			h = h / (1.0f + fabsf(h));
			float harm = bassExBqProc(b->bp, b->bpz[c], h);
			float out = x + b->mix * 2.0f * harm;
			if (b->band2)
			{
				float sub2 = bassExBqProc(b->lp2, b->lp2z[c], x);
				float h2 = fabsf(sub2) * b->drive2;
				h2 = h2 - b->dc2State[c];
				b->dc2State[c] += h2 * 0.0005f;
				h2 = h2 / (1.0f + fabsf(h2));
				float harm2 = bassExBqProc(b->bp2, b->bp2z[c], h2);
				out += b->mix2 * 2.0f * harm2;
			}
			jdsp->tmpBuffer[c][i] = out;
		}
	}
}

void BassExciterEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->bassExEnabled)
	{
		memset(jdsp->bassEx.lpz, 0, sizeof(jdsp->bassEx.lpz));
		memset(jdsp->bassEx.bpz, 0, sizeof(jdsp->bassEx.bpz));
		memset(jdsp->bassEx.lp2z, 0, sizeof(jdsp->bassEx.lp2z));
		memset(jdsp->bassEx.bp2z, 0, sizeof(jdsp->bassEx.bp2z));
		jdsp->bassEx.dcState[0] = jdsp->bassEx.dcState[1] = 0.0f;
		jdsp->bassEx.dc2State[0] = jdsp->bassEx.dc2State[1] = 0.0f;
	}
	jdsp->bassExEnabled = 1;
}

void BassExciterDisable(JamesDSPLib *jdsp)
{
	jdsp->bassExEnabled = 0;
}
