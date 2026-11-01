/*
 * Small helpers shared by the short-time Fourier effects (spectral shaper,
 * binaural upmix). They build the two tables the engine's real FHT codelets
 * need, with the same maths as LLbitReversalTbl and fhtsinHalfTblFloat, plus a
 * periodic Hann window, so an effect and its native harness can link without
 * the filter design library.
 *
 * Header only, static inline: each effect gets its own copy and the compiled
 * code is the same as when these loops were written out in the effect itself.
 */
#ifndef STFT_COMMON_H
#define STFT_COMMON_H

#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

/* Bit-reversal permutation of 0 .. N - 1, N a power of two. */
static inline void stftBitRevTable(unsigned *bitRev, int N)
{
	int bits = 0;
	while ((1 << bits) < N) bits++;
	for (int i = 0; i < N; i++)
	{
		unsigned r = 0, v = (unsigned)i;
		for (int b = 0; b < bits; b++) { r = (r << 1) | (v & 1u); v >>= 1; }
		bitRev[i] = r;
	}
}

/* sin(2 pi i / N), the table the codelets take. */
static inline void stftSinTable(float *sinTab, int N)
{
	for (int i = 0; i < N; i++)
		sinTab[i] = (float)sin(2.0 * M_PI * (double)i / (double)N);
}

/* Periodic Hann of length n: copies shifted by n / 2 sum to exactly 1, and by
   n / 4 their squares sum to exactly 1.5. */
static inline void stftHannPeriodic(float *win, int n)
{
	for (int i = 0; i < n; i++)
		win[i] = (float)(0.5 - 0.5 * cos(2.0 * M_PI * (double)i / (double)n));
}

/* The inverse wants bit-reversed input. Bit reversal is an involution, so
   swapping each pair once permutes the whole buffer in place. */
static inline void stftBitRevPermute(float *A, const unsigned *br, int N)
{
	for (int i = 0; i < N; i++)
	{
		const unsigned j = br[i];
		if (j > (unsigned)i)
		{
			const float t = A[i]; A[i] = A[j]; A[j] = t;
		}
	}
}

#endif
