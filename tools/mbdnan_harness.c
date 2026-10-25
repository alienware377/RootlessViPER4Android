// Does one bad sample silence a multiband distortion channel?
//
//   NDK=$LOCALAPPDATA/Android/Sdk/ndk/<ver>/toolchains/llvm/prebuilt/windows-x86_64/bin
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J \
//       tools/mbdnan_harness.c $J/Effects/multibandDist.c $J/generalDSP/digitalFilters.c \
//       tools/lockstub.c -lm -o tools/mbdnan_x86
//
// Below 96 kHz the distortion runs 2x oversampled, through recursive
// filters. Its guard for a non-finite output cleared the band filters, the
// DC blocker, the tone filter and the chorus line, but not the oversampler's
// filters. After one NaN or Inf in, those held it, so the guard fired on
// every sample from then on and wrote 0: the channel was silent, dry part
// included, at any mix, until the effect was switched off and on, and it
// cleared the 8192-float chorus line on every one of those samples.
//
// A 440 Hz tone, one bad sample on the left, then 1 s more: the left must
// come back. Last, the oversampler state is poisoned directly, which only the
// guard can clear.
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "jdsp_header.h"
static JamesDSPLib g;
static float L[1024], R[1024];
static long t = 0;
static double now(void){struct timespec s;clock_gettime(CLOCK_MONOTONIC,&s);return s.tv_sec+s.tv_nsec*1e-9;}
static void setup(float fs, float chorusMix, float mix)
{
	memset(&g,0,sizeof(g)); t = 0;
	g.fs = fs; g.tmpBuffer[0]=L; g.tmpBuffer[1]=R;
	MultibandDistSetParam(&g,0,0,50,0,50,16,0,50,100,0.5f,2,0,0,2,chorusMix,mix);
	float band[4]={1000.0f,6.0f,1.0f,0.0f};
	MultibandDistSetBands(&g,band,1);
	MultibandDistEnable(&g);
}
static void tone(int blocks,double*pkL,double*pkR,int*nonfin,double*secs)
{
	*pkL=*pkR=0;*nonfin=0;double t0=now();
	for(int b=0;b<blocks;b++){
		for(int i=0;i<1024;i++,t++) L[i]=R[i]=0.3f*(float)sin(2*M_PI*440.0*t/g.fs);
		MultibandDistProcess(&g,1024);
		for(int i=0;i<1024;i++){ if(!isfinite(L[i])||!isfinite(R[i]))(*nonfin)++;
			if(fabs(L[i])>*pkL)*pkL=fabs(L[i]); if(fabs(R[i])>*pkR)*pkR=fabs(R[i]); }
	}
	*secs=now()-t0;
}
static int run(float fs,float bad,float chorusMix,float mix,const char*tag)
{
	double a,b,s; int nf;
	setup(fs,chorusMix,mix);
	tone(47,&a,&b,&nf,&s);
	printf("[%s fs=%.0f mix=%.0f chorus=%.0f] os=%d before: L %.4f R %.4f (%.3fs)\n",tag,fs,mix,chorusMix,g.multibandDist.osFactor,a,b,s);
	for(int i=0;i<1024;i++,t++) L[i]=R[i]=0.3f*(float)sin(2*M_PI*440.0*t/g.fs);
	L[100]=bad;
	MultibandDistProcess(&g,1024);
	int fu=1; /* is lpfU state finite? probe via output only */
	tone(47,&a,&b,&nf,&s);
	printf("   after 1 bad sample on L: L %.4f R %.4f nonfinite-out %d (%.3fs)\n",a,b,nf,s);
	int fail = (a<0.01 && b>0.1);
	MultibandDistDisable(&g); MultibandDistEnable(&g);
	tone(47,&a,&b,&nf,&s);
	printf("   after off/on:            L %.4f R %.4f\n",a,b);
	(void)fu;
	return fail;
}
int main(void)
{
	int f=0;
	f|=run(48000,NAN,0,100,"NaN");
	f|=run(44100,NAN,0,100,"NaN");
	f|=run(48000,NAN,0,10,"NaN low mix");
	f|=run(48000,INFINITY,0,100,"Inf");
	f|=run(48000,NAN,50,100,"NaN chorus");
	{
		double a,b,s; int nf;
		setup(48000,0,100);
		g.multibandDist.smpUp[0].lpfU._xn1 = NAN;
		tone(47,&a,&b,&nf,&s);
		printf("[oversampler state poisoned] L %.4f R %.4f nonfinite-out %d\n",a,b,nf);
		f|=(a<0.01 && b>0.1);
	}
	printf(f?"FAIL (a channel stays silent after one bad sample)\n":"PASS\n");
	return f;
}
