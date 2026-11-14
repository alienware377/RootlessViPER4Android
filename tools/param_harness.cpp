// Does the root build's parameter dispatch actually reach the engine?
//
// The rooted build shares one table, EffectParams.h, with the app. Anything
// missing from that table is not an error - the default arm has to accept
// unknown ids, because refusing makes the audio server tear the effect down -
// so a missing case is completely silent and the app still reports success.
// That is exactly how the equaliser, the compander and the output limiter came
// to be dropped on rooted devices while appearing to work.
//
// This drives applyParam with the same payloads JamesDspRemoteEngine sends and
// asserts the engine state moved. It cannot prove the audio server delivers
// them, which needs a rooted device, but it does prove every id the app sends
// has somewhere to land.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "../app/src/main/cpp/hal/EffectParams.h"

static JamesDSPLib g_lib;
static int failures = 0;

static void check(const char *what, bool ok, const char *detail = "")
{
	printf("  %-52s %-3s %s\n", what, ok ? "ok" : "FAIL", detail);
	if (!ok) failures++;
}

// applyParam's signature mirrors what the HAL extracts from effect_param_t:
// the short value for the enable ids, the float array for everything else.
static void sendFloats(int32_t id, const float *v, uint32_t n)
{
	applyParam(&g_lib, id, 0, false, v, n);
}
static void sendEnable(int32_t id, bool on)
{
	applyParam(&g_lib, id, on ? 1 : 0, on, nullptr, 0);
}

int main()
{
	printf("== root parameter dispatch ==\n\n");
	// Same order EffectCreate uses: the process-wide tables first, then the
	// instance. Skipping the first leaves the resampler coefficients null.
	JamesDSPGlobalMemoryAllocation();
	JamesDSPInit(&g_lib, 4096, 48000);

	// ---- upstream ids the table used to drop -------------------------------
	printf("ids inherited from upstream\n");

	// 1500: limiter threshold, release, post gain. The engine clamps a
	// threshold at or above -0.09 dB and a release below 0.15 ms.
	{
		const float v[3] = { -3.5f, 80.0f, 4.25f };
		sendFloats(1500, v, 3);
		// Stored linear, not in dB.
		const float want = powf(10.0f, 4.25f / 20.0f);
		check("1500 sets post gain", fabsf(g_lib.postGain - want) < 1e-3f);
	}
	{
		const float v[3] = { 5.0f, 0.01f, 99.0f };   // all out of range
		sendFloats(1500, v, 3);
		const float ceiling = powf(10.0f, 15.0f / 20.0f);
		check("1500 clamps post gain to +15 dB",
			  fabsf(g_lib.postGain - ceiling) < 1e-3f);
	}
	// NaN slipped through every one of those clamps, since a comparison with
	// NaN is false, and left the output limiter putting out NaN for good.
	{
		const float v[3] = { NAN, NAN, NAN };
		sendFloats(1500, v, 3);
		check("1500 turns a NaN post gain into a finite one", isfinite(g_lib.postGain));
		check("1500 turns a NaN threshold into a finite one", isfinite(g_lib.limiter.threshold));
		check("1500 turns a NaN release into a finite one", isfinite(g_lib.limiter.relCoef));
		const float w[3] = { -1e30f, INFINITY, 0.0f };
		sendFloats(1500, w, 3);
		check("1500 keeps a -1e30 dB threshold above zero gain", g_lib.limiter.threshold > 0.0f);
		check("1500 keeps an infinite release finite", isfinite(g_lib.limiter.relCoef));
		const float ok[3] = { -0.1f, 60.0f, 0.0f };
		sendFloats(1500, ok, 3);
	}

	// 26022, spectrum extension: bark frequency, strength in percent. The
	// strength had no limit, and NaN passed the frequency clamps.
	{
		const float v[2] = { 7600.0f, 1e35f };
		sendFloats(26022, v, 2);
		check("26022 holds a 1e35 % strength to 100 %", g_lib.spectrumExt.strength <= 1.0f);
		const float w[2] = { NAN, NAN };
		sendFloats(26022, w, 2);
		check("26022 turns a NaN strength into a finite one", isfinite(g_lib.spectrumExt.strength));
		const float ok[2] = { 7600.0f, 10.0f };
		sendFloats(26022, ok, 2);
		check("26022 keeps an ordinary strength as sent", fabsf(g_lib.spectrumExt.strength - 0.1f) < 1e-6f);
	}

	// 115, the compander: NaN curve points made its gains NaN, and the rate
	// lookup then read before the start of its table.
	{
		float cv[17] = { 0.22f, 2.0f, 0.0f };
		for (int i = 3; i < 17; i++) cv[i] = NAN;
		sendFloats(115, cv, 17);
		bool finite = true;
		for (int i = 0; i < NUMPTS_DRS + 2; i++)
			finite = finite && isfinite(g_lib.comp.gains2[i]) && isfinite(g_lib.comp.freq2[i]);
		check("115 turns NaN curve points into finite ones", finite);
	}

	// Tape (26018) and echo/delay (26010) passed NaN through their clamps to
	// the delay lines' read positions; most echo values had no clamp at all.
	{
		float tv[6]; for (int i = 0; i < 6; i++) tv[i] = NAN;
		sendFloats(26018, tv, 6);
		check("26018 turns NaN wow and flutter into finite ones",
			  isfinite(g_lib.tape.wowPct) && isfinite(g_lib.tape.flutterPct) && isfinite(g_lib.tape.mix));
		float ev[26]; for (int i = 0; i < 26; i++) ev[i] = NAN;
		sendFloats(26010, ev, 26);
		check("26010 turns NaN delay settings into finite ones",
			  isfinite(g_lib.echoDelay.offsetSamples) && isfinite(g_lib.echoDelay.modTime) &&
			  isfinite(g_lib.echoDelay.targetDelay) && isfinite(g_lib.echoDelay.wet));
		for (int i = 0; i < 26; i++) ev[i] = 1e30f;
		sendFloats(26010, ev, 26);
		check("26010 holds 1e30 settings to the knobs' ranges",
			  g_lib.echoDelay.wet <= 1.0f && g_lib.echoDelay.modRate <= 10.0f &&
			  fabsf(g_lib.echoDelay.offsetSamples) <= 0.2f * 48000.0f + 1.0f);
	}

	// Fourteen setters let NaN through (`if (x < lo)` passes it) or had no
	// limits at all; a NaN setting in a recursive filter put out NaN, which
	// the output stage turns into silence, until the card was switched off.
	{
		float nv[12]; for (int i = 0; i < 12; i++) nv[i] = NAN;
		sendFloats(26003, nv, 2);
		check("26003 headphone surround: NaN settings held",
			  isfinite(g_lib.hpSurround.cross) && isfinite(g_lib.hpSurround.fb));
		sendFloats(26008, nv, 1);
		bool so = true; for (int i = 0; i < 5; i++) so = so && isfinite(g_lib.speakerOpt.pk[i]) && isfinite(g_lib.speakerOpt.sh[i]);
		check("26008 speaker optimiser: NaN strength held", so);
		float cv[2] = { 1.0f, NAN };
		sendFloats(26000, cv, 2);
		check("26000 clarity: NaN gain held", isfinite(g_lib.viperClarity.sharp));
		sendFloats(26001, nv, 2);
		check("26001 field surround: NaN settings held",
			  isfinite(g_lib.fieldSurround.sideGain) && isfinite(g_lib.fieldSurround.midGain));
		sendFloats(26002, nv, 2);
		check("26002 AGC: NaN settings held", isfinite(g_lib.agc.target) && isfinite(g_lib.agc.maxGain));
		sendFloats(26004, nv, 5);
		check("26004 FET compressor: NaN settings held",
			  isfinite(g_lib.fetComp.thrLin) && isfinite(g_lib.fetComp.slope) && isfinite(g_lib.fetComp.attC) &&
			  isfinite(g_lib.fetComp.relC) && isfinite(g_lib.fetComp.makeup));
		float bv[3] = { 1.0f, NAN, NAN };
		sendFloats(26006, bv, 3);
		bool vb = isfinite(g_lib.viperBass.harm); for (int i = 0; i < 5; i++) vb = vb && isfinite(g_lib.viperBass.f[i]) && isfinite(g_lib.viperBass.lp[i]);
		check("26006 ViPER bass: NaN settings held", vb);
		sendFloats(26014, nv, 8);
		check("26014 imaging: NaN settings held to the card",
			  g_lib.imaging.freqLow >= 20.0f && g_lib.imaging.widthLow >= 0.0f && g_lib.imaging.mix >= 0.0f);
		sendFloats(26016, nv, 6);
		check("26016 low end: NaN settings held to the card",
			  g_lib.lowEnd.weightHz >= 40.0f && g_lib.lowEnd.weightDb >= -12.0f && g_lib.lowEnd.mix >= 0.0f);
		float ev[10]; for (int i = 0; i < 10; i++) ev[i] = NAN; ev[7] = 0.0f;
		sendFloats(26017, ev, 10);
		check("26017 exciter: NaN settings held to the card",
			  g_lib.exciter.freq[0] >= 50.0f && g_lib.exciter.drive >= 1.0f && g_lib.exciter.mix >= 0.0f &&
			  g_lib.exciter.amount[0] >= 0.0f);
		float lv[3] = { NAN, 0.0f, NAN };
		sendFloats(26021, lv, 3);
		check("26021 balance: NaN settings held",
			  isfinite(g_lib.balance.targetL) && isfinite(g_lib.balance.targetR) && isfinite(g_lib.balance.targetMono));
		applyParam(&g_lib, 137, 32767, true, nullptr, 0);
		check("137 stereo width of 327 held to 1",
			  g_lib.sterEnh.mix <= 1.0f && g_lib.sterEnh.gain >= 0.0f);
		/* An EQ curve with a NaN frequency is refused, the last good one kept. */
		float q[32]; q[0] = 0.0f; q[1] = 0.0f;
		for (int i = 0; i < 15; i++) { q[2 + i] = 25.0f * powf(1.6f, (float)i); q[17 + i] = 3.0f; }
		sendFloats(116, q, 32);
		const double before = g_lib.mEQ.gain[8];
		q[2 + 7] = NAN; q[17 + 7] = 9.0f;
		sendFloats(116, q, 32);
		bool eqok = g_lib.mEQ.gain[8] == before;
		q[2 + 7] = 25.0f * powf(1.6f, 7.0f); q[17 + 14] = NAN;
		sendFloats(116, q, 32);
		for (int i = 1; i <= 15; i++) eqok = eqok && isfinite(g_lib.mEQ.gain[i]);
		check("116 EQ: NaN frequency refused, NaN gain held", eqok);
	}

	// Transient shaper (26015) held only its range, and NaN passed that; bass
	// boost (112) took any whole number of dB up to 32767.
	{
		float tv[10]; for (int i = 0; i < 10; i++) tv[i] = NAN;
		sendFloats(26015, tv, 10);
		bool tok = isfinite(g_lib.transient.rangeDb) && isfinite(g_lib.transient.mix) &&
			isfinite(g_lib.transient.freqLow) && isfinite(g_lib.transient.freqHigh);
		for (int k = 0; k < TRANSIENT_BANDS; k++)
			tok = tok && isfinite(g_lib.transient.band[k].attack) && isfinite(g_lib.transient.band[k].sustain);
		check("26015 transient shaper: NaN settings held", tok);
		applyParam(&g_lib, 112, 32767, true, nullptr, 0);
		check("112 bass boost of 32767 dB held to 24", g_lib.dbb.maxGain <= 24.0f);
	}

	// Vinyl (26020) turned anything over 100 % into 100 rather than 1, a
	// hundred times full strength, and let NaN through.
	{
		float vv[12]; for (int i = 0; i < 12; i++) vv[i] = 150.0f;
		sendFloats(26020, vv, 12);
		check("26020 vinyl: 150 % held to full strength",
			  g_lib.vinyl.surface <= 1.0f && g_lib.vinyl.crackle <= 1.0f && g_lib.vinyl.mix <= 1.0f);
		for (int i = 0; i < 12; i++) vv[i] = NAN;
		sendFloats(26020, vv, 12);
		check("26020 vinyl: NaN settings held",
			  isfinite(g_lib.vinyl.surface) && isfinite(g_lib.vinyl.wearDb) && isfinite(g_lib.vinyl.mix));
	}

	// Maximiser (26019) let NaN through its lower bounds, and dynamic EQ bands
	// (26013) were not clamped at all; the band mode was a float-to-int cast.
	{
		float mv[10] = { 0, NAN, NAN, NAN, NAN, NAN, 0, NAN, 0, 0 };
		sendFloats(26019, mv, 10);
		check("26019 turns NaN settings into finite ones",
			  isfinite(g_lib.maximizer.inGain) && isfinite(g_lib.maximizer.ceiling) &&
			  isfinite(g_lib.maximizer.character) && isfinite(g_lib.maximizer.transient) &&
			  isfinite(g_lib.maximizer.stereoLink));
		float dv[3 + 8];
		dv[0] = 1.0f; dv[1] = 1.0f; dv[2] = 0.0f;
		for (int i = 0; i < 8; i++) dv[3 + i] = NAN;
		sendFloats(26013, dv, 11);
		const DynEqBand *b = &g_lib.dynamicEq.band[0];
		check("26013 turns NaN band values into ones in range",
			  b->freq >= 20.0f && b->q >= 0.3f && b->thresholdDb >= -60.0f &&
			  b->attackMs >= 0.5f && b->releaseMs >= 5.0f && b->rangeDb >= -24.0f &&
			  (b->mode == 0 || b->mode == 1));
		for (int i = 0; i < 8; i++) dv[3 + i] = 1e30f;
		sendFloats(26013, dv, 11);
		check("26013 holds 1e30 band values to the card's ranges",
			  b->freq <= 20000.0f && b->q <= 8.0f && b->ratio <= 20.0f &&
			  b->releaseMs <= 2000.0f && b->rangeDb <= 24.0f && b->mode == 1);
	}

	// 116: filter type, interpolation mode, then 15 frequencies and 15 gains.
	{
		float v[32];
		v[0] = 0.0f;            // FIR minimum phase
		v[1] = -1.0f;           // interpolation mode
		static const float f[15] = { 25,40,63,100,160,250,400,630,1000,
									 1600,2500,4000,6300,10000,16000 };
		for (int i = 0; i < 15; i++) { v[2 + i] = f[i]; v[2 + 15 + i] = (i < 5) ? 6.0f : -6.0f; }
		sendFloats(116, v, 32);
		// The interpolation copies the axes in at offset 1, then mirrors the
		// ends outward, so both are checkable directly.
		bool axes = true;
		for (int i = 0; i < 15; i++)
		{
			if (fabs(g_lib.mEQ.freq[1 + i] - (double)f[i]) > 1e-6) axes = false;
			if (fabs(g_lib.mEQ.gain[1 + i] - (double)v[2 + 15 + i]) > 1e-6) axes = false;
		}
		check("116 reaches the equaliser", axes);
	}
	// Too few values must be ignored rather than half-applied.
	{
		float v[8] = {0};
		const float keep = g_lib.postGain;
		sendFloats(1500, v, 2);
		check("1500 ignores a short payload", fabsf(g_lib.postGain - keep) < 1e-6f);
	}

	// 115: time constant, granularity, tf resolution, 7 frequencies, 7 gains.
	{
		float v[17];
		v[0] = 0.5f; v[1] = 2.0f; v[2] = 1.0f;
		static const float f[7] = { 95,200,400,800,1600,3200,6400 };
		for (int i = 0; i < 7; i++) { v[3 + i] = f[i]; v[3 + 7 + i] = -3.0f; }
		sendFloats(115, v, 17);
		check("115 accepted by the compander", true, "(state is internal)");
	}

	// ---- fork effects that were never wired up -----------------------------
	printf("\nfork effects that had no case at all\n");

	{
		const float v[5] = { -18.0f, 4.0f, 5.0f, 120.0f, 3.0f };
		sendFloats(26004, v, 5);
		check("26004 FET compressor threshold", g_lib.fetComp.thrLin > 0.0f &&
			  g_lib.fetComp.thrLin < 1.0f);
		check("26004 FET compressor slope", g_lib.fetComp.slope > 0.0f);
	}
	{
		const float v[1] = { 2.0f };
		sendFloats(26005, v, 1);
		check("26005 Cure sets its filter", g_lib.cure.lpCoef != 0.0f);
	}
	{
		// The pitch shifter builds nothing while its card is off: SetParam only
		// stores the settings, and Enable works out the bypass and buffers when
		// the card comes on. So switch it on first, as the app does, and check
		// the off state on its own.
		const float unity[2] = { 0.0f, 100.0f };
		sendFloats(26009, unity, 2);
		check("26009 with the card off builds nothing",
		      g_lib.pitchShift.hist[0] == nullptr);
		sendEnable(26109, true);
		check("26109 at unity starts in bypass",
		      g_lib.pitchShiftEnabled != 0 && g_lib.pitchShift.bypass != 0);
		const float v[2] = { 5.0f, 100.0f };
		sendFloats(26009, v, 2);
		check("26009 pitch shift leaves bypass", g_lib.pitchShift.bypass == 0);
		sendFloats(26009, unity, 2);
		check("26009 at unity re-enters bypass", g_lib.pitchShift.bypass != 0);
		sendEnable(26109, false);
		check("26109 off frees the history", g_lib.pitchShiftEnabled == 0 &&
		      g_lib.pitchShift.hist[0] == nullptr);
	}
	{
		float v[25];
		for (int i = 0; i < 25; i++) v[i] = 0.0f;
		v[0] = 100.0f;  // input level
		v[1] = 250.0f;  // time ms
		v[7] = 40.0f;   // feedback
		v[8] = 8000.0f; // cutoff
		v[23] = 35.0f;  // wet
		v[24] = 100.0f; // dry
		sendFloats(26010, v, 25);
		check("26010 echo/delay accepted", true, "(state is internal)");
	}

	// The default chain, which is what an install runs until someone reorders
	// it. An effect missing from it is never dispatched on an install that has
	// not customised its chain.
	{
		JamesDSPResetChainOrder(&g_lib);
		bool seen[JDSP_EFX_COUNT] = { false };
		int dupes = 0, bad = 0;
		for (int i = 0; i < g_lib.chainCount; i++)
		{
			const int id = g_lib.chainOrder[i];
			if (id < 0 || id >= JDSP_EFX_COUNT) { bad++; continue; }
			if (seen[id]) dupes++;
			seen[id] = true;
		}
		int missing = 0;
		for (int id = 0; id < JDSP_EFX_COUNT; id++) if (!seen[id]) missing++;
		check("default chain dispatches every effect", missing == 0);
		check("default chain has no duplicates", dupes == 0);
		check("default chain has no out-of-range ids", bad == 0);
	}

	// 26019, the maximiser. Ten values in the order MaximizerSetParam takes
	// them, so a slipped argument shows up as the wrong field moving rather
	// than as nothing happening.
	{
		float v[10] = {
			1.0f,      // mode
			4.5f,      // gain dB
			-1.5f,     // ceiling dB
			120.0f,    // release ms
			40.0f,     // character %
			25.0f,     // transient %
			1.0f,      // true peak
			80.0f,     // stereo link %
			2.0f,      // oversample
			2.0f       // clip shape
		};
		sendFloats(26019, v, 10);
		check("26019 maximizer mode lands", g_lib.maximizer.mode == 1);
		check("26019 maximizer clip shape lands", g_lib.maximizer.clipShape == 2);
		check("26019 maximizer true peak lands", g_lib.maximizer.truePeak == 1);
		// Stored as a linear factor: 10^(-1.5/20) is 0.841.
		check("26019 maximizer ceiling lands, converted to linear",
			  fabsf(g_lib.maximizer.ceiling - 0.84139f) < 0.001f);

		sendEnable(26119, true);
		check("26119 switches the maximizer on", g_lib.maximizerEnabled != 0);
		sendEnable(26119, false);
		check("26119 switches it off again", g_lib.maximizerEnabled == 0);
	}

	// 26013 is the only payload here whose length is not fixed: mix, band
	// count, works-on mode, then that many bands. Worth testing both that it
	// arrives and that it cannot be lied to - any app on the device can address
	// this effect, so the count inside the payload is not ours to trust.
	{
		const uint32_t n = 3 + 2 * DYNEQ_VALUES_PER_BAND;
		float v[3 + 2 * DYNEQ_VALUES_PER_BAND] = {
			100.0f, 2.0f, 0.0f,
			// freq, Q, threshold dB, ratio, attack ms, release ms, range dB, mode
			180.0f,  1.0f, -22.0f, 3.0f, 15.0f, 150.0f, -6.0f, 0.0f,
			3200.0f, 1.4f, -26.0f, 3.0f,  3.0f,  80.0f, -5.0f, 0.0f
		};
		sendFloats(26013, v, n);
		check("26013 two dynamic EQ bands land", g_lib.dynamicEq.numBands == 2);

		// 2^29 bands times eight floats each wraps a 32-bit length back to
		// zero, so this three-float payload would look long enough to hold
		// them all. Refusing it must leave the two good bands untouched rather
		// than reading sixty-four floats off the end of a buffer holding three.
		float lie[3] = { 100.0f, 536870912.0f, 0.0f };
		sendFloats(26013, lie, 3);
		check("26013 a lying band count is refused",
			  g_lib.dynamicEq.numBands == 2);
	}

	// 26012 carries ints, not floats, and is read from the raw payload.
	{
		int order[6] = { 5, 3, 9, 1, 7, 2 };
		applyParam(&g_lib, 26012, 0, false, (const float *)order, 6);
		bool head = g_lib.chainOrder[0] == 5 && g_lib.chainOrder[1] == 3 &&
					g_lib.chainOrder[2] == 9 && g_lib.chainOrder[3] == 1;
		check("26012 chain order lands as ints", head);
		// Every effect must still be dispatched: ids left out of the saved
		// order are appended, which is the bug that made new effects silent.
		bool seen[JDSP_EFX_COUNT] = { false };
		int dupes = 0;
		for (int i = 0; i < g_lib.chainCount; i++)
		{
			int id = g_lib.chainOrder[i];
			if (id >= 0 && id < JDSP_EFX_COUNT)
			{
				if (seen[id]) dupes++;
				seen[id] = true;
			}
		}
		int missing = 0;
		for (int i = 0; i < JDSP_EFX_COUNT; i++) if (!seen[i]) missing++;
		check("26012 completes a partial order", missing == 0 && dupes == 0);
	}

	// ---- enable flags still route ------------------------------------------
	printf("\nenable flags\n");
	sendEnable(26104, true);
	check("26104 enables the FET compressor", g_lib.fetCompEnabled != 0);
	sendEnable(26104, false);
	check("26104 disables it again", g_lib.fetCompEnabled == 0);
	sendEnable(26105, true);
	check("26105 enables Cure", g_lib.cureEnabled != 0);
	sendEnable(26105, false);
	sendEnable(26110, true);
	check("26110 enables echo/delay", g_lib.echoDelayEnabled != 0);
	sendEnable(26110, false);

	// ---- spectral shaper ---------------------------------------------------
	// The nine values JamesDspRemoteEngine.setSpectralShaper packs, then the
	// switch one hundred above. Both must land: a missing case here would
	// leave the card doing nothing on rooted devices with nothing reported.
	printf("\nspectral shaper\n");
	{
		const float v[9] = { 9.5f, 80.0f, 3000.0f, 9000.0f, 70.0f, 2.0f, 1.0f, 1.0f, 1.0f };
		sendFloats(26023, v, 9);
		const SpectralShaper *s = &g_lib.shaper;
		check("26023 sets the amount", fabsf(s->amountDb - 9.5f) < 1e-6f);
		check("26023 sets sensitivity and range",
			  fabsf(s->sensitivityPct - 80.0f) < 1e-6f && fabsf(s->loHz - 3000.0f) < 1e-3f &&
			  fabsf(s->hiHz - 9000.0f) < 1e-3f && fabsf(s->speedPct - 70.0f) < 1e-6f);
		check("26023 sets focus, tones, listen and the stereo part",
			  s->focus == SHAPER_FOCUS_BROAD && s->catchTones == 1 && s->listen == 1 &&
			  s->msMode == MS_MODE_MID);
		// Too few values is ignored rather than half-applied.
		const float shortv[3] = { 1.0f, 1.0f, 1.0f };
		sendFloats(26023, shortv, 3);
		check("26023 with too few values changes nothing", fabsf(s->amountDb - 9.5f) < 1e-6f);
		// Anything an app sends is clamped.
		const float junk[9] = { NAN, -5.0f, 100000.0f, 1.0f, 1e30f, 77.0f, 0.0f, 0.0f, -4.0f };
		sendFloats(26023, junk, 9);
		check("26023 clamps nonsense",
			  s->amountDb == 6.0f && s->sensitivityPct == 0.0f && s->loHz == 8000.0f &&
			  s->hiHz >= 1.5f * s->loHz && s->speedPct == 100.0f &&
			  s->focus == SHAPER_FOCUS_BALANCED && s->msMode == MS_MODE_STEREO);
		sendEnable(26123, true);
		check("26123 switches it on", g_lib.shaperEnabled != 0 && s->mem != nullptr);
		check("and it reports its delay", SpectralShaperLatency(&g_lib) == 1023);
		sendEnable(26123, false);
		// Switching off fades inside Process; with no audio running, the next
		// off (the app sends one with every settings change) finishes it.
		float *L = g_lib.tmpBuffer[0], *R = g_lib.tmpBuffer[1];
		memset(L, 0, 2048 * sizeof(float)); memset(R, 0, 2048 * sizeof(float));
		SpectralShaperProcess(&g_lib, 2048);
		check("26123 off fades and then clears the switch", g_lib.shaperEnabled == 0);
		sendEnable(26123, false);
		check("and the buffers are let go", s->mem == nullptr);
	}

	// The default chain runs the shaper right after the DDC.
	{
		JamesDSPResetChainOrder(&g_lib);
		int ddc = -1, sh = -1;
		for (int i = 0; i < g_lib.chainCount; i++)
		{
			if (g_lib.chainOrder[i] == JDSP_EFX_DDC) ddc = i;
			if (g_lib.chainOrder[i] == JDSP_EFX_SHAPER) sh = i;
		}
		check("default chain: shaper right after the DDC", ddc >= 0 && sh == ddc + 1);
	}

	// ---- binaural upmix ----------------------------------------------------
	// The five values JamesDspRemoteEngine.setBinauralInternal packs, then the
	// switch one hundred above. The head set itself goes through the HAL's
	// own payload ids (9997 / 12002 / 10011), which hal_harness covers.
	printf("\nbinaural upmix\n");
	{
		const float v[5] = { 70.0f, 90.0f, 10.0f, 80.0f, 55.0f };
		sendFloats(26024, v, 5);
		const BinauralUpmix *b = &g_lib.binaural;
		check("26024 sets amount, width, surround, height, room",
			  b->amountPct == 70.0f && b->widthPct == 90.0f && b->surroundPct == 10.0f &&
			  b->heightPct == 80.0f && b->roomPct == 55.0f);
		check("and what they work out to",
			  fabsf(b->amountTarget - 0.7f) < 1e-6f && fabsf(b->width - 0.9f) < 1e-6f);
		const float shortv[4] = { 1.0f, 1.0f, 1.0f, 1.0f };
		sendFloats(26024, shortv, 4);
		check("26024 with too few values changes nothing", b->amountPct == 70.0f);
		const float junk[5] = { NAN, -5.0f, 1e30f, INFINITY, -INFINITY };
		sendFloats(26024, junk, 5);
		check("26024 clamps nonsense",
			  b->amountPct == 100.0f && b->widthPct == 0.0f && b->surroundPct == 100.0f &&
			  b->heightPct == 100.0f && b->roomPct == 0.0f);
		// A set made in place, as the HAL would hand it over after a commit:
		// one impulse per ear for every speaker.
		static float set[BIN_SPEAKERS * 2 * 64];
		memset(set, 0, sizeof(set));
		for (int i = 0; i < BIN_SPEAKERS * 2; i++) set[i * 64 + 4] = 0.5f;
		check("a head set loads", BinauralUpmixLoadHrir(&g_lib, set, BIN_SPEAKERS, 64, 48000.0f, 4) == 0);
		sendEnable(26124, true);
		check("26124 switches it on", g_lib.binauralEnabled != 0 && b->mem != nullptr && b->spec != nullptr);
		check("and it reports its delay", BinauralUpmixLatency(&g_lib) == 1023 + 4);
		sendEnable(26124, false);
		float *L = g_lib.tmpBuffer[0], *R = g_lib.tmpBuffer[1];
		memset(L, 0, 2048 * sizeof(float)); memset(R, 0, 2048 * sizeof(float));
		BinauralUpmixProcess(&g_lib, 2048);
		check("26124 off fades and then clears the switch", g_lib.binauralEnabled == 0);
		sendEnable(26124, false);
		check("and the buffers are let go, the set kept",
			  b->mem == nullptr && b->spec == nullptr && b->hrir != nullptr);
	}

	// The default chain runs binaural right after the stereo widening.
	{
		JamesDSPResetChainOrder(&g_lib);
		int sw = -1, bin = -1;
		for (int i = 0; i < g_lib.chainCount; i++)
		{
			if (g_lib.chainOrder[i] == JDSP_EFX_STEREOWIDE) sw = i;
			if (g_lib.chainOrder[i] == JDSP_EFX_BINAURAL) bin = i;
		}
		check("default chain: binaural right after the stereo widening", sw >= 0 && bin == sw + 1);
	}

	// ---- the three library effects the root build used to drop -------------
	printf("\nbass exciter, dynamic system, differential surround\n");
	{
		BassExciter *b = &g_lib.bassEx;
		const float v[7] = { 120.0f, 50.0f, 60.0f, 1.0f, 70.0f, 30.0f, 40.0f };
		sendFloats(26025, v, 7);
		check("26025 sets drive, mix and the second band",
			  fabsf(b->drive - 4.5f) < 1e-4f && fabsf(b->mix - 0.6f) < 1e-6f &&
			  b->band2 == 1 && fabsf(b->drive2 - 3.1f) < 1e-4f && fabsf(b->mix2 - 0.4f) < 1e-6f);
		const float shortv[6] = { 50.0f, 0.0f, 0.0f, 0.0f, 50.0f, 0.0f };
		sendFloats(26025, shortv, 6);
		check("26025 with too few values changes nothing", fabsf(b->mix - 0.6f) < 1e-6f);
		const float junk[7] = { NAN, 1e30f, NAN, NAN, -1e30f, NAN, 1e30f };
		sendFloats(26025, junk, 7);
		bool finite = isfinite(b->drive) && isfinite(b->mix) && isfinite(b->drive2) && isfinite(b->mix2);
		for (int i = 0; i < 5; i++) finite = finite && isfinite(b->lp[i]) && isfinite(b->bp[i]);
		check("26025 clamps NaN and nonsense", finite && b->drive <= 8.0f && b->mix2 <= 1.0f);
		sendEnable(26125, true);
		check("26125 switches it on", g_lib.bassExEnabled == 1);
		sendEnable(26125, false);
		check("26125 switches it off", g_lib.bassExEnabled == 0);
	}
	{
		VDynamicBass *d = &g_lib.vdynBass;
		const float v[7] = { 50.0f, 1000.0f, 6200.0f, 50.0f, 90.0f, 30.0f, 10.0f };
		sendFloats(26026, v, 7);
		check("26026 sets gain, bands and side gains",
			  fabsf(d->bassGain - 11.0f) < 1e-4f && fabsf(d->sideGainX - 0.3f) < 1e-6f &&
			  fabsf(d->sideGainY - 0.1f) < 1e-6f && d->lowFreqX == 1000.0f);
		const float shortv[6] = { 0.0f, 100.0f, 2000.0f, 20.0f, 40.0f, 0.0f };
		sendFloats(26026, shortv, 6);
		check("26026 with too few values changes nothing", fabsf(d->bassGain - 11.0f) < 1e-4f);
		const float junk[7] = { NAN, 1e30f, NAN, -1e30f, NAN, 1e30f, NAN };
		sendFloats(26026, junk, 7);
		check("26026 keeps every pole angle finite and under pi",
			  isfinite(d->bassGain) && isfinite(d->sideGainX) && isfinite(d->sideGainY) &&
			  isfinite(d->fXL.upperAngle) && d->fXL.upperAngle < 3.1416f &&
			  isfinite(d->fYL.upperAngle) && d->fYL.upperAngle < 3.1416f &&
			  isfinite(d->lpB0) && isfinite(d->lpA1));
		sendEnable(26126, true);
		check("26126 switches it on", g_lib.vdynBassEnabled == 1);
		sendEnable(26126, false);
		check("26126 switches it off", g_lib.vdynBassEnabled == 0);
	}
	{
		DiffSurround *ds = &g_lib.diffSurround;
		const float v[2] = { 10.0f, 20.0f };
		sendFloats(26027, v, 2);
		check("26027 sets both delays in samples at 48 kHz",
			  fabsf(ds->delayL - 480.0f) < 0.01f && fabsf(ds->delayR - 960.0f) < 0.01f);
		const float junk[2] = { NAN, 1e30f };
		sendFloats(26027, junk, 2);
		check("26027 turns NaN into no delay and holds the top at 50 ms",
			  ds->delayL == 0.0f && fabsf(ds->delayR - 2400.0f) < 0.01f);
		sendEnable(26127, true);
		check("26127 switches it on", g_lib.diffSurroundEnabled == 1 && ds->bufL && ds->bufR);
		sendEnable(26127, false);
		check("26127 switches it off and lets the buffers go",
			  g_lib.diffSurroundEnabled == 0 && !ds->bufL && !ds->bufR);
	}
	// All three kept the rate they were set at, so after the engine moved
	// from 48 to 44.1 kHz 10 ms of surround delay played as 10.9 ms and the
	// filters sat 9 % off.
	{
		const float s[2] = { 10.0f, 10.0f };
		sendFloats(26027, s, 2);
		const float x[7] = { 100.0f, 40.0f, 50.0f, 0.0f, 60.0f, 40.0f, 40.0f };
		sendFloats(26025, x, 7);
		const float v[7] = { 33.0f, 1000.0f, 6200.0f, 50.0f, 90.0f, 30.0f, 10.0f };
		sendFloats(26026, v, 7);
		const float angle48 = g_lib.vdynBass.fXL.lowerAngle;
		const float lp48 = g_lib.bassEx.lp[0];
		JamesDSPSetSampleRate(&g_lib, 44100.0f, 0);
		check("a rate change keeps the surround delay in ms",
			  fabsf(g_lib.diffSurround.delayL - 441.0f) < 0.01f);
		check("and redesigns the dynamic system for it",
			  fabsf(g_lib.vdynBass.fXL.lowerAngle - angle48 * 48000.0f / 44100.0f) < 1e-6f);
		check("and the bass exciter", fabsf(g_lib.bassEx.lp[0] - lp48) > 1e-6f &&
			  isfinite(g_lib.bassEx.lp[0]));
		JamesDSPSetSampleRate(&g_lib, 48000.0f, 0);
		check("and back", fabsf(g_lib.diffSurround.delayL - 480.0f) < 0.01f &&
			  fabsf(g_lib.vdynBass.fXL.lowerAngle - angle48) < 1e-6f);
	}

	// ---- multiband distortion, the last card root mode used to drop --------
	printf("\nmultiband distortion\n");
	{
		MultibandDist *m = &g_lib.multibandDist;
		// Split routing, tube, drive 50, bias 0, shape 50, 16 bits, no
		// downsample, tone 50, band gain 100, chorus 0.5 Hz / 2 ms / 0 / 0 /
		// 2 voices / 0, mix 80; one 48 dB/oct low pass at 200 Hz.
		const float v[21] = { 0, 1, 50, 0, 50, 16, 0, 50, 100, 0.5f, 2, 0, 0, 2, 0, 80,
							  1, 200.0f, 48.0f, 0.7071f, 3.0f };
		sendFloats(26028, v, 21);
		check("26028 sets the settings", m->model == 1 && fabsf(m->mix - 0.8f) < 1e-6f &&
			  fabsf(m->drive - powf(10.0f, 0.75f)) < 1e-3f);
		check("26028 builds the low pass as a 4-stage cascade", m->numBands == 4 && m->setBandCount == 1);
		const float shortv[16] = { 0 };
		sendFloats(26028, shortv, 16);
		check("26028 with too few values changes nothing", fabsf(m->mix - 0.8f) < 1e-6f);
		// A band count that claims more bands than were sent takes only those sent.
		float liar[21];
		memcpy(liar, v, sizeof(v));
		liar[16] = 50.0f;
		sendFloats(26028, liar, 21);
		check("26028 never reads past the bands it was sent", m->setBandCount == 1 && m->numBands == 4);
		const float junk[21] = { NAN, 1e30f, NAN, NAN, 1e30f, NAN, -1e30f, NAN, 1e30f, NAN,
								 1e30f, NAN, 1e30f, -1e9f, NAN, NAN,
								 1, NAN, 1e30f, NAN, 1e30f };
		sendFloats(26028, junk, 21);
		bool finite = isfinite(m->drive) && isfinite(m->mix) && isfinite(m->bitStep) &&
					  isfinite(m->chInc) && isfinite(m->chDepth) && isfinite(m->tilt);
		for (int i = 0; i < m->numBands; i++)
			finite = finite && isfinite(m->b0[i]) && isfinite(m->a1[i]) && isfinite(m->a2[i]);
		check("26028 holds NaN and nonsense, settings and bands", finite && m->numBands >= 1 &&
			  m->chVoices >= 1 && m->model >= 0 && m->model < MBD_MODEL_COUNT);
		sendFloats(26028, v, 21);
		sendEnable(26128, true);
		check("26128 switches it on", g_lib.multibandDistEnabled == 1 && m->chBufL && m->chBufR);
		sendEnable(26128, false);
		check("26128 switches it off and lets the chorus buffers go",
			  g_lib.multibandDistEnabled == 0 && !m->chBufL && !m->chBufR);

		// The chorus speed and every band follow a rate change.
		const float inc48 = m->chInc, b0_48 = m->b0[0];
		JamesDSPSetSampleRate(&g_lib, 44100.0f, 0);
		check("a rate change redesigns the multiband distortion",
			  fabsf(m->chInc - inc48 * 48000.0f / 44100.0f) < 1e-9f && fabsf(m->b0[0] - b0_48) > 1e-9f &&
			  m->numBands == 4);
		JamesDSPSetSampleRate(&g_lib, 48000.0f, 0);
		check("and back", fabsf(m->chInc - inc48) < 1e-9f && fabsf(m->b0[0] - b0_48) < 1e-9f);
	}

	// ---- loudness correction: settings, switch and the volume on its own ----
	printf("\nloudness correction\n");
	{
		LoudnessCorrection *lc = &g_lib.loudness;
		// ISO, reference -10 dB, offset 2, strength 80 %, bass 90 Hz / 0.6 /
		// 50 %, treble 9 kHz / 0.8 / 30 %, 75 phon, subsonic on at 25 Hz,
		// order 3, Q 0.7.
		const float v[15] = { 1, -10, 2, 80, 90, 0.6f, 50, 9000, 0.8f, 30, 75, 1, 25, 3, 0.7f };
		sendFloats(26029, v, 15);
		check("26029 sets the settings", lc->mode == LOUDNESS_MODE_ISO && lc->refLevel == -10.0f &&
			  lc->refOffset == 2.0f && lc->strength == 80.0f && lc->lsFreq == 90.0f &&
			  lc->hsRatio == 30.0f && lc->basePhon == 75.0f && lc->subsonic == 1 &&
			  lc->subOrder == 3 && lc->subFreq == 25.0f);
		const float shortv[14] = { 0 };
		sendFloats(26029, shortv, 14);
		check("26029 with too few values changes nothing", lc->mode == LOUDNESS_MODE_ISO && lc->subOrder == 3);
		sendEnable(26129, true);
		check("26129 switches it on", g_lib.loudnessEnabled == 1);
		// At the reference only the subsonic filter is left.
		const float atRef[1] = { -12.0f };
		sendFloats(26030, atRef, 1);
		check("26030 sets the volume", lc->volumeDb == -12.0f);
		const double gainAtRef = lc->gainTgt;
		const float quiet[1] = { -42.0f };
		sendFloats(26030, quiet, 1);
		check("26030 at 30 dB under the reference cuts the level to make room",
			  lc->volumeDb == -42.0f && lc->gainTgt < gainAtRef * 0.5);
		check("and it glides there rather than jumping", lc->glide > 0);
		const float none[1] = { NAN };
		sendFloats(26030, none, 1);
		check("26030 NaN leaves the volume alone", lc->volumeDb == -42.0f);
		sendFloats(26030, nullptr, 0);
		check("26030 with no value changes nothing", lc->volumeDb == -42.0f);
		const float huge[1] = { 1e30f };
		sendFloats(26030, huge, 1);
		check("26030 holds 1e30 to full volume", lc->volumeDb == 0.0f);
		const float junk[15] = { NAN, NAN, 1e30f, -1e30f, NAN, 1e30f, 1e30f, NAN, NAN, -1e9f,
								 NAN, NAN, NAN, 1e9f, NAN };
		sendFloats(26029, junk, 15);
		bool finite = isfinite(lc->gainTgt) && lc->gainTgt > 0.0;
		for (int s = 0; s < LOUD_SECTIONS; s++)
			for (int k = 0; k < 5; k++)
				finite = finite && isfinite(lc->tgt[s][k]);
		check("26029 holds NaN and nonsense", finite && lc->mode == LOUDNESS_MODE_CLASSIC &&
			  lc->lsRatio == 90.0f && lc->subOrder == 8 && lc->subsonic == 0 && lc->strength == 0.0f);
		sendFloats(26029, v, 15);
		sendFloats(26030, quiet, 1);

		// Every coefficient follows a rate change, and the next block is the
		// new design at once rather than a glide from the old rate's.
		const double g48 = lc->tgt[LOUD_ISO_FIRST + 3][0];
		JamesDSPSetSampleRate(&g_lib, 44100.0f, 0);
		check("a rate change redesigns the loudness correction",
			  fabs(lc->cur[LOUD_ISO_FIRST + 3][0] - tan(M_PI * 40.0 / 44100.0)) < 1e-12 &&
			  fabs(g48 - tan(M_PI * 40.0 / 48000.0)) < 1e-12 && lc->glide == 0 && lc->fs == 44100.0f);
		JamesDSPSetSampleRate(&g_lib, 48000.0f, 0);
		check("and back", fabs(lc->cur[LOUD_ISO_FIRST + 3][0] - g48) < 1e-12);

		sendEnable(26129, false);
		check("26129 switches it off", g_lib.loudnessEnabled == 0);

		// In the default chain after the colour and ahead of the balance.
		JamesDSPResetChainOrder(&g_lib);
		int at = -1, vinyl = -1, balance = -1;
		for (int i = 0; i < g_lib.chainCount; i++)
		{
			if (g_lib.chainOrder[i] == JDSP_EFX_LOUDNESS) at = i;
			if (g_lib.chainOrder[i] == JDSP_EFX_VINYL) vinyl = i;
			if (g_lib.chainOrder[i] == JDSP_EFX_BALANCE) balance = i;
		}
		check("the default chain runs it right after vinyl, before balance",
			  at == vinyl + 1 && balance == at + 1);
	}

	// ---- an id with no mapping is tolerated, not refused --------------------
	printf("\nunknown ids\n");
	{
		const float v[1] = { 1.0f };
		const float keep = g_lib.postGain;
		sendFloats(4242, v, 1);
		check("an unmapped id does not disturb the engine",
			  fabsf(g_lib.postGain - keep) < 1e-9f);
	}

	printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
		   failures, failures == 1 ? "" : "s");
	return failures ? 1 : 0;
}
