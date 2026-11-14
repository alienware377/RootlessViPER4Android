/*
 * Smooth volume: a fine volume control that runs after everything else.
 *
 * Android gives media about fifteen volume steps, three or four dB apart on
 * most phones, which is too coarse for quiet listening on sensitive earphones.
 * The app holds the system volume still and moves this gain instead, in half
 * dB steps from 0 down to -100 dB.
 *
 * It sits after the output limiter, not before it. A gain ahead of the limiter
 * would change what the limiter does: turned down, material that used to be
 * limited would no longer reach the threshold, so the volume control would
 * also be a dynamics control. After it, the limiter sees exactly what it saw
 * with this off, and the gain only scales the result.
 *
 * The gain walks towards each new level a sample at a time. Volume keys arrive
 * as steps, up to eight dB apart while a key is held, and an unsmoothed step
 * on a held note is a click. Switching on starts the walk from unity, which is
 * what the signal had while this was off; switching off walks back to unity
 * and only then stops processing, so neither end is heard as a jump. At unity
 * with nothing left to walk the samples are not touched at all.
 */
#include <math.h>
#include "../jdsp_header.h"

/* Long enough that the largest key step is a fade rather than a click, short
   enough that a single press still sounds immediate. */
#define SMOOTH_VOLUME_MS 25.0f
/* The card's range. -100 dB is 1e-5, far below anything audible after the
   held system volume, and gives two hundred half dB steps from the top. */
#define SMOOTH_VOLUME_MIN_DB -100.0f
#define SMOOTH_VOLUME_MAX_DB 0.0f

static void smoothVolumeDesign(SmoothVolume *s, float fs)
{
	s->smooth = expf(-1.0f / ((SMOOTH_VOLUME_MS * 0.001f) * fs));
	s->fs = fs;
}

/* For a sample rate change; the caller holds the lock. */
void SmoothVolumeRefresh(JamesDSPLib *jdsp)
{
	smoothVolumeDesign(&jdsp->smoothVolume, jdsp->fs > 0.0f ? jdsp->fs : 48000.0f);
}

void SmoothVolumeSetParam(JamesDSPLib *jdsp, float gainDb)
{
	/* NaN keeps the level that was there. Any app can send this in root mode,
	   and neither silence nor full volume is a safe guess at what was meant.
	   The infinities are only out of range, and are clamped like any other
	   value past the ends. */
	if (gainDb != gainDb)
		return;
	if (gainDb < SMOOTH_VOLUME_MIN_DB) gainDb = SMOOTH_VOLUME_MIN_DB;
	if (gainDb > SMOOTH_VOLUME_MAX_DB) gainDb = SMOOTH_VOLUME_MAX_DB;
	jdsp_lock(jdsp);
	SmoothVolume *s = &jdsp->smoothVolume;
	s->targetDb = gainDb;
	/* Exactly unity at 0 dB, so the stage can step aside there. */
	const float g = gainDb >= 0.0f ? 1.0f : db2magf(gainDb);
	s->level = g;
	/* While switched off the level is only kept: the walk back to unity
	   carries on, and switching on picks the level up from here. */
	if (jdsp->smoothVolumeEnabled)
		s->target = g;
	jdsp_unlock(jdsp);
}

void SmoothVolumeProcess(JamesDSPLib *jdsp, size_t n)
{
	SmoothVolume *s = &jdsp->smoothVolume;
	const double t = s->target;
	double g = s->gain;
	if (g == t && t == 1.0)
	{
		/* Nothing to do: at unity, or back at unity after switching off. */
		if (!jdsp->smoothVolumeEnabled)
			s->engaged = 0;
		return;
	}
	float *left = jdsp->tmpBuffer[0];
	float *right = jdsp->tmpBuffer[1];
	const double c = s->smooth;
	if (g == t)
	{
		const float gf = s->target;
		for (size_t i = 0; i < n; i++)
		{
			left[i] *= gf;
			right[i] *= gf;
		}
		return;
	}
	for (size_t i = 0; i < n; i++)
	{
		g = t + c * (g - t);
		left[i] *= (float)g;
		right[i] *= (float)g;
	}
	/* The walk only approaches the level; once it is within a millionth of it
	   it is the level, and the cheaper loop above (or no loop at all, at
	   unity) takes over from the next block. */
	if (fabs(g - t) <= t * 1e-6)
		g = t;
	s->gain = g;
}

void SmoothVolumeEnable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	SmoothVolume *s = &jdsp->smoothVolume;
	if (!s->engaged)
	{
		/* Bypassed means unity, so the walk to the level starts there. */
		s->gain = 1.0f;
		s->engaged = 1;
	}
	s->target = s->level;
	jdsp->smoothVolumeEnabled = 1;
	jdsp_unlock(jdsp);
}

void SmoothVolumeDisable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	/* Walk back to unity first; Process steps aside once it gets there. */
	jdsp->smoothVolumeEnabled = 0;
	jdsp->smoothVolume.target = 1.0f;
	jdsp_unlock(jdsp);
}
