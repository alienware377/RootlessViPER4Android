/* The few out-of-line functions libfmq's headers call, written for this
 * project.
 *
 * On the platform these live in libhidlbase (logError, errorWriteLog, check)
 * and libcutils (native_handle_*). Neither is a library a plugin can count on
 * being allowed to load from inside a vendor process, and the old build only
 * got past their absence by telling the linker to ignore unresolved symbols,
 * which would have surfaced as a failed dlopen in the audio HAL. They are
 * small, so they are provided here and the link is strict.
 *
 * Behaviour matches the platform's, with one deliberate exception: check()
 * logs instead of aborting, for the reason given in android-base/logging.h.
 * Its only caller is a size overflow guard on queue sizes this plugin chooses
 * itself and bounds well below the limit.
 *
 * The native handles never leave this library: they carry the queue's file
 * descriptors between libfmq's own objects, and what crosses the binder is
 * the AIDL descriptor built from them. */
#include <errno.h>
#include <stdlib.h>
#include <unistd.h>

#include <string>

#include <android/log.h>
#include <cutils/native_handle.h>

#define TAG "RV4A-FMQ"

namespace android::hardware::details {

void logError(const std::string& message) {
    __android_log_write(ANDROID_LOG_ERROR, TAG, message.c_str());
}

void errorWriteLog(int tag, const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "security event 0x%x: %s", tag,
                        message ? message : "");
}

void check(bool exp, const char* message) {
    if (!exp) __android_log_print(ANDROID_LOG_FATAL, TAG, "check failed: %s", message ? message : "");
}

}  // namespace android::hardware::details

extern "C" {

native_handle_t* native_handle_create(int numFds, int numInts) {
    if (numFds < 0 || numInts < 0 || numFds > NATIVE_HANDLE_MAX_FDS ||
        numInts > NATIVE_HANDLE_MAX_INTS) {
        errno = EINVAL;
        return nullptr;
    }
    const size_t size = sizeof(native_handle_t) + sizeof(int) * (size_t)(numFds + numInts);
    native_handle_t* h = static_cast<native_handle_t*>(malloc(size));
    if (h) {
        h->version = sizeof(native_handle_t);
        h->numFds = numFds;
        h->numInts = numInts;
    }
    return h;
}

int native_handle_close(const native_handle_t* h) {
    if (!h) return 0;
    if (h->version != (int)sizeof(native_handle_t)) return -EINVAL;
    int saved = errno;
    for (int i = 0; i < h->numFds; i++) close(h->data[i]);
    errno = saved;
    return 0;
}

int native_handle_delete(native_handle_t* h) {
    if (!h) return 0;
    if (h->version != (int)sizeof(native_handle_t)) return -EINVAL;
    free(h);
    return 0;
}

}
