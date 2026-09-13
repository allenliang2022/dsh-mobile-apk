#!/usr/bin/env python3
"""Emulator-only APK/snapshot upgrade and Android instrumentation acceptance.
No physical serials, screenshots, full logcat, model calls or account data.
"""
import argparse
import hashlib
import http.client
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
from types import SimpleNamespace

PACKAGE = "com.dsharnessmobile.shell"
MARKER = "files/home/.dsh/workspaces/debian-rootfs/.native-upgrade-fixture"
LINK = "files/home/.dsh/workspaces/debian-rootfs/.native-upgrade-link"
CLASSES = ",".join(PACKAGE + "." + n for n in
                   ("NativeProotAcceptanceTest", "NativeProotHostAcceptanceTest"))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--old-apk", type=Path, required=True)
    ap.add_argument("--old-sha256", required=True)
    ap.add_argument("--new-apk", type=Path, required=True)
    ap.add_argument("--test-apk", type=Path, required=True)
    ap.add_argument("--snapshot-sha256", required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--boot-timeout", type=int, default=900)
    args = ap.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise SystemExit("Refusing non-emulator ADB serial")
    for value in (args.old_sha256, args.snapshot_sha256):
        if not re.fullmatch(r"[a-f0-9]{64}", value):
            raise SystemExit("Expected an explicit SHA-256 digest")
    for p in (args.old_apk, args.new_apk, args.test_apk):
        if not p.is_file():
            raise SystemExit("Required APK missing: " + str(p))
    with args.old_apk.open("rb") as f:
        if hashlib.file_digest(f, "sha256").hexdigest() != args.old_sha256:
            raise SystemExit("Old release APK digest mismatch")
    args.out.mkdir(parents=True, exist_ok=True)
    evidence = {"scope": "isolated-x86_64-emulator", "checks": [],
                "nativeArm64Acceptance": False}

    def adb(*parts, timeout=60, ok=True):
        # Disk-backed capture avoids an unbounded PIPE buffer; only 1 MiB is read.
        with tempfile.TemporaryFile() as log:
            result = subprocess.run(["adb", "-s", args.serial, *parts],
                                    stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            if log.tell() > 1024 * 1024:
                raise RuntimeError("ADB diagnostic output exceeded 1 MiB")
            log.seek(0)
            text = log.read(1024 * 1024).decode("utf-8", "replace")
        if ok and result.returncode:
            raise RuntimeError("ADB test command failed: " + parts[0] + "\n" + text[-4000:])
        return SimpleNamespace(returncode=result.returncode, stdout=text)

    def record(name):
        evidence["checks"].append(name)
        print("PASS " + name, flush=True)

    def run_as(command, ok=True):
        return adb("shell", "run-as", PACKAGE, "sh", "-c", shlex.quote(command), ok=ok)

    qemu = adb("shell", "getprop", "ro.kernel.qemu").stdout.strip()
    hardware = adb("shell", "getprop", "ro.hardware").stdout.strip()
    abi = adb("shell", "getprop", "ro.product.cpu.abi").stdout.strip()
    if qemu != "1" or hardware not in ("ranchu", "goldfish") or abi != "x86_64":
        raise SystemExit("Target is not an isolated x86_64 Android emulator")
    api = int(adb("shell", "getprop", "ro.build.version.sdk").stdout.strip())
    if api < 30:
        raise SystemExit("Host acceptance requires scoped storage, API30+")
    evidence["api"] = api
    record("emulator-only execution guard")
    if "package:" in adb("shell", "pm", "path", PACKAGE, ok=False).stdout:
        raise SystemExit("Expected a fresh emulator without a pre-existing target app")
    port = None

    def await_runtime(expected=None):
        deadline = time.monotonic() + args.boot_timeout
        while time.monotonic() < deadline:
            state = run_as('test -s files/.snapshot-fingerprint && '
                           'test ! -e files/.snapshot-transaction && '
                           'test -x files/usr/bin/node && cat files/.snapshot-fingerprint', ok=False)
            fingerprint = state.stdout.strip()
            ready = (state.returncode == 0 and re.fullmatch(r"[a-f0-9]{64}", fingerprint)
                     and (expected is None or fingerprint == expected))
            healthy = False
            if ready:
                connection = None
                try:
                    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    connection.request("GET", "/")
                    response = connection.getresponse()
                    healthy = response.status in (200, 302, 303, 401)
                    response.read(4096)  # Never print boot/session auth material.
                except (OSError, http.client.HTTPException):
                    pass
                finally:
                    if connection is not None:
                        connection.close()
            if ready and healthy:
                return fingerprint
            time.sleep(3)
        # Never force-stop while a snapshot transaction is being extracted/swapped.
        raise RuntimeError("Runtime did not reach committed snapshot + HTTP readiness")

    try:
        adb("install", "-r", "-t", str(args.old_apk), timeout=180)
        # Permissions only on the identity-checked throwaway emulator.
        adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
        if api >= 33:
            adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", ok=False)
        port = int(adb("forward", "tcp:0", "tcp:3080").stdout.strip())
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        old_fingerprint = await_runtime()
        record("old release commits its snapshot and serves HTTP")
        payload = "native-proot-workspace-upgrade-fixture-v1"
        expected_hash = hashlib.sha256(payload.encode()).hexdigest()
        command = ('set -e; mkdir -p ' + shlex.quote(str(Path(MARKER).parent)) + '; '
                   'printf %s ' + shlex.quote(payload) + ' > ' + shlex.quote(MARKER) + '; '
                   'ln -s .native-upgrade-fixture ' + shlex.quote(LINK))
        run_as(command)
        if run_as('sha256sum ' + shlex.quote(MARKER)).stdout.split()[0] != expected_hash:
            raise RuntimeError("Fixture write verification failed")
        record("workspace fixture and relative symlink created")
        # Actual PackageManager replacement after the old transaction ends.
        adb("install", "-r", "-t", str(args.new_apk), timeout=180)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        new_fingerprint = await_runtime(args.snapshot_sha256)
        if new_fingerprint == old_fingerprint:
            raise RuntimeError("No snapshot change: upgrade was not exercised")
        record("source APK upgrade commits a different snapshot and serves HTTP")
        after = run_as('sha256sum ' + shlex.quote(MARKER)).stdout.split()[0]
        target = run_as('readlink ' + shlex.quote(LINK)).stdout.strip()
        linked = run_as('sha256sum ' + shlex.quote(LINK)).stdout.split()[0]
        if after != expected_hash or linked != expected_hash or target != ".native-upgrade-fixture":
            raise RuntimeError("Workspace rootfs fixture changed during upgrade")
        record("workspace content and symlink survive actual APK/snapshot upgrade")
        # Reduce broad storage grants before the emulator-only host suite. Scoped
        # storage is not a sandbox for public files previously owned by the app.
        adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "deny")
        adb("shell", "pm", "revoke", PACKAGE, "android.permission.WRITE_EXTERNAL_STORAGE", ok=False)
        adb("install", "-r", "-t", str(args.test_apk), timeout=180)
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", CLASSES,
                     PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=600, ok=False)
        (args.out / "instrumentation.log").write_text(result.stdout)
        summary = re.search(r"OK \(\d+ tests?\)", result.stdout)
        if (result.returncode or not summary or
                re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", result.stdout)):
            raise RuntimeError("Instrumentation failed; see bounded technical report")
        evidence["instrumentationSummary"] = summary.group(0)
        record("real Android PM/filesystem/host launcher instrumentation")
        evidence["status"] = "passed"
    except Exception as exc:
        evidence["status"] = "failed"
        evidence["reason"] = str(exc)
        raise
    finally:
        (args.out / "summary.json").write_text(json.dumps(evidence, indent=2) + "\n")
        if port is not None:
            adb("forward", "--remove", "tcp:" + str(port), ok=False)


if __name__ == "__main__":
    main()
