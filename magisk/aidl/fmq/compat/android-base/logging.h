/* Stand-in for libbase's logging.h, written for this project.
 *
 * Only the streaming LOG(SEVERITY) form is used by libfmq and AOSP's effect
 * base classes, and libbase is not something a plugin should depend on, so
 * this provides the same shape on top of the NDK's liblog.
 *
 * The one deliberate difference: LOG(FATAL) and LOG_ALWAYS_FATAL_IF log at
 * fatal level but do NOT abort. This code runs inside the device's audio HAL
 * process, which also carries every other audio path on the phone. Killing it
 * restarts all audio and, if the condition repeats, puts the HAL into a crash
 * loop. The callers in the effect base classes all check the state they would
 * have aborted on before using it, and our own code validates its input before
 * reaching them, so carrying on is the safer of the two failures. */
#pragma once
#include <android/log.h>
#include <sstream>

#include <android-base/macros.h>  // FALLTHROUGH_INTENDED, as the real header provides

#ifndef LOG_TAG
#define LOG_TAG "RV4A-AIDL"
#endif

namespace rv4a_log {

enum Severity { VERBOSE, DEBUG, INFO, WARNING, ERROR, FATAL };

inline int toAndroid(Severity s) {
    switch (s) {
        case VERBOSE: return ANDROID_LOG_VERBOSE;
        case DEBUG:   return ANDROID_LOG_DEBUG;
        case INFO:    return ANDROID_LOG_INFO;
        case WARNING: return ANDROID_LOG_WARN;
        case ERROR:   return ANDROID_LOG_ERROR;
        default:      return ANDROID_LOG_FATAL;
    }
}

class Message {
  public:
    Message(Severity s, const char* tag) : mSeverity(s), mTag(tag) {}
    ~Message() { __android_log_write(toAndroid(mSeverity), mTag, mStream.str().c_str()); }
    template <typename T>
    Message& operator<<(const T& value) {
        mStream << value;
        return *this;
    }

  private:
    Severity mSeverity;
    const char* mTag;
    std::ostringstream mStream;
};

}  // namespace rv4a_log

/* The severity is pasted onto the namespace rather than defined as a global
   macro, so names like ERROR or INFO stay free for the enums in the generated
   interface code. */
#define LOG(severity) ::rv4a_log::Message(::rv4a_log::severity, LOG_TAG)

#ifndef LOG_ALWAYS_FATAL_IF
#define LOG_ALWAYS_FATAL_IF(cond, ...)                                              \
    do {                                                                            \
        if (cond) __android_log_print(ANDROID_LOG_FATAL, LOG_TAG, __VA_ARGS__);     \
    } while (0)
#endif
