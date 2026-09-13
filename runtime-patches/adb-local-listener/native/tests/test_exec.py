#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Host-only subprocess tests; every payload is our synthetic C fixture."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

BUILD = Path(sys.argv[1]).resolve()
del sys.argv[1]
ORIGINAL = "adb.dsh-original-v1"


class ExecTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="fixture-", dir=BUILD)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin with spaces"
        self.bin.mkdir()
        self.shim = self.bin / "adb"
        self.original = self.bin / ORIGINAL
        shutil.copy2(BUILD / "adb-shim-host-test", self.shim)
        self.env = {"HOME": "/synthetic-do-not-read", "PATH": "/synthetic/bin",
                    "KEEP": "spaces\nquotes'\"$;", "EMPTY": ""}

    def install_fake(self, status=37):
        shutil.copy2(BUILD / f"fake-original-{status}", self.original)

    def invoke(self, args=(), data=b"", executable=None, prefix=(), env=None):
        command = [*map(str, prefix), str(executable or self.shim), *args]
        process = subprocess.Popen(command, cwd=self.root,
                                   env=self.env if env is None else env,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE)
        out, err = process.communicate(data, timeout=10)
        return process.pid, process.returncode, out, err

    def verify_payload(self, result, args, data, expected_env, status=37):
        pid, code, out, err = result
        self.assertEqual(code, status)
        self.assertEqual(err, b"fake-original stderr\n")
        lines = out.decode("ascii").splitlines()
        self.assertEqual(lines[0], "DSH_FAKE_ORIGINAL_V1")
        entries = dict(line.split("=", 1) for line in lines[1:])
        self.assertEqual(len(entries), len(lines) - 1)
        self.assertEqual(int(entries.pop("pid")), pid, "exec must retain PID")
        self.assertEqual(int(entries.pop("argc")), len(args) + 1)
        self.assertEqual(bytes.fromhex(entries.pop("arg0")),
                         os.fsencode(self.original.resolve()))
        for i, arg in enumerate(args, 1):
            self.assertEqual(bytes.fromhex(entries.pop(f"arg{i}")), os.fsencode(arg))
        self.assertEqual(int(entries.pop("envc")), len(expected_env))
        for i, (key, value) in enumerate(expected_env.items()):
            self.assertEqual(bytes.fromhex(entries.pop(f"env{i}")),
                             os.fsencode(f"{key}={value}"))
        self.assertEqual(bytes.fromhex(entries.pop("stdin")), data)
        self.assertEqual(entries, {})

    def test_arguments_environment_stdio_pid_and_nonzero_exit(self):
        self.install_fake()
        self.env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
        args = ["", "a b", "'\"\\$;*", "line\nbreak", "测试", "-L",
                "tcp:127.0.0.1:5037", "--", "-P", "12345", "a" * 10000]
        data = bytes(range(256)) * 40 + b"\x00end\n"
        expected = dict(self.env, ADB_SERVER_SOCKET="tcp:5037")
        self.verify_payload(self.invoke(args, data), args, data, expected)

    def test_nonmatching_and_missing_sockets(self):
        self.install_fake()
        sockets = [None, "", "tcp:5037", "tcp:localhost:5037", "tcp:[::1]:5037",
                   "tcp:192.0.2.1:5037", "tcp:127.0.0.1:0", "tcp:127.0.0.1:65536",
                   "tcp:127.0.0.1:+5037", "tcp:127.0.0.1:5037\n"]
        for socket in sockets:
            with self.subTest(socket=socket):
                env = dict(self.env)
                if socket is not None:
                    env["ADB_SERVER_SOCKET"] = socket
                self.verify_payload(self.invoke(env=env), [], b"", env)

    def test_boundary_ports_and_leading_zeroes(self):
        self.install_fake()
        for digits in ["1", "65535", "0005037"]:
            with self.subTest(digits=digits):
                env = dict(self.env, ADB_SERVER_SOCKET=f"tcp:127.0.0.1:{digits}")
                expected = dict(env, ADB_SERVER_SOCKET=f"tcp:{digits}")
                self.verify_payload(self.invoke(env=env), [], b"", expected)

    def test_empty_environment(self):
        self.install_fake()
        self.verify_payload(self.invoke(env={}), [], b"", {})

    def test_other_exit_statuses(self):
        for status in [0, 255]:
            with self.subTest(status=status):
                self.install_fake(status)
                self.verify_payload(self.invoke(), [], b"", self.env, status)

    def test_canonical_loaded_path_not_cwd_or_alias_directory(self):
        self.install_fake()
        alias_directory = self.root / "aliases"
        alias_directory.mkdir()
        alias = alias_directory / "different-name"
        alias.symlink_to(self.shim)
        # A wrong alias-directory lookup would find this non-executable decoy.
        (alias_directory / ORIGINAL).write_bytes(b"not the original")
        self.verify_payload(self.invoke(executable=alias), [], b"", self.env)

    def test_explicit_host_loader_when_available(self):
        loaders = [Path("/lib64/ld-linux-x86-64.so.2"),
                   Path("/lib/ld-linux-aarch64.so.1")]
        loader = next((p for p in loaders if p.is_file()), None)
        if loader is None:
            self.skipTest("No known glibc loader; Android linker is a separate integration gate")
        self.install_fake()
        self.env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
        expected = dict(self.env, ADB_SERVER_SOCKET="tcp:5037")
        # /proc/self/exe is the host loader here, not the shim. Real dladdr
        # discovery still runs: the compile-time seam is only the final exec.
        self.verify_payload(self.invoke(prefix=[loader]), [], b"", expected)

    def assert_rejected(self, expected=b"adb shim: unsafe or unavailable preserved original\n"):
        _, code, out, err = self.invoke(["synthetic-argument-must-not-be-logged"])
        self.assertEqual(code, 126)
        self.assertEqual(out, b"")
        self.assertEqual(err, expected)

    def test_missing_original(self):
        self.assert_rejected()

    def test_directory_original(self):
        self.original.mkdir()
        self.assert_rejected()

    def test_symlink_original(self):
        self.original.symlink_to(BUILD / "fake-original-37")
        self.assert_rejected()

    def test_broken_symlink_original(self):
        self.original.symlink_to(self.root / "absent")
        self.assert_rejected()

    def test_self_hardlink_original(self):
        os.link(self.shim, self.original)
        self.assert_rejected()

    def test_self_symlink_original(self):
        self.original.symlink_to(self.shim)
        self.assert_rejected()

    def test_fifo_original_does_not_block(self):
        os.mkfifo(self.original)
        self.assert_rejected()

    def test_invalid_executable_has_no_shell_fallback(self):
        self.original.write_bytes(b"this is not an ELF or a script\n")
        self.original.chmod(0o700)
        self.assert_rejected(b"adb shim: execution failed\n")


if __name__ == "__main__":
    unittest.main(verbosity=2)
