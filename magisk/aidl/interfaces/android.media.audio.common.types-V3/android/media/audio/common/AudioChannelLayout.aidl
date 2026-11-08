/*
 * Copyright (C) 2021 The Android Open Source Project
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
@JavaDerive(equals=true, toString=true) @SuppressWarnings(value={"redundant-name"}) @VintfStability
union AudioChannelLayout {
  int none = 0;
  int invalid = 0;
  int indexMask;
  int layoutMask;
  int voiceMask;
  const int INDEX_MASK_1 = ((1 << 1) - 1) /* 1 */;
  const int INDEX_MASK_2 = ((1 << 2) - 1) /* 3 */;
  const int INDEX_MASK_3 = ((1 << 3) - 1) /* 7 */;
  const int INDEX_MASK_4 = ((1 << 4) - 1) /* 15 */;
  const int INDEX_MASK_5 = ((1 << 5) - 1) /* 31 */;
  const int INDEX_MASK_6 = ((1 << 6) - 1) /* 63 */;
  const int INDEX_MASK_7 = ((1 << 7) - 1) /* 127 */;
  const int INDEX_MASK_8 = ((1 << 8) - 1) /* 255 */;
  const int INDEX_MASK_9 = ((1 << 9) - 1) /* 511 */;
  const int INDEX_MASK_10 = ((1 << 10) - 1) /* 1023 */;
  const int INDEX_MASK_11 = ((1 << 11) - 1) /* 2047 */;
  const int INDEX_MASK_12 = ((1 << 12) - 1) /* 4095 */;
  const int INDEX_MASK_13 = ((1 << 13) - 1) /* 8191 */;
  const int INDEX_MASK_14 = ((1 << 14) - 1) /* 16383 */;
  const int INDEX_MASK_15 = ((1 << 15) - 1) /* 32767 */;
  const int INDEX_MASK_16 = ((1 << 16) - 1) /* 65535 */;
  const int INDEX_MASK_17 = ((1 << 17) - 1) /* 131071 */;
  const int INDEX_MASK_18 = ((1 << 18) - 1) /* 262143 */;
  const int INDEX_MASK_19 = ((1 << 19) - 1) /* 524287 */;
  const int INDEX_MASK_20 = ((1 << 20) - 1) /* 1048575 */;
  const int INDEX_MASK_21 = ((1 << 21) - 1) /* 2097151 */;
  const int INDEX_MASK_22 = ((1 << 22) - 1) /* 4194303 */;
  const int INDEX_MASK_23 = ((1 << 23) - 1) /* 8388607 */;
  const int INDEX_MASK_24 = ((1 << 24) - 1) /* 16777215 */;
  const int LAYOUT_MONO = CHANNEL_FRONT_LEFT /* 1 */;
  const int LAYOUT_STEREO = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) /* 3 */;
  const int LAYOUT_2POINT1 = ((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_LOW_FREQUENCY) /* 11 */;
  const int LAYOUT_TRI = ((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_FRONT_CENTER) /* 7 */;
  const int LAYOUT_TRI_BACK = ((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_BACK_CENTER) /* 259 */;
  const int LAYOUT_3POINT1 = (((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_FRONT_CENTER) | CHANNEL_LOW_FREQUENCY) /* 15 */;
  const int LAYOUT_2POINT0POINT2 = (((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_TOP_SIDE_LEFT) | CHANNEL_TOP_SIDE_RIGHT) /* 786435 */;
  const int LAYOUT_2POINT1POINT2 = (LAYOUT_2POINT0POINT2 | CHANNEL_LOW_FREQUENCY) /* 786443 */;
  const int LAYOUT_3POINT0POINT2 = ((((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_FRONT_CENTER) | CHANNEL_TOP_SIDE_LEFT) | CHANNEL_TOP_SIDE_RIGHT) /* 786439 */;
  const int LAYOUT_3POINT1POINT2 = (LAYOUT_3POINT0POINT2 | CHANNEL_LOW_FREQUENCY) /* 786447 */;
  const int LAYOUT_QUAD = (((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_BACK_LEFT) | CHANNEL_BACK_RIGHT) /* 51 */;
  const int LAYOUT_QUAD_SIDE = (((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_SIDE_LEFT) | CHANNEL_SIDE_RIGHT) /* 1539 */;
  const int LAYOUT_SURROUND = (((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_FRONT_CENTER) | CHANNEL_BACK_CENTER) /* 263 */;
  const int LAYOUT_PENTA = (LAYOUT_QUAD | CHANNEL_FRONT_CENTER) /* 55 */;
  const int LAYOUT_5POINT1 = (((((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_FRONT_CENTER) | CHANNEL_LOW_FREQUENCY) | CHANNEL_BACK_LEFT) | CHANNEL_BACK_RIGHT) /* 63 */;
  const int LAYOUT_5POINT1_SIDE = (((((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_FRONT_CENTER) | CHANNEL_LOW_FREQUENCY) | CHANNEL_SIDE_LEFT) | CHANNEL_SIDE_RIGHT) /* 1551 */;
  const int LAYOUT_5POINT1POINT2 = ((LAYOUT_5POINT1 | CHANNEL_TOP_SIDE_LEFT) | CHANNEL_TOP_SIDE_RIGHT) /* 786495 */;
  const int LAYOUT_5POINT1POINT4 = ((((LAYOUT_5POINT1 | CHANNEL_TOP_FRONT_LEFT) | CHANNEL_TOP_FRONT_RIGHT) | CHANNEL_TOP_BACK_LEFT) | CHANNEL_TOP_BACK_RIGHT) /* 184383 */;
  const int LAYOUT_6POINT1 = ((((((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_FRONT_CENTER) | CHANNEL_LOW_FREQUENCY) | CHANNEL_BACK_LEFT) | CHANNEL_BACK_RIGHT) | CHANNEL_BACK_CENTER) /* 319 */;
  const int LAYOUT_7POINT1 = ((LAYOUT_5POINT1 | CHANNEL_SIDE_LEFT) | CHANNEL_SIDE_RIGHT) /* 1599 */;
  const int LAYOUT_7POINT1POINT2 = ((LAYOUT_7POINT1 | CHANNEL_TOP_SIDE_LEFT) | CHANNEL_TOP_SIDE_RIGHT) /* 788031 */;
  const int LAYOUT_7POINT1POINT4 = ((((LAYOUT_7POINT1 | CHANNEL_TOP_FRONT_LEFT) | CHANNEL_TOP_FRONT_RIGHT) | CHANNEL_TOP_BACK_LEFT) | CHANNEL_TOP_BACK_RIGHT) /* 185919 */;
  const int LAYOUT_9POINT1POINT4 = ((LAYOUT_7POINT1POINT4 | CHANNEL_FRONT_WIDE_LEFT) | CHANNEL_FRONT_WIDE_RIGHT) /* 50517567 */;
  const int LAYOUT_9POINT1POINT6 = ((LAYOUT_9POINT1POINT4 | CHANNEL_TOP_SIDE_LEFT) | CHANNEL_TOP_SIDE_RIGHT) /* 51303999 */;
  const int LAYOUT_13POINT_360RA = ((((((((((((CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT) | CHANNEL_FRONT_CENTER) | CHANNEL_SIDE_LEFT) | CHANNEL_SIDE_RIGHT) | CHANNEL_TOP_FRONT_LEFT) | CHANNEL_TOP_FRONT_RIGHT) | CHANNEL_TOP_FRONT_CENTER) | CHANNEL_TOP_BACK_LEFT) | CHANNEL_TOP_BACK_RIGHT) | CHANNEL_BOTTOM_FRONT_LEFT) | CHANNEL_BOTTOM_FRONT_RIGHT) | CHANNEL_BOTTOM_FRONT_CENTER) /* 7534087 */;
  const int LAYOUT_22POINT2 = ((((((((((((LAYOUT_7POINT1POINT4 | CHANNEL_FRONT_LEFT_OF_CENTER) | CHANNEL_FRONT_RIGHT_OF_CENTER) | CHANNEL_BACK_CENTER) | CHANNEL_TOP_CENTER) | CHANNEL_TOP_FRONT_CENTER) | CHANNEL_TOP_BACK_CENTER) | CHANNEL_TOP_SIDE_LEFT) | CHANNEL_TOP_SIDE_RIGHT) | CHANNEL_BOTTOM_FRONT_LEFT) | CHANNEL_BOTTOM_FRONT_RIGHT) | CHANNEL_BOTTOM_FRONT_CENTER) | CHANNEL_LOW_FREQUENCY_2) /* 16777215 */;
  const int LAYOUT_MONO_HAPTIC_A = (LAYOUT_MONO | CHANNEL_HAPTIC_A) /* 1073741825 */;
  const int LAYOUT_STEREO_HAPTIC_A = (LAYOUT_STEREO | CHANNEL_HAPTIC_A) /* 1073741827 */;
  const int LAYOUT_HAPTIC_AB = (CHANNEL_HAPTIC_A | CHANNEL_HAPTIC_B) /* 1610612736 */;
  const int LAYOUT_MONO_HAPTIC_AB = (LAYOUT_MONO | LAYOUT_HAPTIC_AB) /* 1610612737 */;
  const int LAYOUT_STEREO_HAPTIC_AB = (LAYOUT_STEREO | LAYOUT_HAPTIC_AB) /* 1610612739 */;
  const int LAYOUT_FRONT_BACK = (CHANNEL_FRONT_CENTER | CHANNEL_BACK_CENTER) /* 260 */;
  const int INTERLEAVE_LEFT = 0;
  const int INTERLEAVE_RIGHT = 1;
  const int CHANNEL_FRONT_LEFT = (1 << 0) /* 1 */;
  const int CHANNEL_FRONT_RIGHT = (1 << 1) /* 2 */;
  const int CHANNEL_FRONT_CENTER = (1 << 2) /* 4 */;
  const int CHANNEL_LOW_FREQUENCY = (1 << 3) /* 8 */;
  const int CHANNEL_BACK_LEFT = (1 << 4) /* 16 */;
  const int CHANNEL_BACK_RIGHT = (1 << 5) /* 32 */;
  const int CHANNEL_FRONT_LEFT_OF_CENTER = (1 << 6) /* 64 */;
  const int CHANNEL_FRONT_RIGHT_OF_CENTER = (1 << 7) /* 128 */;
  const int CHANNEL_BACK_CENTER = (1 << 8) /* 256 */;
  const int CHANNEL_SIDE_LEFT = (1 << 9) /* 512 */;
  const int CHANNEL_SIDE_RIGHT = (1 << 10) /* 1024 */;
  const int CHANNEL_TOP_CENTER = (1 << 11) /* 2048 */;
  const int CHANNEL_TOP_FRONT_LEFT = (1 << 12) /* 4096 */;
  const int CHANNEL_TOP_FRONT_CENTER = (1 << 13) /* 8192 */;
  const int CHANNEL_TOP_FRONT_RIGHT = (1 << 14) /* 16384 */;
  const int CHANNEL_TOP_BACK_LEFT = (1 << 15) /* 32768 */;
  const int CHANNEL_TOP_BACK_CENTER = (1 << 16) /* 65536 */;
  const int CHANNEL_TOP_BACK_RIGHT = (1 << 17) /* 131072 */;
  const int CHANNEL_TOP_SIDE_LEFT = (1 << 18) /* 262144 */;
  const int CHANNEL_TOP_SIDE_RIGHT = (1 << 19) /* 524288 */;
  const int CHANNEL_BOTTOM_FRONT_LEFT = (1 << 20) /* 1048576 */;
  const int CHANNEL_BOTTOM_FRONT_CENTER = (1 << 21) /* 2097152 */;
  const int CHANNEL_BOTTOM_FRONT_RIGHT = (1 << 22) /* 4194304 */;
  const int CHANNEL_LOW_FREQUENCY_2 = (1 << 23) /* 8388608 */;
  const int CHANNEL_FRONT_WIDE_LEFT = (1 << 24) /* 16777216 */;
  const int CHANNEL_FRONT_WIDE_RIGHT = (1 << 25) /* 33554432 */;
  const int CHANNEL_HAPTIC_B = (1 << 29) /* 536870912 */;
  const int CHANNEL_HAPTIC_A = (1 << 30) /* 1073741824 */;
  const int VOICE_UPLINK_MONO = CHANNEL_VOICE_UPLINK /* 16384 */;
  const int VOICE_DNLINK_MONO = CHANNEL_VOICE_DNLINK /* 32768 */;
  const int VOICE_CALL_MONO = (CHANNEL_VOICE_UPLINK | CHANNEL_VOICE_DNLINK) /* 49152 */;
  const int CHANNEL_VOICE_UPLINK = 0x4000;
  const int CHANNEL_VOICE_DNLINK = 0x8000;
}
