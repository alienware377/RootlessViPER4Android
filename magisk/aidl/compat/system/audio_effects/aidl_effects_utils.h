/* Stand-in for system/media's aidl_effects_utils.h, written for this project.
 *
 * AOSP's effect base classes take a handful of protocol constants from that
 * header. The real one also brings range clamping helpers that need further
 * platform headers (audio_utils, elementwise_op) and that a vendor extension
 * effect never uses, so only the constants are reproduced. The values are part
 * of the effect HAL's wire protocol with the framework and must not change:
 * the framework wakes the effect with these exact event flag bits. */
#pragma once
#include <cstdint>

namespace aidl::android::hardware::audio::effect {

// IEffect.reopen() exists from effect HAL V2.
static constexpr int32_t kReopenSupportedVersion = 2;
// Draining and destroying an effect in any state exist from V3.
static constexpr int32_t kDrainSupportedVersion = 3;
static constexpr int32_t kDestroyAnyStateSupportedVersion = 3;

// Status queue event flag bits. V1 effects are woken with kEventFlagNotEmpty;
// V2 and later with kEventFlagDataMqNotEmpty, and kEventFlagDataMqUpdate tells
// the framework that the data queues were replaced and reopen() is due.
static constexpr uint32_t kEventFlagNotEmpty = 0x1;
static constexpr uint32_t kEventFlagDataMqUpdate = 0x1 << 10;
static constexpr uint32_t kEventFlagDataMqNotEmpty = 0x1 << 11;

}  // namespace aidl::android::hardware::audio::effect
