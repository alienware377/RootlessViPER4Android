/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <memory>
#define LOG_TAG "AHAL_EffectContext"
#include "effect-impl/EffectContext.h"
#include "include/effect-impl/EffectTypes.h"

using aidl::android::hardware::audio::common::getChannelCount;
using aidl::android::hardware::audio::common::getFrameSizeInBytes;
using aidl::android::hardware::audio::effect::IEffect;
using aidl::android::hardware::audio::effect::kReopenSupportedVersion;
using aidl::android::media::audio::common::PcmType;
using ::android::hardware::EventFlag;

namespace aidl::android::hardware::audio::effect {

EffectContext::EffectContext(size_t statusDepth, const Parameter::Common& common) {
    LOG_ALWAYS_FATAL_IF(RetCode::SUCCESS != setCommon(common), "illegalCommonParameter");

    // in/outBuffer size in float (FMQ data format defined for DataMQ)
    size_t inBufferSizeInFloat = common.input.frameCount * mInputFrameSize / sizeof(float);
    size_t outBufferSizeInFloat = common.output.frameCount * mOutputFrameSize / sizeof(float);

    // only status FMQ use the EventFlag
    mStatusMQ = std::make_shared<StatusMQ>(statusDepth, true /*configureEventFlagWord*/);
    mInputMQ = std::make_shared<DataMQ>(inBufferSizeInFloat);
    mOutputMQ = std::make_shared<DataMQ>(outBufferSizeInFloat);

    if (!mStatusMQ->isValid() || !mInputMQ->isValid() || !mOutputMQ->isValid()) {
        LOG(ERROR) << __func__ << " created invalid FMQ, statusMQ: " << mStatusMQ->isValid()
                   << " inputMQ: " << mInputMQ->isValid() << " outputMQ: " << mOutputMQ->isValid();
    }

    ::android::status_t status =
            EventFlag::createEventFlag(mStatusMQ->getEventFlagWord(), &mEfGroup);
    LOG_ALWAYS_FATAL_IF(status != ::android::OK || !mEfGroup, " create EventFlagGroup failed ");
    mWorkBuffer.resize(std::max(inBufferSizeInFloat, outBufferSizeInFloat));
}

// reset buffer status by abandon input data in FMQ
void EffectContext::resetBuffer() {
    auto buffer = static_cast<float*>(mWorkBuffer.data());
    if (mStatusMQ) {
        std::vector<IEffect::Status> status(mStatusMQ->availableToRead());
    }
    if (mInputMQ) {
        mInputMQ->read(buffer, mInputMQ->availableToRead());
    }
}

void EffectContext::dupeFmqWithReopen(IEffect::OpenEffectReturn* effectRet) {
    if (!mInputMQ) {
        mInputMQ = std::make_shared<DataMQ>(mCommon.input.frameCount * mInputFrameSize /
                                            sizeof(float));
    }
    if (!mOutputMQ) {
        mOutputMQ = std::make_shared<DataMQ>(mCommon.output.frameCount * mOutputFrameSize /
                                             sizeof(float));
    }
    dupeFmq(effectRet);
}

void EffectContext::dupeFmq(IEffect::OpenEffectReturn* effectRet) {
    if (effectRet && mStatusMQ && mInputMQ && mOutputMQ) {
        effectRet->statusMQ = mStatusMQ->dupeDesc();
        effectRet->inputDataMQ = mInputMQ->dupeDesc();
        effectRet->outputDataMQ = mOutputMQ->dupeDesc();
    }
}

float* EffectContext::getWorkBuffer() {
    return static_cast<float*>(mWorkBuffer.data());
}

size_t EffectContext::getWorkBufferSize() const {
    return mWorkBuffer.size();
}

std::shared_ptr<EffectContext::StatusMQ> EffectContext::getStatusFmq() const {
    return mStatusMQ;
}

std::shared_ptr<EffectContext::DataMQ> EffectContext::getInputDataFmq() const {
    return mInputMQ;
}

std::shared_ptr<EffectContext::DataMQ> EffectContext::getOutputDataFmq() const {
    return mOutputMQ;
}

size_t EffectContext::getInputFrameSize() const {
    return mInputFrameSize;
}

size_t EffectContext::getOutputFrameSize() const {
    return mOutputFrameSize;
}

int EffectContext::getSessionId() const {
    return mCommon.session;
}

int EffectContext::getIoHandle() const {
    return mCommon.ioHandle;
}

RetCode EffectContext::setOutputDevice(
        const std::vector<aidl::android::media::audio::common::AudioDeviceDescription>& device) {
    mOutputDevice = device;
    return RetCode::SUCCESS;
}

std::vector<aidl::android::media::audio::common::AudioDeviceDescription>
EffectContext::getOutputDevice() {
    return mOutputDevice;
}

RetCode EffectContext::setAudioMode(const aidl::android::media::audio::common::AudioMode& mode) {
    mMode = mode;
    return RetCode::SUCCESS;
}
aidl::android::media::audio::common::AudioMode EffectContext::getAudioMode() {
    return mMode;
}

RetCode EffectContext::setAudioSource(
        const aidl::android::media::audio::common::AudioSource& source) {
    mSource = source;
    return RetCode::SUCCESS;
}

aidl::android::media::audio::common::AudioSource EffectContext::getAudioSource() {
    return mSource;
}

RetCode EffectContext::setVolumeStereo(const Parameter::VolumeStereo& volumeStereo) {
    mVolumeStereo = volumeStereo;
    return RetCode::SUCCESS;
}

Parameter::VolumeStereo EffectContext::getVolumeStereo() {
    return mVolumeStereo;
}

RetCode EffectContext::setCommon(const Parameter::Common& common) {
    auto& input = common.input;
    auto& output = common.output;

    if (input.base.format.pcm != aidl::android::media::audio::common::PcmType::FLOAT_32_BIT ||
        output.base.format.pcm != aidl::android::media::audio::common::PcmType::FLOAT_32_BIT) {
        LOG(ERROR) << __func__ << " illegal IO, input "
                   << ::android::internal::ToString(input.base.format) << ", output "
                   << ::android::internal::ToString(output.base.format);
        return RetCode::ERROR_ILLEGAL_PARAMETER;
    }

    if (auto ret = updateIOFrameSize(common); ret != RetCode::SUCCESS) {
        return ret;
    }

    mInputChannelCount = getChannelCount(input.base.channelMask);
    mOutputChannelCount = getChannelCount(output.base.channelMask);
    if (mInputChannelCount == 0 || mOutputChannelCount == 0) {
        LOG(ERROR) << __func__ << " illegal channel count input " << mInputChannelCount
                   << ", output " << mOutputChannelCount;
        return RetCode::ERROR_ILLEGAL_PARAMETER;
    }

    mCommon = common;
    return RetCode::SUCCESS;
}

Parameter::Common EffectContext::getCommon() {
    return mCommon;
}

EventFlag* EffectContext::getStatusEventFlag() {
    return mEfGroup;
}

RetCode EffectContext::updateIOFrameSize(const Parameter::Common& common) {
    const auto prevInputFrameSize = mInputFrameSize;
    const auto prevOutputFrameSize = mOutputFrameSize;
    mInputFrameSize = ::aidl::android::hardware::audio::common::getFrameSizeInBytes(
            common.input.base.format, common.input.base.channelMask);
    mOutputFrameSize = ::aidl::android::hardware::audio::common::getFrameSizeInBytes(
            common.output.base.format, common.output.base.channelMask);

    // workBuffer and data MQ not allocated yet, no need to update
    if (mWorkBuffer.size() == 0 || !mInputMQ || !mOutputMQ) {
        return RetCode::SUCCESS;
    }
    // IEffect::reopen introduced in android.hardware.audio.effect-V2
    if (mVersion < kReopenSupportedVersion) {
        LOG(WARNING) << __func__ << " skipped for HAL version " << mVersion;
        return RetCode::SUCCESS;
    }
    bool needUpdateMq = false;
    if (mInputFrameSize != prevInputFrameSize ||
        mCommon.input.frameCount != common.input.frameCount) {
        mInputMQ.reset();
        needUpdateMq = true;
    }
    if (mOutputFrameSize != prevOutputFrameSize ||
        mCommon.output.frameCount != common.output.frameCount) {
        mOutputMQ.reset();
        needUpdateMq = true;
    }

    if (needUpdateMq) {
        mWorkBuffer.resize(std::max(common.input.frameCount * mInputFrameSize / sizeof(float),
                                    common.output.frameCount * mOutputFrameSize / sizeof(float)));
        return notifyDataMqUpdate();
    }
    return RetCode::SUCCESS;
}

RetCode EffectContext::notifyDataMqUpdate() {
    if (!mEfGroup) {
        LOG(ERROR) << __func__ << ": invalid EventFlag group";
        return RetCode::ERROR_EVENT_FLAG_ERROR;
    }

    if (const auto ret = mEfGroup->wake(kEventFlagDataMqUpdate); ret != ::android::OK) {
        LOG(ERROR) << __func__ << ": wake failure with ret " << ret;
        return RetCode::ERROR_EVENT_FLAG_ERROR;
    }
    LOG(VERBOSE) << __func__ << " : signal client for reopen";
    return RetCode::SUCCESS;
}
}  // namespace aidl::android::hardware::audio::effect
