// ViPER FET compressor, Cure+ crossfeed, ViPER bass, Reverberation (freeverb
// style) and Speaker optimization — inspired ports for the V4A edition fork.
#include <math.h>
#include <string.h>
#include "../jdsp_header.h"

static void vx2Biquad(float *c, float fs, float f0, float q, float gainDb, int type)
{
	// type: 0 lowshelf, 1 peaking, 2 highpass
	float A = powf(10.0f, gainDb / 40.0f);
	float w0 = 2.0f * 3.14159265358979f * f0 / fs;
	float cw = cosf(w0), sw = sinf(w0);
	float alpha = sw / (2.0f * q);
	float a0, b0, b1, b2, a1, a2;
	if (type == 0)
	{
		float sq = 2.0f * sqrtf(A) * alpha;
		a0 = (A + 1.0f) + (A - 1.0f) * cw + sq;
		b0 = A * ((A + 1.0f) - (A - 1.0f) * cw + sq);
		b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cw);
		b2 = A * ((A + 1.0f) - (A - 1.0f) * cw - sq);
		a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cw);
		a2 = (A + 1.0f) + (A - 1.0f) * cw - sq;
	}
	else if (type == 1)
	{
		a0 = 1.0f + alpha / A;
		b0 = 1.0f + alpha * A;
		b1 = -2.0f * cw;
		b2 = 1.0f - alpha * A;
		a1 = -2.0f * cw;
		a2 = 1.0f - alpha / A;
	}
	else
	{
		a0 = 1.0f + alpha;
		b0 = (1.0f + cw) * 0.5f;
		b1 = -(1.0f + cw);
		b2 = b0;
		a1 = -2.0f * cw;
		a2 = 1.0f - alpha;
	}
	c[0] = b0 / a0; c[1] = b1 / a0; c[2] = b2 / a0;
	c[3] = a1 / a0; c[4] = a2 / a0;
}

static float vx2Bq(const float *c, float *z, float x)
{
	float y = c[0] * x + c[1] * z[0] + c[2] * z[1] - c[3] * z[2] - c[4] * z[3];
	z[1] = z[0]; z[0] = x;
	z[3] = z[2]; z[2] = y;
	return y;
}

// ---------------- FET compressor ----------------
void FetCompSetParam(JamesDSPLib *jdsp, float thresholdDb, float ratio, float attackMs, float releaseMs, float makeupDb)
{
	FetComp *f = &jdsp->fetComp;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	if (ratio < 1.0f) ratio = 1.0f;
	if (attackMs < 0.05f) attackMs = 0.05f;
	if (releaseMs < 5.0f) releaseMs = 5.0f;
	f->thrLin = powf(10.0f, thresholdDb / 20.0f);
	f->slope = 1.0f - 1.0f / ratio;
	f->attC = 1.0f - expf(-1.0f / (attackMs * 0.001f * fs));
	f->relC = 1.0f - expf(-1.0f / (releaseMs * 0.001f * fs));
	f->makeup = powf(10.0f, makeupDb / 20.0f);
}

void FetCompProcess(JamesDSPLib *jdsp, size_t n)
{
	FetComp *f = &jdsp->fetComp;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		float l = jdsp->tmpBuffer[0][i];
		float r = jdsp->tmpBuffer[1][i];
		float mag = fabsf(l);
		float mr = fabsf(r);
		if (mr > mag) mag = mr;
		if (mag > f->env)
			f->env += f->attC * (mag - f->env);
		else
			f->env += f->relC * (mag - f->env);
		float gain = 1.0f;
		if (f->env > f->thrLin && f->env > 1e-8f)
		{
			float overDb = 20.0f * log10f(f->env / f->thrLin);
			float grDb = overDb * f->slope;
			gain = powf(10.0f, -grDb / 20.0f);
		}
		gain *= f->makeup;
		jdsp->tmpBuffer[0][i] = l * gain;
		jdsp->tmpBuffer[1][i] = r * gain;
	}
}

void FetCompEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->fetCompEnabled)
		jdsp->fetComp.env = 0.0f;
	jdsp->fetCompEnabled = 1;
}
void FetCompDisable(JamesDSPLib *jdsp) { jdsp->fetCompEnabled = 0; }

// ---------------- Cure+ (auditory protection crossfeed) ----------------
void CureSetParam(JamesDSPLib *jdsp, int level)
{
	Cure *c = &jdsp->cure;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	float cutoff = 700.0f, g = 0.30f;
	if (level == 1) { cutoff = 700.0f; g = 0.40f; }
	else if (level == 2) { cutoff = 650.0f; g = 0.55f; }
	c->lpCoef = 1.0f - expf(-2.0f * 3.14159265f * cutoff / fs);
	c->feed = g;
	c->norm = 1.0f / (1.0f + g);
}

void CureProcess(JamesDSPLib *jdsp, size_t n)
{
	Cure *c = &jdsp->cure;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		float l = jdsp->tmpBuffer[0][i];
		float r = jdsp->tmpBuffer[1][i];
		c->lpzL += c->lpCoef * (r - c->lpzL);
		c->lpzR += c->lpCoef * (l - c->lpzR);
		jdsp->tmpBuffer[0][i] = (l + c->feed * c->lpzL) * c->norm;
		jdsp->tmpBuffer[1][i] = (r + c->feed * c->lpzR) * c->norm;
	}
}

void CureEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->cureEnabled)
		jdsp->cure.lpzL = jdsp->cure.lpzR = 0.0f;
	jdsp->cureEnabled = 1;
}
void CureDisable(JamesDSPLib *jdsp) { jdsp->cureEnabled = 0; }

// ---------------- ViPER bass ----------------
void ViperBassSetParam(JamesDSPLib *jdsp, int mode, float freq, float gainDb)
{
	ViperBass *v = &jdsp->viperBass;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	if (freq < 20.0f) freq = 20.0f;
	if (freq > 300.0f) freq = 300.0f;
	if (gainDb < 0.0f) gainDb = 0.0f;
	if (gainDb > 12.0f) gainDb = 12.0f;
	v->mode = mode;
	if (mode == 2)
		vx2Biquad(v->f, fs, freq, 1.2f, gainDb, 1);   // subwoofer: peaking
	else
		vx2Biquad(v->f, fs, freq, 0.707f, gainDb, 0); // shelf
	// pure bass+: gentle sub harmonics
	vx2Biquad(v->lp, fs, freq, 0.7071f, 0.0f, 2);     // reuse as HP? no: need LP
	// build lowpass manually for harmonic path
	{
		float w0 = 2.0f * 3.14159265358979f * freq / fs;
		float cw = cosf(w0), sw = sinf(w0);
		float alpha = sw / (2.0f * 0.7071f);
		float a0 = 1.0f + alpha;
		v->lp[0] = ((1.0f - cw) * 0.5f) / a0;
		v->lp[1] = (1.0f - cw) / a0;
		v->lp[2] = v->lp[0];
		v->lp[3] = (-2.0f * cw) / a0;
		v->lp[4] = (1.0f - alpha) / a0;
	}
	v->harm = (mode == 1) ? gainDb * 0.03f : 0.0f;
}

void ViperBassProcess(JamesDSPLib *jdsp, size_t n)
{
	ViperBass *v = &jdsp->viperBass;
	size_t i;
	int c;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		for (c = 0; c < 2; c++)
		{
			float x = jdsp->tmpBuffer[c][i];
			float y = vx2Bq(v->f, v->fz[c], x);
			if (v->harm > 0.0f)
			{
				float sub = vx2Bq(v->lp, v->lpz[c], x);
				float h = fabsf(sub);
				h -= v->dc[c];
				v->dc[c] += h * 0.0005f;
				y += v->harm * (h / (1.0f + fabsf(h)));
			}
			jdsp->tmpBuffer[c][i] = y;
		}
	}
}

void ViperBassEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->viperBassEnabled)
	{
		memset(jdsp->viperBass.fz, 0, sizeof(jdsp->viperBass.fz));
		memset(jdsp->viperBass.lpz, 0, sizeof(jdsp->viperBass.lpz));
		jdsp->viperBass.dc[0] = jdsp->viperBass.dc[1] = 0.0f;
	}
	jdsp->viperBassEnabled = 1;
}
void ViperBassDisable(JamesDSPLib *jdsp) { jdsp->viperBassEnabled = 0; }

// ---------------- Reverberation (freeverb style) ----------------
static const int vrCombBase[4] = { 1116, 1188, 1277, 1356 };
static const int vrApBase[2] = { 556, 441 };

void VReverbSetParam(JamesDSPLib *jdsp, float roomPct, float dampPct, float widthPct, float wetPct, float dryPct)
{
	VReverb *v = &jdsp->vreverb;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	float scale = fs / 44100.0f;
	int c, k;
	for (c = 0; c < 2; c++)
	{
		for (k = 0; k < 4; k++)
		{
			int len = (int)(vrCombBase[k] * scale) + (c == 1 ? 23 : 0);
			if (len > VREV_COMBLEN - 1) len = VREV_COMBLEN - 1;
			v->clen[c][k] = len;
		}
		for (k = 0; k < 2; k++)
		{
			int len = (int)(vrApBase[k] * scale) + (c == 1 ? 23 : 0);
			if (len > VREV_APLEN - 1) len = VREV_APLEN - 1;
			v->alen[c][k] = len;
		}
	}
	v->fb = 0.70f + 0.28f * roomPct * 0.01f;
	v->damp = 0.05f + 0.9f * dampPct * 0.01f;
	float wet = wetPct * 0.01f * 0.9f;
	float width = widthPct * 0.01f;
	v->wet1 = wet * (width * 0.5f + 0.5f);
	v->wet2 = wet * ((1.0f - width) * 0.5f);
	v->dry = dryPct * 0.01f;
}

static float vrCombProc(VReverb *v, int c, int k, float input)
{
	int idx = v->cidx[c][k];
	float out = v->comb[c][k][idx];
	v->cflt[c][k] = out * (1.0f - v->damp) + v->cflt[c][k] * v->damp;
	v->comb[c][k][idx] = input + v->cflt[c][k] * v->fb;
	if (++idx >= v->clen[c][k]) idx = 0;
	v->cidx[c][k] = idx;
	return out;
}

static float vrApProc(VReverb *v, int c, int k, float input)
{
	int idx = v->aidx[c][k];
	float bufout = v->ap[c][k][idx];
	float out = -input + bufout;
	v->ap[c][k][idx] = input + bufout * 0.5f;
	if (++idx >= v->alen[c][k]) idx = 0;
	v->aidx[c][k] = idx;
	return out;
}

void VReverbProcess(JamesDSPLib *jdsp, size_t n)
{
	VReverb *v = &jdsp->vreverb;
	size_t i;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		float l = jdsp->tmpBuffer[0][i];
		float r = jdsp->tmpBuffer[1][i];
		float input = (l + r) * 0.015f;
		float outL = 0.0f, outR = 0.0f;
		int k;
		for (k = 0; k < 4; k++)
		{
			outL += vrCombProc(v, 0, k, input);
			outR += vrCombProc(v, 1, k, input);
		}
		for (k = 0; k < 2; k++)
		{
			outL = vrApProc(v, 0, k, outL);
			outR = vrApProc(v, 1, k, outR);
		}
		jdsp->tmpBuffer[0][i] = outL * v->wet1 + outR * v->wet2 + l * v->dry;
		jdsp->tmpBuffer[1][i] = outR * v->wet1 + outL * v->wet2 + r * v->dry;
	}
}

void VReverbEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->vreverbEnabled)
	{
		memset(jdsp->vreverb.comb, 0, sizeof(jdsp->vreverb.comb));
		memset(jdsp->vreverb.ap, 0, sizeof(jdsp->vreverb.ap));
		memset(jdsp->vreverb.cidx, 0, sizeof(jdsp->vreverb.cidx));
		memset(jdsp->vreverb.aidx, 0, sizeof(jdsp->vreverb.aidx));
		memset(jdsp->vreverb.cflt, 0, sizeof(jdsp->vreverb.cflt));
	}
	jdsp->vreverbEnabled = 1;
}
void VReverbDisable(JamesDSPLib *jdsp) { jdsp->vreverbEnabled = 0; }

// ---------------- Speaker optimization ----------------
void SpeakerOptSetParam(JamesDSPLib *jdsp, float strengthPct)
{
	SpeakerOpt *s = &jdsp->speakerOpt;
	float fs = (float)jdsp->fs;
	if (fs < 8000.0f) fs = 48000.0f;
	float k = strengthPct * 0.01f;
	vx2Biquad(s->hp, fs, 150.0f, 0.7071f, 0.0f, 2);
	vx2Biquad(s->pk, fs, 2500.0f, 1.0f, 6.0f * k, 1);
	vx2Biquad(s->sh, fs, 8000.0f, 0.7071f, 3.0f * k, 0);
	// sh built as lowshelf at 8k boosts lows below 8k too; rebuild as peaking
	vx2Biquad(s->sh, fs, 8000.0f, 1.0f, 3.0f * k, 1);
	s->mix = k;
}

void SpeakerOptProcess(JamesDSPLib *jdsp, size_t n)
{
	SpeakerOpt *s = &jdsp->speakerOpt;
	size_t i;
	int c;
	if (!jdsp->tmpBuffer[0] || !jdsp->tmpBuffer[1])
		return;
	for (i = 0; i < n; i++)
	{
		for (c = 0; c < 2; c++)
		{
			float x = jdsp->tmpBuffer[c][i];
			float y = vx2Bq(s->hp, s->hpz[c], x);
			y = vx2Bq(s->pk, s->pkz[c], y);
			y = vx2Bq(s->sh, s->shz[c], y);
			jdsp->tmpBuffer[c][i] = x + s->mix * (y - x);
		}
	}
}

void SpeakerOptEnable(JamesDSPLib *jdsp)
{
	if (!jdsp->speakerOptEnabled)
	{
		memset(jdsp->speakerOpt.hpz, 0, sizeof(jdsp->speakerOpt.hpz));
		memset(jdsp->speakerOpt.pkz, 0, sizeof(jdsp->speakerOpt.pkz));
		memset(jdsp->speakerOpt.shz, 0, sizeof(jdsp->speakerOpt.shz));
	}
	jdsp->speakerOptEnabled = 1;
}
void SpeakerOptDisable(JamesDSPLib *jdsp) { jdsp->speakerOptEnabled = 0; }
