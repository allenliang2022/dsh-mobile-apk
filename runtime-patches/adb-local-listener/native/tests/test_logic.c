/* SPDX-License-Identifier: MIT */
#include "shim_logic.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef NDEBUG
#error "Host unit tests require enabled assertions"
#endif

static void expect_socket(const char *input, const char *expected) {
    char *result = NULL;
    int changed = dsh_normalize_socket(input, &result);
    assert(changed == (expected != NULL ? 1 : 0));
    if (expected != NULL) {
        assert(result != NULL);
        assert(strcmp(result, expected) == 0);
        free(result);
    } else {
        assert(result == NULL);
    }
}

static void test_sockets(void) {
    const char *nonmatches[] = {
        NULL, "", "tcp:5037", "tcp:localhost:5037", "tcp:[::1]:5037",
        "tcp:::1:5037", "tcp:127.0.0.2:5037", "tcp:0.0.0.0:5037",
        "tcp:192.0.2.1:5037", "tcp:127.000.0.1:5037", "TCP:127.0.0.1:5037",
        "localabstract:adb", "localfilesystem:/synthetic/adb", "vsock:2:5037",
        "tcp:127.0.0.1:", "tcp:127.0.0.1:0", "tcp:127.0.0.1:0000",
        "tcp:127.0.0.1:65536", "tcp:127.0.0.1:999999999999999999999999",
        "tcp:127.0.0.1:4294967297", "tcp:127.0.0.1:+5037",
        "tcp:127.0.0.1:-5037", "tcp:127.0.0.1: 5037",
        "tcp:127.0.0.1:5037 ", " tcp:127.0.0.1:5037", "tcp:127.0.0.1:5037\n",
        "tcp:127.0.0.1:5037\r", "tcp:127.0.0.1:5037\t",
        "tcp:127.0.0.1:0x13ad", "tcp:127.0.0.1:5e3", "tcp:127.0.0.1:1.0",
        "tcp:127.0.0.1:5037:1", "tcp:127.0.0.1:5037/path",
        "tcp:127.0.0.1:5037?x=1", "tcp:127.0.0.1:5037junk",
        "tcp:127.0.0.1:\xff", "tcp:127.0.0.1:\xef\xbc\x91"
    };
    size_t i;
    unsigned int port;
    char input[64];
    char expected[64];
    char *long_input;
    char *long_expected;

    for (i = 0; i < sizeof(nonmatches) / sizeof(nonmatches[0]); ++i)
        expect_socket(nonmatches[i], NULL);
    for (port = 1; port <= 65535; ++port) {
        snprintf(input, sizeof(input), "tcp:127.0.0.1:%u", port);
        snprintf(expected, sizeof(expected), "tcp:%u", port);
        expect_socket(input, expected);
    }
    expect_socket("tcp:127.0.0.1:00001", "tcp:00001");
    expect_socket("tcp:127.0.0.1:05037", "tcp:05037");
    expect_socket("tcp:127.0.0.1:00065535", "tcp:00065535");
    expect_socket("tcp:127.0.0.1:00065536", NULL);
    long_input = malloc(65536 + 32);
    long_expected = malloc(65536 + 32);
    assert(long_input != NULL && long_expected != NULL);
    strcpy(long_input, "tcp:127.0.0.1:");
    {
        size_t prefix = strlen("tcp:127.0.0.1:");
        memset(long_input + prefix, '0', 65536);
        strcpy(long_input + prefix + 65536, "1");
    }
    strcpy(long_expected, "tcp:");
    memset(long_expected + 4, '0', 65536);
    strcpy(long_expected + 4 + 65536, "1");
    expect_socket(long_input, long_expected);
    free(long_input);
    free(long_expected);
}

static void test_environment(void) {
    char *input[] = {
        "PATH=/synthetic/bin", "HOME=/not-read", "EMPTY=", "UNCHANGED=a b\n'\"$;",
        "ADB_SERVER_SOCKET=tcp:127.0.0.1:5037",
        "ADB_SERVER_SOCKET_EXTRA=tcp:127.0.0.1:5037",
        "XADB_SERVER_SOCKET=tcp:127.0.0.1:5037",
        "ADB_SERVER_SOCKET=tcp:localhost:5037",
        "ADB_SERVER_SOCKET=tcp:127.0.0.1:00001",
        "adb_server_socket=tcp:127.0.0.1:5037", "WITHOUT_EQUALS", NULL
    };
    char *empty[] = {NULL};
    char **result = dsh_normalize_environment(input);
    size_t i;
    assert(result != NULL);
    for (i = 0; input[i] != NULL; ++i) {
        if (i == 4) {
            assert(strcmp(result[i], "ADB_SERVER_SOCKET=tcp:5037") == 0);
            assert(strcmp(input[i], "ADB_SERVER_SOCKET=tcp:127.0.0.1:5037") == 0);
        } else if (i == 8) {
            assert(strcmp(result[i], "ADB_SERVER_SOCKET=tcp:00001") == 0);
        } else {
            assert(result[i] == input[i]);
        }
    }
    assert(result[i] == NULL);
    dsh_free_environment(result, input);
    result = dsh_normalize_environment(empty);
    assert(result != NULL && result[0] == NULL);
    dsh_free_environment(result, empty);
    dsh_free_environment(NULL, empty);
}

static void test_arguments(void) {
    char original[] = "/synthetic bin/adb.dsh-original-v1";
    char *input[] = {
        "caller-chosen-argv0", "", "space separated", "'quotes'\"\\$;*",
        "line\nbreak", "-L", "tcp:127.0.0.1:5037", "--", "-P", "12345",
        "\xe6\xb5\x8b\xe8\xaf\x95", NULL
    };
    int argc = (int)(sizeof(input) / sizeof(input[0])) - 1;
    char **result = dsh_linker_arguments(argc, input, original);
    int i;
    assert(result != NULL);
    assert(strcmp(result[0], DSH_ADB_LINKER) == 0);
    assert(result[1] == original);
    for (i = 1; i < argc; ++i)
        assert(result[i + 1] == input[i]);
    assert(result[argc + 1] == NULL);
    free(result);
    result = dsh_linker_arguments(1, input, original);
    assert(result != NULL && result[1] == original && result[2] == NULL);
    free(result);
    assert(dsh_linker_arguments(0, input, original) == NULL);
    assert(dsh_linker_arguments(-1, input, original) == NULL);
    assert(dsh_linker_arguments(1, NULL, original) == NULL);
    assert(dsh_linker_arguments(1, input, NULL) == NULL);
}

int main(void) {
    test_sockets();
    test_environment();
    test_arguments();
    puts("PASS: strict sockets (all 65535 ports), exact environment, argv preservation");
    return 0;
}
