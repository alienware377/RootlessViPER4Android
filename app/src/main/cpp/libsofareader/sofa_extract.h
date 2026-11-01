/*
 * Reads a head related impulse response set (.sofa, SimpleFreeFieldHRIR) with
 * libmysofa and takes out only what the Binaural upmix needs: the pair
 * measured nearest each of its nine speaker directions, and the power average
 * over every direction that the app's diffuse field equalisation is worked
 * out from.
 *
 * Runs only in the isolated helper process: the file is untrusted.
 */
#ifndef SOFA_EXTRACT_H
#define SOFA_EXTRACT_H

#include <stddef.h>

#define SOFA_SPEAKERS 9
/* Bigger files are refused before parsing. */
#define SOFA_MAX_BYTES ((size_t)64 << 20)
#define SOFA_MAX_MEASUREMENTS 100000
#define SOFA_MAX_TAPS_IN 65536
/* Longest response handed back, per ear; room sets are cut to this. */
#define SOFA_MAX_TAPS_OUT 2048
#define SOFA_MAX_DELAY 512

enum SofaError
{
	SOFA_OK = 0,
	SOFA_ERR_SIZE = 1,       /* empty, or over SOFA_MAX_BYTES */
	SOFA_ERR_PARSE = 2,      /* not a SOFA file libmysofa can read */
	SOFA_ERR_NOT_HRIR = 3,   /* readable, but not a two-ear impulse response set */
	SOFA_ERR_DIMS = 4,       /* sizes or values out of range */
	SOFA_ERR_MEMORY = 5
};

typedef struct
{
	int error;               /* enum SofaError */
	int libError;            /* libmysofa's own code, for the log */
	float fs;
	int measurements;
	int taps;                /* per response in hrir */
	int diffBins;            /* entries in diffuse */
	int swapped;             /* the file lists the right ear first */
	/* Per speaker: azimuth and elevation in degrees of the pair used. */
	float dirs[SOFA_SPEAKERS * 2];
	/* SOFA_SPEAKERS pairs, left ear then right, taps each, with the file's
	   own delays put in front. */
	float *hrir;
	/* Mean power over every measured direction and both ears, each direction
	   weighted by the cosine of its elevation, at bins 0 .. diffBins - 1 of
	   an FFT of 2 * (diffBins - 1) points. */
	float *diffuse;
	char title[256];
	char license[1024];
	char database[256];
} SofaSet;

/* Speaker directions, the same as the engine's (Effects/binauralUpmix.c):
   SOFA convention, azimuth counter-clockwise with +90 on the left. */
extern const float sofaSpeakerAz[SOFA_SPEAKERS];
extern const float sofaSpeakerEl[SOFA_SPEAKERS];

/* Fills out from a whole file in memory. Returns out->error. On success the
   arrays are malloc'd and sofaFree releases them. */
int sofaExtract(const char *data, size_t size, SofaSet *out);
void sofaFree(SofaSet *s);

#endif
