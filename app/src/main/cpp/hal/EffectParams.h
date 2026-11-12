/*
 * Shared parameter dispatch.
 *
 * Both HALs receive the same ids from the app and must act on them identically:
 * the legacy one through EFFECT_CMD_SET_PARAM, the AIDL one through a vendor
 * extension carrying that very same legacy payload. One implementation means
 * the two cannot drift apart as effects are added.
 */
#pragma once

extern "C" {
#include "jdsp_header.h"
}

/* The legacy HAL defines these; the AIDL one logs through its own mechanism, so
   they fall away to nothing there rather than each HAL needing the other's. */
#ifndef LOGD
#define LOGD(...) ((void)0)
#endif

/* Floats from the payload to ints. Any app can send these in root mode, and a
   NaN or an out-of-range float converted to int is undefined behaviour (arm64
   happens to give 0 or saturate; nothing promises it). NaN becomes 0 and the
   rest is held to +-1e9 first; every setter clamps to its own range after. */
static inline int pInt(float v)
{
    if (!(v == v)) return 0;
    if (v > 1e9f) v = 1e9f;
    if (v < -1e9f) v = -1e9f;
    return (int)v;
}
static inline int pRound(float v) { return pInt(v + 0.5f); }

static inline void applyParam(JamesDSPLib *d, int32_t id, int16_t sv, bool on,
                              const float *fv, uint32_t fn)
{
    switch (id)
    {
    /* --- enable flags --------------------------------------------------- */
    /* XxxEnable(d, 0) only refreshes and never clears the flag, so the off
       branches have to call the Disable functions, as the rootless wrapper
       does, or the effect could never be switched off in root mode. */
    case 1200: if (on) CompressorEnable(d, 1); else CompressorDisable(d); break;
    case 1201: if (on) BassBoostEnable(d); else BassBoostDisable(d); break;
    case 1202: if (on) MultimodalEqualizerEnable(d, 1); else MultimodalEqualizerDisable(d); break;
    case 1203: if (on) ReverbEnable(d); else ReverbDisable(d); break;
    case 1204: if (on) StereoEnhancementEnable(d); else StereoEnhancementDisable(d); break;
    case 1205: if (on) Convolver1DEnable(d); else Convolver1DDisable(d); break;
    case 1206: if (on) VacuumTubeEnable(d); else VacuumTubeDisable(d); break;
    case 1208: if (on) CrossfeedEnable(d, 1); else CrossfeedDisable(d); break;
    case 1210: if (on) ArbitraryResponseEqualizerEnable(d, 1); else ArbitraryResponseEqualizerDisable(d); break;
    case 1212: if (on) DDCEnable(d, 1); else DDCDisable(d); break;
    case 1213: if (on) LiveProgEnable(d); else LiveProgDisable(d); break;

    /* --- values --------------------------------------------------------- */
    case 112: BassBoostSetParam(d, (float)sv); break;                 /* max gain, dB */
    /* The same gain in hundredths of a dB. 112 carries whole dB only, so a
       continuous slider set to 7.4 dB gave 7 dB in root mode and 7.4 dB
       rootless. The app sends 112 first and then this, so an older module
       that ignores this id still gets the whole-dB value. */
    case 26200: BassBoostSetParam(d, (float)sv / 100.0f); break;
    case 128: Reverb_SetParam(d, sv); break;                          /* preset index */
    case 137: StereoEnhancementSetParam(d, (float)sv / 100.0f); break; /* width, sent x100 */
    case 150: VacuumTubeSetGain(d, (double)sv / 1000.0); break;       /* sent x1000 */
    case 188: CrossfeedChangeMode(d, sv); break;                      /* mode index */


    /* --- effects this fork adds ----------------------------------------
       Values come as a float array in the order the app packs them; enable
       flags sit one hundred above the value id. Anything with too few values
       is ignored rather than half-applied. */
    case 26000: if (fn >= 2) ViperClaritySetParam(d, pInt(fv[0]), fv[1]); break;
    case 26100: if (on) ViperClarityEnable(d); else ViperClarityDisable(d); break;

    case 26001: if (fn >= 2) FieldSurroundSetParam(d, fv[0], fv[1]); break;
    case 26101: if (on) FieldSurroundEnable(d); else FieldSurroundDisable(d); break;

    case 26002: if (fn >= 2) AgcSetParam(d, fv[0], fv[1]); break;
    case 26102: if (on) AgcEnable(d); else AgcDisable(d); break;

    case 26003: if (fn >= 2) HpSurroundSetParam(d, fv[0], fv[1]); break;
    case 26103: if (on) HpSurroundEnable(d); else HpSurroundDisable(d); break;

    case 26006: if (fn >= 3) ViperBassSetParam(d, pInt(fv[0]), fv[1], fv[2]); break;
    case 26106: if (on) ViperBassEnable(d); else ViperBassDisable(d); break;

    case 26007:
        if (fn >= 12)
            VReverbSetParam(d, pInt(fv[0]), fv[1], fv[2], fv[3], fv[4], fv[5],
                            fv[6], fv[7], fv[8], fv[9], fv[10], fv[11]);
        break;
    case 26107: if (on) VReverbEnable(d); else VReverbDisable(d); break;

    case 26008: if (fn >= 1) SpeakerOptSetParam(d, fv[0]); break;
    case 26108: if (on) SpeakerOptEnable(d); else SpeakerOptDisable(d); break;

    case 26004:
        if (fn >= 5) FetCompSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4]);
        break;
    case 26104: if (on) FetCompEnable(d); else FetCompDisable(d); break;

    case 26005: if (fn >= 1) CureSetParam(d, pInt(fv[0])); break;
    case 26105: if (on) CureEnable(d); else CureDisable(d); break;

    case 26009:
        /* Two floats from an older app build means granular, which is what it
           would have been sending; three carries the mode. The mode goes
           through as a number and the engine resolves it, so the Signalsmith
           methods (4 and 5) need nothing here. Rubber Band is never loaded in
           the audio server, so its two modes play as the matching Signalsmith
           one. A module older than the app clamps 4 and 5 to granular. */
        if (fn >= 3) PitchShiftSetParam(d, fv[0], fv[1], pRound(fv[2]));
        else if (fn >= 2) PitchShiftSetParam(d, fv[0], fv[1], PITCH_MODE_GRANULAR);
        break;
    case 26109: if (on) PitchShiftEnable(d); else PitchShiftDisable(d); break;

    case 26010:
        if (fn >= 25)
            EchoDelaySetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4] != 0.0f, pInt(fv[5]),
                              fv[6], fv[7], fv[8], fv[9], pInt(fv[10]), fv[11], fv[12],
                              fv[13], fv[14], fv[15], fv[16], fv[17], pInt(fv[18]),
                              fv[19], fv[20], fv[21], fv[22], fv[23], fv[24]);
        break;
    case 26110: if (on) EchoDelayEnable(d); else EchoDelayDisable(d); break;

    case 26011: ArbitraryResponseEqualizerSetPhaseMode(d, on ? 1 : 0); break;

    /* Mix, band count, then that many bands of DYNEQ_VALUES_PER_BAND. One
       array rather than two calls, so the bands and the mix cannot arrive out
       of step with each other. */
    case 26013:
        if (fn >= 3)
        {
            int count = pRound(fv[1]);
            /* Clamped BEFORE the length it implies is worked out, which is the
               whole point. Computed the other way round, a count of 2^29
               multiplied by eight wraps a 32-bit length back to zero, so the
               payload appears to need only its three header floats, passes the
               check, and is then handed to SetBands - which clamps to eight
               bands and reads sixty-four floats out of a buffer that holds
               three. Any app on the device can address this effect, so the
               count is not ours to trust. */
            if (count < 0) count = 0;
            if (count > DYNEQ_MAX_BANDS) count = DYNEQ_MAX_BANDS;
            const uint32_t need = 3u + (uint32_t)count * DYNEQ_VALUES_PER_BAND;
            if (fn >= need)
            {
                DynamicEqSetBands(d, fv + 3, count);
                DynamicEqSetParam(d, fv[0], pRound(fv[2]));
            }
        }
        break;
    case 26113: if (on) DynamicEqEnable(d); else DynamicEqDisable(d); break;

    case 26014:
        if (fn >= 8)
            ImagingSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4], fv[5], fv[6], fv[7]);
        break;
    case 26114: if (on) ImagingEnable(d); else ImagingDisable(d); break;

    case 26015:
        if (fn >= 10)
            TransientSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4], fv[5],
                              fv[6], fv[7], fv[8], fv[9]);
        break;
    case 26115: if (on) TransientEnable(d); else TransientDisable(d); break;

    case 26016:
        if (fn >= 6)
            LowEndSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4], fv[5]);
        break;
    case 26116: if (on) LowEndEnable(d); else LowEndDisable(d); break;

    case 26017:
        if (fn >= 10)
            ExciterSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4], fv[5], fv[6],
                            pRound(fv[7]), fv[8], fv[9]);
        break;
    case 26117: if (on) ExciterEnable(d); else ExciterDisable(d); break;

    case 26018:
        if (fn >= 6)
            TapeSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4], fv[5]);
        break;
    case 26118: if (on) TapeEnable(d); else TapeDisable(d); break;

    case 26020:
        if (fn >= 12)
            VinylSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4], fv[5],
                          fv[6], fv[7], fv[8], fv[9], fv[10], fv[11]);
        break;
    case 26120: if (on) VinylEnable(d); else VinylDisable(d); break;

    case 26021:
        if (fn >= 3)
            BalanceSetParam(d, fv[0], pRound(fv[1]), fv[2]);
        break;
    case 26121: if (on) BalanceEnable(d); else BalanceDisable(d); break;

    /* Spectrum extension: bark frequency, strength in percent. Linked in all
       along, like the maximiser, but the remote engine had no id for it. */
    case 26022:
        if (fn >= 2)
            SpectrumExtensionSetParam(d, fv[0], fv[1]);
        break;
    case 26122: if (on) SpectrumExtensionEnable(d); else SpectrumExtensionDisable(d); break;

    /* Spectral shaper: amount dB, sensitivity %, lowest Hz, highest Hz,
       speed %, focus, catch steady tones, listen to removed, stereo part.
       Any app can address this id, so every value is clamped again by
       SpectralShaperSetParam, NaN included. */
    case 26023:
        if (fn >= 9)
            SpectralShaperSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4],
                                   pRound(fv[5]), fv[6] > 0.5f ? 1 : 0,
                                   fv[7] > 0.5f ? 1 : 0, pRound(fv[8]));
        break;
    case 26123: if (on) SpectralShaperEnable(d); else SpectralShaperDisable(d); break;

    /* Binaural upmix: amount, width, surround, height, room, all percent.
       Clamped again by BinauralUpmixSetParam, NaN included. The switch is
       refused by the HAL until a head set has arrived (9997 / 12002 / 10011),
       since with none it can only ever play the plain signal. */
    case 26024:
        if (fn >= 5)
            BinauralUpmixSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4]);
        break;
    case 26124: if (on) BinauralUpmixEnable(d); else BinauralUpmixDisable(d); break;

    /* Three effects the library always had but the root build never sent,
       so their cards did nothing on a rooted phone. Every value is clamped
       again by the setters, NaN included. */
    /* Bass exciter: cutoff Hz, intensity %, mix %, then the second band's
       switch (1 or 0), cutoff, intensity and mix. */
    case 26025:
        if (fn >= 7)
        {
            BassExciterSetParam(d, fv[0], fv[1], fv[2]);
            BassExciterSetParam2(d, fv[3] > 0.5f ? 1 : 0, fv[4], fv[5], fv[6]);
        }
        break;
    case 26125: if (on) BassExciterEnable(d); else BassExciterDisable(d); break;

    /* Dynamic system: gain %, x1, x2, y1, y2 in Hz, side gain x and y %. */
    case 26026:
        if (fn >= 7)
            VDynBassSetParam(d, fv[0], fv[1], fv[2], fv[3], fv[4], fv[5], fv[6]);
        break;
    case 26126: if (on) VDynBassEnable(d); else VDynBassDisable(d); break;

    /* Differential surround: left and right delay in ms. */
    case 26027:
        if (fn >= 2)
            DiffSurroundSetParam(d, fv[0], fv[1]);
        break;
    case 26127: if (on) DiffSurroundEnable(d); else DiffSurroundDisable(d); break;

    /* The maximiser. Ten values in the order MaximizerSetParam takes them.
       The three integer ones travel as floats like every other payload here
       and are rounded on arrival; only truePeak is a flag, and it arrives as
       1 or 0 rather than through the enable id, because it is a setting
       rather than the effect's own switch. */
    case 26019:
        if (fn >= 10)
            MaximizerSetParam(d, pRound(fv[0]), fv[1], fv[2], fv[3],
                              fv[4], fv[5], pRound(fv[6]), fv[7],
                              pRound(fv[8]), pRound(fv[9]));
        break;
    case 26119: if (on) MaximizerEnable(d); else MaximizerDisable(d); break;

    /* The order arrives as ints, so it is read from the raw payload rather
       than through the float view every other effect uses. */
    case 26012:
        /* An empty order, or the app's reset marker (a single -1), restores
           the default chain. JamesDSPSetChainOrder alone would skip the -1
           and append effects in declaration order, which is not the
           default. */
        if (fn == 0 || ((const int *)fv)[0] < 0)
            JamesDSPResetChainOrder(d);
        else
            JamesDSPSetChainOrder(d, (const int *)fv, (int)fn);
        break;

    /* --- upstream ids -------------------------------------------------
       These are not fork additions; they are the app's own EQ, dynamics and
       output stage. Left unhandled, the root build would lose the equaliser,
       the compander and the limiter while reporting success. */
    case 1500:
        if (fn >= 3)
        {
            double threshold = (double)fv[0];
            double release = (double)fv[1];
            double postGain = (double)fv[2];
            /* Same clamps the engine's own handler applies: a threshold at or
               above 0 dB and a release below 0.15 ms both make the limiter
               misbehave rather than merely sound different. */
            if (threshold > -0.09) threshold = -0.09;
            if (release < 0.15) release = 0.15;
            if (postGain > 15.0) postGain = 15.0;
            if (postGain < -15.0) postGain = -15.0;
            JLimiterSetCoefficients(d, threshold, release);
            JamesDSPSetPostGain(d, postGain);
            /* A fourth value is the limiter mode (V4A-only mode pins 3, the
               faithful SoftwareLimiter). Older apps send three. */
            if (fn >= 4)
                JLimiterSetMode(d, pRound(fv[3]));
        }
        break;

    case 115:
        /* time constant, granularity, tf resolution, then 7 frequencies and
           7 gains. */
        if (fn >= 17)
        {
            double axis[14];
            for (int i = 0; i < 7; i++)
            {
                axis[i] = (double)fv[3 + i];
                axis[i + 7] = (double)fv[3 + 7 + i];
            }
            /* Under the lock the compander's dispatch runs under. */
            jdsp_lock(d);
            CompressorSetParam(d, fv[0], pRound(fv[1]), pRound(fv[2]), 0);
            CompressorSetGain(d, axis, axis + 7, 1);
            jdsp_unlock(d);
        }
        break;

    case 116:
        /* filter type, interpolation mode, then 15 frequencies and 15 gains. */
        if (fn >= 32)
        {
            double axis[30];
            for (int i = 0; i < 15; i++)
            {
                axis[i] = (double)fv[2 + i];
                axis[i + 15] = (double)fv[2 + 15 + i];
            }
            MultimodalEqualizerAxisInterpolation(
                d, fv[1] < 0.0f ? 0 : 1, pRound(fv[0]), axis, axis + 15);
        }
        break;

    default:
        LOGD("param id %d ignored (no mapping)", id);
        /* Unknown ids are accepted rather than refused: returning an error
           here makes the audio server tear the effect down entirely. */
        break;
    }
}

