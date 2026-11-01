// Does the AIDL effect plugin behave like the legacy root mode effect?
//
// There is no AIDL rooted test device, so this plays the parts of the vendor's
// effect factory and of audioserver around the real plugin library, the same
// x86_64 build that CI produces, on the emulator:
//
//   - dlopen it and look up the three symbols the factory looks up, and check
//     that nothing else is visible
//   - queryEffect and createEffect directly, as the factory does in process
//   - then drive the effect over binder transactions, as audioserver does:
//     the effect is reached through a proxy of this program's own copy of the
//     interface, so every call goes through the plugin's parcel code
//   - open it, attach to its audio queues, wake it with the event flag bit
//     for its version, and read the status and output queues
//   - send parameters as the framework packs them: a whole effect_param_t in
//     a DefaultExtension, including every buffered payload the app sends
//
// The reference is the legacy effect (hal/JamesDspHalEffect.cpp), compiled
// into this program and driven with the same commands and the same audio.
// Output must match bit for bit: the plugin is meant to be the legacy effect
// behind a different door, not a second implementation of it.
//
// Build and run (tools/run_aidl_harness.sh does all of this):
//   cmake -S magisk/aidl -B <dir> -DRV4A_HAL_VERSION=<N> -DRV4A_HARNESS=ON
//         -DANDROID_ABI=x86_64 ...
//   adb push <dir>/librv4a_aidl_v<N>.so <dir>/aidl_plugin_harness /data/local/tmp/rv4a_aidl/
//   adb shell /data/local/tmp/rv4a_aidl/aidl_plugin_harness /data/local/tmp/rv4a_aidl/librv4a_aidl_v<N>.so
#include <dlfcn.h>
#include <malloc.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "RV4A-AIDL-HARNESS"
#include <aidl/android/hardware/audio/effect/DefaultExtension.h>
#include <aidl/android/hardware/audio/effect/IEffect.h>
#include <aidl/android/hardware/audio/effect/VendorExtension.h>
#include <fmq/AidlMessageQueue.h>
#include <fmq/EventFlag.h>
#include <system/audio_effects/aidl_effects_utils.h>

#include "LegacyBridge.h"
#include "hal_vdc_blob.h"

using aidl::android::hardware::audio::effect::CommandId;
using aidl::android::hardware::audio::effect::DefaultExtension;
using aidl::android::hardware::audio::effect::Descriptor;
using aidl::android::hardware::audio::effect::Flags;
using aidl::android::hardware::audio::effect::IEffect;
using aidl::android::hardware::audio::effect::kEventFlagDataMqNotEmpty;
using aidl::android::hardware::audio::effect::kEventFlagDataMqUpdate;
using aidl::android::hardware::audio::effect::kEventFlagNotEmpty;
using aidl::android::hardware::audio::effect::Parameter;
using aidl::android::hardware::audio::effect::State;
using aidl::android::hardware::audio::effect::VendorExtension;
using aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using aidl::android::media::audio::common::AudioChannelLayout;
using aidl::android::media::audio::common::AudioFormatType;
using aidl::android::media::audio::common::AudioUuid;
using aidl::android::media::audio::common::PcmType;
using ::android::hardware::EventFlag;

typedef binder_exception_t (*CreateFn)(const AudioUuid*, std::shared_ptr<IEffect>*);
typedef binder_exception_t (*QueryFn)(const AudioUuid*, Descriptor*);
typedef binder_exception_t (*DestroyFn)(const std::shared_ptr<IEffect>&);
using StatusMQ = ::android::AidlMessageQueue<IEffect::Status, SynchronizedReadWrite>;
using DataMQ = ::android::AidlMessageQueue<float, SynchronizedReadWrite>;

static int failures = 0;
static void check(const char* what, bool ok, const std::string& detail = "") {
    printf("  %-62s %-4s %s\n", what, ok ? "ok" : "FAIL", detail.c_str());
    if (!ok) failures++;
}

static CreateFn gCreate;
static QueryFn gQuery;
static DestroyFn gDestroy;

static AudioUuid uuid(uint32_t a, uint16_t b, uint16_t c, uint16_t d, std::vector<uint8_t> n) {
    AudioUuid u;
    u.timeLow = (int32_t)a;
    u.timeMid = b;
    u.timeHiAndVersion = c;
    u.clockSeq = d;
    u.node = n;
    return u;
}
static const AudioUuid kType = uuid(0xf98765f4, 0xc321, 0x5de6, 0x9a45, {0x12, 0x34, 0x59, 0x49, 0x5a, 0xb2});
static const AudioUuid kImpl = uuid(0xf27317f4, 0xc984, 0x4de6, 0x9a90, {0x54, 0x57, 0x59, 0x49, 0x5b, 0xf2});

static Parameter::Common makeCommon(int rate, int frames, int32_t layout = AudioChannelLayout::LAYOUT_STEREO,
                                    PcmType pcm = PcmType::FLOAT_32_BIT) {
    Parameter::Common c;
    c.session = 0;
    c.ioHandle = 13;
    c.input.base.sampleRate = rate;
    c.input.base.channelMask = AudioChannelLayout::make<AudioChannelLayout::layoutMask>(layout);
    c.input.base.format.type = AudioFormatType::PCM;
    c.input.base.format.pcm = pcm;
    c.input.frameCount = frames;
    c.output = c.input;
    return c;
}

// ---- parameters, packed as the framework packs a legacy setParameter -------

static std::vector<uint8_t> packParam(int32_t id, const void* val, uint32_t vsize) {
    // effect_param_t: int32 status, uint32 psize, uint32 vsize, then the id
    // padded to 4 bytes, then the value.
    std::vector<uint8_t> b(12 + 4 + vsize, 0);
    uint32_t psize = 4;
    memcpy(&b[4], &psize, 4);
    memcpy(&b[8], &vsize, 4);
    memcpy(&b[12], &id, 4);
    if (vsize) memcpy(&b[16], val, vsize);
    return b;
}

static Parameter vendorParam(const std::vector<uint8_t>& bytes) {
    DefaultExtension ext;
    ext.bytes = bytes;
    VendorExtension v;
    v.extension.setParcelable(ext);
    return Parameter::make<Parameter::specific>(Parameter::Specific::make<Parameter::Specific::vendorEffect>(v));
}

/* One side of the comparison: either the AIDL effect over binder, or the
   legacy effect in this process. Both get exactly the same calls. */
struct Side {
    virtual ~Side() = default;
    virtual bool set(int32_t id, const void* val, uint32_t vsize) = 0;
    virtual bool get(int32_t id, int32_t* out) = 0;
    virtual bool process(const float* in, float* out, int frames) = 0;

    bool setShort(int32_t id, int16_t v) { return set(id, &v, 2); }
    bool setInt(int32_t id, int32_t v) { return set(id, &v, 4); }
    bool setFloats(int32_t id, const std::vector<float>& v) {
        return set(id, v.data(), (uint32_t)(v.size() * 4));
    }
    // setParameterCharBuffer in AudioEffectExtensions.kt
    bool charBuffer(int32_t send, int32_t commit, const char* s) {
        size_t len = strlen(s);
        int parts = (int)((len + 255) / 256);
        int32_t info[2] = {parts, 256};
        bool ok = set(8888, info, 8);
        for (int i = 0; i < parts; i++) {
            char chunk[256] = {0};
            size_t n = len - (size_t)i * 256;
            if (n > 256) n = 256;
            memcpy(chunk, s + (size_t)i * 256, n);
            ok &= set(send, chunk, 256);
        }
        return ok & setShort(commit, 1);
    }
    // setParameterImpulseResponseBuffer
    bool impulse(const std::vector<float>& ir, int ch) {
        int total = (int)ir.size(), parts = (total + 4095) / 4096;
        int32_t info[4] = {total, ch, 0, parts};
        bool ok = set(9999, info, 16);
        std::vector<float> fin((size_t)parts * 4096, 0.0f);
        memcpy(fin.data(), ir.data(), (size_t)total * 4);
        for (int i = 0; i < parts; i++) ok &= set(12000, fin.data() + (size_t)i * 4096, 4096 * 4);
        return ok & setShort(10004, 1);
    }
};

struct LegacySide : Side {
    void* h = nullptr;
    explicit LegacySide(int rate) {
        h = rv4a_legacy_create();
        rv4a_legacy_configure(h, (uint32_t)rate);
        rv4a_legacy_enable(h, true);
    }
    ~LegacySide() override { rv4a_legacy_release(h); }
    bool set(int32_t id, const void* val, uint32_t vsize) override {
        auto b = packParam(id, val, vsize);
        return rv4a_legacy_set_param(h, b.data(), (uint32_t)b.size()) == 0;
    }
    bool get(int32_t id, int32_t* out) override {
        int32_t zero = 0;
        auto q = packParam(id, &zero, 4);
        uint8_t r[64];
        uint32_t rs = sizeof r;
        if (rv4a_legacy_get_param(h, q.data(), (uint32_t)q.size(), r, &rs) != 0) return false;
        memcpy(out, r + 16, 4);
        return true;
    }
    bool process(const float* in, float* out, int frames) override {
        std::vector<float> tmp(in, in + frames * 2);
        if (rv4a_legacy_process(h, tmp.data(), out, (size_t)frames) != 0) return false;
        return true;
    }
};

struct AidlSide : Side {
    std::shared_ptr<IEffect> effect;  // the plugin's object, as the factory holds it
    std::shared_ptr<IEffect> remote;  // a proxy over its binder, as audioserver holds it
    std::unique_ptr<StatusMQ> statusMQ;
    std::unique_ptr<DataMQ> inMQ, outMQ;
    EventFlag* ef = nullptr;
    uint32_t notEmptyBit = 0;
    int version = 0;
    std::string lastError;

    bool attach(const IEffect::OpenEffectReturn& ret) {
        if (ef) EventFlag::deleteEventFlag(&ef);
        statusMQ = std::make_unique<StatusMQ>(ret.statusMQ);
        inMQ = std::make_unique<DataMQ>(ret.inputDataMQ);
        outMQ = std::make_unique<DataMQ>(ret.outputDataMQ);
        if (!statusMQ->isValid() || !inMQ->isValid() || !outMQ->isValid()) return false;
        return EventFlag::createEventFlag(statusMQ->getEventFlagWord(), &ef) == ::android::OK && ef;
    }
    bool open(const Parameter::Common& c, IEffect::OpenEffectReturn* ret) {
        auto st = remote->open(c, std::nullopt, ret);
        if (!st.isOk()) {
            lastError = st.getDescription();
            return false;
        }
        return attach(*ret);
    }
    bool set(int32_t id, const void* val, uint32_t vsize) override {
        auto st = remote->setParameter(vendorParam(packParam(id, val, vsize)));
        if (!st.isOk()) lastError = st.getDescription();
        return st.isOk();
    }
    bool get(int32_t id, int32_t* out) override {
        int32_t zero = 0;
        DefaultExtension q;
        q.bytes = packParam(id, &zero, 4);
        VendorExtension v;
        v.extension.setParcelable(q);
        Parameter p;
        auto st = remote->getParameter(Parameter::Id::make<Parameter::Id::vendorEffectTag>(v), &p);
        if (!st.isOk()) {
            lastError = st.getDescription();
            return false;
        }
        if (p.getTag() != Parameter::specific) return false;
        const auto& s = p.get<Parameter::specific>();
        if (s.getTag() != Parameter::Specific::vendorEffect) return false;
        std::optional<DefaultExtension> r;
        if (s.get<Parameter::Specific::vendorEffect>().extension.getParcelable(&r) != STATUS_OK || !r)
            return false;
        // The framework checks this before copying the reply back.
        if (r->bytes.size() < 20) return false;
        int32_t status;
        memcpy(&status, r->bytes.data(), 4);
        memcpy(out, r->bytes.data() + 16, 4);
        return status == 0;
    }
    bool process(const float* in, float* out, int frames) override {
        const size_t n = (size_t)frames * 2;
        if (!inMQ->write(in, n)) {
            lastError = "input queue full";
            return false;
        }
        ef->wake(notEmptyBit);
        IEffect::Status st{};
        if (!statusMQ->readBlocking(&st, 1, 2000000000LL)) {
            lastError = "no status within 2 s";
            return false;
        }
        if (st.status != 0 || st.fmqConsumed != (int)n || st.fmqProduced != (int)n) {
            char b[96];
            snprintf(b, sizeof b, "status %d consumed %d produced %d", st.status, st.fmqConsumed,
                     st.fmqProduced);
            lastError = b;
            return false;
        }
        return outMQ->read(out, n);
    }
    ~AidlSide() override {
        if (ef) EventFlag::deleteEventFlag(&ef);
    }
};

static std::shared_ptr<IEffect> createRemote(AidlSide& s) {
    if (gCreate(&kImpl, &s.effect) != EX_NONE || !s.effect) return nullptr;
    // What audioserver ends up holding: this program's IEffect proxy over the
    // plugin's binder. Our interface code is a different copy from the
    // plugin's, exactly as the factory's is, so this is a real parcel path.
    s.remote = IEffect::fromBinder(s.effect->asBinder());
    if (s.remote) s.remote->getInterfaceVersion(&s.version);
    s.notEmptyBit = s.version >= 2 ? kEventFlagDataMqNotEmpty : kEventFlagNotEmpty;
    return s.remote;
}

// ---- test audio: deterministic, broadband, and loud enough to hit limiters --

static void makeSignal(std::vector<float>& buf, int frames, uint32_t& seed, double& phase, int rate) {
    buf.resize((size_t)frames * 2);
    for (int i = 0; i < frames; i++) {
        seed = seed * 1664525u + 1013904223u;
        float noise = ((seed >> 8) / 16777216.0f - 0.5f) * 0.3f;
        phase += 2.0 * M_PI * 110.0 / rate;
        float tone = (float)(0.6 * sin(phase));
        buf[(size_t)i * 2] = tone + noise;
        buf[(size_t)i * 2 + 1] = 0.8f * tone - noise;
    }
}

/* The app's full settings sync, roughly in the order JamesDspRemoteEngine
   sends it, with the fork's own effects as well. */
static bool applySettings(Side& s) {
    bool ok = true;
    std::vector<float> comp = {0.22f, 2, 0, 95, 200, 400, 800, 1600, 3400, 7500, .4f, .4f, .4f, .4f, .4f, .4f, .4f};
    ok &= s.setFloats(115, comp) & s.setShort(1200, 1);
    std::vector<float> eq = {0, -1};
    const float f[15] = {25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
    for (float x : f) eq.push_back(x);
    for (int i = 0; i < 15; i++) eq.push_back((i % 3) - 1.0f);
    ok &= s.setFloats(116, eq) & s.setShort(1202, 1);
    ok &= s.setShort(112, 6) & s.setShort(26200, 640) & s.setShort(1201, 1);
    ok &= s.setShort(128, 3) & s.setShort(1203, 1);
    ok &= s.setShort(137, 60) & s.setShort(1204, 1);
    ok &= s.setShort(150, 2000) & s.setShort(1206, 1);
    ok &= s.setShort(188, 2) & s.setShort(1208, 1);
    // fork effects: clarity, then field surround
    ok &= s.setFloats(26000, {1.0f, 3.0f}) & s.setShort(26100, 1);
    ok &= s.setFloats(26001, {0.5f, 0.5f}) & s.setShort(26101, 1);
    // buffered payloads, each followed by the hash the app stores
    const char* geq = "GraphicEQ: 20 0; 100 6; 1000 -6; 4000 6; 16000 0";
    ok &= s.charBuffer(12001, 10006, geq) & s.setInt(25000, 777) & s.setShort(1210, 1);
    ok &= s.charBuffer(12001, 10009, kVdc) & s.setInt(25001, 1234) & s.setShort(1212, 1);
    const char* script = "desc: half gain\n@init\ng=0.5;\n@sample\nspl0=spl0*g;\nspl1=spl1*g;\n";
    ok &= s.charBuffer(12001, 10010, script) & s.setInt(25002, 999) & s.setShort(1213, 1);
    std::vector<float> ir(3000 * 2, 0.0f);
    uint32_t seed = 7;
    for (size_t i = 0; i < ir.size(); i++) {
        seed = seed * 1664525u + 1013904223u;
        ir[i] = ((seed >> 8) / 16777216.0f - 0.5f) * expf(-(float)(i / 2) / 400.0f) * 0.2f;
    }
    ir[0] = ir[1] = 1.0f;
    ok &= s.impulse(ir, 2) & s.setInt(25003, 4242) & s.setShort(1205, 1);
    return ok;
}

/* Runs both sides over the same audio and compares every sample. */
static void compareRun(const char* what, Side& a, Side& b, int rate, int frames, int blocks,
                       uint32_t seed) {
    std::vector<float> in, oa((size_t)frames * 2), ob((size_t)frames * 2);
    double phase = 0;
    size_t mismatches = 0, firstBad = SIZE_MAX, changed = 0, total = 0;
    double maxDiff = 0, energy = 0, inEnergy = 0;
    bool okA = true, okB = true;
    for (int k = 0; k < blocks && okA && okB; k++) {
        makeSignal(in, frames, seed, phase, rate);
        okA = a.process(in.data(), oa.data(), frames);
        okB = b.process(in.data(), ob.data(), frames);
        for (size_t i = 0; i < oa.size(); i++) {
            if (memcmp(&oa[i], &ob[i], 4) != 0) {
                if (firstBad == SIZE_MAX) firstBad = (size_t)k * oa.size() + i;
                mismatches++;
                maxDiff = std::max(maxDiff, (double)fabsf(oa[i] - ob[i]));
            }
            if (memcmp(&oa[i], &in[i], 4) != 0) changed++;
            total++;
            energy += (double)ob[i] * ob[i];
            inEnergy += (double)in[i] * in[i];
        }
    }
    char d[200];
    snprintf(d, sizeof d, "%d blocks of %d, %zu differing samples, max diff %.3g, out/in energy %.3f",
             blocks, frames, mismatches, maxDiff, inEnergy > 0 ? energy / inEnergy : 0.0);
    check(what, okA && okB && mismatches == 0, d);
    // Identical output would also come from two effects that both did
    // nothing, so the audio has to have been changed as well.
    snprintf(d, sizeof d, "%zu of %zu samples changed", changed, total);
    check("  and the effect really processed it", changed > total / 2, d);
    if (!okA || !okB) printf("    process failed: aidl=%d legacy=%d\n", okA, okB);
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <path to librv4a_aidl_vN.so>\n", argv[0]);
        return 2;
    }
    printf("== AIDL effect plugin harness: %s ==\n", argv[1]);
    char d[256];

    // ---- 1: what the factory sees when it loads the library -----------------
    printf("\n[1] loading, as the effect factory does\n");
    void* lib = dlopen(argv[1], RTLD_LAZY);
    check("dlopen", lib != nullptr, lib ? "" : dlerror());
    if (!lib) return 1;
    gCreate = (CreateFn)dlsym(lib, "createEffect");
    gQuery = (QueryFn)dlsym(lib, "queryEffect");
    gDestroy = (DestroyFn)dlsym(lib, "destroyEffect");
    check("createEffect, queryEffect and destroyEffect resolve", gCreate && gQuery && gDestroy);
    const char* hidden[] = {"EffectCreate", "AUDIO_EFFECT_LIBRARY_INFO_SYM", "JamesDSPInit",
                            "_ZN4aidl7android8hardware5audio6effect7IEffect10fromBinderERKN3ndk8SpAIBinderE"};
    bool leaked = false;
    for (const char* s : hidden) {
        if (dlsym(lib, s)) {
            printf("    visible: %s\n", s);
            leaked = true;
        }
    }
    check("legacy entry points, engine and interface code stay hidden", !leaked);

    // ---- 2: queryEffect -------------------------------------------------------
    printf("\n[2] queryEffect\n");
    Descriptor desc;
    check("queryEffect accepts our uuid", gQuery(&kImpl, &desc) == EX_NONE);
    check("type and implementation uuids", desc.common.id.type == kType && desc.common.id.uuid == kImpl,
          desc.common.id.toString());
    snprintf(d, sizeof d, "'%s' by %s", desc.common.name.c_str(), desc.common.implementor.c_str());
    check("name says RootlessViPER4Android and never v3",
          desc.common.name.find("RootlessViPER4Android") != std::string::npos &&
                  desc.common.name.find("v3") == std::string::npos,
          d);
    check("insert, first in the chain, no volume control",
          desc.common.flags.type == Flags::Type::INSERT && desc.common.flags.insert == Flags::Insert::FIRST &&
                  desc.common.flags.volume == Flags::Volume::NONE);
    Descriptor other;
    check("queryEffect refuses another uuid", gQuery(&kType, &other) == EX_ILLEGAL_ARGUMENT);
    check("queryEffect refuses a null uuid", gQuery(nullptr, &other) == EX_ILLEGAL_ARGUMENT);

    // ---- 3: createEffect and the binder identity -----------------------------
    printf("\n[3] createEffect, then the interface as audioserver sees it\n");
    {
        std::shared_ptr<IEffect> e;
        check("createEffect refuses another uuid", gCreate(&kType, &e) == EX_ILLEGAL_ARGUMENT && !e);
        check("createEffect refuses a null output", gCreate(&kImpl, nullptr) == EX_ILLEGAL_ARGUMENT);
    }
    AidlSide aidl;
    check("createEffect gives an effect, reachable over binder", createRemote(aidl) != nullptr);
    if (!aidl.remote) return 1;
    // The plugin carries its own copy of the IEffect binder class, as any
    // NDK-built plugin must, so another copy of the interface code (the
    // factory's, or audioserver's after a round trip) sees a proxy rather
    // than the object. Recorded here because the factory's destroyEffect
    // looks effects up by that object; see DESIGN.md.
    check("a different copy of the interface code gets a proxy, not the object",
          aidl.remote.get() != aidl.effect.get());
    std::string hash;
    aidl.remote->getInterfaceHash(&hash);
    snprintf(d, sizeof d, "V%d %s", aidl.version, hash.c_str());
    check("interface version and frozen hash match this program's", aidl.version == IEffect::version &&
                                                                         hash == IEffect::hash, d);
    Descriptor viaBinder;
    aidl.remote->getDescriptor(&viaBinder);
    // Common only: the capability's ParcelableHolder compares by identity, so
    // two equal descriptors never compare equal as a whole.
    check("getDescriptor over binder equals queryEffect", viaBinder.common == desc.common,
          viaBinder.common == desc.common ? "" : viaBinder.toString() + " vs " + desc.toString());
    State state = State::PROCESSING;
    aidl.remote->getState(&state);
    check("starts in INIT", state == State::INIT);

    // ---- 4: configurations the engine cannot take are refused, not fatal ------
    printf("\n[4] open with configurations the engine cannot process\n");
    {
        IEffect::OpenEffectReturn r;
        struct { const char* what; Parameter::Common c; } bad[] = {
            {"mono", makeCommon(48000, 960, AudioChannelLayout::LAYOUT_MONO)},
            {"5.1", makeCommon(48000, 960, AudioChannelLayout::LAYOUT_5POINT1)},
            {"16-bit", makeCommon(48000, 960, AudioChannelLayout::LAYOUT_STEREO, PcmType::INT_16_BIT)},
            {"zero frames", makeCommon(48000, 0)},
            {"a million frames", makeCommon(48000, 1 << 20)},
        };
        for (auto& b : bad) {
            auto st = aidl.remote->open(b.c, std::nullopt, &r);
            snprintf(d, sizeof d, "refused %s", b.what);
            check(d, st.getExceptionCode() == EX_ILLEGAL_ARGUMENT, st.getDescription());
        }
        Parameter::Common mismatch = makeCommon(48000, 960);
        mismatch.output.base.sampleRate = 44100;
        check("refused input and output at different rates",
              aidl.remote->open(mismatch, std::nullopt, &r).getExceptionCode() == EX_ILLEGAL_ARGUMENT);
        aidl.remote->getState(&state);
        check("still in INIT after every refusal", state == State::INIT);
    }

    // ---- 5: open, queues, GET_PARAM -------------------------------------------
    printf("\n[5] open at 48 kHz stereo float, 960 frames\n");
    IEffect::OpenEffectReturn ret;
    check("open", aidl.open(makeCommon(48000, 960), &ret), aidl.lastError);
    snprintf(d, sizeof d, "in %zu out %zu floats", aidl.inMQ->getQuantumCount(), aidl.outMQ->getQuantumCount());
    check("audio queues hold one buffer of stereo frames",
          aidl.inMQ->getQuantumCount() == 1920 && aidl.outMQ->getQuantumCount() == 1920, d);
    aidl.remote->getState(&state);
    check("IDLE after open", state == State::IDLE);
    int32_t v = -1;
    check("GET_PARAM 20001 (engine rate) answers 48000", aidl.get(20001, &v) && v == 48000,
          std::to_string(v));
    check("GET_PARAM 20002 (engine pid) answers this process", aidl.get(20002, &v) && v == getpid(),
          std::to_string(v));
    check("GET_PARAM 19999 (block size) answers", aidl.get(19999, &v) && v > 0, std::to_string(v));
    check("an unknown GET_PARAM id fails cleanly", !aidl.get(4242, &v));
    {
        std::vector<uint8_t> shortParam = {1, 2, 3};
        check("a payload shorter than effect_param_t is refused",
              !aidl.remote->setParameter(vendorParam(shortParam)).isOk());
        auto lying = packParam(112, nullptr, 0);
        uint32_t big = 1 << 20;
        memcpy(&lying[8], &big, 4);
        check("a vsize past the end of the payload is refused",
              !aidl.remote->setParameter(vendorParam(lying)).isOk());
    }
    int32_t commitsBefore = 0;
    aidl.get(19998, &commitsBefore);

    // ---- 6: the full settings sync, then audio, against the legacy effect -----
    printf("\n[6] full settings sync and audio, against the legacy effect\n");
    LegacySide legacy(48000);
    aidl.lastError.clear();
    bool aidlOk = applySettings(aidl);
    bool legacyOk = applySettings(legacy);
    check("every parameter accepted over binder", aidlOk, aidl.lastError);
    check("every parameter accepted by the legacy effect", legacyOk);
    int32_t commitsAfter = 0;
    aidl.get(19998, &commitsAfter);
    snprintf(d, sizeof d, "%d -> %d", commitsBefore, commitsAfter);
    check("GET_PARAM 19998 counts the commits", commitsAfter > commitsBefore + 40, d);
    const int32_t wantHash[4] = {777, 1234, 999, 4242};
    for (int i = 0; i < 4; i++) {
        int32_t a = -1, l = -2;
        aidl.get(30000 + i, &a);
        legacy.get(30000 + i, &l);
        snprintf(d, sizeof d, "GET_PARAM %d returns the stored payload hash", 30000 + i);
        char dd[64];
        snprintf(dd, sizeof dd, "aidl %d legacy %d", a, l);
        check(d, a == wantHash[i] && a == l, dd);
    }
    check("START", aidl.remote->command(CommandId::START).isOk());
    aidl.remote->getState(&state);
    check("PROCESSING after START", state == State::PROCESSING);
    compareRun("output identical to the legacy effect, 48 kHz", aidl, legacy, 48000, 960, 400, 1);

    // ---- 7: a route change: new rate and buffer size --------------------------
    printf("\n[7] route change to 44.1 kHz and 512 frames\n");
    {
        auto st = aidl.remote->setParameter(Parameter::make<Parameter::common>(makeCommon(44100, 512)));
        check("setParameter(common) accepted", st.isOk(), st.getDescription());
        uint32_t efState = 0;
        aidl.ef->wait(kEventFlagDataMqUpdate, &efState, 1, true);
        if (aidl.version >= 2) {
            check("the effect raised the queue update flag", (efState & kEventFlagDataMqUpdate) != 0);
            IEffect::OpenEffectReturn re;
            st = aidl.remote->reopen(&re);
            check("reopen gives new queues", st.isOk() && aidl.attach(re), st.getDescription());
            snprintf(d, sizeof d, "%zu floats", aidl.inMQ->getQuantumCount());
            check("new queues are sized for 512 frames", aidl.inMQ->getQuantumCount() == 1024, d);
        }
        rv4a_legacy_configure(legacy.h, 44100);
        check("GET_PARAM 20001 follows the new rate", aidl.get(20001, &v) && v == 44100, std::to_string(v));
        for (int i = 0; i < 4; i++) {
            int32_t a = -1;
            aidl.get(30000 + i, &a);
            if (a != wantHash[i]) printf("    hash %d lost: %d\n", 30000 + i, a);
        }
        check("payload hashes survive the route change", [&] {
            for (int i = 0; i < 4; i++) {
                int32_t a = -1;
                if (!aidl.get(30000 + i, &a) || a != wantHash[i]) return false;
            }
            return true;
        }());
        compareRun("output identical to the legacy effect, 44.1 kHz", aidl, legacy, 44100, 512, 400, 2);
    }

    // ---- 8: STOP, RESET, START ---------------------------------------------------
    printf("\n[8] STOP, RESET and START again\n");
    {
        check("STOP", aidl.remote->command(CommandId::STOP).isOk());
        aidl.remote->getState(&state);
        check("IDLE after STOP", state == State::IDLE);
        check("RESET", aidl.remote->command(CommandId::RESET).isOk());
        check("START", aidl.remote->command(CommandId::START).isOk());
        rv4a_legacy_enable(legacy.h, false);
        rv4a_legacy_enable(legacy.h, true);
        compareRun("output identical after a STOP and START", aidl, legacy, 44100, 512, 100, 3);
    }

    // ---- 9: parameters while audio is flowing ------------------------------------
    printf("\n[9] parameters from another thread while audio flows\n");
    {
        std::atomic<bool> stop{false};
        std::atomic<int> setFailures{0}, sets{0};
        std::thread params([&] {
            int16_t on = 0;
            while (!stop) {
                on ^= 1;
                if (!aidl.setShort(1201, on)) setFailures++;
                if (!aidl.setShort(112, (int16_t)(on ? 9 : 3))) setFailures++;
                if (!aidl.setFloats(26000, {1.0f, on ? 6.0f : 2.0f})) setFailures++;
                int32_t r;
                if (!aidl.get(20001, &r)) setFailures++;
                sets += 4;
            }
        });
        std::vector<float> in, out(1024);
        uint32_t seed = 9;
        double phase = 0;
        int blocks = 0, bad = 0;
        auto t0 = std::chrono::steady_clock::now();
        while (std::chrono::steady_clock::now() - t0 < std::chrono::seconds(3)) {
            makeSignal(in, 512, seed, phase, 44100);
            if (!aidl.process(in.data(), out.data(), 512)) bad++;
            for (float x : out)
                if (!std::isfinite(x)) {
                    bad++;
                    break;
                }
            blocks++;
        }
        stop = true;
        params.join();
        snprintf(d, sizeof d, "%d blocks, %d calls, %d bad blocks, %d failed calls", blocks, sets.load(), bad,
                 setFailures.load());
        check("no failed block, no failed call, all output finite", bad == 0 && setFailures == 0, d);
    }

    // ---- 10: close and destroy ------------------------------------------------------
    printf("\n[10] close and destroyEffect\n");
    {
        check("close while PROCESSING is refused", !aidl.remote->close().isOk());
        check("STOP then close", aidl.remote->command(CommandId::STOP).isOk() && aidl.remote->close().isOk());
        aidl.remote->getState(&state);
        check("INIT after close", state == State::INIT);
        aidl.remote.reset();
        check("destroyEffect", gDestroy(aidl.effect) == EX_NONE);
        aidl.effect.reset();
    }

    // ---- 11: repeated create, open, process, destroy ------------------------------
    printf("\n[11] 60 effects created, used and destroyed\n");
    {
        int okCount = 0;
        size_t before = 0;
        for (int i = 0; i < 60; i++) {
            if (i == 10) before = mallinfo().uordblks;
            AidlSide s;
            IEffect::OpenEffectReturn r;
            if (!createRemote(s) || !s.open(makeCommon(48000, 480), &r)) continue;
            bool ok = s.setShort(112, 6) && s.setShort(1201, 1) &&
                      s.remote->command(CommandId::START).isOk();
            std::vector<float> in(960, 0.25f), out(960);
            for (int k = 0; k < 8 && ok; k++) ok = s.process(in.data(), out.data(), 480);
            // V3 and later let the factory destroy an effect in any state;
            // earlier versions need it closed first, as audioserver does.
            if (s.version < 3) ok &= s.remote->command(CommandId::STOP).isOk() && s.remote->close().isOk();
            s.remote.reset();
            ok &= gDestroy(s.effect) == EX_NONE;
            s.effect.reset();
            okCount += ok;
        }
        const size_t after = mallinfo().uordblks;
        long grown = (long)after - (long)before;
        snprintf(d, sizeof d, "%d of 60 ok, heap %zu -> %zu bytes (%+ld) over the last 50", okCount,
                 before, after, grown);
        check("every cycle works and the engine is freed each time", okCount == 60 && grown < 4 * 1024 * 1024, d);
    }

    printf("\n%s: %d failure%s\n", failures ? "FAILED" : "PASSED", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
