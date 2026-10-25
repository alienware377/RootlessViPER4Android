/*
 * System-wide audio effect wrapper around this fork's DSP engine.
 *
 * Root mode drives a system effect through Android's AudioEffect API rather
 * than the engine bundled in the APK, which is why the fork's own effects do
 * nothing there when the stock JamesDSP module is installed. This library
 * exposes the same descriptor the app looks for, so a Magisk module shipping
 * it makes root mode run *this* engine - extra reverb rooms, the ViPER classic
 * limiter, the rebuilt delay and the retuned speaker optimisation included.
 *
 * The descriptor UUID matches EFFECT_JAMESDSP in JamesDspRemoteEngine, and the
 * type UUID matches EFFECT_TYPE_CUSTOM, so no app-side change is needed.
 */
#include <string.h>
#include <stdlib.h>
#include <hardware/audio_effect.h>
#include <unistd.h>
#include <pthread.h>

#include "EffectParams.h"

#include <android/log.h>

#define LOG_TAG "RV4A-HAL"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* Buffers the audio server hands us vary by device and route; the engine sizes
   its internals from the block size given at init, so process in chunks no
   larger than this rather than trusting whatever arrives. */
#define RV4A_BLOCK 4096

extern "C" {
#include "jdsp_header.h"
// Defined in jdspController.c but not exposed by the header, so declare it here
// rather than editing the vendored engine.
void JamesDSPProcess(JamesDSPLib *jdsp, size_t n);
}

static effect_descriptor_t rv4a_descriptor =
{
    { 0xf98765f4, 0xc321, 0x5de6, 0x9a45, { 0x12, 0x34, 0x59, 0x49, 0x5a, 0xb2 } },
    { 0xf27317f4, 0xc984, 0x4de6, 0x9a90, { 0x54, 0x57, 0x59, 0x49, 0x5b, 0xf2 } },
    EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_FIRST,
    10,
    1,
    /* The app matches the engine by uuid, and stock JamesDSP uses the same
       one, so the only thing that can tell the two apart is this string. It
       said nothing about which engine or which version, which is why a device
       running the stock module showed every fork card and responded to none
       of them: unhandled ids are dropped without a word at both ends. */
    "RootlessViPER4Android engine v4",
    "alienware377"
};

struct rv4a_context
{
    const struct effect_interface_s *itfe;
    JamesDSPLib dsp;
    bool active;
    bool configured;
    uint32_t sampleRate;
    uint32_t channels;
    uint8_t outAccessMode;   /* write or accumulate */
    uint64_t blocks;         /* processed block counter, for log throttling */

    /* The app writes a checksum of each large payload it pushes (DDC,
       convolver, GraphicEQ, liveprog) and reads it back before deciding to
       push again. Storing them here is what makes that skip work. */
    int32_t hashSlot[4];
    uint32_t paramCommits;

    /* Payloads too big for one call arrive as a geometry header, then a run of
       fixed-size partitions, then a commit id. */
    char *stringBuf;
    size_t stringCapacity;
    int stringIndex;

    float *irBuf;
    size_t irCapacity;      /* in floats */
    int irPartsSeen, irParts;
    int irChannels, irFrames;

    /* Whether anything was ever loaded behind each subsystem. Enabling one of
       these with an empty engine ranges from silent to a crash in the audio
       server, so the enable is refused until its payload has arrived. */
    bool haveIr, haveGraphicEq, haveDdc, haveLiveprog;

    /* Only used when the server asks us to add to its buffer rather than
       overwrite it. Per context, never shared: a buffer shared between effects
       on one mixer thread is freed by whichever is released first and then
       written through by the others. */
    float *mixBuf;
    size_t mixFrames;
};

/* A frame count larger than this is a corrupt descriptor rather than a real
   buffer, and the engine would size an allocation from it on the audio thread.
   Real mixer buffers are a few hundred frames. */
#define RV4A_MAX_FRAMES 65536

/* Grown on demand and owned by the context. Returns false if it could not be
   made big enough, in which case the caller must not touch it. */
static bool rv4a_ensure_mix(rv4a_context *c, size_t frames)
{
    if (c->mixBuf && c->mixFrames >= frames)
        return true;
    float *grown = (float *)realloc(c->mixBuf, frames * 2 * sizeof(float));
    if (!grown)
    {
        LOGE("process: out of memory for the accumulate buffer (%zu frames)", frames);
        return false;
    }
    c->mixBuf = grown;
    c->mixFrames = frames;
    return true;
}

/* An effect_param_t whose parameter and value are both one 32-bit word. Every
   status read the app performs has this shape. */
struct rv4a_reply_1x4_1x4
{
    int32_t status;
    uint32_t psize;
    uint32_t vsize;
    int32_t cmd;
    int32_t data;
};

static int32_t rv4a_process(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out)
{
    rv4a_context *c = reinterpret_cast<rv4a_context *>(self);
    if (!c || !in || !out || !in->raw || !out->raw)
    {
        LOGE("process: null buffer (self=%p in=%p out=%p)", self, in, out);
        return -EINVAL;
    }
    if (!c->active)
        return -ENODATA;              /* convention: nothing written */
    if (!c->configured)
    {
        LOGW("process: called before SET_CONFIG, passing through");
        return -ENODATA;
    }

    /* Never read or write past the shorter of the two buffers. */
    size_t frames = in->frameCount < out->frameCount ? in->frameCount : out->frameCount;
    if (frames == 0)
        return 0;

    if (frames > RV4A_MAX_FRAMES)
    {
        LOGE("process: implausible frame count %zu, refusing", frames);
        return -EINVAL;
    }
    if (!c->dsp.processFloatMultiplexd)
    {
        LOGE("process: engine has no process function; not initialised");
        return -ENODATA;
    }

    /* The engine owns tmpBuffer, and owns it as ONE allocation: tmpBuffer[1]
       through [5] are interior pointers into the block tmpBuffer[0] holds, and
       JamesDSPFree frees exactly that pointer. Writing our own buffers into [0]
       and [1] therefore leaked the engine's block and handed its free() a
       pointer we still meant to use, so releasing one effect left every other
       effect on the same mixer thread writing through freed memory, and two of
       them freed it twice. It fired on ordinary route changes.

       processFloatMultiplexd is the engine's own interleaved entry point, the
       one the app has always used. It deinterleaves into its own buffers, so
       nothing here touches tmpBuffer at all. It also runs the resampler, which
       the old path skipped: anything that was not 44.1 or 48 kHz, a USB DAC
       most likely, was being filtered at the wrong rate. */
    const bool accumulate = (c->outAccessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE);
    float *dst = out->f32;
    if (accumulate)
    {
        if (!rv4a_ensure_mix(c, frames))
            return -ENOMEM;
        dst = c->mixBuf;
    }

    c->dsp.processFloatMultiplexd(&c->dsp, in->f32, dst, frames);

    if (accumulate)
        for (size_t i = 0; i < frames * 2; i++)
            out->f32[i] += dst[i];

    /* One line every few thousand blocks: enough to confirm audio is flowing
       without flooding logcat on the audio thread. */
    if ((c->blocks++ % 2000) == 0)
        LOGD("process: alive, %zu frames, rate %u, %s", frames, c->sampleRate,
             accumulate ? "accumulate" : "write");
    return 0;
}

/*
 * The parts of a large payload, and the commit that installs it.
 *
 * The app cannot hand a whole impulse response or DDC file to an effect in one
 * call, so it sends a geometry header first (how many partitions, how big),
 * then the partitions one at a time, then a commit id. Sizes and ids are the
 * engine's own convention, mirrored from jamesdsp.c so a payload written by
 * this app is read the same way by either engine.
 *
 * @return true if the id belonged to this protocol and was consumed
 */
static bool handleBufferedPayload(rv4a_context *c, int32_t id,
                                  const void *val, uint32_t vsize)
{
    switch (id)
    {
    case 8888:      /* how much text is coming */
    {
        if (vsize < 8) return true;
        const int32_t *v = (const int32_t *)val;
        const long long parts = v[0], per = v[1];
        free(c->stringBuf);
        c->stringBuf = nullptr;
        c->stringCapacity = 0;
        c->stringIndex = 0;
        /* Sanity-bound it: the geometry comes from another process, and a
           bogus product here would be a wild allocation followed by a wild
           write in the partition handler below. */
        if (parts <= 0 || per <= 0 || parts * per > (16 << 20))
        {
            LOGE("string payload geometry rejected (%lld x %lld)", parts, per);
            return true;
        }
        c->stringCapacity = (size_t)(parts * per);
        c->stringBuf = (char *)calloc(c->stringCapacity + 1, sizeof(char));
        if (!c->stringBuf) c->stringCapacity = 0;
        return true;
    }
    case 12001:     /* one 256-byte slice of it */
    {
        if (!c->stringBuf || vsize < 256) return true;
        const size_t offset = (size_t)c->stringIndex * 256;
        if (offset + 256 > c->stringCapacity)
        {
            LOGE("string partition %d past the end", c->stringIndex);
            return true;
        }
        memcpy(c->stringBuf + offset, val, 256);
        c->stringIndex++;
        return true;
    }
    case 9999:      /* how much impulse response is coming */
    {
        if (vsize < 16) return true;
        const int32_t *v = (const int32_t *)val;
        const int channels = v[1];
        const int parts = v[3];
        free(c->irBuf);
        c->irBuf = nullptr;
        c->irCapacity = 0;
        c->irPartsSeen = 0;
        if (channels <= 0 || channels > 2 || parts <= 0 ||
            (long long)parts * channels * 4096 > (64 << 20))
        {
            LOGE("impulse geometry rejected (%d ch, %d parts)", channels, parts);
            return true;
        }
        /* v[0] is the total sample count the app says it is sending. It was
           taken on trust and divided, so a zero or negative value produced a
           frame count the convolver would run with, and an oversized one made
           it read past the end of the buffer we are about to allocate. The
           buffer holds 4096 frames per partition per channel; nothing may
           claim more than that. */
        const int64_t total = v[0];
        const int64_t maxFrames = (int64_t)4096 * parts;
        if (total <= 0 || total / channels <= 0 || total / channels > maxFrames)
        {
            LOGE("impulse length rejected (%lld samples over %d ch, %d parts)",
                 (long long)total, channels, parts);
            return true;
        }
        c->irChannels = channels;
        c->irFrames = (int)(total / channels);
        c->irParts = parts;
        c->irCapacity = (size_t)4096 * channels * parts;
        c->irBuf = (float *)calloc(c->irCapacity, sizeof(float));
        if (!c->irBuf) c->irCapacity = 0;
        return true;
    }
    case 12000:     /* one 4096-float slice of it */
    {
        if (!c->irBuf || vsize < 4096 * sizeof(float)) return true;
        const size_t offset = (size_t)c->irPartsSeen * 4096;
        if (offset + 4096 > c->irCapacity)
        {
            LOGE("impulse partition %d past the end", c->irPartsSeen);
            return true;
        }
        memcpy(c->irBuf + offset, val, 4096 * sizeof(float));
        c->irPartsSeen++;
        return true;
    }

    case 10004:     /* commit: impulse response */
        if (c->irBuf)
        {
            /* Used to warn and load anyway, which handed the convolver a
               frame count covering partitions that never arrived. Refuse it:
               a truncated impulse is not worth a read past the buffer. */
            if (c->irPartsSeen != c->irParts)
            {
                LOGE("impulse committed with %d of %d partitions, discarding",
                     c->irPartsSeen, c->irParts);
                free(c->irBuf);
                c->irBuf = nullptr;
                c->irCapacity = 0;
                c->irPartsSeen = 0;
                return true;
            }
            const int rc = Convolver1DLoadImpulseResponse(
                &c->dsp, c->irBuf, (int16_t)c->irChannels, c->irFrames, 1);
            c->haveIr = rc >= 0;
            LOGI("convolver load %s (%d ch, %d frames)",
                 c->haveIr ? "ok" : "failed", c->irChannels, c->irFrames);
            free(c->irBuf);
            c->irBuf = nullptr;
            c->irCapacity = 0;
            c->irPartsSeen = 0;
        }
        return true;

    case 10006:     /* commit: GraphicEQ / AutoEq curve */
    case 10009:     /* commit: DDC */
    case 10010:     /* commit: liveprog script */
        if (c->stringBuf)
        {
            c->stringBuf[c->stringCapacity] = '\0';
            if (id == 10006)
            {
                ArbitraryResponseEqualizerStringParser(&c->dsp, c->stringBuf);
                c->haveGraphicEq = true;
            }
            else if (id == 10009)
            {
                DDCStringParser(&c->dsp, c->stringBuf);
                c->haveDdc = true;
            }
            else
            {
                /* The parser returns 1 when the script compiled, 0 when it has
                   no @init, and negative for the other errors. This used to
                   test for 0, so every valid script was refused at enable and
                   only a script with nothing compiled was let through. */
                const int rc = LiveProgStringParser(&c->dsp, c->stringBuf);
                c->haveLiveprog = rc > 0;
                if (rc <= 0) LOGE("liveprog rejected the script (%d)", rc);
            }
            free(c->stringBuf);
            c->stringBuf = nullptr;
            c->stringCapacity = 0;
        }
        c->stringIndex = 0;
        return true;

    default:
        return false;
    }
}

static int32_t rv4a_command(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
                            void *pCmdData, uint32_t *replySize, void *pReplyData)
{
    rv4a_context *c = reinterpret_cast<rv4a_context *>(self);
    if (!c)
        return -EINVAL;

    switch (cmdCode)
    {
    case EFFECT_CMD_INIT:
        LOGI("INIT");
        if (pReplyData && replySize && *replySize == sizeof(int))
            *(int *)pReplyData = 0;
        return 0;

    case EFFECT_CMD_SET_CONFIG:
    {
        if (!pCmdData || cmdSize != sizeof(effect_config_t))
        {
            LOGE("SET_CONFIG: bad payload (size %u, expected %zu)",
                 cmdSize, sizeof(effect_config_t));
            return -EINVAL;
        }
        effect_config_t *cfg = (effect_config_t *)pCmdData;

        LOGI("SET_CONFIG: in rate=%u fmt=0x%x chans=0x%x | out rate=%u fmt=0x%x chans=0x%x access=%d",
             cfg->inputCfg.samplingRate, cfg->inputCfg.format, cfg->inputCfg.channels,
             cfg->outputCfg.samplingRate, cfg->outputCfg.format, cfg->outputCfg.channels,
             cfg->outputCfg.accessMode);

        /* The engine works on planar float stereo. Anything else would be
           misread as floats and produce noise, so refuse it loudly instead. */
        if (cfg->inputCfg.format != AUDIO_FORMAT_PCM_FLOAT ||
            cfg->outputCfg.format != AUDIO_FORMAT_PCM_FLOAT)
        {
            LOGE("SET_CONFIG: refusing non-float format (in 0x%x out 0x%x)",
                 cfg->inputCfg.format, cfg->outputCfg.format);
            return -EINVAL;
        }
        if (cfg->inputCfg.channels != AUDIO_CHANNEL_OUT_STEREO ||
            cfg->outputCfg.channels != AUDIO_CHANNEL_OUT_STEREO)
        {
            LOGE("SET_CONFIG: refusing non-stereo (in 0x%x out 0x%x)",
                 cfg->inputCfg.channels, cfg->outputCfg.channels);
            return -EINVAL;
        }

        c->sampleRate = cfg->inputCfg.samplingRate;
        c->channels = 2;
        c->outAccessMode = cfg->outputCfg.accessMode;

        /* EffectCreate has already built the engine, at 48 kHz, and it lives
           as long as the effect does. This arrives again on every route
           change. It used to build a new engine here instead: the first time
           over EffectCreate's without freeing it, which leaked about 271 MB of
           allocations in audioserver per effect instance, and every time after
           that by freeing the old one first, which put every effect back to
           off and every setting to its default. Nothing on the app side sends
           them again after a route change, so root mode silently lost all its
           processing the first time the output changed.

           So only the rate is followed now, the way the legacy effect does it.
           The engine keeps its settings and its loaded payloads, so the
           payload flags and hashes stay true. Same rate: nothing to do. */
        JamesDSPSetSampleRate(&c->dsp, (float)c->sampleRate, 0);
        if (!JamesDSPGetMutexStatus(&c->dsp))
            LOGW("SET_CONFIG: engine reports no mutex; concurrent access unsafe");
        c->configured = true;
        LOGI("SET_CONFIG: engine at %u Hz (working rate %.0f), block %d",
             c->sampleRate, c->dsp.fs, RV4A_BLOCK);

        if (pReplyData && replySize && *replySize == sizeof(int))
            *(int *)pReplyData = 0;
        return 0;
    }

    case EFFECT_CMD_GET_CONFIG:
    {
        /* Some servers query this before enabling; failing it can stop the
           effect being used at all. It used to report success without writing
           anything, so the caller read whatever was already in the buffer and
           believed it was our configuration. */
        if (!pReplyData || !replySize || *replySize < sizeof(effect_config_t))
        {
            if (replySize)
                *replySize = 0;
            return -EINVAL;
        }
        effect_config_t *reply = (effect_config_t *)pReplyData;
        memset(reply, 0, sizeof(*reply));
        reply->inputCfg.samplingRate = reply->outputCfg.samplingRate =
            c->sampleRate ? c->sampleRate : 48000;
        reply->inputCfg.channels = reply->outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
        reply->inputCfg.format = reply->outputCfg.format = AUDIO_FORMAT_PCM_FLOAT;
        reply->inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
        reply->outputCfg.accessMode = c->outAccessMode;
        reply->inputCfg.bufferProvider.getBuffer = NULL;
        reply->inputCfg.bufferProvider.releaseBuffer = NULL;
        reply->inputCfg.bufferProvider.cookie = NULL;
        reply->outputCfg.bufferProvider = reply->inputCfg.bufferProvider;
        reply->inputCfg.mask = reply->outputCfg.mask = EFFECT_CONFIG_ALL;
        *replySize = sizeof(effect_config_t);
        return 0;
    }

    case EFFECT_CMD_RESET:
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_INPUT_DEVICE:
    case EFFECT_CMD_SET_VOLUME:
    case EFFECT_CMD_SET_AUDIO_MODE:
        /* Benign notifications. Refusing them makes the audio server treat the
           effect as broken and drop it, which is worse than ignoring them. */
        LOGD("command 0x%x acknowledged", cmdCode);
        if (pReplyData && replySize && *replySize == sizeof(int))
            *(int *)pReplyData = 0;
        return 0;

    case EFFECT_CMD_ENABLE:
        c->active = true;
        LOGI("ENABLE (configured=%d, rate=%u)", c->configured, c->sampleRate);
        if (pReplyData && replySize && *replySize == sizeof(int))
            *(int *)pReplyData = 0;
        return 0;

    case EFFECT_CMD_DISABLE:
        c->active = false;
        LOGI("DISABLE");
        if (pReplyData && replySize && *replySize == sizeof(int))
            *(int *)pReplyData = 0;
        return 0;

    case EFFECT_CMD_SET_PARAM:
    {
        /* AudioEffect packs the id and value into effect_param_t: the id is a
           4-byte int, the value follows it aligned to 4 bytes, and vsize says
           how wide it is (2 for the short overload the app mostly uses). */
        if (!pCmdData || cmdSize < sizeof(effect_param_t))
            return -EINVAL;
        effect_param_t *p = (effect_param_t *)pCmdData;
        if (p->psize != sizeof(int32_t))
            return -EINVAL;

        /* psize and vsize describe a tail that has to actually be inside the
           buffer the server handed us. Nothing checked that: a caller could
           claim any vsize it liked and every read below, including the reads
           the buffered-payload handler makes, walked off the end. Computed in
           64 bits because both fields are uint32_t and would otherwise wrap
           straight back into a size that looks small enough to pass. */
        const uint64_t need = (uint64_t)sizeof(effect_param_t)
                            + (((uint64_t)p->psize + 3u) & ~3ull)
                            + (uint64_t)p->vsize;
        if (need > (uint64_t)cmdSize)
        {
            LOGE("SET_PARAM: psize %u + vsize %u do not fit in cmdSize %u",
                 p->psize, p->vsize, cmdSize);
            return -EINVAL;
        }

        const int32_t id = *(int32_t *)p->data;
        const void *val = p->data + ((p->psize + 3) & ~3);
        const int16_t sv = (p->vsize >= sizeof(int16_t)) ? *(const int16_t *)val : 0;
        const bool on = sv != 0;

        c->paramCommits++;

        /* Payloads that do not fit in one call: a geometry header, a run of
           fixed-size partitions, then a commit. Handled here rather than in the
           shared parameter table because the partial state belongs to this
           instance, not to the engine. */
        if (handleBufferedPayload(c, id, val, p->vsize))
        {
            if (pReplyData && replySize && *replySize == sizeof(int))
                *(int *)pReplyData = 0;
            return 0;
        }

        /* Refuse to switch on a subsystem that has nothing behind it. The app
           enables these from saved preferences on every sync, which can easily
           run before - or without - the payload ever arriving. */
        if ((id == 1205 && !c->haveIr) || (id == 1210 && !c->haveGraphicEq) ||
            (id == 1212 && !c->haveDdc) || (id == 1213 && !c->haveLiveprog))
        {
            if (on)
            {
                LOGW("refusing to enable %d: no payload loaded", id);
                if (pReplyData && replySize && *replySize == sizeof(int))
                    *(int *)pReplyData = 0;
                return 0;
            }
        }

        /* Payload checksums are bookkeeping rather than DSP settings: the app
           writes one after pushing a payload and reads it back next time to
           decide whether the push can be skipped. Storing them here rather
           than passing them to the engine keeps that round trip honest. */
        if (id >= 25000 && id <= 25003)
        {
            /* Kept only when the payload it describes actually loaded. The
               app sends the hash straight after the payload whether or not it
               took, and next time skips the payload when the hash matches, so
               keeping it for a rejected payload meant never trying again. */
            const bool loaded[4] = { c->haveGraphicEq, c->haveDdc, c->haveLiveprog, c->haveIr };
            if (p->vsize >= sizeof(int32_t))
                c->hashSlot[id - 25000] = loaded[id - 25000] ? *(const int32_t *)val : 0;
            if (pReplyData && replySize && *replySize == sizeof(int))
                *(int *)pReplyData = 0;
            return 0;
        }

        /* Fork effects arrive as one float array per effect rather than an id
           per value, so hand the payload through as well. */
        LOGD("SET_PARAM id=%d vsize=%u short=%d", id, p->vsize, sv);
        applyParam(&c->dsp, id, sv, on, (const float *)val, p->vsize / sizeof(float));

        if (pReplyData && replySize && *replySize == sizeof(int))
            *(int *)pReplyData = 0;
        return 0;
    }

    case EFFECT_CMD_GET_PARAM:
    {
        /* Not optional. JamesDspRemoteEngine reads the pid (20002) and the
           sample rate (20001) on every settings sync and treats a failure as
           the engine having crashed - it toasts and re-creates the effect. An
           unhandled GET_PARAM is therefore not a missing feature but a reboot
           loop, with no settings ever surviving to be applied. */
        if (!pCmdData || !pReplyData || !replySize ||
            *replySize < sizeof(rv4a_reply_1x4_1x4))
            return -EINVAL;
        /* sizeof(effect_param_t) stops at the header, so a cmdSize of exactly
           that passed the old check and the read of q->data below was four
           bytes past the end. The id has to be in the buffer too. */
        if (cmdSize < sizeof(effect_param_t) + sizeof(int32_t))
            return -EINVAL;

        effect_param_t *q = (effect_param_t *)pCmdData;
        if (q->psize != sizeof(int32_t) || q->vsize != sizeof(int32_t))
            return -EINVAL;

        const int32_t cmd = *(const int32_t *)q->data;
        int32_t data;
        switch (cmd)
        {
        case 19998: data = (int32_t)c->paramCommits; break;
        case 19999: data = RV4A_BLOCK; break;
        case 20000: data = RV4A_BLOCK; break;
        case 20001: data = (int32_t)c->sampleRate; break;
        case 20002: data = (int32_t)getpid(); break;
        case 30000:
        case 30001:
        case 30002:
        case 30003: data = c->hashSlot[cmd - 30000]; break;
        default:
            LOGD("GET_PARAM %d not handled", cmd);
            return -EINVAL;
        }

        rv4a_reply_1x4_1x4 *r = (rv4a_reply_1x4_1x4 *)pReplyData;
        r->status = 0;
        r->psize = sizeof(int32_t);
        r->vsize = sizeof(int32_t);
        r->cmd = cmd;
        r->data = data;
        *replySize = sizeof(rv4a_reply_1x4_1x4);
        return 0;
    }

    default:
        LOGD("command 0x%x not handled", cmdCode);
        return -EINVAL;
    }
}

static int32_t rv4a_getDescriptor(effect_handle_t self, effect_descriptor_t *pDescriptor)
{
    if (!pDescriptor)
        return -EINVAL;
    memcpy(pDescriptor, &rv4a_descriptor, sizeof(effect_descriptor_t));
    return 0;
}

static const struct effect_interface_s rv4a_interface =
{
    rv4a_process,
    rv4a_command,
    rv4a_getDescriptor,
    NULL
};

/* The library is built with hidden visibility so the engine's internals stay
 * private, which means the four symbols the audio server looks up must be
 * marked visible explicitly - without this the module loads but exports
 * nothing, and the effect silently never appears. */
#define HAL_EXPORT __attribute__((visibility("default")))

extern "C" {

HAL_EXPORT int32_t EffectCreate(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId,
                     effect_handle_t *pHandle)
{
    if (!pHandle || !uuid)
        return -EINVAL;
    if (memcmp(uuid, &rv4a_descriptor.uuid, sizeof(effect_uuid_t)) != 0)
        return -EINVAL;

    rv4a_context *c = (rv4a_context *)calloc(1, sizeof(rv4a_context));
    if (!c)
    {
        LOGE("EffectCreate: out of memory");
        return -ENOMEM;
    }
    c->itfe = &rv4a_interface;
    c->active = false;
    c->configured = false;
    c->outAccessMode = EFFECT_BUFFER_ACCESS_WRITE;

    /* Shared tables, allocated once for the process rather than per effect.
       A plain bool was only safe because the legacy factory happens to
       serialise creation under its own lock; nothing promises that, and the
       AIDL path makes no such promise at all. */
    static pthread_once_t globalOnce = PTHREAD_ONCE_INIT;
    pthread_once(&globalOnce, []() {
        JamesDSPGlobalMemoryAllocation();
        LOGI("global engine tables allocated");
    });
    JamesDSPInit(&c->dsp, RV4A_BLOCK, 48000);
    LOGI("EffectCreate: session=%d io=%d handle=%p", sessionId, ioId, (void *)c);
    *pHandle = (effect_handle_t)c;
    return 0;
}

HAL_EXPORT int32_t EffectRelease(effect_handle_t handle)
{
    rv4a_context *c = reinterpret_cast<rv4a_context *>(handle);
    if (!c)
        return -EINVAL;
    LOGI("EffectRelease: handle=%p after %llu blocks", (void *)c,
         (unsigned long long)c->blocks);
    JamesDSPFree(&c->dsp);
    /* A payload interrupted between its header and its commit leaves these
       holding the partitions gathered so far. */
    free(c->stringBuf);
    free(c->irBuf);
    free(c->mixBuf);
    free(c);
    return 0;
}

HAL_EXPORT int32_t EffectGetDescriptor(const effect_uuid_t *uuid, effect_descriptor_t *pDescriptor)
{
    if (!pDescriptor || !uuid)
        return -EINVAL;
    /* Answered for any uuid at all until now, so a config naming our library
       against somebody else's uuid got our descriptor back and the effect was
       registered as ours. EffectCreate always compared; this did not. */
    if (memcmp(uuid, &rv4a_descriptor.uuid, sizeof(effect_uuid_t)) != 0)
        return -EINVAL;
    memcpy(pDescriptor, &rv4a_descriptor, sizeof(effect_descriptor_t));
    return 0;
}

HAL_EXPORT audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM =
{
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_LIBRARY_API_VERSION,
    .name = "RootlessViPER4Android Effect Library",
    .implementor = "alienware377",
    .create_effect = EffectCreate,
    .release_effect = EffectRelease,
    .get_descriptor = EffectGetDescriptor,
};

}
