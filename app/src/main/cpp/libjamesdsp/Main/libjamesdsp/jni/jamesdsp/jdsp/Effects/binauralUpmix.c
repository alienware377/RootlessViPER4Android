/*
 * Binaural upmix: spreads a stereo mix over nine fixed virtual speakers around
 * the listener and renders them for headphones through a head related impulse
 * response (HRIR) set, with a small room behind it.
 *
 * Method, all of it from published work:
 *
 *   - Short-time Fourier analysis and overlap-add (Allen and Rabiner 1977;
 *     J. O. Smith, "Spectral Audio Signal Processing", OLA STFT processing).
 *     A periodic Hann window of Nw samples at a hop of Nw / 2 sums to exactly
 *     1, so there is no synthesis window. Each frame is zero padded to Nf =
 *     2 Nw, which leaves room for the speaker filters (HRIR plus decorrelation
 *     delay) without circular wrap, which would be heard as pre-echo.
 *
 *   - Upmix in the frequency domain after Avendano and Jot ("Ambience
 *     extraction and synthesis from stereo signals for multi-channel audio
 *     up-mix", ICASSP 2002; "A frequency-domain approach to multichannel
 *     upmix", JAES 52(7/8), 2004). Per bin, the inter-channel coherence of
 *     smoothed auto and cross spectra says how much is ambience, and their
 *     panning index says where the rest sits. Ambience is modelled with equal
 *     power in both channels, so a bin with sound on one side only is a panned
 *     source, not ambience. The masks are real and complementary, so the parts
 *     always add back to the input, and a mono centre goes wholly to the
 *     centre speaker. Nothing here estimates a direction per bin: every bin is
 *     rendered through the same fixed speakers.
 *
 *   - Width is constant power panning between the 30 and 60 degree speakers
 *     (the two dimensional case of VBAP, Pulkki, JAES 45(6), 1997).
 *
 *   - Ambience decorrelation by short delays per speaker, inside the precedence
 *     window (Kendall, Computer Music Journal 19(4), 1995; Faller, IEEE TASLP
 *     14(1), 2006), baked into each speaker's filter so it costs nothing.
 *
 *   - The room is a feedback delay network (Jot and Chaigne, AES 90th
 *     Convention, 1991; Rocchesso and Smith, IEEE TSAP 5(1), 1997): eight
 *     lines, a Hadamard matrix, a low pass in each loop. Its two outputs reach
 *     the ears mostly alike in the bass and nearly independent above, as in a
 *     diffuse field (Jacobsen and Roisin, JASA 108(1), 2000).
 *
 * The only parts reused from the engine are its real FHT codelets; the tables
 * they need come from stftCommon.h, shared with the spectral shaper.
 *
 * Buffering is sample by sample, as in the shaper: every input sample is pushed
 * into a ring, a frame runs every R pushes, and one output sample is popped per
 * input sample. The delay is exactly Nw - 1 plus the onset of the set's centre
 * pair, for any block size, and the dry signal is read from the same ring at
 * that delay so Amount 0 is the input, delayed, bit for bit.
 */
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "../jdsp_header.h"
#include "eel2/numericSys/codelet.h"
#include "stftCommon.h"

/* Coherence above HI is a source, below LO is ambience. Starting values from
   the design notes, calibrated in the harness: averaging over a few frames
   biases the coherence of uncorrelated noise upwards. */
#ifndef BIN_PHI_HI
#define BIN_PHI_HI 0.90f
#endif
#ifndef BIN_PHI_LO
#define BIN_PHI_LO 0.50f
#endif
/* A bin whose quieter channel is this far down (power ratio) is a panned
   source; between the two it blends. 30 and 20 dB. */
#define BIN_BAL_LO 0.001f
#define BIN_BAL_HI 0.01f
/* Spectrum smoothing over frames. */
#define BIN_COV_MS 25.0f
/* Switch on and off, and the per-sample smoothing of Amount and Room. */
#define BIN_FADE_MS 10.0f
#define BIN_SMOOTH_MS 10.0f
/* An input sample beyond this (+60 dBFS), or not a number at all, is a fault
   upstream rather than audio. It is taken as silence and everything held is
   started again: a burst at +120 dB would otherwise take the room and the
   smoothed spectra most of a second to forget. */
#define BIN_INPUT_LIMIT 1000.0f
/* Samples of the zero padding kept free for the impulse responses of the
   masks, per 1024 of window. */
#define BIN_MARGIN 111
/* Room: decay time, damping, and the level at Room 100 %. */
#define BIN_RT60 0.3f
#define BIN_FDN_LP_HZ 5000.0f
#define BIN_XOVER_HZ 700.0f
#ifndef BIN_ROOM_LEVEL
#define BIN_ROOM_LEVEL 0.7f
#endif

#define BIN_INV_SQRT2 0.70710678118654752f

/* Decorrelation delay per speaker in ms: none for the speakers that carry the
   direct sound, distinct non-integer ratios for the ambience-only ones. */
static const float binDelayMs[BIN_SPEAKERS] = {
	0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 10.0f, 13.7f, 6.3f, 8.9f
};
/* Delay line lengths of the room, spread over 11 to 47 ms. */
static const float binFdnMs[BINAURAL_FDN_LINES] = {
	11.3f, 14.9f, 18.7f, 22.1f, 27.4f, 31.9f, 39.3f, 46.7f
};

static float buClamp(float v, float lo, float hi, float fallback)
{
	if (!(v == v)) return fallback; /* NaN */
	if (v < lo) return lo;
	if (v > hi) return hi;
	return v;
}

static int buWindow(float fs)
{
	if (fs < 64000.0f) return 1024;
	if (fs < 128000.0f) return 2048;
	return 4096;
}

static int buFftSize(int Nw)
{
#ifdef BROKEN_NOPAD
	return Nw;
#else
	return 2 * Nw;
#endif
}

static void (*buFftFor(int N))(float *, const float *)
{
	switch (N)
	{
	case 1024: return DFT1024;
	case 2048: return DFT2048;
	case 4096: return DFT4096;
	default: return DFT8192;
	}
}

static int buIsPrime(int n)
{
	if (n < 2) return 0;
	for (int d = 2; d * d <= n; d++)
		if (n % d == 0) return 0;
	return 1;
}

/* Floats needed at one rate. One block, so a rate change is one allocation. */
static size_t buBytes(int Nw, int Nf, int fdnTotal)
{
	const size_t K = (size_t)(Nf / 2 + 1);
	size_t floats = 7 * (size_t)Nf + (size_t)Nw + 7 * K + (size_t)fdnTotal;
	return floats * sizeof(float) + (size_t)Nf * sizeof(unsigned) + 64;
}

static void buClearState(BinauralUpmix *b)
{
	const int Nf = b->Nf, K = b->K;
	memset(b->in[0], 0, sizeof(float) * Nf);
	memset(b->in[1], 0, sizeof(float) * Nf);
	memset(b->acc[0], 0, sizeof(float) * Nf);
	memset(b->acc[1], 0, sizeof(float) * Nf);
	memset(b->pll, 0, sizeof(float) * K);
	memset(b->prr, 0, sizeof(float) * K);
	memset(b->plrRe, 0, sizeof(float) * K);
	memset(b->plrIm, 0, sizeof(float) * K);
	int total = 0;
	for (int i = 0; i < BINAURAL_FDN_LINES; i++)
	{
		total += b->fdnLen[i];
		b->fdnPos[i] = 0;
		b->fdnLp[i] = 0.0f;
	}
	memset(b->fdnBuf, 0, sizeof(float) * total);
	b->xo[0] = b->xo[1] = b->xo[2] = b->xo[3] = 0.0f;
	b->pos = 0;
	b->hop = 0;
	b->dryXfade = 0;
}

/* Settings that follow the controls or the rate. Never touches audio state,
   so moving a control mid-song cannot click: Amount and Room are smoothed per
   sample, and overlap-add crossfades every frame the mask gains change. */
static void buDerive(BinauralUpmix *b)
{
	b->width = b->widthPct * 0.01f;
	float as = b->surroundPct * 0.01f * 0.85f;
	float ah = b->heightPct * 0.01f * 0.6f;
	const float s2 = as * as + ah * ah;
	if (s2 > 1.0f)
	{
		const float k = 1.0f / sqrtf(s2);
		as *= k;
		ah *= k;
	}
	const float f2 = 1.0f - as * as - ah * ah;
	b->ambFront = f2 > 0.0f ? sqrtf(f2) : 0.0f;
	b->ambSurround = as;
	b->ambHeight = ah;
	b->amountTarget = b->amountPct * 0.01f;
	b->roomTarget = b->roomPct * 0.01f * BIN_ROOM_LEVEL;
	const float fs = b->fs > 0.0f ? b->fs : 48000.0f;
	if (b->R > 0)
		b->covC = 1.0f - expf(-(float)b->R / (BIN_COV_MS * 0.001f * fs));
	b->smoothC = 1.0f - expf(-1.0f / (BIN_SMOOTH_MS * 0.001f * fs));
	b->xoverC = 1.0f - expf(-2.0f * (float)M_PI * BIN_XOVER_HZ / fs);
	b->fdnLpC = expf(-2.0f * (float)M_PI * BIN_FDN_LP_HZ / fs);
	b->fadeLen = (int)(BIN_FADE_MS * 0.001f * fs);
	if (b->fadeLen < 1) b->fadeLen = 1;
	for (int i = 0; i < BINAURAL_FDN_LINES; i++)
	{
		if (!b->fdnLen[i]) continue;
#ifdef BROKEN_FDNGAIN
		b->fdnG[i] = 1.0f;
#else
		/* -60 dB after RT60 seconds of round trips. */
		b->fdnG[i] = powf(10.0f, -3.0f * (float)b->fdnLen[i] / (fs * BIN_RT60));
#endif
	}
}

/* Builds the filter spectra for a frame size from a set at the engine rate:
   each speaker's pair, its decorrelation delay in front, zero padded to Nf.
   Self-contained (own tables), so it can run outside the lock. */
static float *buBuildSpec(int Nw, int Nf, float fs, const float *h, int taps, int cOff, int *cOffOut)
{
	const int K = Nf / 2 + 1;
	float *spec = (float *)calloc((size_t)BIN_SPEAKERS * 2 * K * 2, sizeof(float));
	float *work = (float *)calloc((size_t)Nf * 2 + 16, sizeof(float));
	unsigned *br = (unsigned *)calloc((size_t)Nf, sizeof(unsigned));
	if (!spec || !work || !br)
	{
		free(spec); free(work); free(br);
		return NULL;
	}
	float *sinTab = work, *buf = work + Nf;
	stftBitRevTable(br, Nf);
	stftSinTable(sinTab, Nf);
	void (*fft)(float *, const float *) = buFftFor(Nf);

	/* At most a quarter window of taps: 256 at 44.1 and 48 kHz. */
	int used = taps < Nw / 4 ? taps : Nw / 4;
	int d[BIN_SPEAKERS], maxD = 0;
	for (int s = 0; s < BIN_SPEAKERS; s++)
	{
		d[s] = (int)(binDelayMs[s] * 0.001f * fs + 0.5f);
		if (d[s] > maxD) maxD = d[s];
	}
#ifndef BROKEN_NOPAD
	/* Delay plus filter has to fit the padding with a margin left for the
	   masks; between the standard rates that only bites above 48 kHz. */
	const int budget = Nf - Nw + 1 - (BIN_MARGIN * Nw) / 1024;
	if (maxD + used > budget && maxD > 0)
	{
		const float k = (float)(budget - used) / (float)maxD;
		for (int s = 0; s < BIN_SPEAKERS; s++)
			d[s] = k > 0.0f ? (int)((float)d[s] * k) : 0;
	}
#endif
	/* A set cut short ends on a short half Hann rather than a step. */
	const int fade = used < taps ? (used < 64 ? used / 4 : 16) : 0;
	for (int s = 0; s < BIN_SPEAKERS; s++)
		for (int e = 0; e < 2; e++)
		{
			const float *src = h + ((size_t)s * 2 + e) * taps;
			memset(buf, 0, sizeof(float) * Nf);
			for (int n = 0; n < used; n++)
			{
				float g = 1.0f;
				if (fade && n >= used - fade)
					g = 0.5f + 0.5f * cosf((float)M_PI * (float)(n - (used - fade) + 1) / (float)(fade + 1));
				const int at = (d[s] + n) & (Nf - 1);
				buf[br[at]] += src[n] * g;
			}
			fft(buf, sinTab);
			float *dst = spec + ((size_t)s * 2 + e) * K * 2;
			for (int k = 0; k < K; k++)
			{
				const int kk = (Nf - k) & (Nf - 1);
				dst[2 * k] = 0.5f * (buf[k] + buf[kk]);
				dst[2 * k + 1] = 0.5f * (buf[k] - buf[kk]);
			}
		}
	free(work);
	free(br);
	*cOffOut = cOff < used ? cOff : used - 1;
	return spec;
}

/* Makes sure the buffers match the rate. Returns 0 if memory could not be
   had. Called from Enable and Refresh only, never from Process. */
static int buPrepare(BinauralUpmix *b, float fs)
{
	if (b->mem && b->fs == fs)
	{
		buDerive(b);
		return 1;
	}
	const int Nw = buWindow(fs), Nf = buFftSize(Nw);
	int len[BINAURAL_FDN_LINES], total = 0;
	for (int i = 0; i < BINAURAL_FDN_LINES; i++)
	{
		int n = (int)(binFdnMs[i] * 0.001f * fs + 0.5f);
		while (!buIsPrime(n)) n++;
		len[i] = n;
		total += n;
	}
	void *mem = calloc(1, buBytes(Nw, Nf, total));
	if (!mem) return 0;
	void *old = b->mem;
	b->mem = mem;
	b->fs = fs;
	b->Nw = Nw;
	b->Nf = Nf;
	b->R = Nw / 2;
	b->K = Nf / 2 + 1;
	b->fft = buFftFor(Nf);
	const int K = b->K;
	float *f = (float *)mem;
	b->in[0] = f; f += Nf;
	b->in[1] = f; f += Nf;
	b->acc[0] = f; f += Nf;
	b->acc[1] = f; f += Nf;
	b->sinTab = f; f += Nf;
	b->bufL = f; f += Nf;
	b->bufR = f; f += Nf;
	b->win = f; f += Nw;
	b->pll = f; f += K;
	b->prr = f; f += K;
	b->plrRe = f; f += K;
	b->plrIm = f; f += K;
	b->gAmb = f; f += K;
	b->pan = f; f += K;
	b->tmp = f; f += K;
	b->fdnBuf = f;
	int off = 0;
	for (int i = 0; i < BINAURAL_FDN_LINES; i++)
	{
		b->fdnLen[i] = len[i];
		b->fdnOff[i] = off;
		off += len[i];
	}
	f += total;
	b->bitRev = (unsigned *)f;
	stftBitRevTable(b->bitRev, Nf);
	stftSinTable(b->sinTab, Nf);
	stftHannPeriodic(b->win, Nw);
	buClearState(b);
	/* Fresh buffers hold nothing worth hearing, so the wet signal waits for
	   them to fill and then fades in, exactly as on switch-on. */
	b->filled = 0;
	b->wetGain = 0.0f;
	buDerive(b);
	if (old) free(old);
	return 1;
}

/* Rebuilds the spectra from the stored set if it is at this rate and they are
   missing or for another frame size. Lock held, or the effect not running. */
static void buSyncSpec(BinauralUpmix *b)
{
	if (!b->mem || !b->hrir || b->hrirFs != b->fs) return;
	if (b->spec && b->specNf == b->Nf && b->specFs == b->fs) return;
	int cOff = 0;
	float *spec = buBuildSpec(b->Nw, b->Nf, b->fs, b->hrir, b->hrirTaps, b->hrirCOff, &cOff);
	if (!spec) return;
	free(b->spec);
	b->spec = spec;
	b->specNf = b->Nf;
	b->specFs = b->fs;
	b->specCOff = cOff;
}

static int buValid(const BinauralUpmix *b)
{
#ifdef BROKEN_RATECHECK
	return b->spec && b->specNf == b->Nf;
#else
	return b->spec && b->specNf == b->Nf && b->specFs == b->fs;
#endif
}

/* One frame: analysis, masks, speaker feeds, rendering, overlap-add. Returns
   -1 if the frame went non-finite. */
static int buFrame(BinauralUpmix *b)
{
	const int Nw = b->Nw, Nf = b->Nf, K = b->K, mask = Nf - 1;
	const int start = (b->pos - Nw + 1) & mask;
	float *A = b->bufL, *B = b->bufR;
	const unsigned *br = b->bitRev;
	const float *win = b->win;

	for (int j = 0; j < Nw; j++)
	{
		const int idx = (start + j) & mask;
		A[br[j]] = b->in[0][idx] * win[j];
		B[br[j]] = b->in[1][idx] * win[j];
	}
	for (int j = Nw; j < Nf; j++)
	{
		A[br[j]] = 0.0f;
		B[br[j]] = 0.0f;
	}
	b->fft(A, b->sinTab);
	b->fft(B, b->sinTab);

	/* Per bin, from the Hartley coefficients: re = (H[k] + H[N-k]) / 2 and
	   im = (H[k] - H[N-k]) / 2, which is the imaginary part with its sign
	   flipped. The filter spectra use the same convention, so products and
	   magnitudes come out right and the way back is H[k] = re + im. */
	const float c = b->covC;
	float total = 0.0f;
	for (int k = 0; k < K; k++)
	{
		const int kk = (Nf - k) & mask;
		const float lr = 0.5f * (A[k] + A[kk]), li = 0.5f * (A[k] - A[kk]);
		const float rr = 0.5f * (B[k] + B[kk]), ri = 0.5f * (B[k] - B[kk]);
		const float pl = lr * lr + li * li, pr = rr * rr + ri * ri;
		total += pl + pr;
		float sll = b->pll[k] + c * (pl - b->pll[k]);
		float srr = b->prr[k] + c * (pr - b->prr[k]);
		float scr = b->plrRe[k] + c * ((lr * rr + li * ri) - b->plrRe[k]);
		float sci = b->plrIm[k] + c * ((li * rr - lr * ri) - b->plrIm[k]);
		if (sll < 1.0e-30f) sll = 0.0f;
		if (srr < 1.0e-30f) srr = 0.0f;
		if (fabsf(scr) < 1.0e-30f) scr = 0.0f;
		if (fabsf(sci) < 1.0e-30f) sci = 0.0f;
		b->pll[k] = sll; b->prr[k] = srr; b->plrRe[k] = scr; b->plrIm[k] = sci;

		const float sum = sll + srr;
		const float cm = sqrtf(scr * scr + sci * sci);
		float g = 0.0f, p = 0.0f;
		if (sum > 1.0e-20f)
		{
			/* Coherence to ambience share. */
			const float phi = cm / sqrtf(sll * srr + 1.0e-30f);
			g = (BIN_PHI_HI - phi) / (BIN_PHI_HI - BIN_PHI_LO);
			if (g < 0.0f) g = 0.0f;
			if (g > 1.0f) g = 1.0f;
			/* Out of phase counts as ambience, not as a centre source. */
			if (cm > 1.0e-30f)
			{
				const float anti = -scr / cm;
				if (anti > g) g = anti;
			}
			/* Ambience has power on both sides; sound on one side only is a
			   panned source. */
			const float lo = sll < srr ? sll : srr, hi = sll < srr ? srr : sll;
			const float r = lo / hi;
			float bal = (r - BIN_BAL_LO) / (BIN_BAL_HI - BIN_BAL_LO);
			if (bal < 0.0f) bal = 0.0f;
			if (bal > 1.0f) bal = 1.0f;
			g *= bal;
			/* Panning index: 0 in the centre, +1 hard left, -1 hard right. */
			float psi = 2.0f * cm / sum;
			if (psi > 1.0f) psi = 1.0f;
			const float side = sll > srr ? 1.0f : (sll < srr ? -1.0f : 0.0f);
			p = (1.0f - psi) * side;
		}
#ifdef BROKEN_NOCOH
		g = 0.5f;
#endif
		b->gAmb[k] = g;
		b->pan[k] = p;
	}
#ifndef BROKEN_NOSCRUB
	if (!isfinite(total)) return -1;
#endif

	/* [1 2 1] / 4 across frequency, mirrored at the ends, so no mask changes
	   faster than every few bins and its impulse response stays short. */
	{
		float *x = b->gAmb, *y = b->pan;
		float px = x[1], py = y[1];
		for (int k = 0; k < K; k++)
		{
			const float cx = x[k], cy = y[k];
			const float nx = k + 1 < K ? x[k + 1] : x[K - 2];
			const float ny = k + 1 < K ? y[k + 1] : y[K - 2];
			x[k] = 0.25f * (px + 2.0f * cx + nx);
			y[k] = 0.25f * (py + 2.0f * cy + ny);
			px = cx; py = cy;
		}
	}

	const float width = b->width, af = b->ambFront, as = b->ambSurround, ah = b->ambHeight;
	const float *H = b->spec;
	const size_t earStride = (size_t)K * 2, spkStride = 2 * earStride;
#ifdef BROKEN_DRYRESYNTH
	const float amt = b->amountTarget;
#endif
	for (int k = 0; k < K; k++)
	{
		const int kk = (Nf - k) & mask;
		const float lr = 0.5f * (A[k] + A[kk]), li = 0.5f * (A[k] - A[kk]);
		const float rr = 0.5f * (B[k] + B[kk]), ri = 0.5f * (B[k] - B[kk]);
		const float g = b->gAmb[k], p = b->pan[k];
		/* Ambience and primary, which add back to the input exactly. */
		const float alr = g * lr, ali = g * li, arr = g * rr, ari = g * ri;
		const float plr = lr - alr, pli = li - ali, prr = rr - arr, pri = ri - ari;
		/* Centre by panning index. Folding the centre back equally into both
		   sides gives the primary pair again, so a mono centre lands wholly on
		   the centre speaker and an anti-phase pair not at all. */
		const float ap = fabsf(p);
#ifdef BROKEN_CENTRE
		const float wc = 0.0f;
#else
		const float wc = ap < 0.5f ? 0.5f + 0.5f * cosf(2.0f * (float)M_PI * ap) : 0.0f;
#endif
		const float sr = plr + prr, si = pli + pri;
		const float cr = wc * sr * BIN_INV_SQRT2, ci = wc * si * BIN_INV_SQRT2;
#ifdef BROKEN_FOLD
		float flr = plr - wc * sr * BIN_INV_SQRT2, fli = pli - wc * si * BIN_INV_SQRT2;
		float frr = prr - wc * sr * BIN_INV_SQRT2, fri = pri - wc * si * BIN_INV_SQRT2;
#else
		float flr = plr - 0.5f * wc * sr, fli = pli - 0.5f * wc * si;
		float frr = prr - 0.5f * wc * sr, fri = pri - 0.5f * wc * si;
#endif
		float wlr = 0.0f, wli = 0.0f, wrr = 0.0f, wri = 0.0f;
		if (width > 0.0f && ap > 0.5f)
		{
			/* Hard panned sound moves out from 30 toward 60 degrees. */
			const float bb = width * (ap - 0.5f) * 2.0f;
			const float cb = cosf(0.5f * (float)M_PI * bb), sb = sinf(0.5f * (float)M_PI * bb);
			if (p > 0.0f)
			{
				wlr = sb * flr; wli = sb * fli;
				flr *= cb; fli *= cb;
			}
			else
			{
				wrr = sb * frr; wri = sb * fri;
				frr *= cb; fri *= cb;
			}
		}
#ifdef BROKEN_MIRROR
		wlr += wrr; wli += wri; wrr = 0.0f; wri = 0.0f;
#endif
		flr += af * alr; fli += af * ali;
		frr += af * arr; fri += af * ari;
		const float fr[BIN_SPEAKERS] = { cr, flr, frr, wlr, wrr, as * alr, as * arr, ah * alr, ah * arr };
		const float fi[BIN_SPEAKERS] = { ci, fli, fri, wli, wri, as * ali, as * ari, ah * ali, ah * ari };
#ifdef BINAURAL_STATS
		{
			const double w = (k == 0 || kk == k) ? 1.0 : 2.0;
			for (int s = 0; s < BIN_SPEAKERS; s++)
				b->feedEnergy[s] += w * ((double)fr[s] * fr[s] + (double)fi[s] * fi[s]);
			b->primaryEnergy += w * ((double)plr * plr + (double)pli * pli + (double)prr * prr + (double)pri * pri);
			b->ambienceEnergy += w * ((double)alr * alr + (double)ali * ali + (double)arr * arr + (double)ari * ari);
		}
#endif
		float ylr = 0.0f, yli = 0.0f, yrr = 0.0f, yri = 0.0f;
		const float *h = H + 2 * (size_t)k;
		for (int s = 0; s < BIN_SPEAKERS; s++)
		{
#ifdef BROKEN_SWAP
			const float *hl = h + (size_t)s * spkStride + earStride, *hr = h + (size_t)s * spkStride;
#else
			const float *hl = h + (size_t)s * spkStride, *hr = hl + earStride;
#endif
			const float xr = fr[s], xi = fi[s];
			ylr += xr * hl[0] - xi * hl[1];
			yli += xr * hl[1] + xi * hl[0];
			yrr += xr * hr[0] - xi * hr[1];
			yri += xr * hr[1] + xi * hr[0];
		}
#ifdef BROKEN_DRYRESYNTH
		{
			/* Dry mixed in the spectrum, delayed by a phase ramp: harness only. */
			const float th = 2.0f * (float)M_PI * (float)k * (float)b->specCOff / (float)Nf;
			const float ct = cosf(th), st = sinf(th);
			ylr = amt * ylr + (1.0f - amt) * (lr * ct - li * st);
			yli = amt * yli + (1.0f - amt) * (lr * st + li * ct);
			yrr = amt * yrr + (1.0f - amt) * (rr * ct - ri * st);
			yri = amt * yri + (1.0f - amt) * (rr * st + ri * ct);
		}
#endif
		A[k] = ylr + yli;
		B[k] = yrr + yri;
		if (kk != k)
		{
			A[kk] = ylr - yli;
			B[kk] = yrr - yri;
		}
	}
	stftBitRevPermute(A, br, Nf);
	stftBitRevPermute(B, br, Nf);
	b->fft(A, b->sinTab);
	b->fft(B, b->sinTab);

	/* No synthesis window: the analysis windows alone sum to 1. */
	const float scale = 1.0f / (float)Nf;
	for (int j = 0; j < Nf; j++)
	{
		const int idx = (start + j) & mask;
		b->acc[0][idx] += A[j] * scale;
		b->acc[1][idx] += B[j] * scale;
	}
	return 0;
}

/* One sample of the room. */
static inline void buRoom(BinauralUpmix *b, float xL, float xR, float *eL, float *eR)
{
	float y[BINAURAL_FDN_LINES], s[BINAURAL_FDN_LINES];
	const float lpc = b->fdnLpC;
	for (int i = 0; i < BINAURAL_FDN_LINES; i++)
	{
		const float v = b->fdnBuf[b->fdnOff[i] + b->fdnPos[i]];
		y[i] = v;
		float lp = v + lpc * (b->fdnLp[i] - v);
		if (fabsf(lp) < 1.0e-25f) lp = 0.0f;
		b->fdnLp[i] = lp;
		s[i] = b->fdnG[i] * lp;
	}
	/* Hadamard, 8 x 8, as three butterfly stages, then 1 / sqrt(8). */
	for (int h = 1; h < BINAURAL_FDN_LINES; h <<= 1)
		for (int i = 0; i < BINAURAL_FDN_LINES; i += h << 1)
			for (int j = i; j < i + h; j++)
			{
				const float a = s[j], c = s[j + h];
				s[j] = a + c;
				s[j + h] = a - c;
			}
	const float n8 = 0.35355339059327373f;
	for (int i = 0; i < BINAURAL_FDN_LINES; i++)
	{
		const float in = (i & 1) ? xR : xL;
		float v = s[i] * n8 + in * 0.5f;
		if (fabsf(v) < 1.0e-25f) v = 0.0f;
		b->fdnBuf[b->fdnOff[i] + b->fdnPos[i]] = v;
		if (++b->fdnPos[i] >= b->fdnLen[i]) b->fdnPos[i] = 0;
	}
	/* Two orthogonal taps, then mostly alike below the crossover and nearly
	   independent above it. */
	float y1 = 0.0f, y2 = 0.0f;
	for (int i = 0; i < BINAURAL_FDN_LINES; i++)
	{
		y1 += y[i];
		y2 += (i & 1) ? -y[i] : y[i];
	}
	y1 *= n8; y2 *= n8;
	const float xc = b->xoverC;
	float x0 = b->xo[0] + xc * (y1 - b->xo[0]);
	float x1 = b->xo[1] + xc * (y2 - b->xo[1]);
	/* Kept out of the denormal range, which costs many times a normal
	   multiply on some cores, in the silence after the music. */
	if (fabsf(x0) < 1.0e-25f) x0 = 0.0f;
	if (fabsf(x1) < 1.0e-25f) x1 = 0.0f;
	b->xo[0] = x0;
	b->xo[1] = x1;
	const float y1h = y1 - x0, y2h = y2 - x1;
	/* cos and sin of 0.15 rad below, of pi / 4 above. */
	const float cl = 0.98877107793604228f, sl = 0.14943813247359922f;
	const float ch = BIN_INV_SQRT2, sh = BIN_INV_SQRT2;
	const float l = cl * x0 + sl * x1 + ch * y1h + sh * y2h;
	const float r = cl * x0 - sl * x1 + ch * y1h - sh * y2h;
	if (!isfinite(l + r))
	{
		/* A room gone non-finite starts again empty, before it is heard. */
		int total = 0;
		for (int i = 0; i < BINAURAL_FDN_LINES; i++) { total += b->fdnLen[i]; b->fdnLp[i] = 0.0f; }
		memset(b->fdnBuf, 0, sizeof(float) * total);
		b->xo[0] = b->xo[1] = 0.0f;
		*eL = *eR = 0.0f;
		return;
	}
	*eL = l;
	*eR = r;
}

void BinauralUpmixProcess(JamesDSPLib *jdsp, size_t n)
{
	BinauralUpmix *b = &jdsp->binaural;
	if (!b->mem)
	{
		/* Never prepared (no memory). Nothing to fade, so finish any switch-off. */
		if (b->fadingOut) { b->fadingOut = 0; jdsp->binauralEnabled = 0; }
		return;
	}
	float *left = jdsp->tmpBuffer[0];
	float *right = jdsp->tmpBuffer[1];
	const int Nw = b->Nw, mask = b->Nf - 1, R = b->R;
	const int valid = buValid(b);
	const int cOff = valid ? b->specCOff : 0;
	const int lat = Nw - 1 + cOff;
	const float step = 1.0f / (float)b->fadeLen;
	const float sc = b->smoothC;
	const float target = (b->fadingOut || !valid) ? 0.0f : 1.0f;
#ifdef BROKEN_FIFO
	/* Hop counted per block, as a hop-aligned buffer would: harness only. */
	b->hop = 0;
#endif

	for (size_t i = 0; i < n; i++)
	{
		float xL = left[i], xR = right[i];
#ifndef BROKEN_NOSCRUB
		/* Written so that NaN fails the test too. */
		if (!(fabsf(xL) <= BIN_INPUT_LIMIT)) xL = 0.0f;
		if (!(fabsf(xR) <= BIN_INPUT_LIMIT)) xR = 0.0f;
#endif
		b->in[0][b->pos] = xL;
		b->in[1][b->pos] = xR;
		if (++b->hop >= R)
		{
			b->hop = 0;
			if (valid && buFrame(b) < 0)
			{
				/* Something non-finite got in. Start again from silence rather
				   than let it latch every smoother; the output is the input
				   until the ring has filled again, then fades back in. */
				buClearState(b);
				b->filled = 0;
				b->wetGain = 0.0f;
				b->in[0][b->pos] = xL;
				b->in[1][b->pos] = xR;
			}
		}
		/* The slot Nw - 1 behind the write head has had every frame that
		   touches it; the dry is read a further cOff behind, so it lines up
		   with the centre speaker. */
		const int rd = (b->pos - Nw + 1) & mask;
		const int dr = (b->pos - Nw + 1 - cOff) & mask;
		const float wL = b->acc[0][rd], wR = b->acc[1][rd];
		b->acc[0][rd] = 0.0f;
		b->acc[1][rd] = 0.0f;
		float dL = b->in[0][dr], dR = b->in[1][dr];
		if (b->dryXfade > 0 && valid)
		{
			/* A set with another centre onset just came in: glide from the
			   old read point to the new one rather than jump, which would
			   click whenever any of the dry is heard. */
			const int dro = (b->pos - Nw + 1 - b->dryCOffOld) & mask;
			const float t = (float)b->dryXfade / (float)(b->fadeLen + 1);
			dL += t * (b->in[0][dro] - dL);
			dR += t * (b->in[1][dro] - dR);
			b->dryXfade--;
		}
		b->pos = (b->pos + 1) & mask;

		float eL, eR;
		buRoom(b, dL, dR, &eL, &eR);

		float a;
		if (b->filled < lat)
		{
			b->filled++;
			b->wetGain = 0.0f;
			b->amount = b->amountTarget;
			b->room = b->roomTarget;
			a = 0.0f;
		}
		else
		{
			if (b->filled < b->Nf) b->filled++;
			float am = b->amount + sc * (b->amountTarget - b->amount);
			if (fabsf(am - b->amountTarget) < 1.0e-6f) am = b->amountTarget;
			b->amount = am;
			float rm = b->room + sc * (b->roomTarget - b->room);
			if (fabsf(rm - b->roomTarget) < 1.0e-6f) rm = b->roomTarget;
			b->room = rm;
#ifdef BROKEN_NOFADE
			b->wetGain = target;
#else
			if (b->wetGain < target)
			{
				b->wetGain += step;
				if (b->wetGain > target) b->wetGain = target;
			}
			else if (b->wetGain > target)
			{
				b->wetGain -= step;
				if (b->wetGain < target) b->wetGain = target;
			}
#endif
			a = b->wetGain;
		}
		if (a <= 0.0f)
		{
			left[i] = xL;
			right[i] = xR;
			continue;
		}
#ifdef BROKEN_DRYRESYNTH
		const float mL = wL + b->room * eL, mR = wR + b->room * eR;
#else
		const float am = b->amount;
		const float mL = dL + am * ((wL + b->room * eL) - dL);
		const float mR = dR + am * ((wR + b->room * eR) - dR);
#endif
		if (a >= 1.0f)
		{
			left[i] = mL;
			right[i] = mR;
		}
		else
		{
			left[i] = xL + (mL - xL) * a;
			right[i] = xR + (mR - xR) * a;
		}
	}

	/* The switch-off has run its course, so the chain can stop calling us. */
	if (b->fadingOut && (b->wetGain <= 0.0f || b->filled < lat))
	{
		b->fadingOut = 0;
		b->wetGain = 0.0f;
		jdsp->binauralEnabled = 0;
	}
}

void BinauralUpmixSetParam(JamesDSPLib *jdsp, float amountPct, float widthPct,
	float surroundPct, float heightPct, float roomPct)
{
	/* Under the lock: Process reads every one of these on the audio thread. */
	jdsp_lock(jdsp);
	BinauralUpmix *b = &jdsp->binaural;
	b->amountPct = buClamp(amountPct, 0.0f, 100.0f, 100.0f);
	b->widthPct = buClamp(widthPct, 0.0f, 100.0f, 40.0f);
	b->surroundPct = buClamp(surroundPct, 0.0f, 100.0f, 60.0f);
	b->heightPct = buClamp(heightPct, 0.0f, 100.0f, 40.0f);
	b->roomPct = buClamp(roomPct, 0.0f, 100.0f, 25.0f);
#ifdef BROKEN_RESET
	if (b->mem) { buClearState(b); b->filled = 0; b->wetGain = 0.0f; }
#endif
	buDerive(b);
	jdsp_unlock(jdsp);
}

int BinauralUpmixLoadHrir(JamesDSPLib *jdsp, const float *hrir, int speakers,
	int taps, float fs, int cOff)
{
	if (!hrir || speakers != BIN_SPEAKERS || taps < 16 || taps > 1024 ||
		!(fs >= 8000.0f && fs <= 384000.0f) || cOff < 0 || cOff >= taps)
		return -1;
	const size_t n = (size_t)BIN_SPEAKERS * 2 * (size_t)taps;
	double energy = 0.0;
	for (size_t i = 0; i < n; i++)
	{
		const float v = hrir[i];
		if (!isfinite(v) || fabsf(v) >= 100.0f) return -2;
		energy += (double)v * v;
	}
	if (!(energy > 0.0)) return -3;
	float *copy = (float *)calloc(n, sizeof(float));
	if (!copy) return -4;
	memcpy(copy, hrir, n * sizeof(float));

	/* The spectra are built outside the lock for the frame size running now,
	   and only swapped in if that is still the one running. */
	BinauralUpmix *b = &jdsp->binaural;
	jdsp_lock(jdsp);
	const int Nw = b->Nw, Nf = b->Nf, prepared = b->mem != 0;
	const float efs = b->fs;
	jdsp_unlock(jdsp);
	float *spec = 0;
	int specCOff = 0;
	if (prepared && fs == efs)
		spec = buBuildSpec(Nw, Nf, fs, copy, taps, cOff, &specCOff);

	jdsp_lock(jdsp);
	const int wasValid = b->mem && buValid(b);
	float *oldHrir = b->hrir, *oldSpec = 0;
	b->hrir = copy;
	b->hrirTaps = taps;
	b->hrirCOff = cOff;
	b->hrirFs = fs;
	if (spec && b->mem && b->Nw == Nw && b->Nf == Nf && b->fs == efs)
	{
		/* At a frame boundary by construction (Process holds the lock), and
		   frames rendered with the old and new filters overlap-add, which is
		   itself a crossfade. */
		oldSpec = b->spec;
		b->spec = spec;
		b->specNf = Nf;
		b->specFs = fs;
#ifndef BROKEN_DRYJUMP
		if (wasValid && specCOff != b->specCOff)
		{
			/* The overlap-add crossfades the wet path, but the dry is read
			   at the centre onset, so it would jump by the difference. */
			b->dryCOffOld = b->specCOff;
			b->dryXfade = b->fadeLen;
		}
#endif
		b->specCOff = specCOff;
		spec = 0;
#ifdef BROKEN_SWAPGAP
		memset(b->acc[0], 0, sizeof(float) * Nf);
		memset(b->acc[1], 0, sizeof(float) * Nf);
#endif
	}
	else if (b->mem)
	{
		/* Rate or size moved meanwhile: build again under the lock. */
		buSyncSpec(b);
	}
#ifndef BROKEN_LATESET
	if (!wasValid && b->mem && buValid(b))
	{
		/* No frame has been rendered while there was no usable set (after a
		   rate change, the app sends one for the new rate a little later), so
		   the overlap-add holds nothing. Wait for whole frames again, as on
		   switch-on, rather than fade in over that gap. */
		b->filled = 0;
		b->wetGain = 0.0f;
	}
#endif
	jdsp_unlock(jdsp);
	free(oldHrir);
	free(oldSpec);
	free(spec);
	return 0;
}

void BinauralUpmixEnable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	BinauralUpmix *b = &jdsp->binaural;
	if (jdsp->binauralEnabled)
	{
		/* Already on, or fading out: turn round rather than refill. */
		b->fadingOut = 0;
		jdsp_unlock(jdsp);
		return;
	}
	const float fs = jdsp->fs > 0.0f ? jdsp->fs : 48000.0f;
	if (!buPrepare(b, fs))
	{
		jdsp_unlock(jdsp);
		return;
	}
	buSyncSpec(b);
	buClearState(b);
	b->filled = 0;
	b->wetGain = 0.0f;
	b->amount = b->amountTarget;
	b->room = b->roomTarget;
	b->fadingOut = 0;
	jdsp->binauralEnabled = 1;
	jdsp_unlock(jdsp);
}

void BinauralUpmixDisable(JamesDSPLib *jdsp)
{
	jdsp_lock(jdsp);
	BinauralUpmix *b = &jdsp->binaural;
	if (jdsp->binauralEnabled)
	{
		/* Process fades out and clears the flag itself, so the output does not
		   jump from the delayed signal to the live one. */
		b->fadingOut = 1;
	}
	else if (b->mem)
	{
		/* Fully off. The app sends the switch with every settings change, so
		   this is reached soon after the fade. The set itself is kept. */
		free(b->mem);
		b->mem = 0;
		free(b->spec);
		b->spec = 0;
		b->specNf = 0;
		b->Nw = b->Nf = 0;
		b->fs = 0.0f;
	}
	jdsp_unlock(jdsp);
}

/* Unlocked on purpose: JamesDSPSetSampleRate holds the lock. A new rate
   reallocates; a set for the old rate then fades to dry and waits for the
   app to send one for the new rate. */
void BinauralUpmixRefresh(JamesDSPLib *jdsp)
{
	BinauralUpmix *b = &jdsp->binaural;
	const float fs = jdsp->fs > 0.0f ? jdsp->fs : 48000.0f;
	if (!b->mem) return;
#ifdef BROKEN_NOREFRESH
	(void)fs;
	buDerive(b);
#else
	if (buPrepare(b, fs)) buSyncSpec(b);
#endif
}

void BinauralUpmixFree(JamesDSPLib *jdsp)
{
	BinauralUpmix *b = &jdsp->binaural;
	free(b->mem);
	b->mem = 0;
	free(b->spec);
	b->spec = 0;
	free(b->hrir);
	b->hrir = 0;
	b->Nw = b->Nf = 0;
	jdsp->binauralEnabled = 0;
}

int BinauralUpmixLatency(JamesDSPLib *jdsp)
{
	int lat = 0;
	jdsp_lock(jdsp);
	BinauralUpmix *b = &jdsp->binaural;
	if (jdsp->binauralEnabled && b->mem)
	{
#ifdef BROKEN_LATENCY
		lat = b->Nw - 1;
#else
		lat = b->Nw - 1 + (buValid(b) ? b->specCOff : 0);
#endif
	}
	jdsp_unlock(jdsp);
	return lat;
}
