// Spectrum extension (ViPER-style high frequency exciter)
// Regenerates treble "air" by synthesizing harmonics from upper-mid content.
#include <math.h>
#include <string.h>
#include "../jdsp_header.h"

static void spxHighpass(float *c, float fs, float f0)
{
	float w0 = 2.0f * 3.14159265358979f * f0 / fs;
	float cw = cosf(w0);
	float sw = sinf(w0);
	float alpha = sw / (2.0f * 0.7071f);
	float a0 = 1.0f + alpha;
	c[0] = ((1.0f + cw) * 0.5f) / a0;
	c[1] = (-(1.0f + cw)) / a0;
	c[2] = c[0];
	c[3] = (-2.0f * cw) / a0;
	c[4] = (1.0f - alpha) / a0;
}

static float spxBqProc(const float *c, float *z, float x)
{
	float y = c[0] * x + c[1] * z[0] + c[2] * z[1] - c[3] * z[2] - c[4] * z[3];
	z[1] = z[0]; z[0] = x;
	z[3] = z[2]; z[2] = y;
	return y;
}

void SpectrumExtensionSetParam(JamesDSPLib *jdsp, float barkFreq, float strengthPct)
{
	SpectrumExtension *s = &jdsp->spectrumExt;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f)
		fs = 48000.0f;
	if (barkFreq < 3000.0f) barkFreq = 3000.0f;
	if (barkFreq > 16000.0f) barkFreq = 16000.0f;
	float preF = barkFreq * 0.5f;
	if (preF < 1000.0f) preF = 1000.0f;
	float postF = barkFreq;
	if (postF > fs * 0.45f) postF = fs * 0.45f;
	spxHighpass(s->pre, fs, preF);
	spxHighpass(s->post, fs, postF);
	s->strength = strengthPct * 0.01f;
}

void SpectrumExtensionProcess(JamesDSPLib *jdsp, size_t n)
{
	SpectrumExtension *s = &jdsp->spectrumExt;
	size_t i;
	int c;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		for (c = 0; c < 2; c++)
		{
			float x = jdsp->tmpBuffer[c][i];
			float h = spxBqProc(s->pre, s->prez[c], x);
			h = fabsf(h);
			h = h - s->dcState[c];
			s->dcState[c] += h * 0.001f;
			if (h > 2.0f) h = 2.0f;
			if (h < -2.0f) h = -2.0f;
			h = h * (2.0f - fabsf(h));
			float air = spxBqProc(s->post, s->postz[c], h);
			jdsp->tmpBuffer[c][i] = x + s->strength * air;
		}
	}
}

void SpectrumExtensionEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->spectrumExtEnabled)
	{
		memset(jdsp->spectrumExt.prez, 0, sizeof(jdsp->spectrumExt.prez));
		memset(jdsp->spectrumExt.postz, 0, sizeof(jdsp->spectrumExt.postz));
		jdsp->spectrumExt.dcState[0] = jdsp->spectrumExt.dcState[1] = 0.0f;
	}
	jdsp->spectrumExtEnabled = 1;
}

void SpectrumExtensionDisable(JamesDSPLib *jdsp)
{
	jdsp->spectrumExtEnabled = 0;
}
