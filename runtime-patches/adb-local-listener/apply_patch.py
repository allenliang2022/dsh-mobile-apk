#!/usr/bin/env python3
"""Opt-in ADB shim manager; Python standard library, no subprocesses.

status/preflight never create directories, locks, or files. Mutations use a
cooperative flock and last-moment identity/hash comparison, NOT a security
boundary against malicious same-UID races. No processes are started/stopped,
no APK is installed, and no configuration or keys are accessed.

Manifest: {"schema":1,"patchId":"adb-local-listener-v1","artifacts":{
 "arm64-v8a":{"file":"adb-shim-arm64-v8a","sha256":"<64 lowercase hex>",
 "originalSha256":"<64 lowercase hex>","elfMachine":183}}}
x86_64 uses adb-shim-x86_64 and machine 62. At least one entry is required.
Hashes must come from independently checked pinned factory inputs; a manifest
is not a signature. ELF checks are structural, not a substitute for build-time
program-header/interpreter/dependency checks and device acceptance.
"""

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import tempfile

PATCH_ID = "adb-local-listener-v1"
ORIGINAL_NAME = "adb.dsh-original-v1"
DEFAULT_APP_FILES = "/data/user/0/com.dsharnessmobile.shell/files"
MACHINES = {"arm64-v8a": 183, "x86_64": 62}
PHASES = {"prepared", "applied", "rollback-prepared", "rolled-back"}
HASH_RE = re.compile(r"[0-9a-f]{64}\Z")
MAX_FILE = 64 * 1024 * 1024


class PatchError(Exception):
    """Failed safety check: never force-overwrite."""


def checked_path(value):
    """No general realpath: only Android's fixed, root-owned user-0 alias."""
    path = Path(os.path.abspath(os.fspath(value)))
    alias = Path("/data/user/0")
    if path == alias or alias in path.parents:
        try:
            info = alias.lstat()
        except FileNotFoundError:
            pass
        else:
            if stat.S_ISLNK(info.st_mode):
                if info.st_uid != 0 or os.readlink(alias) != "/data/data":
                    raise PatchError("unsafe Android user-0 alias")
                path = Path("/data/data") / path.relative_to(alias)
    return path


def check_dirs(path, allow_missing=False):
    """Reject symlinks, foreign owners and untrusted writable ancestors.

    Root-owned sticky ancestors (/tmp in synthetic tests) are allowed.
    Android system-owned ancestors and same-UID group-writable dirs are normal.
    """
    path = checked_path(path)
    for item in (*reversed(path.parents), path):
        try:
            info = item.lstat()
        except FileNotFoundError:
            if allow_missing:
                return
            raise PatchError(f"missing directory: {item}")
        if not stat.S_ISDIR(info.st_mode):
            raise PatchError(f"not a real directory (symlinks forbidden): {item}")
        if info.st_uid not in {0, os.geteuid(), 1000}:
            raise PatchError(f"foreign directory owner: {item}")
        sticky_root = info.st_uid == 0 and info.st_mode & stat.S_ISVTX
        if info.st_mode & stat.S_IWOTH and not sticky_root:
            raise PatchError(f"world-writable directory: {item}")
        if (info.st_mode & stat.S_IWGRP and not sticky_root
                and info.st_gid not in {0, 1000, os.getegid()}):
            raise PatchError(f"untrusted group-writable directory: {item}")


def identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid,
            info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def read_regular(path, private=False):
    path = checked_path(path)
    check_dirs(path.parent)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
            raise PatchError(f"not a single-link regular file: {path}")
        if before.st_uid != os.geteuid():
            raise PatchError(f"foreign file owner: {path}")
        if before.st_mode & (stat.S_ISUID | stat.S_ISGID | stat.S_IWGRP | stat.S_IWOTH):
            raise PatchError(f"unsafe file permissions: {path}")
        if private and stat.S_IMODE(before.st_mode) != 0o600:
            raise PatchError(f"private file must have mode 0600: {path}")
        if before.st_size > MAX_FILE:
            raise PatchError(f"file too large: {path}")
        with os.fdopen(os.dup(fd), "rb") as stream:
            data = stream.read(MAX_FILE + 1)
        if len(data) > MAX_FILE or identity(before) != identity(os.fstat(fd)):
            raise PatchError(f"file changed while reading: {path}")
        if identity(before) != identity(path.lstat()):
            raise PatchError(f"path changed while reading: {path}")
        return data, before
    finally:
        os.close(fd)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def elf_machine(data):
    if (len(data) < 64 or data[:4] != b"\x7fELF" or data[4:7] != b"\x02\x01\x01"
            or struct.unpack_from("<H", data, 16)[0] not in {2, 3}
            or struct.unpack_from("<I", data, 20)[0] != 1
            or struct.unpack_from("<H", data, 52)[0] != 64):
        raise PatchError("expected a complete little-endian ELF64 executable header")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine not in MACHINES.values():
        raise PatchError(f"unsupported ELF machine: {machine}")
    return machine


def valid_hash(value):
    return isinstance(value, str) and HASH_RE.fullmatch(value) is not None


def load_json(path, private=False):
    data, info = read_regular(path, private=private)
    try:
        def no_duplicates(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError(f"duplicate key: {key}")
                result[key] = value
            return result
        value = json.loads(data, object_pairs_hook=no_duplicates)
    except (ValueError, UnicodeError) as error:
        raise PatchError(f"invalid JSON: {path}: {error}") from error
    if not isinstance(value, dict):
        raise PatchError(f"expected JSON object: {path}")
    return value, info


def fsync_dir(path):
    check_dirs(path)
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def same_file(path, expected_data, expected_info, private=False):
    data, info = read_regular(path, private=private)
    if identity(info) != identity(expected_info) or sha(data) != sha(expected_data):
        raise PatchError(f"concurrent modification detected: {path}")


def unlink_created(path, info):
    """Only unlink the inode this invocation created."""
    try:
        current = path.lstat()
    except FileNotFoundError:
        return
    if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
        raise PatchError(f"temporary file changed: {path}")
    path.unlink()


def stage_file(directory, data, mode):
    check_dirs(directory)
    fd, name = tempfile.mkstemp(prefix=".adb-dsh-stage-", dir=directory)
    path = Path(name)
    info = os.fstat(fd)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fchmod(stream.fileno(), mode)
            os.fsync(stream.fileno())
        fsync_dir(directory)
        return path
    except BaseException:
        unlink_created(path, info)
        raise


class Installer:
    def __init__(self, app_files=DEFAULT_APP_FILES, manifest=None, abi=None,
                 checkpoint=None):
        self.root = checked_path(app_files)
        self.bin = self.root / "usr/bin"
        self.adb = self.bin / "adb"
        self.sibling = self.bin / ORIGINAL_NAME
        self.state = self.root / "home/.dsh/workspaces/runtime-patches" / PATCH_ID
        self.receipt_path = self.state / "receipt.json"
        self.backup = self.state / "original-adb"
        self.manifest_path = checked_path(manifest or Path(__file__).with_name("manifest.json"))
        self.abi = abi
        self.checkpoint = checkpoint or (lambda point: None)

    def _paths(self):
        check_dirs(self.bin)
        check_dirs(self.root / "home")
        check_dirs(self.state, allow_missing=True)
        if self.state.exists():
            info = self.state.lstat()
            if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise PatchError("private state directory must be owned by this UID and mode 0700")

    def _receipt(self):
        self._paths()
        if not self.state.exists():
            return None
        try:
            value, _ = load_json(self.receipt_path, private=True)
        except FileNotFoundError:
            return None
        required = {"schema", "patchId", "appFiles", "abi", "elfMachine",
                    "originalSha256", "shimSha256", "originalMode", "phase"}
        if (set(value) != required or type(value["schema"]) is not int
                or value["schema"] != 1 or value["patchId"] != PATCH_ID
                or not isinstance(value["abi"], str)
                or value["appFiles"] != str(self.root)
                or value["abi"] not in MACHINES
                or type(value["elfMachine"]) is not int
                or value["elfMachine"] != MACHINES[value["abi"]]
                or not valid_hash(value["originalSha256"])
                or not valid_hash(value["shimSha256"])
                or value["originalSha256"] == value["shimSha256"]
                or type(value["originalMode"]) is not int
                or value["originalMode"] not in {0o500, 0o550, 0o555, 0o700, 0o750, 0o755}
                or not isinstance(value["phase"], str) or value["phase"] not in PHASES):
            raise PatchError("invalid/stale receipt; manual inspection required")
        if self.abi is not None and self.abi != value["abi"]:
            raise PatchError("receipt ABI differs from requested ABI")
        return value

    def _artifact(self, current):
        manifest, _ = load_json(self.manifest_path)
        if (set(manifest) != {"schema", "patchId", "artifacts"}
                or type(manifest["schema"]) is not int or manifest["schema"] != 1
                or manifest["patchId"] != PATCH_ID
                or not isinstance(manifest["artifacts"], dict)
                or not manifest["artifacts"]):
            raise PatchError("invalid manifest schema/patchId/artifacts")
        for abi, entry in manifest["artifacts"].items():
            if (abi not in MACHINES or not isinstance(entry, dict)
                    or set(entry) != {"file", "sha256", "originalSha256", "elfMachine"}
                    or entry["file"] != f"adb-shim-{abi}"
                    or type(entry["elfMachine"]) is not int
                    or entry["elfMachine"] != MACHINES[abi]
                    or not valid_hash(entry["sha256"])
                    or not valid_hash(entry["originalSha256"])
                    or entry["sha256"] == entry["originalSha256"]):
                raise PatchError(f"invalid artifact entry: {abi}")
        machine = elf_machine(current)
        abi = self.abi or next(name for name, number in MACHINES.items() if number == machine)
        if abi not in manifest["artifacts"] or MACHINES.get(abi) != machine:
            raise PatchError("manifest/requested ABI does not match current adb ELF")
        entry = manifest["artifacts"][abi]
        shim, _ = read_regular(self.manifest_path.parent / entry["file"])
        if sha(shim) != entry["sha256"] or elf_machine(shim) != machine:
            raise PatchError("shim hash/ELF/ABI mismatch")
        return abi, entry, shim

    def _verified_backup(self, path, receipt, optional=False):
        try:
            data, info = read_regular(path, private=(path == self.backup))
        except FileNotFoundError:
            if optional:
                return None
            raise PatchError(f"required original backup is missing: {path}")
        expected_mode = 0o600 if path == self.backup else receipt["originalMode"]
        if (sha(data) != receipt["originalSha256"]
                or elf_machine(data) != receipt["elfMachine"]
                or stat.S_IMODE(info.st_mode) != expected_mode):
            raise PatchError(f"original backup hash/ELF/mode mismatch: {path}")
        return data, info

    def _inspect(self, require_artifact=False):
        self._paths()
        current, info = read_regular(self.adb)
        machine = elf_machine(current)
        receipt = self._receipt()
        artifact = self._artifact(current) if require_artifact else None
        if receipt is None:
            for path in (self.sibling, self.backup):
                if os.path.lexists(path):
                    raise PatchError(f"unowned existing backup; refusing adoption: {path}")
            if artifact and sha(current) != artifact[1]["originalSha256"]:
                raise PatchError("current adb is not the pinned factory original; refusing custom/unknown binary")
            if stat.S_IMODE(info.st_mode) not in {0o500, 0o550, 0o555, 0o700, 0o750, 0o755}:
                raise PatchError("unsupported original executable mode")
            state = "ready" if artifact else "unmanaged"
        else:
            if machine != receipt["elfMachine"]:
                raise PatchError("current adb ABI differs from receipt")
            if artifact:
                abi, entry, _ = artifact
                if (abi != receipt["abi"] or entry["sha256"] != receipt["shimSha256"]
                        or entry["originalSha256"] != receipt["originalSha256"]):
                    raise PatchError("receipt differs from manifest; refusing stale/foreign transaction")
            digest = sha(current)
            is_original = digest == receipt["originalSha256"]
            is_shim = digest == receipt["shimSha256"]
            if not is_original and not is_shim:
                raise PatchError("current adb drifted; refusing to overwrite a later update")
            if stat.S_IMODE(info.st_mode) != receipt["originalMode"]:
                raise PatchError("current adb mode drifted")
            phase = receipt["phase"]
            if (phase == "applied" and not is_shim) or (phase == "rolled-back" and not is_original):
                raise PatchError("stale receipt phase does not match current adb")
            required = is_shim or phase in {"applied", "rollback-prepared", "rolled-back"}
            self._verified_backup(self.backup, receipt, optional=not required)
            self._verified_backup(self.sibling, receipt, optional=not is_shim)
            if phase == "prepared":
                state = "prepared-installed" if is_shim else "prepared-original"
            elif phase == "rollback-prepared":
                state = "rollback-prepared-original" if is_original else "rollback-prepared-installed"
            else:
                state = phase
        return {"state": state, "patchId": PATCH_ID, "appFiles": str(self.root),
                "currentSha256": sha(current), "elfMachine": machine}, current, info, receipt, artifact

    def status(self):
        """Read-only, manifest-independent receipt and backup integrity report."""
        return self._inspect()[0]

    def preflight(self):
        """Read-only; validates manifest, artifact, and installed state."""
        return self._inspect(require_artifact=True)[0]

    @contextlib.contextmanager
    def _lock(self):
        self._paths()
        missing = []
        path = self.state
        while not path.exists():
            missing.append(path)
            path = path.parent
        for path in reversed(missing):
            check_dirs(path.parent)
            try:
                path.mkdir(mode=0o700)
                fsync_dir(path.parent)
            except FileExistsError:
                pass
            check_dirs(path)
        self._paths()
        path = self.state / "installer.lock"
        fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, 0o600)
        try:
            info = os.fstat(fd)
            if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
                    or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600):
                raise PatchError("unsafe installer lock")
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise PatchError("another installer operation holds the cooperative lock") from error
            if identity(info) != identity(path.lstat()):
                raise PatchError("installer lock path changed")
            os.fsync(fd)
            fsync_dir(self.state)
            yield
        finally:
            os.close(fd)

    def _write_receipt(self, receipt):
        old = self._receipt()
        old_data = old_info = None
        if old is not None:
            old_data, old_info = read_regular(self.receipt_path, private=True)
        encoded = (json.dumps(receipt, sort_keys=True, indent=2) + "\n").encode()
        stage = stage_file(self.state, encoded, 0o600)
        stage_info = stage.lstat()
        try:
            if old is None:
                if os.path.lexists(self.receipt_path):
                    raise PatchError("receipt appeared concurrently")
            else:
                same_file(self.receipt_path, old_data, old_info, private=True)
            os.replace(stage, self.receipt_path)
            fsync_dir(self.state)
        finally:
            unlink_created(stage, stage_info)

    def _copy_new(self, path, data, mode, receipt):
        if self._verified_backup(path, receipt, optional=True) is not None:
            return
        # Fully durable temporary copy before publishing a backup name. A hard
        # crash while writing leaves at most an unreferenced stage, never a
        # partial original-adb that a later invocation could accidentally use.
        stage = stage_file(path.parent, data, mode)
        info = stage.lstat()
        try:
            if os.path.lexists(path):
                raise PatchError(f"backup appeared concurrently: {path}")
            os.rename(stage, path)
            fsync_dir(path.parent)
            self._verified_backup(path, receipt)
        finally:
            unlink_created(stage, info)

    def _replace_adb(self, data, mode, previous, previous_info, prefix, receipt):
        stage = stage_file(self.bin, data, mode)
        stage_info = stage.lstat()
        try:
            stage_data, stage_info = read_regular(stage)
            if sha(stage_data) != sha(data) or stat.S_IMODE(stage_info.st_mode) != mode:
                raise PatchError("staged executable bytes/mode differ from verified input")
            self.checkpoint(f"after_{prefix}stage")
            self.checkpoint(f"before_{prefix}replace")
            self._verified_backup(self.backup, receipt)
            self._verified_backup(self.sibling, receipt)
            same_file(stage, stage_data, stage_info)
            # Final operation before rename. flock coordinates installers only;
            # this does not defeat hostile same-UID races.
            same_file(self.adb, previous, previous_info)
            os.replace(stage, self.adb)
            fsync_dir(self.bin)
            self.checkpoint(f"after_{prefix}replace")
        finally:
            unlink_created(stage, stage_info)

    def apply(self):
        # Reject unknown binaries/artifacts before creating any private state.
        self.preflight()
        with self._lock():
            result, current, info, receipt, artifact = self._inspect(require_artifact=True)
            if result["state"].startswith("rollback-prepared"):
                raise PatchError("finish the interrupted rollback before applying again")
            if receipt is None:
                abi, entry, _ = artifact
                receipt = {"schema": 1, "patchId": PATCH_ID, "appFiles": str(self.root),
                           "abi": abi, "elfMachine": entry["elfMachine"],
                           "originalSha256": entry["originalSha256"],
                           "shimSha256": entry["sha256"],
                           "originalMode": stat.S_IMODE(info.st_mode), "phase": "prepared"}
                self._write_receipt(receipt)
                self.checkpoint("after_receipt_prepared")
            elif result["state"] == "rolled-back":
                receipt = dict(receipt, phase="prepared")
                self._write_receipt(receipt)
                self.checkpoint("after_receipt_prepared")
            if sha(current) == receipt["originalSha256"]:
                self._copy_new(self.backup, current, 0o600, receipt)
                self.checkpoint("after_private_backup")
                self._copy_new(self.sibling, current, receipt["originalMode"], receipt)
                self.checkpoint("after_sibling_backup")
                self._replace_adb(artifact[2], receipt["originalMode"], current, info, "", receipt)
            if receipt["phase"] != "applied":
                self._write_receipt(dict(receipt, phase="applied"))
                self.checkpoint("after_receipt_applied")
            return self.status()

    def _remove_sibling(self, receipt):
        backup = self._verified_backup(self.sibling, receipt, optional=True)
        if backup is not None:
            same_file(self.sibling, *backup)
            self.sibling.unlink()
            fsync_dir(self.bin)

    def rollback(self):
        # Independent of source manifest/artifact: receipt + backups suffice.
        result, _, _, receipt, _ = self._inspect()
        if receipt is None:
            return dict(result, state="not-installed")
        with self._lock():
            result, current, info, receipt, _ = self._inspect()
            if sha(current) == receipt["shimSha256"]:
                original, _ = self._verified_backup(self.backup, receipt)
                receipt = dict(receipt, phase="rollback-prepared")
                self._write_receipt(receipt)
                self.checkpoint("after_receipt_rollback_prepared")
                self._replace_adb(original, receipt["originalMode"], current, info, "rollback_", receipt)
            elif receipt["phase"] == "prepared":
                # Failure before either copy still leaves untouched source.
                self._copy_new(self.backup, current, 0o600, receipt)
            receipt = dict(receipt, phase="rolled-back")
            self._write_receipt(receipt)
            self.checkpoint("after_receipt_rolled_back")
            self._remove_sibling(receipt)
            # Keep receipt + durable original (0600) and stable lock inode.
            # Never recursively delete directories or discard recovery bytes.
            return self.status()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("status", "preflight", "apply", "rollback"))
    parser.add_argument("--app-files", default=DEFAULT_APP_FILES)
    parser.add_argument("--manifest", type=Path, help="default: manifest.json next to this script")
    parser.add_argument("--abi", choices=tuple(MACHINES), help="default: detect current adb ELF machine")
    args = parser.parse_args(argv)
    try:
        manager = Installer(args.app_files, args.manifest, args.abi)
        result = getattr(manager, args.command)()
    except (PatchError, OSError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, sort_keys=True), file=sys.stderr)
        return 1
    print(json.dumps(dict(result, ok=True), sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
