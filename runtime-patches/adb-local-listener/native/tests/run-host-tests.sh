#!/bin/sh
# SPDX-License-Identifier: MIT
# Linux host tests only; no real adb binary is executed.
set -eu
native=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
compiler=${CC:-cc}
if ! command -v "$compiler" >/dev/null 2>&1; then
    echo "ERROR: host C compiler unavailable ($compiler); no dependencies installed" >&2
    exit 77
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: python3 unavailable; no dependencies installed" >&2
    exit 77
fi
build=$(mktemp -d "$native/.host-tests.XXXXXXXX")
trap 'rm -rf -- "$build"' EXIT HUP INT TERM
# CFLAGS is the usual compiler flag list, not a command; no eval is used.
# shellcheck disable=SC2086
"$compiler" -std=c11 -Wall -Wextra -Werror -O2 -fPIE -pie ${CFLAGS:-} \
    -I"$native" "$native/shim_logic.c" "$native/tests/test_logic.c" \
    -o "$build/test-logic"
"$build/test-logic"
# Also compile the unchanged production branch (do not execute it on the host).
# shellcheck disable=SC2086
"$compiler" -std=c11 -Wall -Wextra -Werror -O2 -fPIE -pie ${CFLAGS:-} \
    -I"$native" "$native/adb_shim.c" "$native/shim_logic.c" -ldl \
    -o "$build/adb-shim-production-compile-only"
# shellcheck disable=SC2086
"$compiler" -std=c11 -Wall -Wextra -Werror -O2 -fPIE -pie ${CFLAGS:-} \
    -DDSH_ADB_SHIM_HOST_TEST -I"$native" \
    "$native/adb_shim.c" "$native/shim_logic.c" -ldl \
    -o "$build/adb-shim-host-test"
for status in 0 37 255; do
    # shellcheck disable=SC2086
    "$compiler" -std=c11 -Wall -Wextra -Werror -O2 -fPIE -pie ${CFLAGS:-} \
        -DDSH_FAKE_EXIT_STATUS="$status" "$native/tests/fake_original.c" \
        -o "$build/fake-original-$status"
done
python3 "$native/tests/test_exec.py" "$build"
