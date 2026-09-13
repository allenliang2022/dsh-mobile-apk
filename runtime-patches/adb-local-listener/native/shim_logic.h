/* SPDX-License-Identifier: MIT */
#ifndef DSH_ADB_SHIM_LOGIC_H
#define DSH_ADB_SHIM_LOGIC_H

#define DSH_ADB_ORIGINAL_BASENAME "adb.dsh-original-v1"
#define DSH_ADB_LINKER "/system/bin/linker64"

/* Return 1 with an allocated replacement, 0 (no match), or -1 (allocation
 * failure). *replacement is NULL unless returning 1. Decimal digits, including
 * leading zeroes, are preserved; their numeric value must be 1..65535. */
int dsh_normalize_socket(const char *socket, char **replacement);

/* Clone only the environment pointer array and exact matching socket entries.
 * Other strings, ordering, duplicates, and absent variables remain unchanged.
 * Release with dsh_free_environment(result, input). */
char **dsh_normalize_environment(char *const input[]);
void dsh_free_environment(char **result, char *const input[]);

/* Build linker argv: LINKER, ORIGINAL, incoming[1..argc-1], NULL.
 * No shell parsing or argument rewriting. The array alone is allocated.
 * Android's linker necessarily sets the payload's argv[0] to ORIGINAL. */
char **dsh_linker_arguments(int argc, char *const incoming[], char *original);

#endif
