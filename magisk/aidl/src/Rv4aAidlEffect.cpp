/*
 * Copyright (C) 2022 The Android Open Source Project
 *               2025 anonymix007
 *               2026 alienware377
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The rooted engine as an AIDL effect plugin.
 *
 * On devices whose audio effects run behind the AIDL effect HAL (Android 14
 * and later on most new phones), the vendor's effect factory reads
 * audio_effects_config.xml, dlopens every library it lists and looks up three
 * functions: createEffect, queryEffect and destroyEffect. This file provides
 * them. Nothing replaces or proxies the factory, so if this library fails,
 * our effect is missing and every other effect keeps working.
 *
 * The shape of the adapter follows anonymix007's JamesDSP AIDL wrapper
 * (JamesDSPManager pull request 68): AOSP's EffectImpl does the IEffect
 * protocol and the audio queues, and the effect itself is the legacy one,
 * driven through its own command and process entry points. So every
 * parameter, payload and safety check of the legacy root mode applies here
 * unchanged, and the app needs no AIDL code: the framework hands legacy
 * setParameter and getParameter calls to an AIDL effect as a VendorExtension
 * whose DefaultExtension holds the original effect_param_t bytes.
 *
 * Built once per effect HAL version (RV4A_HAL_VERSION), against that version's
 * frozen interfaces and AOSP's effect base from the matching release. The
 * factory and this library exchange C++ objects directly, so a library built
 * for another version would write a differently laid out Descriptor into the
 * factory's memory. The installer only ever installs the matching build.
 */
#include <errno.h>
#include <string.h>

#include <algorithm>
#include <memory>
#include <string>
#include <vector>

#define LOG_TAG "RV4A-AIDL"
#include <android-base/logging.h>

#include <aidl/android/hardware/audio/effect/DefaultExtension.h>
#include <aidl/android/hardware/audio/effect/VendorExtension.h>
#include <aidl/android/media/audio/common/AudioChannelLayout.h>

#include "effect-impl/EffectImpl.h"

#include "LegacyBridge.h"

#ifndef RV4A_HAL_VERSION
#error "RV4A_HAL_VERSION must be set to the effect HAL version this build targets"
#endif

using aidl::android::hardware::audio::effect::CommandId;
using aidl::android::hardware::audio::effect::DefaultExtension;
using aidl::android::hardware::audio::effect::Descriptor;
using aidl::android::hardware::audio::effect::EffectContext;
using aidl::android::hardware::audio::effect::EffectImpl;
using aidl::android::hardware::audio::effect::Flags;
using aidl::android::hardware::audio::effect::IEffect;
using aidl::android::hardware::audio::effect::Parameter;
using aidl::android::hardware::audio::effect::RetCode;
using aidl::android::hardware::audio::effect::VendorExtension;
using aidl::android::media::audio::common::AudioChannelLayout;
using aidl::android::media::audio::common::AudioConfig;
using aidl::android::media::audio::common::AudioFormatType;
using aidl::android::media::audio::common::AudioUuid;
using aidl::android::media::audio::common::PcmType;

namespace {

/* Same type and implementation uuids as the legacy descriptor and as
   EFFECT_TYPE_CUSTOM / EFFECT_JAMESDSP in JamesDspRemoteEngine. AudioUuid
   holds the fields as signed ints, so values with the top bit set are
   written as their bit pattern. */
AudioUuid makeUuid(uint32_t timeLow, uint16_t timeMid, uint16_t timeHi, uint16_t clockSeq,
                   std::initializer_list<uint8_t> node) {
    AudioUuid u;
    u.timeLow = static_cast<int32_t>(timeLow);
    u.timeMid = timeMid;
    u.timeHiAndVersion = timeHi;
    u.clockSeq = clockSeq;
    u.node = std::vector<uint8_t>(node);
    return u;
}

const AudioUuid& typeUuid() {
    static const AudioUuid u =
            makeUuid(0xf98765f4, 0xc321, 0x5de6, 0x9a45, {0x12, 0x34, 0x59, 0x49, 0x5a, 0xb2});
    return u;
}

const AudioUuid& implUuid() {
    static const AudioUuid u =
            makeUuid(0xf27317f4, 0xc984, 0x4de6, 0x9a90, {0x54, 0x57, 0x59, 0x49, 0x5b, 0xf2});
    return u;
}

/* The app recognises its own engine by "RootfulViPER4Android" in the name
   and reads the engine version from "engine v<N>", so the name is the legacy
   effect's own, with " AIDL" after it to tell the two paths apart in logs and
   dumps. rv4a_legacy_name is only called from inside this function-local
   static's initialiser, which C++ runs exactly once. */
const Descriptor& rv4aDescriptor() {
    static const Descriptor d = [] {
        Descriptor desc;
        desc.common.id.type = typeUuid();
        desc.common.id.uuid = implUuid();
        desc.common.flags.type = Flags::Type::INSERT;
        desc.common.flags.insert = Flags::Insert::FIRST;
        desc.common.flags.volume = Flags::Volume::NONE;
        /* Offloaded and hardware-accelerated tracks bypass software effects
           on every device; saying otherwise would only get us asked to
           process audio we never see. */
        desc.common.flags.offloadIndication = false;
        desc.common.cpuLoad = 10;
        desc.common.memoryUsage = 1;
        desc.common.name = std::string(rv4a_legacy_name()) + " AIDL";
        desc.common.implementor = "alienware377";
        return desc;
    }();
    return d;
}

/* The engine processes interleaved float stereo and nothing else. Anything
   else is refused when the effect is opened or reconfigured, before AOSP's
   base classes see it: their constructor treats a bad configuration as fatal,
   and fatal inside the audio HAL means every sound on the phone stops. */
bool isStereo(const AudioChannelLayout& layout) {
    switch (layout.getTag()) {
        case AudioChannelLayout::Tag::layoutMask:
            return layout.get<AudioChannelLayout::Tag::layoutMask>() ==
                   AudioChannelLayout::LAYOUT_STEREO;
        case AudioChannelLayout::Tag::indexMask:
            return layout.get<AudioChannelLayout::Tag::indexMask>() ==
                   AudioChannelLayout::INDEX_MASK_2;
        default:
            return false;
    }
}

/* Same bound as RV4A_MAX_FRAMES in the legacy effect: beyond this a frame
   count is a corrupt configuration rather than a real buffer. */
constexpr int64_t kMaxFrames = 65536;

const char* checkConfig(const Parameter::Common& common) {
    const AudioConfig& in = common.input;
    const AudioConfig& out = common.output;
    if (in.base.format.type != AudioFormatType::PCM ||
        out.base.format.type != AudioFormatType::PCM ||
        in.base.format.pcm != PcmType::FLOAT_32_BIT ||
        out.base.format.pcm != PcmType::FLOAT_32_BIT)
        return "only 32-bit float audio is supported";
    if (!isStereo(in.base.channelMask) || !isStereo(out.base.channelMask))
        return "only stereo is supported";
    if (in.base.sampleRate <= 0 || in.base.sampleRate != out.base.sampleRate)
        return "input and output rates must match";
    if (in.frameCount <= 0 || out.frameCount <= 0 || in.frameCount > kMaxFrames ||
        out.frameCount > kMaxFrames)
        return "implausible frame count";
    return nullptr;
}

class Rv4aContext final : public EffectContext {
  public:
    Rv4aContext(int statusDepth, const Parameter::Common& common)
        : EffectContext(statusDepth, common), mLegacy(rv4a_legacy_create()) {
        if (mLegacy && rv4a_legacy_configure(mLegacy, common.input.base.sampleRate) != 0) {
            LOG(ERROR) << "legacy SET_CONFIG refused " << common.input.base.sampleRate << " Hz";
            rv4a_legacy_release(mLegacy);
            mLegacy = nullptr;
        }
    }
    ~Rv4aContext() override { rv4a_legacy_release(mLegacy); }

    bool valid() const { return mLegacy != nullptr; }
    void* legacy() const { return mLegacy; }

    /* Arrives on every route change. The legacy effect keeps its settings and
       loaded payloads across SET_CONFIG and only follows the rate. */
    RetCode setCommon(const Parameter::Common& common) override {
        if (const char* why = checkConfig(common)) {
            LOG(ERROR) << "setCommon refused: " << why;
            return RetCode::ERROR_ILLEGAL_PARAMETER;
        }
        if (auto ret = EffectContext::setCommon(common); ret != RetCode::SUCCESS) return ret;
        if (mLegacy && rv4a_legacy_configure(mLegacy, common.input.base.sampleRate) != 0) {
            LOG(ERROR) << "legacy SET_CONFIG refused " << common.input.base.sampleRate << " Hz";
            return RetCode::ERROR_ILLEGAL_PARAMETER;
        }
        return RetCode::SUCCESS;
    }

  private:
    void* mLegacy;
};

class Rv4aAidlEffect final : public EffectImpl {
  public:
    Rv4aAidlEffect() = default;
    ~Rv4aAidlEffect() override { cleanUp(); }

    ndk::ScopedAStatus open(const Parameter::Common& common,
                            const std::optional<Parameter::Specific>& specific,
                            OpenEffectReturn* ret) override {
        if (const char* why = checkConfig(common)) {
            LOG(ERROR) << "open refused: " << why << " (" << common.toString() << ")";
            return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, why);
        }
        return EffectImpl::open(common, specific, ret);
    }

    ndk::ScopedAStatus getDescriptor(Descriptor* desc) override {
        if (!desc) return ndk::ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
        *desc = rv4aDescriptor();
        return ndk::ScopedAStatus::ok();
    }

    std::string getEffectName() override { return "RV4A"; }

    std::shared_ptr<EffectContext> createContext(const Parameter::Common& common) override {
        if (mContext) return mContext;
        auto context = std::make_shared<Rv4aContext>(1 /* status queue depth */, common);
        if (!context->valid()) {
            LOG(ERROR) << "createContext: the engine could not be created";
            return nullptr;
        }
        mContext = context;
        return mContext;
    }

    RetCode releaseContext() override {
        mContext.reset();
        return RetCode::SUCCESS;
    }

    /* SET_PARAM from the app, as the whole legacy effect_param_t. */
    ndk::ScopedAStatus setParameterSpecific(const Parameter::Specific& specific) override {
        RETURN_IF(!mContext, EX_NULL_POINTER, "nullContext");
        RETURN_IF(specific.getTag() != Parameter::Specific::vendorEffect, EX_ILLEGAL_ARGUMENT,
                  "onlyVendorExtensionSupported");
        const auto& ext = specific.get<Parameter::Specific::vendorEffect>();
        std::optional<DefaultExtension> payload;
        RETURN_IF(ext.extension.getParcelable(&payload) != STATUS_OK || !payload.has_value(),
                  EX_ILLEGAL_ARGUMENT, "notADefaultExtension");
        const auto& bytes = payload->bytes;
        RETURN_IF(bytes.size() < rv4a_legacy_param_header_size() || bytes.size() > UINT32_MAX,
                  EX_ILLEGAL_ARGUMENT, "shortParam");
        const int32_t rc = rv4a_legacy_set_param(mContext->legacy(), bytes.data(),
                                                 static_cast<uint32_t>(bytes.size()));
        RETURN_IF(rc != 0, EX_ILLEGAL_ARGUMENT, "setParamRefused");
        return ndk::ScopedAStatus::ok();
    }

    /* GET_PARAM from the app. The reply is a whole effect_param_t too: the
       framework copies status, sizes, id and value back from it. The app
       reads the engine's pid and rate on every settings sync and treats a
       failure as a crashed engine, re-creating it, so this is not optional. */
    ndk::ScopedAStatus getParameterSpecific(const Parameter::Id& id,
                                            Parameter::Specific* specific) override {
        RETURN_IF(!mContext, EX_NULL_POINTER, "nullContext");
        RETURN_IF(!specific, EX_NULL_POINTER, "nullSpecific");
        RETURN_IF(id.getTag() != Parameter::Id::vendorEffectTag, EX_ILLEGAL_ARGUMENT,
                  "onlyVendorExtensionSupported");
        const auto& ext = id.get<Parameter::Id::vendorEffectTag>();
        std::optional<DefaultExtension> query;
        RETURN_IF(ext.extension.getParcelable(&query) != STATUS_OK || !query.has_value(),
                  EX_ILLEGAL_ARGUMENT, "notADefaultExtension");
        RETURN_IF(query->bytes.size() < rv4a_legacy_param_header_size() ||
                          query->bytes.size() > 4096,
                  EX_ILLEGAL_ARGUMENT, "badQuery");

        DefaultExtension reply;
        reply.bytes.resize(64);
        uint32_t replySize = static_cast<uint32_t>(reply.bytes.size());
        const int32_t rc = rv4a_legacy_get_param(mContext->legacy(), query->bytes.data(),
                                                 static_cast<uint32_t>(query->bytes.size()),
                                                 reply.bytes.data(), &replySize);
        RETURN_IF(rc != 0 || replySize > reply.bytes.size(), EX_ILLEGAL_ARGUMENT,
                  "getParamRefused");
        reply.bytes.resize(replySize);

        VendorExtension out;
        RETURN_IF(out.extension.setParcelable(reply) != STATUS_OK, EX_ILLEGAL_ARGUMENT,
                  "setParcelableFailed");
        specific->set<Parameter::Specific::vendorEffect>(out);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus commandImpl(CommandId command) override {
        RETURN_IF(!mContext, EX_NULL_POINTER, "nullContext");
        switch (command) {
            case CommandId::START:
                RETURN_IF(rv4a_legacy_enable(mContext->legacy(), true) != 0, EX_ILLEGAL_STATE,
                          "enableRefused");
                break;
            case CommandId::STOP:
            case CommandId::RESET:
                rv4a_legacy_enable(mContext->legacy(), false);
                break;
            default:
                break;
        }
        /* The base keeps the context's own state and, on RESET, drops audio
           still queued from before. */
        return EffectImpl::commandImpl(command);
    }

    /* Runs on the effect's worker thread with the effect lock held, so it
       never races a parameter change. in and out are the same buffer. */
    IEffect::Status effectProcessImpl(float* in, float* out, int samples) override {
        if (samples <= 0) return {STATUS_OK, 0, 0};
        const size_t frames = static_cast<size_t>(samples) / 2;
        const int32_t rc = mContext && frames
                                   ? rv4a_legacy_process(mContext->legacy(), in, out, frames)
                                   : -ENODATA;
        if (rc != 0) {
            /* Not enabled yet, or a buffer the engine refused. Pass the audio
               through untouched rather than report an error: an effect that
               fails its status makes the framework drop the whole chain, and
               returning nothing would be a gap in every sound. */
            if (in != out) memcpy(out, in, static_cast<size_t>(samples) * sizeof(float));
            if (rc != -ENODATA && (mRefusals++ % 1000) == 0)
                LOG(WARNING) << "process: engine refused " << frames << " frames (" << rc
                             << "), passing through";
        }
        return {STATUS_OK, samples, samples};
    }

  private:
    std::shared_ptr<Rv4aContext> mContext;
    uint32_t mRefusals = 0;
};

}  // namespace

/* The three entry points the effect factory looks up. Everything else in the
   library is hidden by the version script, including the legacy effect's own
   entry points and our private copy of the interface code. destroyEffect is
   AOSP's, in EffectImpl.cpp. */
extern "C" binder_exception_t createEffect(const AudioUuid* in_impl_uuid,
                                           std::shared_ptr<IEffect>* instanceSpp) {
    if (!in_impl_uuid || *in_impl_uuid != implUuid()) {
        LOG(ERROR) << "createEffect: not our uuid";
        return EX_ILLEGAL_ARGUMENT;
    }
    if (!instanceSpp) {
        LOG(ERROR) << "createEffect: null output";
        return EX_ILLEGAL_ARGUMENT;
    }
    *instanceSpp = ndk::SharedRefBase::make<Rv4aAidlEffect>();
    LOG(INFO) << "createEffect: effect HAL V" << RV4A_HAL_VERSION << " instance "
              << instanceSpp->get();
    return EX_NONE;
}

extern "C" binder_exception_t queryEffect(const AudioUuid* in_impl_uuid, Descriptor* _aidl_return) {
    if (!in_impl_uuid || *in_impl_uuid != implUuid()) {
        LOG(ERROR) << "queryEffect: not our uuid";
        return EX_ILLEGAL_ARGUMENT;
    }
    if (!_aidl_return) return EX_ILLEGAL_ARGUMENT;
    *_aidl_return = rv4aDescriptor();
    return EX_NONE;
}
