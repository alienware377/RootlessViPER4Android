/*
 * Sub-harmonic expander: new notes an octave and more below the bass, the way
 * a subwoofer that is not there would add them.
 *
 * Based on the Sub-Harmonic Expander by evoeram, from Loudness-PEQ-
 * RootlessJamesDSP (https://github.com/evoeram/Loundess-PEQ-RootlessJamesDSP,
 * engine in https://github.com/evoeram/JamesDSPManager, branch "extensions",
 * Effects/subHarmonicExpander.c), GNU GPL version 3 like this file. The idea,
 * the controls (one amount per divider from f/2 to f/10, the band the bass is
 * taken from, the mix) and the low-passed bass band as the thing to follow
 * come from there. The tracking and the synthesis are new.
 *
 * The original measured the pitch from the gaps between bare zero crossings of
 * the low-passed bass, smoothed that into a frequency and ran free sine
 * oscillators at fractions of it. That holds a plain sine well, but the
 * second harmonic a bass guitar is full of puts extra crossings in every
 * cycle, and the sub then wanders around the note. All the dividers also
 * advanced one shared phase, so two of them at once made one tone at the sum
 * of their frequencies, and each settings change reset the oscillators,
 * which clicked.
 *
 * This works like the octave dividers in hardware sub-harmonic synthesisers
 * instead. The bass's own crossings are counted, with hysteresis so that
 * ripple near zero is not counted, and divider N completes one cycle every N
 * cycles of the bass. That count, rather than a frequency estimate, says
 * where each sub-harmonic should be in its cycle, so it stays locked to the
 * note. Where the hardware switches a square wave and filters it, each
 * divider here drives a sine whose phase is pulled towards the count: it
 * advances at the rate the period measured over whole cycles predicts and
 * makes up any error gradually, never faster than half again or slower than
 * half its own speed. A crossing that comes early or late, or two notes
 * beating against each other, move it smoothly instead of making it jump,
 * and its step from one sample to the next stays within what a clean sine at
 * one and a half times its frequency would take. Its level follows the
 * band's (an RMS follower, so the ripple a peak detector has on low notes is
 * not written into the sub), which is also what lets it die away with the
 * note.
 *
 * Everything works on the mid of the two channels and the same sub is added to
 * both: there is no direction to bass this low, and two trackers could drift
 * apart and cancel. A sub-harmonic that would land below about 20 Hz fades out,
 * since all it would do there is take headroom from the limiter. Nothing is
 * allocated, so switching on and off costs nothing; switching off fades out
 * over 20 ms first, so a sub that is sounding does not stop with a thump.
 */
#include <math.h>
#include <string.h>
#include "../jdsp_header.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#define SUBH_PI 3.14159265358979f
#define SUBH_TWO_PI 6.28318530717959f
/* Beyond +60 dBFS or not a number: taken as silence by the analysis. The
   signal itself goes on as it came. */
#define SUBH_INPUT_LIMIT 1000.0f
/* A crossing counts once the band is this far past zero, as a fraction of its
   own level. Enough to ignore ripple and noise riding on the note, little
   enough that a quiet note is still followed. */
#define SUBH_HYSTERESIS 0.25f
/* Below -80 dBFS the band counts as silent and nothing is followed. */
#define SUBH_GATE 1e-4f
/* Notes are followed from here up to one and a half times the band edge. */
#define SUBH_LOWEST_HZ 25.0f
#define SUBH_HIGHEST_HZ 450.0f
/* Sub-harmonics fade out between these two. */
#define SUBH_FADE_LO_HZ 16.0f
#define SUBH_FADE_HI_HZ 24.0f
/* How far each measured half period moves the smoothed one. */
#define SUBH_TRACK 0.35f
/* Below this, filter and follower state is flushed to zero. */
#define SUBH_TINY 1e-20f

/* Direct form I on purpose. The band and output filters are redesigned while
   they run whenever the band is moved, and a transposed form keeps a mix of
   the old and new coefficients in its state: with poles this close to 1 that
   steps the output by about the coefficient change times the signal, an
   audible click on a sub at full level. Here the state is only past samples,
   so the new design simply carries on from them. */
static inline float subhRun(SubHarmonicStage *s, float x)
{
	const float y = s->b0 * x + s->b1 * s->x1 + s->b2 * s->x2 - s->a1 * s->y1 - s->a2 * s->y2;
	s->x2 = s->x1;
	s->x1 = x;
	s->y2 = s->y1;
	s->y1 = y;
	return y;
}

/* Second-order Butterworth sections. Two of the lowpass in a row make the
   fourth-order Linkwitz-Riley slope the band is taken with. */
static void subhDesignLowpass(SubHarmonicStage *s, double f, double fs)
{
	const double w0 = 2.0 * M_PI * f / fs;
	const double cw = cos(w0);
	const double alpha = sin(w0) / (2.0 * 0.70710678118654752);
	const double a0 = 1.0 + alpha;
	s->b0 = (float)((1.0 - cw) * 0.5 / a0);
	s->b1 = (float)((1.0 - cw) / a0);
	s->b2 = s->b0;
	s->a1 = (float)(-2.0 * cw / a0);
	s->a2 = (float)((1.0 - alpha) / a0);
}

static void subhDesignHighpass(SubHarmonicStage *s, double f, double fs)
{
	const double w0 = 2.0 * M_PI * f / fs;
	const double cw = cos(w0);
	const double alpha = sin(w0) / (2.0 * 0.70710678118654752);
	const double a0 = 1.0 + alpha;
	s->b0 = (float)((1.0 + cw) * 0.5 / a0);
	s->b1 = (float)(-(1.0 + cw) / a0);
	s->b2 = s->b0;
	s->a1 = (float)(-2.0 * cw / a0);
	s->a2 = (float)((1.0 - alpha) / a0);
}

/* How much of each divider the note being followed allows: none of a
   sub-harmonic below 16 Hz, all of one above 24 Hz. */
static void subhUpdateWeights(SubHarmonic *s)
{
	for (int k = 0; k < SUBH_DIVIDERS; k++)
	{
		float w = 0.0f;
		if (s->half > 0.0f)
		{
			const float f = s->fs / (2.0f * s->half * (float)(k + 2));
			w = (f - SUBH_FADE_LO_HZ) * (1.0f / (SUBH_FADE_HI_HZ - SUBH_FADE_LO_HZ));
			if (w < 0.0f) w = 0.0f;
			if (w > 1.0f) w = 1.0f;
		}
		s->lowWeight[k] = w;
	}
}

/* Everything that depends on the rate, from the settings already held, so a
   rate change can redo it without the caller replaying SetParam. The tracker
   keeps its periods in samples, so they are rescaled to the new rate and the
   note being followed stays the same note. */
static void subhSetRate(SubHarmonic *s, float fs)
{
	if (s->fs > 0.0f && fs != s->fs)
	{
		const float r = fs / s->fs;
		s->half *= r;
		s->lastGap *= r;
		s->prevGap *= r;
		s->since *= r;
		s->sinceGood *= r;
	}
	s->fs = fs;
	subhDesignLowpass(&s->band1, s->bandHz, fs);
	subhDesignLowpass(&s->band2, s->bandHz, fs);
	/* Rumble and DC kept out of what is counted. */
	subhDesignHighpass(&s->track, 20.0, fs);
	/* The sub itself: nothing below 12 Hz, nothing above the band. */
	subhDesignHighpass(&s->outHp, 12.0, fs);
	subhDesignLowpass(&s->outLp, s->bandHz, fs);
	/* Two 8 ms poles on the power: a 40 Hz note ripples the level by under 3 %. */
	s->envC = (float)(1.0 - exp(-1.0 / (0.008 * fs)));
	s->confC = (float)(1.0 - exp(-1.0 / (0.015 * fs)));
	s->smoothC = (float)(1.0 - exp(-1.0 / (0.010 * fs)));
	s->fadeStep = 1.0f / (0.020f * fs);
	float top = 1.5f * s->bandHz;
	if (top > SUBH_HIGHEST_HZ) top = SUBH_HIGHEST_HZ;
	s->minHalf = fs / (2.0f * top);
	s->maxHalf = fs / (2.0f * SUBH_LOWEST_HZ);
	subhUpdateWeights(s);
}

static void subhClear(SubHarmonic *s)
{
	SubHarmonicStage *all[5] = { &s->band1, &s->band2, &s->track, &s->outHp, &s->outLp };
	for (int i = 0; i < 5; i++)
		all[i]->x1 = all[i]->x2 = all[i]->y1 = all[i]->y2 = 0.0f;
	s->env1 = s->env2 = s->conf = 0.0f;
	s->since = s->sinceGood = s->half = s->lastGap = s->prevGap = 0.0f;
	s->polarity = 0;
	for (int k = 0; k < SUBH_DIVIDERS; k++)
	{
		s->count[k] = 0;
		s->phase[k] = s->refOff[k] = 0.0f;
		s->gain[k] = 0.0f;
	}
	s->mixCur = s->mix;
	s->outCur = 1.0f;
	subhUpdateWeights(s);
}

/* Nothing to add and nothing to trim, so the input can go through untouched. */
static int subhIdle(const SubHarmonic *s)
{
	if (s->outGain != 1.0f || s->outCur != 1.0f)
		return 0;
	if (s->mix == 0.0f && s->mixCur == 0.0f)
		return 1;
	for (int k = 0; k < SUBH_DIVIDERS; k++)
		if (s->amount[k] != 0.0f || s->gain[k] != 0.0f)
			return 0;
	return 1;
}

/* Near 1 a float step smaller than half its spacing rounds to nothing, which
   at these rates leaves a walk stuck about 1.4e-5 short: the output level
   would never get back to exactly 1 and the bit exact idle never return. A
   walk that has stopped moving is taken as there. */
static inline float subhWalk(float cur, float target, float c)
{
	const float next = cur + c * (target - cur);
	return (fabsf(target - next) < 1e-6f || next == cur) ? target : next;
}

static inline float subhFlush(float x)
{
	return fabsf(x) < SUBH_TINY ? 0.0f : x;
}

/* No lock: the only caller is JamesDSPSetSampleRate, which already holds it,
   and jdsp_lock is not recursive. */
void SubHarmonicRefresh(JamesDSPLib *jdsp)
{
	subhSetRate(&jdsp->subHarmonic, jdsp->fs > 0.0f ? jdsp->fs : 48000.0f);
}

void SubHarmonicSetParam(JamesDSPLib *jdsp, const float *amountPct,
                         float bandHz, float mixPct, float outputDb)
{
	/* Held for the whole update: the audio thread runs Process under this
	   lock, and a band filter half redesigned is a burst, not a glitch. */
	jdsp_lock(jdsp);
	SubHarmonic *s = &jdsp->subHarmonic;
	/* The card's ranges, NaN included: any app can send these in root mode. */
	for (int k = 0; k < SUBH_DIVIDERS; k++)
		s->amount[k] = amountPct ? JDSP_HOLD(amountPct[k] * 0.01f, 0.0f, 1.0f) : 0.0f;
	s->bandHz = JDSP_HOLD(bandHz, 40.0f, 300.0f);
	s->mix = JDSP_HOLD(mixPct * 0.01f, 0.0f, 1.0f);
	const float db = JDSP_HOLD(outputDb, -12.0f, 6.0f);
	s->outGain = (float)pow(10.0, (double)db / 20.0);
	subhSetRate(s, jdsp->fs > 0.0f ? jdsp->fs : 48000.0f);
	jdsp_unlock(jdsp);
}

void SubHarmonicProcess(JamesDSPLib *jdsp, size_t n)
{
	SubHarmonic *s = &jdsp->subHarmonic;
	if (subhIdle(s))
	{
		s->idle = 1;
		s->fade = 1.0f;
		if (s->fadingOut)
		{
			s->fadingOut = 0;
			jdsp->subHarmonicEnabled = 0;
		}
		return;
	}
	/* Coming back from idle: whatever the filters held then is long stale. */
	if (s->idle)
	{
		subhClear(s);
		s->idle = 0;
	}

	float *left = jdsp->tmpBuffer[0];
	float *right = jdsp->tmpBuffer[1];
	const float fadeStep = s->fadingOut ? -s->fadeStep : s->fadeStep;
	const float twoMaxHalf = 2.0f * s->maxHalf;

	for (size_t i = 0; i < n; i++)
	{
		const float xL = left[i], xR = right[i];
		float mid = 0.5f * (xL + xR);
		/* Written so that NaN fails the test too. */
		if (!(fabsf(mid) <= SUBH_INPUT_LIMIT))
			mid = 0.0f;
		const float b = subhRun(&s->track, subhRun(&s->band2, subhRun(&s->band1, mid)));

		s->env1 += s->envC * (b * b - s->env1);
		s->env2 += s->envC * (s->env1 - s->env2);
		const float amp = sqrtf(2.0f * s->env2);

		if (s->since < 1e6f) s->since += 1.0f;
		if (s->sinceGood < 1e6f) s->sinceGood += 1.0f;
		int newNote = 0;
		if (amp > SUBH_GATE)
		{
			const float h = SUBH_HYSTERESIS * amp;
			const int was = s->polarity;
			int crossed = 0;
			if (was <= 0 && b > h) { s->polarity = 1; crossed = 1; }
			else if (was >= 0 && b < -h) { s->polarity = -1; crossed = 1; }
			if (crossed)
			{
				/* Only gaps that could be half a bass note's cycle move the
				   period; every crossing is counted, though, since skipping
				   one would put the dividers half a cycle out. The period is
				   taken over the last two gaps, a whole cycle, because a
				   bass note with a strong second harmonic spends longer on
				   one side of zero than the other. A cycle far from the
				   period that the one before agrees with is a new note, and
				   the period goes straight to it rather than gliding over a
				   few cycles; anything else is smoothed in. Either way, a
				   period that moved by more than a tenth starts the dividers'
				   phase relation afresh (see below). */
				const float gap = s->since;
				if (was != 0 && gap >= s->minHalf && gap <= s->maxHalf)
				{
					if (s->lastGap > 0.0f)
					{
						const float cyc = 0.5f * (gap + s->lastGap);
						const float before = 0.5f * (s->lastGap + s->prevGap);
						const float old = s->half;
						if (old <= 0.0f)
							s->half = cyc;
						else if (fabsf(cyc - old) > 0.2f * old && s->prevGap > 0.0f &&
								 fabsf(cyc - before) < 0.1f * cyc)
							s->half = cyc;
						else
							s->half += SUBH_TRACK * (cyc - old);
						newNote = old <= 0.0f || fabsf(s->half - old) > 0.1f * old;
						s->sinceGood = 0.0f;
						subhUpdateWeights(s);
					}
					s->prevGap = s->lastGap;
					s->lastGap = gap;
				}
				else
					s->lastGap = s->prevGap = 0.0f;
				s->since = 0.0f;
				for (int k = 0; k < SUBH_DIVIDERS; k++)
					if (++s->count[k] >= 2 * (k + 2))
						s->count[k] = 0;
			}
		}
		/* Sure of a note only while plausible crossings keep coming. A held
		   DC offset or a gap in the bass lets the sub die away. */
		const float sure = (s->half > 0.0f && amp > SUBH_GATE && s->sinceGood < twoMaxHalf) ? 1.0f : 0.0f;
		s->conf = subhWalk(s->conf, sure, s->confC);

		float sub = 0.0f;
		const float half = s->half;
		float frac = half > 0.0f ? s->since / half : 0.0f;
		if (frac > 1.0f) frac = 1.0f;
		for (int k = 0; k < SUBH_DIVIDERS; k++)
		{
			const float g = subhWalk(s->gain[k], s->amount[k] * s->lowWeight[k], s->smoothC);
			s->gain[k] = g;
			if (half <= 0.0f)
				continue;
			/* Where the count says divider N should be: N bass cycles, 2N
			   counted crossings, per cycle of its own. On a new note it
			   carries on from where it is: nothing ties a sub's phase to the
			   note's, only its speed, so rather than chase the old relation
			   at the new speed, which takes cycles off pitch, the relation
			   starts again here. */
			const float N = (float)(k + 2);
			const float w = SUBH_PI / (N * half);
			const float counted = ((float)s->count[k] + frac) * (SUBH_PI / N);
			if (newNote)
			{
				float off = s->phase[k] - counted;
				if (off < 0.0f) off += SUBH_TWO_PI;
				s->refOff[k] = off;
			}
			float target = counted + s->refOff[k];
			if (target >= SUBH_TWO_PI) target -= SUBH_TWO_PI;
			float err = target - s->phase[k];
			if (err > SUBH_PI) err -= SUBH_TWO_PI;
			else if (err < -SUBH_PI) err += SUBH_TWO_PI;
			float inc = w * (1.0f + err * (1.0f / SUBH_PI));
			if (inc < 0.5f * w) inc = 0.5f * w;
			else if (inc > 1.5f * w) inc = 1.5f * w;
			float p = s->phase[k] + inc;
			if (p >= SUBH_TWO_PI) p -= SUBH_TWO_PI;
			s->phase[k] = p;
			if (g != 0.0f)
				sub += g * sinf(p);
		}
		sub *= amp * s->conf;
		sub = subhRun(&s->outLp, subhRun(&s->outHp, sub));

		s->mixCur = subhWalk(s->mixCur, s->mix, s->smoothC);
		s->outCur = subhWalk(s->outCur, s->outGain, s->smoothC);
		float f = s->fade + fadeStep;
		if (f < 0.0f) f = 0.0f;
		if (f > 1.0f) f = 1.0f;
		s->fade = f;
		const float add = sub * s->mixCur * f;
		const float gain = 1.0f + (s->outCur - 1.0f) * f;
		left[i] = (xL + add) * gain;
		right[i] = (xR + add) * gain;
	}

	/* Once per block: a silence would otherwise leave every one of these
	   decaying through the denormal range. */
	SubHarmonicStage *all[5] = { &s->band1, &s->band2, &s->track, &s->outHp, &s->outLp };
	for (int i = 0; i < 5; i++)
	{
		all[i]->x1 = subhFlush(all[i]->x1);
		all[i]->x2 = subhFlush(all[i]->x2);
		all[i]->y1 = subhFlush(all[i]->y1);
		all[i]->y2 = subhFlush(all[i]->y2);
	}
	s->env1 = subhFlush(s->env1);
	s->env2 = subhFlush(s->env2);

	if (s->fadingOut && s->fade <= 0.0f)
	{
		s->fadingOut = 0;
		jdsp->subHarmonicEnabled = 0;
	}
}

void SubHarmonicEnable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	SubHarmonic *s = &jdsp->subHarmonic;
	if (jdsp->subHarmonicEnabled)
	{
		/* On, or still fading out: turn round where it is. */
		s->fadingOut = 0;
		jdsp_unlock(jdsp);
		return;
	}
	subhSetRate(s, jdsp->fs > 0.0f ? jdsp->fs : 48000.0f);
	subhClear(s);
	s->idle = 0;
	s->fade = 0.0f;
	s->fadingOut = 0;
	jdsp->subHarmonicEnabled = 1;
	jdsp_unlock(jdsp);
}

void SubHarmonicDisable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	/* Process fades out and clears the flag itself. With nothing allocated
	   there is nothing else to let go. */
	if (jdsp->subHarmonicEnabled)
		jdsp->subHarmonic.fadingOut = 1;
	jdsp_unlock(jdsp);
}
