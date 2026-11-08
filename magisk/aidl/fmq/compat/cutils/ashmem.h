/* Stand-in for libcutils' ashmem helpers.
 *
 * The audio queues are shared with audioserver, so the region should be the
 * same kind of memory the platform's own effects hand it. libcutils picks:
 *
 *  - memfd when the vendor API level is 202604 or later (Android 17 vendors);
 *  - memfd when sys.use_memfd is true (off by default on 15 and 16);
 *  - /dev/ashmem otherwise.
 *
 * This does the same, and falls back to the other kind when the first cannot
 * be created, so a device that refuses one still gets working queues. The
 * size and protection helpers work on either kind of descriptor. */
#pragma once
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <linux/ashmem.h>
#include <linux/memfd.h>
#include <stddef.h>

static inline bool rv4a_prefer_memfd(void) {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.vendor.api_level", v) > 0 && atoi(v) >= 202604) return true;
    v[0] = 0;
    if (__system_property_get("sys.use_memfd", v) > 0 &&
        (strcmp(v, "true") == 0 || strcmp(v, "1") == 0))
        return true;
    return false;
}

static inline int rv4a_memfd_region(const char* name, size_t size) {
    int fd = (int)syscall(__NR_memfd_create, name, MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)size) < 0) { close(fd); return -1; }
    fcntl(fd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW);
    return fd;
}

static inline int rv4a_ashmem_region(const char* name, size_t size) {
    int fd = open("/dev/ashmem", O_RDWR | O_CLOEXEC);
    if (fd < 0) return -1;
    char buf[ASHMEM_NAME_LEN];
    strncpy(buf, name, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;
    if (ioctl(fd, ASHMEM_SET_NAME, buf) < 0 || ioctl(fd, ASHMEM_SET_SIZE, size) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static inline int ashmem_create_region(const char* name, size_t size) {
    const char* n = name ? name : "rv4a-fmq";
    if (rv4a_prefer_memfd()) {
        int fd = rv4a_memfd_region(n, size);
        return fd >= 0 ? fd : rv4a_ashmem_region(n, size);
    }
    int fd = rv4a_ashmem_region(n, size);
    return fd >= 0 ? fd : rv4a_memfd_region(n, size);
}

/* ashmem takes a protection mask; a memfd has none (its seals are set at
   creation), so for one the call is a no-op, as in libcutils. */
static inline int ashmem_set_prot_region(int fd, int prot) {
    if (ioctl(fd, ASHMEM_SET_PROT_MASK, (unsigned long)prot) == 0) return 0;
    return errno == ENOTTY || errno == EINVAL ? 0 : -1;
}

static inline int ashmem_get_size_region(int fd) {
    int sz = ioctl(fd, ASHMEM_GET_SIZE, NULL);
    if (sz >= 0) return sz;
    struct stat st;
    if (fstat(fd, &st) < 0) return -1;
    return (int)st.st_size;
}

static inline int ashmem_valid(int fd) { return fd >= 0; }
