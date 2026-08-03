// Debug-fork native crash recorder: writes signal info + module maps
// to filesDir so the app can display it after restart.
#include <jni.h>
#include <signal.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <ucontext.h>

static char gDir[512] = {0};

static void writeAll(int fd, const char* s)
{
    write(fd, s, strlen(s));
}

static void crashHandler(int sig, siginfo_t* si, void* ucRaw)
{
    if (gDir[0])
    {
        char path[600];
        snprintf(path, sizeof(path), "%s/native_crash.txt", gDir);
        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0)
        {
            char buf[256];
            snprintf(buf, sizeof(buf), "signal=%d code=%d fault_addr=%p\n",
                     sig, si ? si->si_code : 0, si ? si->si_addr : (void*)0);
            writeAll(fd, buf);
#if defined(__aarch64__)
            ucontext_t* uc = (ucontext_t*)ucRaw;
            if (uc)
            {
                snprintf(buf, sizeof(buf), "pc=%p lr=%p sp=%p x0=%p\n",
                         (void*)uc->uc_mcontext.pc,
                         (void*)uc->uc_mcontext.regs[30],
                         (void*)uc->uc_mcontext.sp,
                         (void*)uc->uc_mcontext.regs[0]);
                writeAll(fd, buf);
            }
#endif
            int mf = open("/proc/self/maps", O_RDONLY);
            if (mf >= 0)
            {
                char rd[1024];
                static char line[2048];
                int lineLen = 0;
                ssize_t n;
                while ((n = read(mf, rd, sizeof(rd))) > 0)
                {
                    for (ssize_t i = 0; i < n; i++)
                    {
                        char ch = rd[i];
                        if (lineLen < (int)sizeof(line) - 1)
                            line[lineLen++] = ch;
                        if (ch == '\n')
                        {
                            line[lineLen] = 0;
                            if (strstr(line, "jamesdsp"))
                                writeAll(fd, line);
                            lineLen = 0;
                        }
                    }
                }
                close(mf);
            }
            close(fd);
        }
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_initCrashGuard(JNIEnv *env, jobject obj, jstring dir)
{
    const char* d = env->GetStringUTFChars(dir, nullptr);
    if (d)
    {
        strncpy(gDir, d, sizeof(gDir) - 1);
        env->ReleaseStringUTFChars(dir, d);
    }
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    const int sigs[] = { SIGSEGV, SIGBUS, SIGFPE, SIGILL, SIGABRT };
    for (unsigned i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++)
        sigaction(sigs[i], &sa, nullptr);
}
