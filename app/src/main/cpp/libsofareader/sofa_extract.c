#include "sofa_extract.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "mysofa.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

/* C, FL, FR, WL, WR, SL, SR, TL, TR */
const float sofaSpeakerAz[SOFA_SPEAKERS] = { 0, 30, -30, 60, -60, 110, -110, 45, -45 };
const float sofaSpeakerEl[SOFA_SPEAKERS] = { 0, 0, 0, 0, 0, 0, 0, 40, 40 };

static void copyAttr(struct MYSOFA_HRTF *h, const char *name, char *dst, size_t cap)
{
	dst[0] = 0;
	const char *v = mysofa_getAttribute(h->attributes, (char *)name);
	if (!v) return;
	size_t n = strnlen(v, cap - 1);
	memcpy(dst, v, n);
	dst[n] = 0;
	/* Control characters other than line breaks are dropped; the text is
	   shown on the card. */
	size_t j = 0;
	for (size_t i = 0; i < n; i++)
	{
		const unsigned char c = (unsigned char)dst[i];
		if (c >= 32 || c == '\n' || c == '\t') dst[j++] = (char)c;
	}
	dst[j] = 0;
}

/* In-place radix-2 FFT (Cooley and Tukey), n a power of two. */
static void fftRadix2(double *re, double *im, int n)
{
	for (int i = 1, j = 0; i < n; i++)
	{
		int bit = n >> 1;
		for (; j & bit; bit >>= 1) j ^= bit;
		j ^= bit;
		if (i < j)
		{
			double t = re[i]; re[i] = re[j]; re[j] = t;
			t = im[i]; im[i] = im[j]; im[j] = t;
		}
	}
	for (int len = 2; len <= n; len <<= 1)
	{
		const double ang = -2.0 * M_PI / (double)len;
		const double wr = cos(ang), wi = sin(ang);
		for (int i = 0; i < n; i += len)
		{
			double cr = 1.0, ci = 0.0;
			for (int k = 0; k < len / 2; k++)
			{
				const int a = i + k, b = a + len / 2;
				const double xr = re[b] * cr - im[b] * ci;
				const double xi = re[b] * ci + im[b] * cr;
				re[b] = re[a] - xr; im[b] = im[a] - xi;
				re[a] += xr; im[a] += xi;
				const double t = cr * wr - ci * wi;
				ci = cr * wi + ci * wr;
				cr = t;
			}
		}
	}
}

static void unitVector(float azDeg, float elDeg, double *v)
{
	const double az = azDeg * M_PI / 180.0, el = elDeg * M_PI / 180.0;
	v[0] = cos(el) * cos(az);
	v[1] = cos(el) * sin(az);
	v[2] = sin(el);
}

static int fail(SofaSet *out, int code, struct MYSOFA_HRTF *h)
{
	if (h) mysofa_free(h);
	free(out->hrir);
	free(out->diffuse);
	out->hrir = NULL;
	out->diffuse = NULL;
	out->error = code;
	return code;
}

int sofaExtract(const char *data, size_t size, SofaSet *out)
{
	memset(out, 0, sizeof(*out));
#ifdef BROKEN_NOSIZECHECK
	if (!data || size == 0)
#else
	if (!data || size == 0 || size > SOFA_MAX_BYTES)
#endif
		return fail(out, SOFA_ERR_SIZE, NULL);

	int err = 0;
	struct MYSOFA_HRTF *h = mysofa_load_data(data, size, &err);
	out->libError = err;
	if (!h || err != MYSOFA_OK)
		return fail(out, SOFA_ERR_PARSE, h);
	err = mysofa_check(h);
	out->libError = err;
	if (err != MYSOFA_OK)
		return fail(out, SOFA_ERR_NOT_HRIR, h);

	/* Every count is checked before it sizes anything. */
	const unsigned M = h->M, N = h->N, R = h->R;
	if (R != 2 || M == 0 || M > SOFA_MAX_MEASUREMENTS || N == 0 || N > SOFA_MAX_TAPS_IN ||
		!h->DataIR.values || h->DataIR.elements != M * R * N ||
		!h->SourcePosition.values || h->SourcePosition.elements != M * 3 ||
		!h->DataSamplingRate.values || h->DataSamplingRate.elements < 1)
		return fail(out, SOFA_ERR_DIMS, h);
	const float fs = h->DataSamplingRate.values[0];
	if (!(fs >= 8000.0f && fs <= 384000.0f))
		return fail(out, SOFA_ERR_DIMS, h);
	for (unsigned i = 0; i < h->DataIR.elements; i++)
		if (!isfinite(h->DataIR.values[i]) || fabsf(h->DataIR.values[i]) > 1.0e6f)
			return fail(out, SOFA_ERR_DIMS, h);

	/* Old writers listed the right ear first; libmysofa lets those through
	   with a warning, so follow the receiver positions. */
	const int swapped = h->ReceiverPosition.values && h->ReceiverPosition.elements >= 6 &&
		h->ReceiverPosition.values[1] < 0.0f;

	mysofa_tospherical(h);
	for (unsigned m = 0; m < M * 3; m++)
		if (!isfinite(h->SourcePosition.values[m]))
			return fail(out, SOFA_ERR_DIMS, h);

	/* Nearest measured direction to each speaker, by angle. */
	int pick[SOFA_SPEAKERS];
	for (int s = 0; s < SOFA_SPEAKERS; s++)
	{
		double want[3], best = -2.0;
		unitVector(sofaSpeakerAz[s], sofaSpeakerEl[s], want);
		pick[s] = 0;
		for (unsigned m = 0; m < M; m++)
		{
			double v[3];
			unitVector(h->SourcePosition.values[m * 3], h->SourcePosition.values[m * 3 + 1], v);
			const double d = v[0] * want[0] + v[1] * want[1] + v[2] * want[2];
			if (d > best) { best = d; pick[s] = (int)m; }
		}
#ifdef BROKEN_NEAREST
		pick[s] = 0;
#endif
		out->dirs[s * 2] = h->SourcePosition.values[pick[s] * 3];
		out->dirs[s * 2 + 1] = h->SourcePosition.values[pick[s] * 3 + 1];
	}

	/* Delays in samples, per receiver or per measurement and receiver. */
	const unsigned nd = h->DataDelay.values ? h->DataDelay.elements : 0;
	int maxDelay = 0;
	int delay[SOFA_SPEAKERS][2];
	for (int s = 0; s < SOFA_SPEAKERS; s++)
		for (unsigned r = 0; r < 2; r++)
		{
			float d = 0.0f;
			if (nd == R) d = h->DataDelay.values[r];
			else if (nd == M * R) d = h->DataDelay.values[(unsigned)pick[s] * R + r];
			int di = isfinite(d) ? (int)lrintf(d) : 0;
			if (di < 0) di = 0;
			if (di > SOFA_MAX_DELAY) di = SOFA_MAX_DELAY;
			delay[s][r] = di;
			if (di > maxDelay) maxDelay = di;
		}
	int taps = (int)N + maxDelay;
	if (taps > SOFA_MAX_TAPS_OUT) taps = SOFA_MAX_TAPS_OUT;
	out->hrir = (float *)calloc((size_t)SOFA_SPEAKERS * 2 * (size_t)taps, sizeof(float));
	if (!out->hrir) return fail(out, SOFA_ERR_MEMORY, h);
	for (int s = 0; s < SOFA_SPEAKERS; s++)
		for (int e = 0; e < 2; e++)
		{
			const unsigned r = swapped ? (unsigned)(1 - e) : (unsigned)e;
			const float *src = h->DataIR.values + ((size_t)pick[s] * R + r) * N;
			float *dst = out->hrir + ((size_t)s * 2 + e) * (size_t)taps;
			const int d = delay[s][r];
			for (int n = 0; n < (int)N && n + d < taps; n++) dst[n + d] = src[n];
		}

	/* Diffuse field power average. */
	int P = 256;
	const int span = (int)N < SOFA_MAX_TAPS_OUT ? (int)N : SOFA_MAX_TAPS_OUT;
	while (P < span) P <<= 1;
	const int K = P / 2 + 1;
	double *re = (double *)malloc(sizeof(double) * (size_t)P * 2);
	double *acc = (double *)calloc((size_t)K, sizeof(double));
	out->diffuse = (float *)calloc((size_t)K, sizeof(float));
	if (!re || !acc || !out->diffuse)
	{
		free(re); free(acc);
		return fail(out, SOFA_ERR_MEMORY, h);
	}
	double *im = re + P, wsum = 0.0;
	for (unsigned m = 0; m < M; m++)
	{
		const double w = cos(h->SourcePosition.values[m * 3 + 1] * M_PI / 180.0);
		if (!(w > 1.0e-6)) continue;
		for (unsigned r = 0; r < 2; r++)
		{
			const float *src = h->DataIR.values + ((size_t)m * R + r) * N;
			for (int i = 0; i < P; i++) { re[i] = i < span ? src[i] : 0.0; im[i] = 0.0; }
			fftRadix2(re, im, P);
			for (int k = 0; k < K; k++) acc[k] += w * (re[k] * re[k] + im[k] * im[k]);
			wsum += w;
		}
	}
	if (!(wsum > 0.0))
	{
		free(re); free(acc);
		return fail(out, SOFA_ERR_DIMS, h);
	}
	for (int k = 0; k < K; k++) out->diffuse[k] = (float)(acc[k] / wsum);
	free(re);
	free(acc);

	out->fs = fs;
	out->measurements = (int)M;
	out->taps = taps;
	out->diffBins = K;
	out->swapped = swapped;
	copyAttr(h, "Title", out->title, sizeof(out->title));
	copyAttr(h, "License", out->license, sizeof(out->license));
	copyAttr(h, "DatabaseName", out->database, sizeof(out->database));
	mysofa_free(h);
	out->error = SOFA_OK;
	return SOFA_OK;
}

void sofaFree(SofaSet *s)
{
	if (!s) return;
	free(s->hrir);
	free(s->diffuse);
	s->hrir = NULL;
	s->diffuse = NULL;
}
