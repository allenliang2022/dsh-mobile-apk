/* SPDX-License-Identifier: MIT */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "shim_logic.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#if defined(DSH_ADB_SHIM_HOST_TEST) && defined(__ANDROID__)
#error "The direct-exec host test seam must never be used for Android artifacts"
#endif

extern char **environ;

/* Taking an address in THIS ELF works with both ordinary PT_INTERP loading
 * and explicit /system/bin/linker64 invocation. /proc/self/exe can instead
 * name the linker and must not be used to find our sibling. */
static void shim_address_anchor(void) {}

static int same_file(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
}

static char *original_path(struct stat *self_stat) {
    Dl_info info;
    char *self;
    char *slash;
    char *original;
    size_t directory_length;

    memset(&info, 0, sizeof(info));
    if (dladdr((const void *)&shim_address_anchor, &info) == 0 ||
        info.dli_fname == NULL || info.dli_fname[0] == '\0')
        return NULL;
    self = realpath(info.dli_fname, NULL);
    if (self == NULL)
        return NULL;
    if (stat(self, self_stat) != 0 || !S_ISREG(self_stat->st_mode)) {
        free(self);
        return NULL;
    }
    slash = strrchr(self, '/');
    if (slash == NULL) {
        free(self);
        return NULL;
    }
    directory_length = (size_t)(slash - self) + 1;
    if (directory_length > SIZE_MAX - sizeof(DSH_ADB_ORIGINAL_BASENAME)) {
        free(self);
        return NULL;
    }
    original = malloc(directory_length + sizeof(DSH_ADB_ORIGINAL_BASENAME));
    if (original != NULL) {
        memcpy(original, self, directory_length);
        memcpy(original + directory_length, DSH_ADB_ORIGINAL_BASENAME,
               sizeof(DSH_ADB_ORIGINAL_BASENAME));
    }
    free(self);
    return original;
}

/* Keep an O_CLOEXEC descriptor during preparation, reject symlinks without
 * following them, and compare the opened inode against both the named file
 * and the shim. O_NONBLOCK also avoids a blocking open if a FIFO is raced in.
 * The installer must serialize changes to this app-private directory: the
 * required pathname-based linker exec cannot close the final rename race. */
static int open_original(const char *path, const struct stat *self_stat,
                         struct stat *opened_stat) {
    struct stat named_stat;
    int fd;
    if (lstat(path, &named_stat) != 0 || !S_ISREG(named_stat.st_mode) ||
        same_file(&named_stat, self_stat))
        return -1;
    fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0)
        return -1;
    if (fstat(fd, opened_stat) != 0 || !S_ISREG(opened_stat->st_mode) ||
        !same_file(&named_stat, opened_stat) || same_file(opened_stat, self_stat)) {
        close(fd);
        return -1;
    }
    return fd;
}

int main(int argc, char **argv) {
    struct stat self_stat;
    struct stat opened_stat;
    struct stat final_stat;
    char *original = NULL;
    char **arguments = NULL;
    char **environment = NULL;
    const char *error = "adb shim: cannot locate loaded shim\n";
    int original_fd = -1;
    int status = 126;

    original = original_path(&self_stat);
    if (original == NULL)
        goto fail;
    error = "adb shim: unsafe or unavailable preserved original\n";
    original_fd = open_original(original, &self_stat, &opened_stat);
    if (original_fd < 0)
        goto fail;
    error = "adb shim: cannot prepare execution\n";
    arguments = dsh_linker_arguments(argc, argv, original);
    environment = dsh_normalize_environment(environ);
    if (arguments == NULL || environment == NULL)
        goto fail;
    error = "adb shim: preserved original changed\n";
    if (lstat(original, &final_stat) != 0 || !S_ISREG(final_stat.st_mode) ||
        !same_file(&final_stat, &opened_stat))
        goto fail;

    /* A single exec, with no fork, stdio changes, or shell. Once it succeeds,
     * the payload's exit code and terminating signal reach the caller as-is. */
#ifdef DSH_ADB_SHIM_HOST_TEST
    /* Only replace the execution mechanism, not discovery, validation,
     * normalization, or argv construction. No runtime bypass exists. */
    execve(original, arguments + 1, environment);
#else
    execve(DSH_ADB_LINKER, arguments, environment);
#endif
    status = errno == ENOENT ? 127 : 126;
    error = "adb shim: execution failed\n";
fail:
    /* Constant diagnostics only: never paths, arguments, environment, keys,
     * pairing material, or strerror data from the payload. */
    fputs(error, stderr);
    if (original_fd >= 0)
        close(original_fd);
    dsh_free_environment(environment, environ);
    free(arguments);
    free(original);
    return status;
}
