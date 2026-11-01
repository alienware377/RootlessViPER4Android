/*
 * Copyright (C) 2022 The Android Open Source Project
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
 */

#pragma once
#include <memory>
#include <vector>

#include <Utils.h>
#include <android-base/logging.h>
#include <fmq/AidlMessageQueue.h>
#include <fmq/EventFlag.h>

#include <aidl/android/hardware/audio/effect/BnEffect.h>
#include "EffectTypes.h"

namespace aidl::android::hardware::audio::effect {

class EffectContext {
  public:
    typedef ::android::AidlMessageQueue<
            IEffect::Status, ::aidl::android::hardware::common::fmq::SynchronizedReadWrite>
            StatusMQ;
    typedef ::android::AidlMessageQueue<
            float, ::aidl::android::hardware::common::fmq::SynchronizedReadWrite>
            DataMQ;

    EffectContext(size_t statusDepth, const Parameter::Common& common);
    virtual ~EffectContext() {
        if (mEfGroup) {
            ::android::hardware::EventFlag::deleteEventFlag(&mEfGroup);
        }
    }

    void setVersion(int version) { mVersion = version; }
    std::shared_ptr<StatusMQ> getStatusFmq() const;
    std::shared_ptr<DataMQ> getInputDataFmq() const;
    std::shared_ptr<DataMQ> getOutputDataFmq() const;

    float* getWorkBuffer();
    size_t getWorkBufferSize() const;

    // reset buffer status by abandon input data in FMQ
    void resetBuffer();
    void dupeFmq(IEffect::OpenEffectReturn* effectRet);
    size_t getInputFrameSize() const;
    size_t getOutputFrameSize() const;
    int getSessionId() const;
    int getIoHandle() const;

    virtual void dupeFmqWithReopen(IEffect::OpenEffectReturn* effectRet);

    virtual RetCode setOutputDevice(
            const std::vector<aidl::android::media::audio::common::AudioDeviceDescription>& device);

    virtual std::vector<aidl::android::media::audio::common::AudioDeviceDescription>
    getOutputDevice();

    virtual RetCode setAudioMode(const aidl::android::media::audio::common::AudioMode& mode);
    virtual aidl::android::media::audio::common::AudioMode getAudioMode();

    virtual RetCode setAudioSource(const aidl::android::media::audio::common::AudioSource& source);
    virtual aidl::android::media::audio::common::AudioSource getAudioSource();

    virtual RetCode setVolumeStereo(const Parameter::VolumeStereo& volumeStereo);
    virtual Parameter::VolumeStereo getVolumeStereo();

    virtual RetCode setCommon(const Parameter::Common& common);
    virtual Parameter::Common getCommon();

    virtual ::android::hardware::EventFlag* getStatusEventFlag();

  protected:
    int mVersion = 0;
    size_t mInputFrameSize = 0;
    size_t mOutputFrameSize = 0;
    size_t mInputChannelCount = 0;
    size_t mOutputChannelCount = 0;
    Parameter::Common mCommon = {};
    std::vector<aidl::android::media::audio::common::AudioDeviceDescription> mOutputDevice = {};
    aidl::android::media::audio::common::AudioMode mMode =
            aidl::android::media::audio::common::AudioMode::SYS_RESERVED_INVALID;
    aidl::android::media::audio::common::AudioSource mSource =
            aidl::android::media::audio::common::AudioSource::SYS_RESERVED_INVALID;
    Parameter::VolumeStereo mVolumeStereo = {};
    RetCode updateIOFrameSize(const Parameter::Common& common);
    RetCode notifyDataMqUpdate();

  private:
    // fmq and buffers
    std::shared_ptr<StatusMQ> mStatusMQ = nullptr;
    std::shared_ptr<DataMQ> mInputMQ = nullptr;
    std::shared_ptr<DataMQ> mOutputMQ = nullptr;
    // std::shared_ptr<IEffect::OpenEffectReturn> mRet;
    // work buffer set by effect instances, the access and update are in same thread
    std::vector<float> mWorkBuffer = {};

    ::android::hardware::EventFlag* mEfGroup = nullptr;
};
}  // namespace aidl::android::hardware::audio::effect
