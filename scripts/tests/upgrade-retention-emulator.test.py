#!/usr/bin/env python3
"""Offline guards/fixture assertions only: NOT APK or Android acceptance."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

sys.dont_write_bytecode = True
RUNNER = Path(__file__).resolve().parents[1] / "upgrade-retention-emulator.py"
SPEC = importlib.util.spec_from_file_location("upgrade_retention", RUNNER)
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
CI = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted"}


class GuardTest(unittest.TestCase):
    def runner(self, serial="emulator-5554"):
        return M.Regression(SimpleNamespace(serial=serial, boot_timeout=1), {"checks": [], "fixtures": []})

    def test_non_emulator_serial_never_calls_adb(self):
        for serial in ("physical", "127.0.0.1:5555", "emulator-", "emulator-5554;echo x"):
            runner = self.runner(serial)
            with self.subTest(serial=serial), patch.object(runner, "adb") as adb:
                with self.assertRaisesRegex(M.CheckFailure, "non-emulator-serial"):
                    runner.guard()
                adb.assert_not_called()

    def test_local_or_self_hosted_execution_never_calls_adb(self):
        for env in ({}, {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted"}):
            runner = self.runner()
            with patch.dict(os.environ, env, clear=True), patch.object(runner, "adb") as adb:
                with self.assertRaisesRegex(M.CheckFailure, "requires-disposable"):
                    runner.guard()
                adb.assert_not_called()

    def test_identity_or_preexisting_app_fails_before_mutation(self):
        correct = {"ro.kernel.qemu": "1", "ro.product.cpu.abi": "x86_64",
                   "ro.build.version.sdk": "35", "ro.hardware": "ranchu", M.PACKAGE: ""}
        for prop, bad in (("ro.kernel.qemu", "0"), ("ro.product.cpu.abi", "arm64-v8a"),
                          ("ro.build.version.sdk", "34"), ("ro.hardware", "phone"),
                          (M.PACKAGE, "package:" + M.PACKAGE)):
            values = dict(correct, **{prop: bad})
            runner = self.runner()
            def fake(*parts, **kwargs):
                self.assertIn(parts[:2], (("shell", "getprop"), ("shell", "pm")))
                return 0, values[parts[-1]]
            with self.subTest(prop=prop), patch.dict(os.environ, CI), patch.object(runner, "adb", side_effect=fake):
                with self.assertRaises(M.CheckFailure):
                    runner.guard()

    def test_adb_uses_only_explicit_serial_and_hides_diagnostics(self):
        runner = self.runner()
        def fake(command, **kwargs):
            self.assertEqual(command, ["adb", "-s", "emulator-5554", "shell", "getprop", "ro.hardware"])
            kwargs["stdout"].write(b"DO-NOT-REPORT-SECRET")
            return SimpleNamespace(returncode=1)
        with patch.object(M.subprocess, "run", side_effect=fake):
            with self.assertRaisesRegex(M.CheckFailure, "^adb-command-failed$"):
                runner.adb("shell", "getprop", "ro.hardware")

    def test_asset_hash_is_derived_and_verified(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "fixture.apk"
            payload = b"synthetic archive bytes; not a real APK"
            expected = hashlib.sha256(payload).hexdigest()
            for claimed in (expected, "0" * 64):
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("assets/snapshot.sha256", claimed + "\n")
                    archive.writestr("assets/snapshot.tar.xz", payload)
                if claimed == expected:
                    self.assertEqual(expected, M.snapshot_digest(apk))
                else:
                    with self.assertRaisesRegex(M.CheckFailure, "snapshot-asset-digest-mismatch"):
                        M.snapshot_digest(apk)

    def test_missing_retention_exits_nonzero_with_safe_failure_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "report"
            def fail(runner):
                runner.evidence["stage"] = "assert-retention"
                raise M.CheckFailure("expected-exactly-one-recovery-slot")
            with patch.object(M.Regression, "run", fail), contextlib.redirect_stdout(io.StringIO()):
                result = M.main(["--serial", "emulator-5554", "--old-apk", "old.apk",
                                 "--new-apk", "new.apk", "--out", str(out)])
            report = json.loads((out / "summary.json").read_text())
            self.assertEqual(1, result)
            self.assertEqual("failed", report["status"])
            self.assertFalse(report["upgradeRetentionAcceptance"])
            self.assertFalse(report["nativeGuestAcceptance"])
            self.assertFalse(report["physicalDeviceAcceptance"])
            self.assertFalse(report["pairedAdbAcceptance"])
            self.assertEqual("assert-retention", report["stage"])

    def test_unexpected_exception_text_never_enters_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = io.StringIO()
            with patch.object(M.Regression, "run", side_effect=ValueError("SECRET-RAW-CONFIG")), contextlib.redirect_stdout(output):
                result = M.main(["--serial", "emulator-5554", "--old-apk", "old.apk",
                                 "--new-apk", "new.apk", "--out", tmp])
            self.assertEqual(1, result)
            self.assertNotIn("SECRET", output.getvalue() + (Path(tmp) / "summary.json").read_text())


class FixtureAssertionTest(unittest.TestCase):
    """Exercise shell fixtures on disposable HOST temp files, never adb/Android."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.runner = M.Regression(SimpleNamespace(), {"checks": [], "fixtures": []})
        self.runner.private = self.local_shell
        self.runner.record = lambda name: None
        self.captured = self.runner.seed()
        recovery = self.root / M.RECOVERY
        recovery.mkdir(mode=0o700)
        self.slot = recovery / "runtime-fixture"
        self.slot.mkdir(mode=0o700)
        previous = self.slot / "previous"
        previous.mkdir()
        (self.root / "files/usr").rename(previous / "usr")
        (self.root / "files/usr").mkdir()
        (self.slot / "receipt.txt").write_text(
            "format=1\nkind=displaced-snapshot\npayload=previous\ncreated_at_ms=1\n"
            "payload_presence=directory-indicates-completed-retention\n"
            "cleanup=explicit-user-only\nauto_restore=false\n")
        (self.slot / "receipt.txt").chmod(0o600)

    def local_shell(self, command, ok=True):
        result = subprocess.run(["sh", "-c", command], cwd=self.root, capture_output=True, text=True, timeout=5)
        M.require(not ok or result.returncode == 0, "fixture-shell-failed")
        return result.returncode, result.stdout.strip()

    def test_synthetic_rename_preserves_every_fixture(self):
        self.runner.assert_retained(self.captured)
        self.assertEqual(len(self.captured), len(self.runner.evidence["fixtures"]))

    def test_missing_or_extra_slot_rejected(self):
        (self.slot.parent / "runtime-extra").mkdir()
        with self.assertRaisesRegex(M.CheckFailure, "expected-exactly-one-recovery-slot"):
            self.runner.assert_retained(self.captured)
        (self.slot.parent / "runtime-extra").rmdir()
        self.slot.rename(self.slot.parent / "not-a-runtime-slot")
        with self.assertRaisesRegex(M.CheckFailure, "expected-exactly-one-recovery-slot"):
            self.runner.assert_retained(self.captured)

    def test_changed_bytes_mode_link_or_workspace_rejected(self):
        choices = [self.slot / "previous/usr/bin/upgrade-retention-ci-tool",
                   self.root / ("files/" + M.WORKSPACE)]
        for path in choices:
            original = path.read_bytes()
            path.write_bytes(b"changed-fixture")
            with self.assertRaises(M.CheckFailure):
                self.runner.assert_retained(self.captured)
            path.write_bytes(original)
        path = choices[0]
        path.chmod(0o700)
        with self.assertRaises(M.CheckFailure):
            self.runner.assert_retained(self.captured)
        path.chmod(0o751)
        link = self.slot / "previous" / M.LINK
        link.unlink()
        link.symlink_to("wrong-target")
        with self.assertRaises(M.CheckFailure):
            self.runner.assert_retained(self.captured)

    def test_unsafe_recovery_mode_rejected(self):
        self.slot.chmod(0o755)
        with self.assertRaisesRegex(M.CheckFailure, "recovery-boundary-not-private"):
            self.runner.assert_retained(self.captured)


if __name__ == "__main__":
    unittest.main()
