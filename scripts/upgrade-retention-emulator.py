#!/usr/bin/env python3
"""Disposable CI API35/x86_64 baseline APK -> source APK retention regression.

Proves committed snapshot replacement retains the displaced usr directory and
controlled private fixtures (bytes, modes, links, device/inode), preserves one
workspace sentinel, and regenerates the managed debian entrance. Does not run
Linux guests, test physical hardware/paired ADB, inject retention failures, or
claim exhaustive user-data/credential preservation. No real config/logs are read
or reported; only allowlisted fixture outcomes are emitted. No SKIP success path.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import tempfile
import time
import zipfile

PACKAGE = "com.dsharnessmobile.shell"
BASELINE_SHA256 = "ceb3eed5f1b9577df56f68d30048cc30b1172cc6995d90995502a1bfa8ff8a13"
RECOVERY = "files/home/.dsh/workspaces/upgrade-recovery"
LEGACY = "usr/var/lib/proot-distro/containers/debian/rootfs"
WORKSPACE = "home/.dsh/workspaces/.upgrade-retention-ci/workspace-sentinel"
# All bytes are synthetic and inert. Even executable fixtures are NEVER executed.
FILES = {
    "usr/bin/upgrade-retention-ci-tool": (b"#!/system/bin/sh\n# synthetic custom executable\nexit 91\n", "751"),
    "usr/bin/debian": (b"#!/system/bin/sh\n# synthetic old legacy entrance\nexit 92\n", "750"),
    LEGACY + "/etc/upgrade-retention-ci.conf": (b"fixture_only=true\nvalue=retained-not-a-secret\n", "640"),
    LEGACY + "/usr/bin/upgrade-retention-ci-binary": (b"\x00\x7fCI-INERT-BINARY\xff\n", "711"),
    WORKSPACE: (b"upgrade-retention-ci-workspace-v1\n", "600"),
}
LINK = LEGACY + "/upgrade-retention-ci-link"
LINK_TARGET = "etc/upgrade-retention-ci.conf"


class CheckFailure(Exception):
    """Only constant, non-sensitive failure codes may be reported."""


def require(condition, code):
    if not condition:
        raise CheckFailure(code)


def digest_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def snapshot_digest(apk):
    """Derive expected commit from the APK, verifying its actual archive bytes."""
    with zipfile.ZipFile(apk) as archive:
        require(archive.namelist().count("assets/snapshot.sha256") == 1 and
                archive.namelist().count("assets/snapshot.tar.xz") == 1,
                "ambiguous-or-missing-snapshot-assets")
        require(archive.getinfo("assets/snapshot.sha256").file_size <= 128,
                "oversized-snapshot-fingerprint")
        expected = archive.read("assets/snapshot.sha256").decode("ascii").strip()
        require(re.fullmatch(r"[a-f0-9]{64}", expected), "invalid-snapshot-fingerprint")
        with archive.open("assets/snapshot.tar.xz") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        require(actual == expected, "snapshot-asset-digest-mismatch")
        return expected


class Regression:
    def __init__(self, args, evidence):
        self.args = args
        self.evidence = evidence
        self.port = None

    def record(self, name):
        self.evidence["checks"].append(name)
        print("PASS " + name, flush=True)

    def adb(self, *parts, timeout=60, ok=True):
        # Never return/print command diagnostics in reports (may contain secrets).
        with tempfile.TemporaryFile() as output:
            try:
                result = subprocess.run(["adb", "-s", self.args.serial, *parts],
                                        stdout=output, stderr=subprocess.STDOUT,
                                        timeout=timeout, check=False)
            except subprocess.TimeoutExpired:
                raise CheckFailure("adb-command-timeout") from None
            require(output.tell() <= 1024 * 1024, "adb-output-limit")
            output.seek(0)
            text = output.read(1024 * 1024).decode("utf-8", "replace")
        require(not ok or result.returncode == 0, "adb-command-failed")
        return result.returncode, text.strip()

    def private(self, command, ok=True):
        return self.adb("shell", "run-as", PACKAGE, "sh", "-c", shlex.quote(command), ok=ok)

    def guard(self):
        require(re.fullmatch(r"emulator-[0-9]+", self.args.serial), "non-emulator-serial")
        require(os.environ.get("GITHUB_ACTIONS") == "true" and
                os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted",
                "requires-disposable-github-hosted-ci")
        expected = {"ro.kernel.qemu": "1", "ro.product.cpu.abi": "x86_64",
                    "ro.build.version.sdk": "35"}
        for prop, value in expected.items():
            require(self.adb("shell", "getprop", prop)[1] == value, "emulator-identity-mismatch")
        require(self.adb("shell", "getprop", "ro.hardware")[1] in ("ranchu", "goldfish"),
                "emulator-hardware-mismatch")
        # Successful package-manager query required; an ADB error is not 'fresh'.
        packages = self.adb("shell", "pm", "list", "packages", PACKAGE)[1]
        require(not packages, "target-app-already-installed")
        self.record("fresh API35 x86_64 CI emulator guard")

    def committed(self, expected):
        code, value = self.private(
            "test -s files/.snapshot-fingerprint && "
            "test ! -e files/.snapshot-transaction && test ! -L files/.snapshot-transaction && "
            "test -x files/usr/bin/node && cat files/.snapshot-fingerprint", ok=False)
        return code == 0 and value == expected

    def await_runtime(self, expected):
        deadline = time.monotonic() + self.args.boot_timeout
        while time.monotonic() < deadline:
            if self.committed(expected):
                connection = None
                try:
                    connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
                    connection.request("GET", "/")
                    response = connection.getresponse()
                    healthy = response.status in (200, 302, 303, 401)
                    response.read(4096)  # Do not expose cookies, body or headers.
                    if healthy:
                        return
                except (OSError, http.client.HTTPException):
                    pass
                finally:
                    if connection is not None:
                        connection.close()
            time.sleep(3)
        # Never force-stop, delete data or attempt recovery on failure.
        raise CheckFailure("committed-snapshot-and-http-readiness-timeout")

    def metadata(self, path, kind):
        quoted = shlex.quote(path)
        checks = {"file": "test -f {p} && test ! -L {p}",
                  "directory": "test -d {p} && test ! -L {p}",
                  "link": "test -L {p}"}
        self.private(checks[kind].format(p=quoted))
        # Android toybox stat defaults to lstat (do not pass -L for symlinks).
        text = self.private("stat -c '%a %i %d' " + quoted)[1]
        require(re.fullmatch(r"[0-7]{3,4} [0-9]+ [0-9]+", text), "invalid-fixture-stat")
        mode, inode, device = text.split()
        return {"mode": mode, "inode": inode, "device": device}

    def capture(self, path, kind="file"):
        state = self.metadata(path, kind)
        if kind == "file":
            value = self.private("sha256sum " + shlex.quote(path))[1].split()[0]
            require(re.fullmatch(r"[a-f0-9]{64}", value), "invalid-fixture-hash")
            state["sha256"] = value
        elif kind == "link":
            target = self.private("readlink " + shlex.quote(path))[1]
            require(target == LINK_TARGET, "fixture-link-target-mismatch")
            state["target"] = LINK_TARGET
        return state

    def seed(self):
        self.private("test ! -e " + RECOVERY + " && test ! -L " + RECOVERY)
        self.private("test ! -e files/.snapshot-previous && test ! -L files/.snapshot-previous")
        # Refuse to clobber anything from the pinned baseline, including an alias.
        for relative, (payload, mode) in FILES.items():
            path = "files/" + relative
            parent = str(PurePosixPath(path).parent)
            octal = "".join("\\0%03o" % byte for byte in payload)
            self.private("set -e; test ! -e {p}; test ! -L {p}; mkdir -p {d}; "
                         "(set -C; printf '%b' {data} > {p}); chmod {mode} {p}".format(
                             p=shlex.quote(path), d=shlex.quote(parent),
                             data=shlex.quote(octal), mode=mode))
        self.private("ln -s " + shlex.quote(LINK_TARGET) + " " + shlex.quote("files/" + LINK))
        captured = {}
        for relative, (payload, mode) in FILES.items():
            state = self.capture("files/" + relative)
            require(state["sha256"] == hashlib.sha256(payload).hexdigest() and state["mode"] == mode,
                    "fixture-seed-verification-failed")
            captured[relative] = state
        captured[LINK] = self.capture("files/" + LINK, "link")
        captured["usr"] = self.capture("files/usr", "directory")
        captured[LEGACY] = self.capture("files/" + LEGACY, "directory")
        self.record("synthetic usr executable, legacy rootfs, alias, link and workspace seeded")
        return captured

    def assert_retained(self, captured):
        # Enumerate slot names only; never recursively read or list real runtime data.
        code, text = self.private("for p in " + RECOVERY + "/runtime-*; do "
                                  "[ -e \"$p\" ] || [ -L \"$p\" ] || continue; "
                                  "printf '%s\\n' \"${p##*/}\"; done")
        slots = text.splitlines()
        require(code == 0 and len(slots) == 1 and
                re.fullmatch(r"runtime-[A-Za-z0-9_-]+", slots[0]), "expected-exactly-one-recovery-slot")
        slot = RECOVERY + "/" + slots[0]
        for directory in (RECOVERY, slot):
            require(self.metadata(directory, "directory")["mode"] == "700", "recovery-boundary-not-private")
        self.metadata(slot + "/previous", "directory")
        require(self.metadata(slot + "/receipt.txt", "file")["mode"] == "600", "receipt-not-private")
        # Read only the known generated receipt, bounded before reading; don't print it.
        size = self.private("stat -c '%s' " + shlex.quote(slot + "/receipt.txt"))[1]
        require(size.isdigit() and 0 < int(size) <= 512, "receipt-size-invalid")
        receipt = self.private("cat " + shlex.quote(slot + "/receipt.txt"))[1]
        require(re.fullmatch(
            r"format=1\nkind=displaced-snapshot\npayload=previous\ncreated_at_ms=[0-9]+\n"
            r"payload_presence=directory-indicates-completed-retention\n"
            r"cleanup=explicit-user-only\nauto_restore=false", receipt), "receipt-schema-invalid")
        self.record("exactly one complete recovery slot; root/slot 0700 and bounded receipt 0600")
        for relative, before in captured.items():
            if relative == WORKSPACE:
                after_path = "files/" + relative
                kind = "file"
            else:
                after_path = slot + "/previous/" + relative
                kind = "link" if relative == LINK else "directory" if relative in ("usr", LEGACY) else "file"
            after = self.capture(after_path, kind)
            require(after == before, "retained-fixture-identity-or-content-mismatch")
            # Report controlled relative fixture paths + hashes/modes/identity, never raw bytes.
            self.evidence["fixtures"].append({"fixture": relative, "unchanged": True, **after})
        require(self.metadata("files/usr", "directory")["inode"] != captured["usr"]["inode"],
                "live-usr-was-not-replaced")
        for relative in FILES:
            if relative.startswith("usr/") and relative != "usr/bin/debian":
                path = shlex.quote("files/" + relative)
                self.private("test ! -e " + path + " && test ! -L " + path)
        self.private("test ! -e files/.snapshot-previous && test ! -L files/.snapshot-previous")
        self.record("old usr and all fixtures retain bytes/mode/link/device/inode; workspace unchanged")
        self.record("old usr displaced, not automatically restored into new runtime")

    def assert_alias(self):
        # NativeProot preparation may follow snapshot commit; wait without executing it.
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.private("test -L files/usr/bin/debian && test -x files/usr/bin/debian", ok=False)[0] == 0:
                break
            time.sleep(2)
        else:
            raise CheckFailure("managed-debian-entrance-not-regenerated")
        # Compare paths and known generated wrapper bytes on-device; emit neither.
        self.private('test "$(readlink files/usr/bin/debian)" = "$PWD/files/native-proot-debian-compat.sh"')
        require(self.metadata("files/native-proot-debian-compat.sh", "file")["mode"] == "700",
                "managed-wrapper-mode-invalid")
        self.private("test -x files/usr/bin/dsh-debian && "
                     "test ! -L files/usr/bin/dsh-debian && "
                     "test \"$(wc -l < files/native-proot-debian-compat.sh)\" -eq 2 && "
                     "grep -q '^#!/system/bin/sh$' files/native-proot-debian-compat.sh && "
                     "grep -q -- ' --bind-sdcard -- ' files/native-proot-debian-compat.sh")
        self.record("regenerated managed debian symlink and explicit sdcard wrapper exist; not executed")

    def run(self):
        self.guard()
        self.evidence["stage"] = "verify-apks"
        require(self.args.old_apk.is_file() and self.args.new_apk.is_file(), "required-apk-missing")
        require(digest_file(self.args.old_apk) == BASELINE_SHA256, "pinned-baseline-apk-digest-mismatch")
        old = snapshot_digest(self.args.old_apk)
        new = snapshot_digest(self.args.new_apk)
        require(old != new, "snapshot-did-not-change")
        self.evidence.update(baselineApkSha256=BASELINE_SHA256,
                             baselineSnapshotSha256=old, sourceSnapshotSha256=new)
        self.record("pinned public baseline and both APK snapshot asset hashes verified")
        self.evidence["stage"] = "boot-baseline"
        self.adb("install", "-t", str(self.args.old_apk), timeout=180)
        self.adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
        self.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        port = self.adb("forward", "tcp:0", "tcp:3080")[1]
        require(port.isdigit() and 0 < int(port) <= 65535, "invalid-forward-port")
        self.port = int(port)
        self.adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        self.await_runtime(old)
        self.record("baseline boot committed exact baseline fingerprint, no transaction, HTTP ready")
        self.evidence["stage"] = "seed-fixtures"
        captured = self.seed()
        require(self.committed(old), "baseline-no-longer-committed")
        self.evidence["stage"] = "upgrade-source-apk"
        # Actual PackageManager replacement, never clear/uninstall/reinstall baseline data.
        self.adb("install", "-r", "-t", str(self.args.new_apk), timeout=180)
        self.adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        self.await_runtime(new)
        self.record("source APK replacement committed exact new fingerprint, no transaction, HTTP ready")
        self.evidence["stage"] = "assert-retention"
        self.assert_retained(captured)
        self.evidence["stage"] = "assert-regenerated-alias"
        self.assert_alias()
        require(self.committed(new), "source-no-longer-committed")
        self.evidence.update(status="passed", upgradeRetentionAcceptance=True, stage="complete")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--old-apk", type=Path, required=True)
    parser.add_argument("--new-apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--boot-timeout", type=int, default=1200)
    args = parser.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    evidence = {"scope": "isolated-ci-api35-x86_64-apk-upgrade-retention",
                "status": "failed", "stage": "guard", "checks": [], "fixtures": [],
                "upgradeRetentionAcceptance": False, "nativeGuestAcceptance": False,
                "physicalDeviceAcceptance": False, "pairedAdbAcceptance": False,
                "retentionFailureInjection": False}
    regression = Regression(args, evidence)
    try:
        require(1 <= args.boot_timeout <= 1800, "invalid-boot-timeout")
        regression.run()
    except Exception as error:
        # Arbitrary exceptions can embed adb output/paths: report type, never str().
        evidence["failure"] = str(error) if isinstance(error, CheckFailure) else "internal-" + type(error).__name__
        print("FAIL " + evidence["failure"], flush=True)
    finally:
        if regression.port is not None:
            try:
                regression.adb("forward", "--remove", "tcp:" + str(regression.port), ok=False)
            except Exception:
                evidence["forwardCleanupFailed"] = True
        (args.out / "summary.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return 0 if evidence["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
