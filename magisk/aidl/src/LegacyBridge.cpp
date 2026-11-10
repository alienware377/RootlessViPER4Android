/*
 * See LegacyBridge.h. Everything here is a thin call into the legacy effect's
 * own interface, the same calls AudioFlinger makes on a legacy device.
 */
#include <errno.h>
#include <string.h>
#include <hardware/audio_effect.h>
#include <android/log.h>

#include "LegacyBridge.h"

#define LOG_TAG "RV4A-AIDL"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
/* Defined in hal/JamesDspHalEffect.cpp, compiled into this same library.
   The version script keeps them out of the dynamic symbol table, so they
   cannot collide with a legacy libjamesdsp.so in the same process. */
int32_t EffectCreate(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId,
                     effect_handle_t *pHandle);
int32_t EffectRelease(effect_handle_t handle);
int32_t EffectGetDescriptor(const effect_uuid_t *uuid, effect_descriptor_t *pDescriptor);
}

/* The implementation uuid, the same one the legacy descriptor carries. */
static const effect_uuid_t kRv4aUuid =
    { 0xf27317f4, 0xc984, 0x4de6, 0x9a90, { 0x54, 0x57, 0x59, 0x49, 0x5b, 0xf2 } };

static int32_t cmd(void *h, uint32_t code, uint32_t size, void *data,
                   uint32_t *replySize, void *reply)
{
    effect_handle_t e = (effect_handle_t)h;
    if (!e || !*e || !(*e)->command)
        return -EINVAL;
    return (*e)->command(e, code, size, data, replySize, reply);
}

/* A command whose reply is a single int status. Both the call and the status
   have to succeed. */
static int32_t cmdStatus(void *h, uint32_t code, uint32_t size, void *data)
{
    int32_t status = 0;
    uint32_t replySize = sizeof(status);
    const int32_t rc = cmd(h, code, size, data, &replySize, &status);
    if (rc != 0)
        return rc;
    return status;
}

extern "C" {

void *rv4a_legacy_create(void)
{
    effect_handle_t h = nullptr;
    const int32_t rc = EffectCreate(&kRv4aUuid, 0, 0, &h);
    if (rc != 0 || !h)
    {
        LOGE("legacy EffectCreate failed (%d)", rc);
        return nullptr;
    }
    const int32_t init = cmdStatus(h, EFFECT_CMD_INIT, 0, nullptr);
    if (init != 0)
    {
        LOGE("legacy INIT failed (%d)", init);
        EffectRelease(h);
        return nullptr;
    }
    return h;
}

void rv4a_legacy_release(void *h)
{
    if (h)
        EffectRelease((effect_handle_t)h);
}

int32_t rv4a_legacy_configure(void *h, uint32_t sampleRate)
{
    effect_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.inputCfg.samplingRate = cfg.outputCfg.samplingRate = sampleRate;
    cfg.inputCfg.channels = cfg.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    cfg.inputCfg.format = cfg.outputCfg.format = AUDIO_FORMAT_PCM_FLOAT;
    cfg.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    /* An AIDL effect always writes its output queue; there is no accumulate
       mode on this path. */
    cfg.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    cfg.inputCfg.mask = cfg.outputCfg.mask = EFFECT_CONFIG_ALL;
    return cmdStatus(h, EFFECT_CMD_SET_CONFIG, sizeof(cfg), &cfg);
}

int32_t rv4a_legacy_enable(void *h, bool on)
{
    return cmdStatus(h, on ? EFFECT_CMD_ENABLE : EFFECT_CMD_DISABLE, 0, nullptr);
}

int32_t rv4a_legacy_set_param(void *h, const void *param, uint32_t size)
{
    /* The legacy command takes a non-const pointer but never writes through
       it. The caller passes its own copy of the bytes it received, so even a
       write would not reach anything the framework holds. */
    if (!param || size < sizeof(effect_param_t))
        return -EINVAL;
    return cmdStatus(h, EFFECT_CMD_SET_PARAM, size, const_cast<void *>(param));
}

int32_t rv4a_legacy_get_param(void *h, const void *param, uint32_t size,
                              void *reply, uint32_t *replySize)
{
    if (!param || size < sizeof(effect_param_t) || !reply || !replySize)
        return -EINVAL;
    return cmd(h, EFFECT_CMD_GET_PARAM, size, const_cast<void *>(param), replySize, reply);
}

int32_t rv4a_legacy_process(void *h, float *in, float *out, size_t frames)
{
    effect_handle_t e = (effect_handle_t)h;
    if (!e || !*e || !(*e)->process)
        return -EINVAL;
    audio_buffer_t ib, ob;
    ib.frameCount = frames;
    ib.f32 = in;
    ob.frameCount = frames;
    ob.f32 = out;
    return (*e)->process(e, &ib, &ob);
}

const char *rv4a_legacy_name(void)
{
    static char name[EFFECT_STRING_LEN_MAX];
    static bool done = false;
    if (!done)
    {
        effect_descriptor_t d;
        memset(&d, 0, sizeof(d));
        if (EffectGetDescriptor(&kRv4aUuid, &d) == 0 && d.name[0])
            memcpy(name, d.name, sizeof(name));
        else
            strcpy(name, "RootfulViPER4Android engine");
        name[sizeof(name) - 1] = 0;
        done = true;
    }
    return name;
}

size_t rv4a_legacy_param_header_size(void)
{
    return sizeof(effect_param_t);
}

}
