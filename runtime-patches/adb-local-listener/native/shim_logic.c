/* SPDX-License-Identifier: MIT */
#include "shim_logic.h"

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

int dsh_normalize_socket(const char *socket, char **replacement) {
    static const char prefix[] = "tcp:127.0.0.1:";
    const char *digits;
    const unsigned char *p;
    unsigned int port = 0;
    size_t length;

    *replacement = NULL;
    if (socket == NULL || strncmp(socket, prefix, sizeof(prefix) - 1) != 0)
        return 0;
    digits = socket + sizeof(prefix) - 1;
    if (*digits == '\0')
        return 0;
    for (p = (const unsigned char *)digits; *p != '\0'; ++p) {
        unsigned int digit;
        if (*p < '0' || *p > '9')
            return 0;
        digit = (unsigned int)(*p - '0');
        if (port > (65535U - digit) / 10U)
            return 0;
        port = port * 10U + digit;
    }
    if (port == 0)
        return 0;
    length = strlen(digits);
    /* Input already contains a longer prefix, so length + 5 cannot overflow. */
    *replacement = malloc(length + 5);
    if (*replacement == NULL)
        return -1;
    memcpy(*replacement, "tcp:", 4);
    memcpy(*replacement + 4, digits, length + 1);
    return 1;
}

void dsh_free_environment(char **result, char *const input[]) {
    size_t i;
    if (result == NULL)
        return;
    for (i = 0; result[i] != NULL; ++i) {
        if (result[i] != input[i])
            free(result[i]);
    }
    free(result);
}

char **dsh_normalize_environment(char *const input[]) {
    static const char key[] = "ADB_SERVER_SOCKET=";
    size_t count = 0;
    size_t i;
    char **result;
    while (input[count] != NULL)
        ++count;
    if (count >= SIZE_MAX / sizeof(*result)) {
        errno = ENOMEM;
        return NULL;
    }
    result = calloc(count + 1, sizeof(*result));
    if (result == NULL)
        return NULL;
    for (i = 0; i < count; ++i) {
        char *socket = NULL;
        int matched = 0;
        if (strncmp(input[i], key, sizeof(key) - 1) == 0)
            matched = dsh_normalize_socket(input[i] + sizeof(key) - 1, &socket);
        if (matched < 0)
            goto fail;
        if (matched == 1) {
            size_t length = strlen(socket);
            if (length > SIZE_MAX - sizeof(key)) {
                free(socket);
                errno = ENOMEM;
                goto fail;
            }
            result[i] = malloc(sizeof(key) + length);
            if (result[i] == NULL) {
                free(socket);
                goto fail;
            }
            memcpy(result[i], key, sizeof(key) - 1);
            memcpy(result[i] + sizeof(key) - 1, socket, length + 1);
            free(socket);
        } else {
            result[i] = input[i];
        }
    }
    return result;
fail:
    dsh_free_environment(result, input);
    return NULL;
}

char **dsh_linker_arguments(int argc, char *const incoming[], char *original) {
    char **result;
    size_t i;
    if (argc < 1 || incoming == NULL || incoming[0] == NULL || original == NULL) {
        errno = EINVAL;
        return NULL;
    }
    if ((size_t)argc > SIZE_MAX / sizeof(*result) - 2) {
        errno = ENOMEM;
        return NULL;
    }
    result = calloc((size_t)argc + 2, sizeof(*result));
    if (result == NULL)
        return NULL;
    result[0] = (char *)DSH_ADB_LINKER;
    result[1] = original;
    for (i = 1; i < (size_t)argc; ++i)
        result[i + 1] = incoming[i];
    return result;
}
