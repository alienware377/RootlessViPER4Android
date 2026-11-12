/*
 * The rooted engine's legacy effect, seen from the AIDL plugin.
 *
 * The AIDL plugin does not reimplement anything the legacy effect does: it
 * creates one through the same EffectCreate the audio server would call, and
 * forwards commands and audio to it. Every root mode fix lives in
 * app/src/main/cpp/hal/JamesDspHalEffect.cpp and reaches both paths.
 *
 * This header keeps the legacy audio headers (hardware/audio_effect.h and its
 * system/audio.h) out of the AIDL translation unit, where their C macros and
 * the generated interface types would otherwise share a namespace.
 */
#pragma once
#include <stddef.h>
#include <stdint.h>

extern "C" {

/* Creates and initialises a legacy effect instance. NULL if it could not be
   made; the reason is logged. */
void *rv4a_legacy_create(void);
void rv4a_legacy_release(void *h);

/* SET_CONFIG for interleaved float stereo at this rate, written in place.
   Returns 0 on success, a negative errno otherwise. */
int32_t rv4a_legacy_configure(void *h, uint32_t sampleRate);

/* ENABLE or DISABLE. */
int32_t rv4a_legacy_enable(void *h, bool on);

/* EFFECT_CMD_SET_PARAM with a whole effect_param_t, as the framework packs it.
   Returns 0 only when the command was accepted and its reply status was 0. */
int32_t rv4a_legacy_set_param(void *h, const void *param, uint32_t size);

/* EFFECT_CMD_GET_PARAM. On entry *replySize is the capacity of reply; on
   success it holds the size of the effect_param_t written there. */
int32_t rv4a_legacy_get_param(void *h, const void *param, uint32_t size,
                              void *reply, uint32_t *replySize);

/* Interleaved float stereo, frames pairs. in and out may be the same buffer.
   Returns 0 when it processed, -ENODATA when the effect is not active, or a
   negative errno when it refused the buffer. */
int32_t rv4a_legacy_process(void *h, float *in, float *out, size_t frames);

/* The legacy descriptor's name, e.g. "RootfulViPER4Android engine v6". The
   app reads the engine version from it, so the plugin reports the same name
   and both paths can never disagree about which engine they carry. Never
   NULL. */
const char *rv4a_legacy_name(void);

/* The header size of effect_param_t, so the AIDL side can bound-check a
   payload before handing it over. */
size_t rv4a_legacy_param_header_size(void);

}
