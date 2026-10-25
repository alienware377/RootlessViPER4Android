// Does the bass boost decimator stay inside originalBuf when the rate moves?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/dbbpos_harness.c $J/Effects/dbb.c $J/generalDSP/digitalFilters.c \
//       $J/generalDSP/generalProg.c tools/lockstub.c -lm -o tools/dbbpos_x86
//
// The bass boost decimates by round(fs / 500): 96 at 48 kHz, 88 at 44.1 kHz.
// Its write position only wrapped when it was exactly equal to the factor, and
// the slider (BassBoostSetParam) changed the factor without touching the
// position. So with the engine moved from 48 to 44.1 kHz, a slider move that
// landed while the position was 88..95 left it above the new factor for good:
// it counted up once per sample, ran off the 960-float originalBuf and wrote
// audio over the rest of the struct, the factor itself first.
//
// For every one of the 96 decimator phases at 48 kHz, this moves to 44.1 kHz,
// moves the slider, keeps the audio flowing, and checks the position never
// passes the factor and the factor is never overwritten.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "jdsp_header.h"

#define BLK 240
static JamesDSPLib g;
static float L[BLK], R[BLK];
static void blk(size_t n, int *t){ for(size_t i=0;i<n;i++){ L[i]=R[i]=0.3f*sinf((*t)++*0.013f);} BassBoostProcess(&g,n); }

int main(void)
{
	int bad = 0, total = 0, firstBad = -1;
	for (int phase = 0; phase < 96; phase++)
	{
		memset(&g, 0, sizeof(g));
		g.tmpBuffer[0] = L; g.tmpBuffer[1] = R;
		g.fs = 48000.0f;
		BassBoostConstructor(&g);
		BassBoostSetParam(&g, 10.0f);
		int t = 0;
		for (int b = 0; b < 20; b++) blk(BLK, &t);
		// land the 48k decimator at a chosen phase
		while (g.dbb.downsamplerPos != phase) blk(1, &t);
		// 48k -> 44.1k: JamesDSPSetSampleRate(...,0) leaves dbb alone
		g.fs = 44100.0f;
		// slider moved -> setBassBoost -> BassBoostSetParam at 44.1k
		BassBoostSetParam(&g, 9.0f);
		int factor = g.dbb.downsampler.factor;
		int startPos = g.dbb.downsamplerPos;
		total++;
		int corrupted = 0, blocks = 0;
		for (; blocks < 6; blocks++)
		{
			int p = g.dbb.downsamplerPos;
			if (p + BLK >= 960) {
				// next block writes originalBuf[960..]: do it once and see the damage
				blk(BLK, &t);
				corrupted = (g.dbb.downsampler.factor != factor);
				if (firstBad < 0) {
					firstBad = phase;
					printf("phase %d: after SetParam factor=%d pos=%d; after %d blocks pos=%d, one more block:\n",
						phase, factor, startPos, blocks, p);
					printf("  downsampler.factor now %d (was %d), downsamplerPos=%d\n",
						g.dbb.downsampler.factor, factor, g.dbb.downsamplerPos);
				}
				break;
			}
			blk(BLK, &t);
		}
		if (g.dbb.downsamplerPos > factor || corrupted) bad++;
	}
	printf("phases tested=%d, overrun phases=%d (expected 8: pos 88..95; pos==88 wraps? see note)\n", total, bad);
	if (bad) { printf("FAIL\n"); return 1; }
	printf("PASS\n"); return 0;
}
