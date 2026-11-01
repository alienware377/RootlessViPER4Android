/* Stand-in for libutils' Trace.h, written for this project.
 *
 * AOSP's effect base class marks each processing pass with ATRACE_NAME for
 * systrace. Tracing needs libcutils, which a plugin should not depend on, and
 * the markers carry no behaviour, so they compile to nothing here. */
#pragma once

#ifndef ATRACE_TAG_AUDIO
#define ATRACE_TAG_AUDIO (1 << 8)
#endif
#define ATRACE_NAME(name) ((void)0)
#define ATRACE_CALL() ((void)0)
