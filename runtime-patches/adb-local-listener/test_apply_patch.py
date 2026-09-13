"""Synthetic-only tests: never instantiate an installer with the live default.

Run: python3 -B -m unittest discover -s <this-directory> -p test_apply_patch.py -v
The small fake ELF headers are test data and are NEVER executed.
"""
import contextlib
import errno
import io
import json
import os
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest import mock

import apply_patch as patch


def fake_elf(machine=183, payload=b"factory"):
    header = bytearray(64)
    header[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHI", header, 16, 3, machine, 1)
    struct.pack_into("<H", header, 52, 64)
    return bytes(header) + payload


def put(path, data, mode=0o600):
    path.write_bytes(data)
    path.chmod(mode)


class Fixture:
    def __init__(self, case, abi="arm64-v8a", mode=0o700):
        temp = tempfile.TemporaryDirectory(prefix="adb-installer-unit-")
        case.addCleanup(temp.cleanup)
        # Some host /tmp values themselves contain symlinks. Resolve only the
        # fixture harness root, not the installer's tested path handling.
        self.base = Path(temp.name).resolve()
        self.root = self.base / "files"
        (self.root / "usr/bin").mkdir(parents=True, mode=0o700)
        (self.root / "home").mkdir(mode=0o700)
        self.package = self.base / "package"
        self.package.mkdir(mode=0o700)
        self.original = fake_elf(patch.MACHINES[abi])
        self.shim = fake_elf(patch.MACHINES[abi], b"shim")
        self.adb = self.root / "usr/bin/adb"
        put(self.adb, self.original, mode)
        self.shim_path = self.package / f"adb-shim-{abi}"
        put(self.shim_path, self.shim, 0o700)
        self.manifest_path = self.package / "manifest.json"
        self.manifest = {"schema": 1, "patchId": patch.PATCH_ID, "artifacts": {
            abi: {"file": self.shim_path.name, "sha256": patch.sha(self.shim),
                  "originalSha256": patch.sha(self.original), "elfMachine": patch.MACHINES[abi]}}}
        self.save_manifest()
        self.manager = patch.Installer(self.root, self.manifest_path)
        self.abi = abi
        self.mode = mode

    def save_manifest(self):
        put(self.manifest_path, json.dumps(self.manifest).encode())

    def fresh(self, checkpoint=None, abi=None):
        return patch.Installer(self.root, self.manifest_path, abi, checkpoint)


class InstallerTests(unittest.TestCase):
    def fixture(self, **kwargs):
        return Fixture(self, **kwargs)

    def assert_refused(self, callable_):
        with self.assertRaises((patch.PatchError, OSError)):
            callable_()

    def test_roundtrip_both_abis_and_modes(self):
        for abi in patch.MACHINES:
            for mode in (0o700, 0o755):
                with self.subTest(abi=abi, mode=oct(mode)):
                    f = self.fixture(abi=abi, mode=mode)
                    m = f.manager
                    before = f.adb.stat().st_ino
                    self.assertEqual(m.preflight()["state"], "ready")
                    self.assertEqual(m.apply()["state"], "applied")
                    self.assertNotEqual(f.adb.stat().st_ino, before)
                    self.assertEqual(f.adb.read_bytes(), f.shim)
                    self.assertEqual(m.sibling.read_bytes(), f.original)
                    self.assertEqual(m.backup.read_bytes(), f.original)
                    self.assertEqual(stat.S_IMODE(m.state.stat().st_mode), 0o700)
                    for path in (m.backup, m.receipt_path, m.state / "installer.lock"):
                        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
                    self.assertEqual(stat.S_IMODE(m.sibling.stat().st_mode), mode)
                    self.assertFalse(m.sibling.is_symlink())
                    self.assertEqual(m.sibling.stat().st_nlink, 1)
                    self.assertEqual(m.rollback()["state"], "rolled-back")
                    self.assertEqual(f.adb.read_bytes(), f.original)
                    self.assertEqual(stat.S_IMODE(f.adb.stat().st_mode), mode)
                    self.assertFalse(m.sibling.exists())
                    self.assertTrue(m.backup.exists())
                    self.assertEqual(m.rollback()["state"], "rolled-back")
                    self.assertEqual(m.apply()["state"], "applied")

    def test_read_only_status_preflight_and_unmanaged_rollback(self):
        f = self.fixture()
        real_open = os.open
        def readonly_open(path, flags, *args, **kwargs):
            self.assertFalse(flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC))
            return real_open(path, flags, *args, **kwargs)
        with mock.patch.object(patch.os, "open", side_effect=readonly_open), \
                mock.patch.object(patch, "fsync_dir", side_effect=AssertionError("write")), \
                mock.patch.object(Path, "mkdir", side_effect=AssertionError("mkdir")):
            self.assertEqual(f.manager.status()["state"], "unmanaged")
            self.assertEqual(f.manager.preflight()["state"], "ready")
            self.assertEqual(f.manager.rollback()["state"], "not-installed")
        self.assertFalse(f.manager.state.exists())

    def test_repeat_apply_does_not_back_up_shim_or_replace_again(self):
        f = self.fixture()
        f.manager.apply()
        inodes = {p: p.stat().st_ino for p in (f.adb, f.manager.sibling, f.manager.backup, f.manager.receipt_path)}
        f.manager.apply()
        self.assertEqual(inodes, {p: p.stat().st_ino for p in inodes})
        self.assertEqual(f.manager.backup.read_bytes(), f.original)

    def test_custom_original_refused_without_creating_state(self):
        f = self.fixture()
        put(f.adb, fake_elf(payload=b"custom"), f.mode)
        self.assert_refused(f.manager.apply)
        self.assertFalse(f.manager.state.exists())
        self.assertFalse(f.manager.sibling.exists())

    def test_bad_shim_hash_and_elf_and_abi(self):
        for data, update_hash in ((b"broken", False), (b"broken", True), (fake_elf(62), True)):
            with self.subTest(data=data, update_hash=update_hash):
                f = self.fixture()
                put(f.shim_path, data, 0o700)
                if update_hash:
                    f.manifest["artifacts"][f.abi]["sha256"] = patch.sha(data)
                    f.save_manifest()
                self.assert_refused(f.manager.apply)
                self.assertEqual(f.adb.read_bytes(), f.original)
                self.assertFalse(f.manager.state.exists())
        f = self.fixture()
        self.assert_refused(f.fresh(abi="x86_64").preflight)

    def test_manifest_path_escape_unknown_abi_and_invalid_schema(self):
        changes = [lambda m: m.update(schema=2), lambda m: m.update(schema=True),
                   lambda m: m.update(patchId="other"), lambda m: m.update(artifacts={}),
                   lambda m: m["artifacts"].update({"mips": {}}),
                   lambda m: m["artifacts"]["arm64-v8a"].update(file="../escape"),
                   lambda m: m["artifacts"]["arm64-v8a"].update(file="/tmp/escape"),
                   lambda m: m["artifacts"]["arm64-v8a"].update(elfMachine=62),
                   lambda m: m["artifacts"]["arm64-v8a"].update(sha256="0" * 63)]
        for change in changes:
            f = self.fixture()
            change(f.manifest)
            f.save_manifest()
            self.assert_refused(f.manager.apply)
            self.assertFalse(f.manager.state.exists())
        f = self.fixture()
        put(f.manifest_path, b'{"schema":1,"schema":1}')
        self.assert_refused(f.manager.preflight)

    def test_symlink_adb_artifact_and_manifest_rejected(self):
        for which in ("adb", "shim_path", "manifest_path"):
            with self.subTest(which=which):
                f = self.fixture()
                path = getattr(f, which)
                target = path.with_name(path.name + ".target")
                path.rename(target)
                path.symlink_to(target)
                self.assert_refused(f.manager.apply)
                self.assertFalse(f.manager.state.exists())

    def test_symlink_ancestors_and_unsafe_permissions(self):
        for relative in ("usr/bin", "home", "home/.dsh"):
            with self.subTest(relative=relative):
                f = self.fixture()
                path = f.root / relative
                if not path.exists():
                    path.mkdir()
                target = f.base / "relocated"
                path.rename(target)
                path.symlink_to(target, target_is_directory=True)
                self.assert_refused(f.manager.apply)
        f = self.fixture()
        (f.root / "usr/bin").chmod(0o777)
        self.assert_refused(f.manager.preflight)
        self.assertEqual(stat.S_IMODE((f.root / "usr/bin").stat().st_mode), 0o777)
        f = self.fixture()
        f.adb.chmod(0o777)
        self.assert_refused(f.manager.apply)

    def test_unowned_sibling_even_matching_original_is_not_adopted(self):
        for kind in ("regular", "symlink", "directory"):
            f = self.fixture()
            sibling = f.manager.sibling
            if kind == "regular":
                put(sibling, f.original, f.mode)
            elif kind == "symlink":
                sibling.symlink_to(f.adb)
            else:
                sibling.mkdir()
            self.assert_refused(f.manager.apply)
            self.assertTrue(os.path.lexists(sibling))
            self.assertFalse(f.manager.state.exists())

    def test_fifo_and_hardlink_rejected(self):
        f = self.fixture()
        f.adb.unlink()
        os.mkfifo(f.adb, 0o700)
        self.assert_refused(f.manager.preflight)
        f = self.fixture()
        try:
            os.link(f.adb, f.base / "hardlink")
        except (AttributeError, OSError) as error:
            if isinstance(error, OSError) and error.errno not in (errno.EPERM, errno.EACCES, errno.ENOTSUP):
                raise
            # Android denies hardlinks. Test the same fstat admission branch
            # without requiring a forbidden link operation to succeed.
            real_fstat = os.fstat
            def linked(fd):
                info = real_fstat(fd)
                values = list(info)
                values[3] = 2
                return os.stat_result(values)
            with mock.patch.object(patch.os, "fstat", side_effect=linked):
                self.assert_refused(f.manager.preflight)
        else:
            self.assert_refused(f.manager.preflight)

    def test_fault_checkpoints_apply_recover_and_rollback(self):
        points = ("after_receipt_prepared", "after_private_backup", "after_sibling_backup",
                  "after_stage", "before_replace", "after_replace", "after_receipt_applied")
        for point in points:
            with self.subTest(point=point):
                f = self.fixture()
                def fail(here):
                    if here == point:
                        raise OSError(errno.ENOSPC, point)
                self.assert_refused(f.fresh(checkpoint=fail).apply)
                self.assertIn(f.adb.read_bytes(), (f.original, f.shim))
                if f.adb.read_bytes() == f.shim:
                    self.assertEqual(f.manager.backup.read_bytes(), f.original)
                    self.assertEqual(f.manager.sibling.read_bytes(), f.original)
                self.assertEqual(f.fresh().apply()["state"], "applied")
                self.assertEqual(f.fresh().rollback()["state"], "rolled-back")
                self.assertEqual(f.adb.read_bytes(), f.original)

    def test_rollback_can_cancel_prepared_transaction_at_every_point(self):
        for point in ("after_receipt_prepared", "after_private_backup", "after_replace"):
            f = self.fixture()
            def fail(here):
                if here == point:
                    raise OSError(point)
            self.assert_refused(f.fresh(checkpoint=fail).apply)
            self.assertEqual(f.fresh().rollback()["state"], "rolled-back")
            self.assertEqual(f.adb.read_bytes(), f.original)

    def test_fault_checkpoints_rollback_recover(self):
        for point in ("after_receipt_rollback_prepared", "after_rollback_stage",
                      "before_rollback_replace", "after_rollback_replace", "after_receipt_rolled_back"):
            with self.subTest(point=point):
                f = self.fixture()
                f.manager.apply()
                def fail(here):
                    if here == point:
                        raise OSError(errno.EIO, point)
                self.assert_refused(f.fresh(checkpoint=fail).rollback)
                self.assertEqual(f.manager.backup.read_bytes(), f.original)
                self.assertEqual(f.fresh().rollback()["state"], "rolled-back")
                self.assertEqual(f.adb.read_bytes(), f.original)

    def test_stage_and_backup_io_failures_leave_original_recoverable(self):
        for destination in ("private", "sibling", "shim"):
            f = self.fixture()
            real_stage = patch.stage_file
            def fail(directory, data, mode):
                match = ((destination == "private" and directory == f.manager.state and data == f.original)
                         or (destination == "sibling" and directory == f.manager.bin and data == f.original)
                         or (destination == "shim" and directory == f.manager.bin and data == f.shim))
                if match:
                    raise OSError(errno.ENOSPC, destination)
                return real_stage(directory, data, mode)
            with mock.patch.object(patch, "stage_file", side_effect=fail):
                self.assert_refused(f.manager.apply)
            self.assertEqual(f.adb.read_bytes(), f.original)
            self.assertEqual(f.fresh().apply()["state"], "applied")

    def test_backup_publish_failure_is_retryable(self):
        f = self.fixture()
        with mock.patch.object(patch.os, "rename", side_effect=OSError(errno.EIO, "backup publish")):
            self.assert_refused(f.manager.apply)
        self.assertEqual(f.adb.read_bytes(), f.original)
        self.assertEqual(f.fresh().apply()["state"], "applied")

    def test_replace_and_receipt_commit_failures_are_recoverable(self):
        for failure in ("replace", "prepared", "applied"):
            f = self.fixture()
            real_replace = os.replace
            def fail(src, dst):
                dst = Path(dst)
                if failure == "replace" and dst == f.adb:
                    raise OSError(errno.EIO, failure)
                if dst == f.manager.receipt_path and failure in ("prepared", "applied"):
                    if json.loads(Path(src).read_bytes())["phase"] == failure:
                        raise OSError(errno.EIO, failure)
                return real_replace(src, dst)
            with mock.patch.object(patch.os, "replace", side_effect=fail):
                self.assert_refused(f.manager.apply)
            expected = f.shim if failure == "applied" else f.original
            self.assertEqual(f.adb.read_bytes(), expected)
            self.assertEqual(f.fresh().apply()["state"], "applied")
            self.assertEqual(f.fresh().rollback()["state"], "rolled-back")

    def test_fsync_failure_after_replace_uses_prepared_receipt(self):
        f = self.fixture()
        real_sync = patch.fsync_dir
        def fail(path):
            if path == f.manager.bin and f.adb.read_bytes() == f.shim:
                raise OSError(errno.EIO, "directory fsync")
            return real_sync(path)
        with mock.patch.object(patch, "fsync_dir", side_effect=fail):
            self.assert_refused(f.manager.apply)
        self.assertEqual(f.manager.status()["state"], "prepared-installed")
        self.assertEqual(f.fresh().rollback()["state"], "rolled-back")

    def test_last_moment_hash_inode_type_and_mode_comparison(self):
        for mutation in ("bytes", "inode", "symlink", "mode"):
            with self.subTest(mutation=mutation):
                f = self.fixture()
                def change(point):
                    if point != "before_replace":
                        return
                    if mutation == "bytes":
                        put(f.adb, fake_elf(payload=b"later update"), f.mode)
                    elif mutation == "inode":
                        replacement = f.base / "replacement"
                        put(replacement, f.original, f.mode)
                        os.replace(replacement, f.adb)
                    elif mutation == "symlink":
                        f.adb.unlink()
                        f.adb.symlink_to(f.manager.backup)
                    else:
                        f.adb.chmod(0o755)
                self.assert_refused(f.fresh(checkpoint=change).apply)
                self.assertNotEqual(f.adb.read_bytes(), f.shim)
                self.assertEqual(f.manager.backup.read_bytes(), f.original)

    def test_backup_corruption_at_commit_is_refused_before_replace(self):
        for target in ("backup", "sibling"):
            f = self.fixture()
            def corrupt(point):
                if point == "before_replace":
                    getattr(f.manager, target).write_bytes(b"corrupt")
            self.assert_refused(f.fresh(checkpoint=corrupt).apply)
            self.assertEqual(f.adb.read_bytes(), f.original)

    def test_corrupt_staged_shim_is_not_installed(self):
        f = self.fixture()
        real_stage = patch.stage_file
        def corrupt(directory, data, mode):
            stage = real_stage(directory, data, mode)
            if data == f.shim:
                stage.write_bytes(b"corrupt stage")
            return stage
        with mock.patch.object(patch, "stage_file", side_effect=corrupt):
            self.assert_refused(f.manager.apply)
        self.assertEqual(f.adb.read_bytes(), f.original)
        self.assertEqual(f.manager.apply()["state"], "applied")

    def test_fsync_failure_during_temporary_write_is_retryable(self):
        f = self.fixture()
        real_fsync = os.fsync
        def fail(fd):
            info = os.fstat(fd)
            if stat.S_ISREG(info.st_mode) and info.st_size > 0:
                raise OSError(errno.ENOSPC, "file fsync")
            return real_fsync(fd)
        with mock.patch.object(patch.os, "fsync", side_effect=fail):
            self.assert_refused(f.manager.apply)
        self.assertEqual(f.adb.read_bytes(), f.original)
        self.assertEqual(f.manager.apply()["state"], "applied")

    def test_interrupted_rollback_must_finish_before_reapply(self):
        f = self.fixture()
        f.manager.apply()
        def fail(point):
            if point == "after_receipt_rollback_prepared":
                raise OSError("interrupted")
        self.assert_refused(f.fresh(checkpoint=fail).rollback)
        self.assert_refused(f.fresh().apply)
        self.assertEqual(f.manager.rollback()["state"], "rolled-back")
        self.assertEqual(f.manager.apply()["state"], "applied")

    def test_rollback_refuses_later_update_or_mode_drift(self):
        for change_mode in (False, True):
            f = self.fixture()
            f.manager.apply()
            later = fake_elf(payload=b"later update")
            if change_mode:
                f.adb.chmod(0o755)
            else:
                put(f.adb, later, f.mode)
            before = f.adb.read_bytes()
            self.assert_refused(f.manager.rollback)
            self.assertEqual(f.adb.read_bytes(), before)
            self.assertTrue(f.manager.sibling.exists())

    def test_rollback_last_moment_compare(self):
        f = self.fixture()
        f.manager.apply()
        later = fake_elf(payload=b"updated during rollback")
        def change(point):
            if point == "before_rollback_replace":
                put(f.adb, later, f.mode)
        self.assert_refused(f.fresh(checkpoint=change).rollback)
        self.assertEqual(f.adb.read_bytes(), later)
        self.assertEqual(f.manager.backup.read_bytes(), f.original)

    def test_corrupt_backup_or_symlink_refuses_without_clobber(self):
        for backup in ("backup", "sibling"):
            for corrupt in ("bytes", "symlink"):
                f = self.fixture()
                f.manager.apply()
                path = getattr(f.manager, backup)
                if corrupt == "bytes":
                    path.write_bytes(b"corrupt")
                else:
                    path.unlink()
                    path.symlink_to(f.adb)
                self.assert_refused(f.manager.rollback)
                self.assert_refused(f.manager.apply)
                self.assertEqual(f.adb.read_bytes(), f.shim)

    def test_stale_receipts_refused(self):
        for field, value in (("phase", "rolled-back"), ("patchId", "foreign"),
                             ("appFiles", "/other"), ("abi", []),
                             ("originalSha256", "0" * 64), ("originalMode", 0o777)):
            f = self.fixture()
            f.manager.apply()
            receipt = json.loads(f.manager.receipt_path.read_bytes())
            receipt[field] = value
            put(f.manager.receipt_path, json.dumps(receipt).encode())
            self.assert_refused(f.manager.apply)
            self.assert_refused(f.manager.rollback)
            self.assertEqual(f.adb.read_bytes(), f.shim)
        f = self.fixture()
        f.manager.apply()
        put(f.adb, f.original, f.mode)
        self.assert_refused(f.manager.rollback)

    def test_private_paths_permissions_and_lock_symlinks(self):
        for target in ("receipt_path", "backup", "lock", "state-mode"):
            f = self.fixture()
            f.manager.apply()
            if target == "state-mode":
                f.manager.state.chmod(0o755)
            else:
                path = f.manager.state / "installer.lock" if target == "lock" else getattr(f.manager, target)
                path.unlink()
                path.symlink_to(f.adb)
            self.assert_refused(f.manager.apply)
            self.assertEqual(f.adb.read_bytes(), f.shim)

    def test_lock_contention_and_status_during_lock(self):
        f = self.fixture()
        with f.manager._lock():
            self.assertEqual(f.fresh().status()["state"], "unmanaged")
            self.assert_refused(f.fresh().apply)
        self.assertEqual(f.manager.apply()["state"], "applied")

    def test_rollback_manifest_independent_preserves_unrelated_files(self):
        f = self.fixture()
        f.manager.apply()
        unrelated = f.manager.state / "user-notes.txt"
        put(unrelated, b"keep", 0o600)
        f.shim_path.unlink()
        f.manifest_path.unlink()
        self.assertEqual(f.manager.status()["state"], "applied")
        self.assertEqual(f.manager.rollback()["state"], "rolled-back")
        self.assertEqual(unrelated.read_bytes(), b"keep")

    def test_cli_json_success_failure_and_unknown_flags(self):
        f = self.fixture()
        flags = ["--app-files", str(f.root), "--manifest", str(f.manifest_path)]
        for command in ("status", "preflight", "apply", "rollback"):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(patch.main([command] + flags), 0)
            self.assertTrue(json.loads(output.getvalue())["ok"])
        put(f.adb, fake_elf(payload=b"custom"), f.mode)
        error = io.StringIO()
        with contextlib.redirect_stderr(error):
            self.assertEqual(patch.main(["apply"] + flags), 1)
        self.assertFalse(json.loads(error.getvalue())["ok"])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            patch.main(["apply", "--force"] + flags)


if __name__ == "__main__":
    unittest.main()
