# Optional native Debian runtime (ARM64, review draft)

## Purpose and scope

The existing Node/Termux engine is unchanged. This adds an **optional Linux/glibc
userland compatibility path**, not a replacement for the host shell or a general
performance upgrade. A same-architecture Debian no longer needs QEMU instruction
translation when PRoot and its static loader are executed from the APK native
library directory. PRoot still has syscall-interposition overhead; no performance
multiplier has been measured. Root, kernel modules, Docker/systemd guarantees and a
security sandbox are out of scope.

First implementation: ARM64 on Android API 28+. x86_64 DSH builds retain their
existing functionality but intentionally contain **no native PRoot**. An earlier
x86_64 donor binary had only a guessed source revision; it is not distributed.
32-bit loader is packaged with its matching engine, but 32-bit execution and
16-KiB-page devices have not been validated (the ARM64 files have 16-KiB PT_LOAD
alignment; loader32 has 4-KiB alignment).

## Packaging and runtime contract

- Gradle targetAbi accepts arm64-v8a or x86_64; direct builds default to x86_64,
  matching the documented debug snapshot convention. Every managed builder passes
  the selected ABI explicitly. An APK must match its embedded snapshot's node ELF.
- extractNativeLibs=true and useLegacyPackaging=true put the ARM64 files in
  ApplicationInfo.nativeLibraryDir. keepDebugSymbols preserves the pinned bytes.
- NativeProot.prepare is called by EngineManager.shellEnv. It resolves the current
  PackageManager directory and atomically refreshes a private state file and
  $PREFIX/bin/dsh-debian; it never copies native binaries into writable app data.
- The launcher remains visible to the shell plugin without widening its PATH or
  replacing its environment policy. It reads current app-owned state, starts a
  clean Android shell without the host LD_PRELOAD, then gives Debian a clean env.
- Missing optional native support or preparation IO failure is diagnostic, not a
  reason to disable the normal DSH engine. No automatic command retry with QEMU:
  a failed user command must not execute a second time.

## Use and data lifecycle

Provide a trusted ARM64 Debian rootfs yourself; this patch does not download,
install, migrate or erase one. The default is:

```text
files/home/.dsh/workspaces/debian-rootfs
```

This is under the existing workspaces user-data preservation boundary. It is NOT
protection against uninstall, explicit workspace deletion, or user-issued deletion.
The earlier experimental installation under usr/var/lib/proot-distro is **not**
protected against a runtime snapshot replacement. Back it up before upgrades;
use the legacy path only explicitly, for example:

```sh
dsh-debian --rootfs /data/data/com.dsharnessmobile.shell/files/usr/var/lib/proot-distro/containers/debian/rootfs /bin/sh
```

Normal use after provisioning the default rootfs:

```sh
dsh-debian
dsh-debian -- /usr/bin/apt-get update
dsh-debian --bind-sdcard -- /bin/sh
```

/sdcard is opt-in. PRoot is not a security boundary; run trusted software only.
This patch does not change or replace any pre-existing user command named debian.
A later APK without the payload cannot run this path; it must fail clearly rather
than claim native availability.

## Source and licences

scripts/native-proot.json pins the donor release, exact source commit and byte
hashes. vendor/native-proot/assets/native-proot-source/ contains the matching
PRoot/talloc source archive, missing upstream cross-answers, patch, licence texts
and an offline rebuild/relink script. These are also packaged in the APK. Source
provenance verified is not the same claim as independently reproduced bytes or
full-device certification.

## Tests and release boundary

```sh
node scripts/tests/native-proot-launcher.test.mjs
node scripts/tests/native-proot-gate.test.mjs
node scripts/check-native-proot.mjs
node scripts/check-native-proot.mjs --abi arm64 --apk /path/to/arm64.apk
node scripts/check-native-proot.mjs --abi x86_64 --apk /path/to/x86_64.apk
./gradlew :app:testDebugUnitTest -PtargetAbi=arm64-v8a
```

The APK check parses binary AXML, verifies CRCs and duplicate entries, checks exact
native payload bytes/ABI, and streams the embedded snapshot to validate its hash
and node ELF. Explicit unsupported x86_64 is an empty-payload contract, not a skipped
test. Negative fixtures must reject malformed/wrong/duplicate payloads.

A prior **0.13.8 experimental repack with the exact ARM64 payload hashes** passed
native Debian startup, file IO, DNS, apt index refresh and basic signal checks on
one ARM64/4-KiB-page phone without QEMU. That is NOT an end-to-end test of this source
branch. Pure JVM tests use real java.nio but Android API stubs for linking; they
are not Android instrumented/Gradle acceptance tests.

Before marking a PR ready for release: full Gradle/unit-test builds, both final APK
artifact gates, x86_64 emulator regression of the host app, ARM64 source-built APK
smoke, upgrade/rootfs persistence and shell-tool (not only console) checks. The
coordinating repository is private; mirrored build/gate changes require a paired
maintainer change. No CI/mirror gate is disabled to make this draft appear green.

### Local verification receipt (before initial review commit)

- Launcher behaviour: 21 passed, 0 failed, 0 skipped.
- Gate/negative fixtures: 62 passed, 0 failed, 0 skipped; includes corrupt/missing/duplicate ZIP and AXML cases plus streaming large members.
- Kotlin runtime helper: 21 JUnit tests passed on JDK21 with Kotlin2.0.21 and JVM target17. Only Android linking APIs were stubbed; this is not Gradle/device verification.
- Source payload/provenance gate, static build-gate wiring, state registry, manifest hardening, bounded IO, Kotlin comment checks, shell syntax and git whitespace check passed.
- A dedicated native-proot-validation workflow will test both host APK ABIs with a SHA-256-pinned public v0.14.0-preview factory snapshot. It is a development integration check, not a replacement for the private coordination mirror or full release pipeline.
- No user token/configuration/rootfs or diagnostic transcript is included in the Git changes.
