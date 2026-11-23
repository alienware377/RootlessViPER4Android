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
///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a snapshot of an AIDL file. Do not edit it manually. There are
// two cases:
// 1). this is a frozen version file - do not edit this in any case.
// 2). this is a 'current' file. If you make a backwards compatible change to
//     the interface (from the latest frozen version), the build system will
//     prompt you to update this file with `m <name>-update-api`.
//
// You must not make a backward incompatible change to any AIDL file built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

package android.media.audio.common;
/* @hide */
@SuppressWarnings(value={"redundant-name"}) @VintfStability
union AudioPolicyForceUse {
  android.media.audio.common.AudioPolicyForceUse.MediaDeviceCategory forMedia = android.media.audio.common.AudioPolicyForceUse.MediaDeviceCategory.NONE;
  android.media.audio.common.AudioPolicyForceUse.CommunicationDeviceCategory forCommunication = android.media.audio.common.AudioPolicyForceUse.CommunicationDeviceCategory.NONE;
  android.media.audio.common.AudioPolicyForceUse.CommunicationDeviceCategory forRecord = android.media.audio.common.AudioPolicyForceUse.CommunicationDeviceCategory.NONE;
  android.media.audio.common.AudioPolicyForceUse.CommunicationDeviceCategory forVibrateRinging = android.media.audio.common.AudioPolicyForceUse.CommunicationDeviceCategory.NONE;
  android.media.audio.common.AudioPolicyForceUse.DockType dock = android.media.audio.common.AudioPolicyForceUse.DockType.NONE;
  boolean systemSounds = false;
  boolean hdmiSystemAudio = false;
  android.media.audio.common.AudioPolicyForceUse.EncodedSurroundConfig encodedSurround = android.media.audio.common.AudioPolicyForceUse.EncodedSurroundConfig.UNSPECIFIED;
  @Backing(type="byte") @VintfStability
  enum CommunicationDeviceCategory {
    NONE = 0,
    SPEAKER,
    BT_SCO,
    BT_BLE,
    WIRED_ACCESSORY,
  }
  @Backing(type="byte") @VintfStability
  enum MediaDeviceCategory {
    NONE = 0,
    SPEAKER,
    HEADPHONES,
    BT_A2DP,
    ANALOG_DOCK,
    DIGITAL_DOCK,
    WIRED_ACCESSORY,
    NO_BT_A2DP,
  }
  @Backing(type="byte") @VintfStability
  enum DockType {
    NONE = 0,
    BT_CAR_DOCK,
    BT_DESK_DOCK,
    ANALOG_DOCK,
    DIGITAL_DOCK,
    WIRED_ACCESSORY,
  }
  @Backing(type="byte") @VintfStability
  enum EncodedSurroundConfig {
    UNSPECIFIED = 0,
    NEVER,
    ALWAYS,
    MANUAL,
  }
}
