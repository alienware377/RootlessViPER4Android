/* Stand-in for libsystem's thread_defs.h, written for this project.
 *
 * Only the two audio priorities the effect worker thread uses are needed.
 * The values are the platform's own (system/core/libsystem), and they are
 * nice levels passed to setpriority(), so they must match exactly. */
#pragma once

enum {
    ANDROID_PRIORITY_AUDIO = -16,
    ANDROID_PRIORITY_URGENT_AUDIO = -19,
};
