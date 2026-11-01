/* Stand-in for libaudioaidlcommon's Utils.h, written for this project.
 *
 * AOSP's EffectContext needs two helpers from it, to size the audio queues
 * from the negotiated format. The real header pulls in libutils and a dozen
 * other helpers, so these two are reimplemented with the same results as the
 * platform's for every layout and sample type. */
#pragma once
#include <cstddef>
#include <cstdint>
#include <limits>

#include <aidl/android/media/audio/common/AudioChannelLayout.h>
#include <aidl/android/media/audio/common/AudioFormatDescription.h>
#include <aidl/android/media/audio/common/AudioFormatType.h>
#include <aidl/android/media/audio/common/PcmType.h>

namespace aidl::android::hardware::audio::common {

constexpr size_t getPcmSampleSizeInBytes(::aidl::android::media::audio::common::PcmType pcm) {
    using ::aidl::android::media::audio::common::PcmType;
    switch (pcm) {
        case PcmType::UINT_8_BIT: return 1;
        case PcmType::INT_16_BIT: return 2;
        case PcmType::INT_32_BIT: return 4;
        case PcmType::FIXED_Q_8_24: return 4;
        case PcmType::FLOAT_32_BIT: return 4;
        case PcmType::INT_24_BIT: return 3;
        default: return 0;
    }
}

inline size_t getChannelCount(
        const ::aidl::android::media::audio::common::AudioChannelLayout& layout,
        int32_t mask = std::numeric_limits<int32_t>::max()) {
    using Layout = ::aidl::android::media::audio::common::AudioChannelLayout;
    switch (layout.getTag()) {
        case Layout::Tag::indexMask:
            return __builtin_popcount(layout.get<Layout::Tag::indexMask>() & mask);
        case Layout::Tag::layoutMask:
            return __builtin_popcount(layout.get<Layout::Tag::layoutMask>() & mask);
        case Layout::Tag::voiceMask:
            return __builtin_popcount(layout.get<Layout::Tag::voiceMask>() & mask);
        default:
            // none, invalid, and any layout kind added after this was written.
            // The effect refuses anything that is not plain stereo before it
            // gets here, so zero only ever reaches a queue that is not used.
            return 0;
    }
}

inline size_t getFrameSizeInBytes(
        const ::aidl::android::media::audio::common::AudioFormatDescription& format,
        const ::aidl::android::media::audio::common::AudioChannelLayout& layout) {
    using ::aidl::android::media::audio::common::AudioFormatDescription;
    using ::aidl::android::media::audio::common::AudioFormatType;
    if (format == AudioFormatDescription{}) return 0;
    if (format.type == AudioFormatType::PCM) {
        return getPcmSampleSizeInBytes(format.pcm) * getChannelCount(layout);
    }
    if (format.type == AudioFormatType::NON_PCM) return getPcmSampleSizeInBytes(format.pcm);
    return 0;
}

}  // namespace aidl::android::hardware::audio::common
