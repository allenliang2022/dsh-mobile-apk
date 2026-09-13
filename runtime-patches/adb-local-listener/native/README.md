# Native local-listener compatibility shim

This directory owns only the native ELF and host unit tests. The parent patch
owns CI, manifest/hash checks, installer/rollback, and Android integration.
Nothing here installs a binary, updates/restarts an APK, touches live `usr/bin`,
changes settings/profiles/keys, starts a server, or executes a real adb command.

## Build contract

Production sources: **`adb_shim.c`, `shim_logic.c`, `shim_logic.h`**.
Runtime dependencies: **Android libc + libdl only**, 64-bit PIE, NDK API 26.
Expected release artifact basenames:

- `adb-shim-arm64-v8a` (ELF machine 183 / AArch64)
- `adb-shim-x86_64` (ELF machine 62 / x86-64)

Run in this directory on a Linux NDK host (adjust the NDK prebuilt host tag on
other hosts). No Gradle/CMake/C++ runtime is required:

```sh
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$NDK_BIN/aarch64-linux-android26-clang" \
  -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie \
  -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
  -Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384 \
  adb_shim.c shim_logic.c -ldl -o adb-shim-arm64-v8a
"$NDK_BIN/x86_64-linux-android26-clang" \
  -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie \
  -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
  -Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384 \
  adb_shim.c shim_logic.c -ldl -o adb-shim-x86_64
```

Do **not** define `DSH_ADB_SHIM_HOST_TEST` in release builds. It is a narrowly
scoped direct-exec seam for the host tests and produces a compile error when
`__ANDROID__` is defined. There are no runtime environment switches to bypass
validation, change the linker, or select an alternate original.

## Exact behavior

The installer places this ELF at `usr/bin/adb`, preserving the approved original
ELF in the **same physical directory** as **`adb.dsh-original-v1`**. Discovery
uses `dladdr` on a function in this ELF followed by `realpath`, not `argv[0]`,
`PATH`, the working directory, or `/proc/self/exe`. Under explicit linker
invocation `/proc/self/exe` can name the linker, not the loaded shim.

Only environment entries with the exact name `ADB_SERVER_SOCKET` are inspected.
Only values matching the complete grammar
`tcp:127.0.0.1:<ASCII decimal digits with numeric value 1..65535>` are rewritten
to `tcp:<same digits>`. Leading zeroes are preserved. No whitespace/signs,
IPv6, other hosts, prefixes/suffixes, or invalid/overflowing ports are accepted.
Absent variables remain absent; other strings, environment order and duplicate
entries are preserved. Each matching duplicate is normalized independently.
Command-line socket arguments such as `-L tcp:127.0.0.1:5037` are **not** changed.

The sole execution handoff is:

```text
execve("/system/bin/linker64",
       ["/system/bin/linker64", CANONICAL_SIBLING_ORIGINAL, incoming argv[1..]],
       normalized environment)
```

There is no fork, shell, background job, signal handler, server management,
pairing/grant behavior, or stdio redirection. The original retains the PID,
stdin/stdout/stderr and incoming command arguments `argv[1..]`. Successful exec
naturally propagates the original's exit status or terminating signal.

**Explicit limitations / integration gates:**

- Android's linker has no `--argv0` facility. The payload receives its canonical
  preserved path as `argv[0]`, not the caller's original `argv[0]`. All following
  arguments are preserved byte-for-byte. This is an accepted linker constraint,
  not a claim of complete `argv[0]` transparency. See the
  [AOSP linker command-line implementation](https://github.com/aosp-mirror/platform_bionic/blob/main/linker/linker_main.cpp).
- API 26 is the **compile/link target**, not proof that direct linker invocation
  works on every API 26 device. Stock
  [Android 8's linker](https://github.com/aosp-mirror/platform_bionic/blob/android-8.0.0_r1/linker/linker_main.cpp)
  did not implement this PROGRAM-loading interface. The current supported ROM's
  explicit `/system/bin/linker64 SHIM` behavior, sibling discovery, SELinux
  permission, and original dependencies must pass the parent's Android tests.
- The shim refuses an absent original, non-regular file, symlink (including
  broken links), and same-inode self/hardlink. It compares `lstat`, no-follow
  open, and `fstat`, then rechecks the pathname before exec. Its temporary fd
  has `O_CLOEXEC`. **This is not a concurrent-installer/security boundary:** the
  required pathname-based linker handoff leaves a final rename race. The
  app-private directory must be trusted and installer changes serialized.
  Byte-hash approval belongs to the installer; the shim does not read key or
  config files, hash binaries, or attempt recovery.
- Preparation/validation failures return 126. A failed `execve` returns 127 for
  `ENOENT`, otherwise 126. Shim diagnostics are fixed strings with no argument,
  path, environment or secret interpolation. Once exec succeeds, any original
  or linker output is their own, not filtered or logged by the shim.
- Normal platform-loader behavior (e.g. secure-exec filtering of `LD_*`) still
  applies. No extra environment variables are injected by this shim.

## Host tests

Files: `tests/test_logic.c`, `tests/fake_original.c`, `tests/test_exec.py`,
`tests/run-host-tests.sh`.

Requirements: existing Linux C compiler (`cc` by default, or `CC=/path/to/gcc`),
libc/libdl development headers/libraries, Python 3, POSIX shell and standard
`mktemp`/`rm` utilities. No packages are downloaded or installed.

```sh
sh tests/run-host-tests.sh
# Optional compiler flags, with an existing toolchain:
CC=clang CFLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer' \
  sh tests/run-host-tests.sh
```

The runner creates temporary build/fixture files **inside this directory** and
removes them on exit. Missing tools fail explicitly with exit 77, not a passing
skip. The production branch is compiled but never executed on the host.
Only the final exec call is replaced for the runnable host shim; all discovery,
validation, argv planning, and normalization code remains production code.

Coverage includes every valid port 1..65535, malformed/overflowing/nonmatching
sockets, long leading-zero strings, exact environment names/order/duplicates,
argument bytes, binary stdin, separate stdout/stderr, same PID, exit codes
0/37/255, canonical symlink invocation from another cwd, unsafe original types,
and no shell fallback. A glibc explicit-loader test runs when a known host
loader is present (otherwise that one test reports a skip). Host coverage does
not replace Android linker/SELinux integration or claim device acceptance.

## Fake original for parent Android integration

`tests/fake_original.c` also compiles with either NDK compiler above using
`-std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie` (no libdl required). Stage its
output as the test directory's `adb.dsh-original-v1`, **never** as a live adb.
It has no adb, network, device, or configuration-file operations. Always pass
only synthetic test arguments/environment; this fixture deliberately prints
all of them. Feed stdin then close the input pipe so the fixture receives EOF.

Default exit status is **37**. To test another fixed status, compile with
`-DDSH_FAKE_EXIT_STATUS=0` (any integer 0..255). The fixture reports:

```text
DSH_FAKE_ORIGINAL_V1
pid=<decimal PID>
argc=<decimal count including argv[0]>
arg0=<lowercase byte hex>
arg1=<lowercase byte hex>
... one argN line per argument ...
envc=<decimal environment entry count>
env0=<lowercase byte hex of full NAME=VALUE entry>
... one envN line per environment entry ...
stdin=<lowercase hex of all raw stdin bytes>
```

All lines end with LF. Stderr is exactly `fake-original stderr\n`. Empty strings
have empty hex values. Fixture I/O failures return 98 (read) or 99 (flush)
rather than its configured status. Compile this fixture separately from the
production shim; it is never part of the deployed artifact.
