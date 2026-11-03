/* Host (Linux) stand-in for the NDK's <android/log.h>, used only by
 * tools/run_harnesses.sh so the harnesses build with the system clang. */
#ifndef HOSTSHIM_ANDROID_LOG_H
#define HOSTSHIM_ANDROID_LOG_H
#include <stdio.h>
#include <stdarg.h>
enum { ANDROID_LOG_UNKNOWN, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG,
       ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT };
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
    (void)prio; (void)tag; (void)fmt;
    return 0;
}
#endif
