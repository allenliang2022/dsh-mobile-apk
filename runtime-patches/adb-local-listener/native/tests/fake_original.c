/* SPDX-License-Identifier: MIT */
/* TEST FIXTURE ONLY. Prints synthetic argv/env/stdin, never invoke with real
 * user data. No adb functionality, network, device commands, or file access. */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#ifndef DSH_FAKE_EXIT_STATUS
#define DSH_FAKE_EXIT_STATUS 37
#endif
#if DSH_FAKE_EXIT_STATUS < 0 || DSH_FAKE_EXIT_STATUS > 255
#error "DSH_FAKE_EXIT_STATUS must be 0..255"
#endif

extern char **environ;

static void hex_bytes(const unsigned char *bytes, size_t size) {
    static const char digits[] = "0123456789abcdef";
    size_t i;
    for (i = 0; i < size; ++i) {
        putchar(digits[bytes[i] >> 4]);
        putchar(digits[bytes[i] & 15]);
    }
}

int main(int argc, char **argv) {
    unsigned char buffer[4096];
    ssize_t length;
    size_t count = 0;
    size_t i;
    while (environ[count] != NULL)
        ++count;
    printf("DSH_FAKE_ORIGINAL_V1\npid=%ld\nargc=%d\n", (long)getpid(), argc);
    for (i = 0; i < (size_t)argc; ++i) {
        printf("arg%zu=", i);
        hex_bytes((const unsigned char *)argv[i], strlen(argv[i]));
        putchar('\n');
    }
    printf("envc=%zu\n", count);
    for (i = 0; i < count; ++i) {
        printf("env%zu=", i);
        hex_bytes((const unsigned char *)environ[i], strlen(environ[i]));
        putchar('\n');
    }
    printf("stdin=");
    for (;;) {
        length = read(STDIN_FILENO, buffer, sizeof(buffer));
        if (length < 0 && errno == EINTR)
            continue;
        if (length < 0)
            return 98;
        if (length == 0)
            break;
        hex_bytes(buffer, (size_t)length);
    }
    putchar('\n');
    fputs("fake-original stderr\n", stderr);
    if (fflush(stdout) != 0 || fflush(stderr) != 0)
        return 99;
    return DSH_FAKE_EXIT_STATUS;
}
