// Where does the arbitrary magnitude EQ's 3 dB go?
//
// A completely flat curve through that EQ comes out 3.01 dB down, measured on
// real hardware by tools/percheq_harness.c. 3.01 dB is exactly half power, so
// it is a factor somewhere rather than filter-design error, and the path
// carries both the graphic EQ and the parametric EQ, so it would be costing
// everyone who uses either.
//
// This splits the question in two. A filter's gain at DC is the sum of its
// taps, and its gain at any frequency is the magnitude of its response there.
// So: build the filter for a flat 0 dB curve and measure it directly. If the
// filter sums to 1.0, the loss is in the convolver. If it sums to 0.707, the
// loss is in the generator and the convolver is innocent.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include "ArbFIRGen.h"
#include "FFTConvolver.h"

#define FS 48000.0f

/* Magnitude of the filter's response at one frequency, in dB. */
static double responseDb(const float *h, unsigned int n, double freq)
{
	double re = 0.0, im = 0.0;
	const double w = 2.0 * M_PI * freq / FS;
	for (unsigned int i = 0; i < n; i++)
	{
		re += (double)h[i] * cos(w * (double)i);
		im -= (double)h[i] * sin(w * (double)i);
	}
	const double mag = sqrt(re * re + im * im);
	return 20.0 * log10(mag > 1e-12 ? mag : 1e-12);
}

static void probe(const char *what, int linearPhase, char *curve)
{
	ArbitraryEq eq;
	memset(&eq, 0, sizeof(eq));
	const unsigned int len = InitArbitraryEq(&eq, linearPhase);
	ArbitraryEqString2SortedNodes(&eq, curve);
	const float *h = eq.GetFilter(&eq, FS);

	double sum = 0.0, energy = 0.0, peak = 0.0;
	for (unsigned int i = 0; i < len; i++)
	{
		sum += (double)h[i];
		energy += (double)h[i] * (double)h[i];
		if (fabs((double)h[i]) > peak) peak = fabs((double)h[i]);
	}

	printf("%s\n", what);
	printf("  taps                  %u\n", len);
	printf("  sum of taps (DC gain) %.6f  -> %+.3f dB\n", sum, 20.0 * log10(fabs(sum) > 1e-12 ? fabs(sum) : 1e-12));
	printf("  sqrt(sum of squares)  %.6f\n", sqrt(energy));
	printf("  largest tap           %.6f\n", peak);
	printf("  response at   100 Hz  %+.3f dB\n", responseDb(h, len, 100.0));
	printf("  response at  1000 Hz  %+.3f dB\n", responseDb(h, len, 1000.0));
	printf("  response at 10000 Hz  %+.3f dB\n", responseDb(h, len, 10000.0));
	printf("\n");
	EqNodesFree(&eq);
}

/* The filter turned out to be exact, so the loss has to be downstream: in the
   convolver that applies it. A single-tap impulse of 1.0 is a straight wire by
   definition, so anything other than unity out of this is the convolver's. */
static void probeConvolver(unsigned int blockSize, unsigned int irLen)
{
	FFTConvolver2x2 conv;
	memset(&conv, 0, sizeof(conv));
	float *ir = (float *)calloc(irLen, sizeof(float));
	ir[0] = 1.0f;
	FFTConvolver2x2Init(&conv);
	const int rc = FFTConvolver2x2LoadImpulseResponse(&conv, blockSize, ir, ir, irLen);

	float *inL = (float *)calloc(blockSize, sizeof(float));
	float *inR = (float *)calloc(blockSize, sizeof(float));
	float *outL = (float *)calloc(blockSize, sizeof(float));
	float *outR = (float *)calloc(blockSize, sizeof(float));

	double lastIn = 0.0, lastOut = 0.0;
	long phase = 0;
	/* Several blocks, so the answer is the settled one rather than the
	   convolver filling up. */
	for (int block = 0; block < 8; block++)
	{
		double se = 0.0;
		for (unsigned int i = 0; i < blockSize; i++, phase++)
		{
			inL[i] = inR[i] = (float)(0.25 * sin(2.0 * M_PI * 1000.0 * (double)phase / FS));
			se += (double)inL[i] * (double)inL[i];
		}
		lastIn = sqrt(se / blockSize);
		FFTConvolver2x2Process(&conv, inL, inR, outL, outR, blockSize);
		double so = 0.0;
		for (unsigned int i = 0; i < blockSize; i++) so += (double)outL[i] * (double)outL[i];
		lastOut = sqrt(so / blockSize);
	}
	const double db = 20.0 * log10((lastOut > 1e-12 ? lastOut : 1e-12) / lastIn);
	printf("convolver, block %5u, ir %5u taps, load rc %d  -> %+.3f dB\n",
	       blockSize, irLen, rc, db);
	free(ir); free(inL); free(inR); free(outL); free(outR);
}

int main(void)
{
	char flat[]  = "GraphicEQ: 20 0; 20000 0";
	char boost[] = "GraphicEQ: 20 0; 500 0; 1000 12; 2000 0; 20000 0";

	printf("== arbitrary magnitude EQ: filter gain, measured from the taps ==\n\n");
	printf("A flat curve should be a straight wire: every response below should\n");
	printf("read 0.000 dB and the taps should sum to 1.0.\n\n");

	probe("flat curve, minimum phase (the default)", 0, flat);
	probe("flat curve, linear phase", 1, flat);
	probe("+12 dB at 1 kHz, minimum phase", 0, boost);

	printf("== the convolver, fed a single-tap impulse (a straight wire) ==\n\n");
	printf("Anything other than 0.000 dB here is the convolver's own doing.\n\n");
	/* The engine loads the arbitrary EQ convolver with the engine's block size
	   and an 8192 tap filter, so that is the case that matters. The others are
	   there to show whether it depends on the ratio between the two. */
	probeConvolver(4096, 8192);
	probeConvolver(4096, 4096);
	probeConvolver(1024, 8192);
	probeConvolver(8192, 8192);
	probeConvolver(256, 8192);
	return 0;
}
