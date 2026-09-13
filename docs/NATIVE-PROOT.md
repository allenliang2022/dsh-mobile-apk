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

## Android and upgrade acceptance

`Native PRoot validation` also builds the Android test APK. After both source APK
builds pass, a separate throwaway Android 35 x86_64 emulator installs the pinned
0.13.8 baseline, waits for the snapshot transaction and HTTP service, writes a
synthetic workspace fixture, and performs a real PackageManager upgrade. It checks
that the snapshot fingerprint changes while fixture bytes and relative symlinks
survive, then runs only the NativeProot instrumentation classes.

`scripts/native-proot-emulator-smoke.py` requires an `emulator-<port>` serial AND
ranchu/goldfish/qemu identity; it rejects pre-existing app installations and never
accepts physical/network serials. Output is bounded technical test evidence, not
screenshots or full device logs. Broad storage permission is revoked before the
Context-isolated host tests. Guard tests are separate from actual acceptance.

The fixture is not a Linux rootfs and x86_64 still has no native PRoot payload.
These checks cover Android PM/NIO/host execution and upgrade data retention, not
ARM64 Linux execution, guest package management or device-specific behaviour.
`NativeProotUpgradeTest` separately exercises the actual snapshot transaction
implementation's commit, rollback and recovery using the production preservation
list rather than reimplementing the copy policy.

A separate helper-only emulator matrix covers API26 and API28. It installs the
source APK and runs the PM/NIO/helper class without the host suite or snapshot
startup. Its report explicitly keeps upgradeAcceptance and nativeArm64Acceptance
false; passing a boundary helper test is not native guest or full host acceptance.

Source revision ded8081 passed the complete API35 x86_64 upgrade flow and all
8 real Android instrumented tests. Both source-APK build jobs also passed. These
receipts cover only their reported emulator and source revision; later changes
must be rerun. The coordinated-root sync allowlist and synthetic layout regressions
are in scripts/native-proot-mirror-files.json and native-proot-mirror.test.mjs.
They are not a receipt of a synchronized real coordinator checkout.

Source revision 5187ba6 additionally passed API26 and API28 helper instrumentation
(7 tests on each emulator) and repeated API35 host/upgrade acceptance (8 tests).
Both Gradle builds reported 267 JVM tests with no errors/failures/skips. The
reported upgrade fingerprints changed from the baseline to the pinned source
snapshot while the synthetic workspace bytes and symlink survived. Neither the
API-boundary report nor the upgrade report claims ARM64 native guest acceptance.

## Independent source rebuild

The native-source-rebuild CI job uses NDK r29 and the bundled offline rebuild
inputs in a fresh output directory. It never replaces the pinned APK payload.
`vendor/native-proot/verify-rebuild.mjs` checks output ELF class/machine, native
interpreter and PT_LOAD alignment, records SHA-256, and reports whether bytes match
the pinned artifacts. Structural checks alone do not establish compilation: the
preceding successful rebuild step is required. Native execution remains explicitly
unverified by this job. No compiled replacement binary is published by this step.

## ARM64 Android software feasibility probe

The standard macos-15 ARM64 runner has no supported nested virtualization, so HVF
is not assumed available. A separate, bounded probe requests the emulator's software
path with both `-accel off` and `-feature -HVF`; this is a feasibility experiment,
not a known working platform or release gate. It uses an official API30 ARM64 image,
checks emulator/kernel ABI and runs only the APK helper suite if boot succeeds.
The tested APK/test-APK artifacts are pinned to source revision 98638a2, independent
of the probe script revision. No Linux rootfs or full guest acceptance is claimed.

References: [standard runner limitations](https://docs.github.com/en/actions/reference/runners/github-hosted-runners#limitations-for-arm64-macos-runners),
[emulator acceleration handling](https://android.googlesource.com/platform/external/qemu/+/ae9d18d2b6261179fbd57fffec720a04f7bfb053/android/android-emu/android/main-common.c#1481),
[HVF feature gate](https://android.googlesource.com/platform/external/qemu/+/ae9d18d2b6261179fbd57fffec720a04f7bfb053/android/emu/feature/src/android/emulation/CpuAccelerator.cpp#815).
The regular x86_64-host launcher rejects ARM64 API28+ AVDs; Linux qemu-user is not
a substitute for an ARM64 Android system environment.

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

Native ARM64 guest execution is a separate acceptance target from source builds,
host-side fixtures and emulator host checks. Standalone JVM helper tests use real
java.nio with Android API stubs for linking; they alone cannot establish Android
or native Linux guest acceptance. Test results must identify the exact source
revision and the runtime contract exercised.

Before marking a PR ready for release: full Gradle/unit-test builds, both final APK
artifact gates, x86_64 emulator regression of the host app, ARM64 source-built APK
smoke, upgrade/rootfs persistence and shell-tool (not only console) checks. The
coordinating repository is private; mirrored build/gate changes require a paired
maintainer change. No CI/mirror gate is disabled to make this draft appear green.

### Local verification receipt (before initial review commit)

- Launcher behaviour: 21 passed, 0 failed, 0 skipped.
- Gate/negative fixtures: 65 passed, 0 failed, 0 skipped; includes corrupt/missing/duplicate ZIP and AXML cases plus streaming large members.
- Kotlin runtime helper: 21 JUnit tests passed on JDK21 with Kotlin2.0.21 and JVM target17. Only Android linking APIs were stubbed; this is not Gradle/device verification.
- Source payload/provenance gate, static build-gate wiring, state registry, manifest hardening, bounded IO, Kotlin comment checks, shell syntax and git whitespace check passed.
- Source revision a12307e passed both host APK builds and artifact gates with a SHA-256-pinned public v0.14.0-preview factory snapshot; both Gradle runs reported 261 JVM tests passed, with no failures/errors/skips. This does not replace runtime acceptance or coordination mirror checks. The additional emulator and upgrade tests must be verified on their own subsequent revision.

### First cloud attempt

Run 34735460798 built the ARM64 source APK and ran Gradle JVM tests successfully,
but final verification rejected a missing .tar.gz corresponding-source asset. The
archive is now packaged as .tgz (same gzip bytes and SHA256, noCompress=tgz) to avoid
.gz asset handling. This failed APK was not delivered. Subsequent validation must
verify the actual asset names and bytes, not merely successful compilation.
