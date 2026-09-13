#!/usr/bin/env python3
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("adb_snapshot", Path(__file__).parents[1] / "adb-bridge-snapshot.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.package = self.root / "package"
        (self.package / "lib").mkdir(parents=True)
        (self.package / "package.json").write_text(json.dumps({"name": MODULE.PACKAGE, "version": "0.2.5"}))
        (self.package / "lib/index.js").write_text("export const version = 'new';")
        (self.package / "lib/client.js").write_text("typed-client")
        (self.package / "lib/new-policy.js").write_text("new-policy")
        self.source = self.root / "factory.tar.xz"
        self.output = self.root / "patched.tar.xz"
        self.make_source()

    def make_source(self, profiles=MODULE.PROFILES):
        with tarfile.open(self.source, "w:xz") as tar:
            for profile in profiles:
                for relative in ("package.json", "lib/index.js", "lib/stale.js"):
                    info = tarfile.TarInfo(MODULE.prefix(profile) + relative)
                    content = b"old-package"
                    info.size = len(content)
                    tar.addfile(info, io.BytesIO(content))
            for name, content in {
                "home/.dsh/profiles/web/cordis.patch.yml": b"web-profile-config",
                "home/.dsh/profiles/headless/cordis.patch.yml": b"different-headless-config",
                "home/.dsh/profiles/headless-bad/node_modules/@dsh-android/dsh-android-bridge/lib/index.js": b"negative-control",
                "usr/bin/other-executable": b"not-touched",
            }.items():
                info = tarfile.TarInfo(name)
                info.mode = 0o755
                info.uid, info.gid, info.mtime = 123, 456, 789
                info.size = len(content)
                tar.addfile(info, io.BytesIO(content))
            info = tarfile.TarInfo("usr/bin/link")
            info.type, info.linkname = tarfile.SYMTYPE, "other-executable"
            tar.addfile(info)

    def other_members(self, path):
        values = {}
        with tarfile.open(path, "r:xz") as tar:
            for member in tar:
                if any(MODULE.package_member(member.name, p) for p in MODULE.PROFILES):
                    continue
                values[member.name] = (member.mode, member.uid, member.gid, member.mtime, member.type,
                                       member.linkname, tar.extractfile(member).read() if member.isfile() else None)
        return values

    def test_only_built_package_changes_in_both_profiles(self):
        old_hash = MODULE.sha256(self.source)
        result = MODULE.inject(self.source, self.output, self.package, old_hash)
        self.assertEqual(result, MODULE.sha256(self.output))
        self.assertEqual(old_hash, MODULE.sha256(self.source))
        MODULE.verify(self.output, MODULE.package_files(self.package))
        self.assertEqual(self.other_members(self.source), self.other_members(self.output))

    def test_bad_input_hash_does_not_replace_destination(self):
        self.output.write_bytes(b"existing-output")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            MODULE.inject(self.source, self.output, self.package, "0" * 64)
        self.assertEqual(b"existing-output", self.output.read_bytes())

    def test_missing_profile_rejects_incomplete_injection(self):
        self.make_source(("web",))
        with self.assertRaisesRegex(ValueError, "absent"):
            MODULE.inject(self.source, self.output, self.package, MODULE.sha256(self.source))
        self.assertFalse(self.output.exists())

    def test_requires_compiled_entrypoints(self):
        (self.package / "lib/client.js").unlink()
        with self.assertRaisesRegex(ValueError, "Build"):
            MODULE.inject(self.source, self.output, self.package, MODULE.sha256(self.source))

    def test_rejects_unexpected_package_or_same_path(self):
        with self.assertRaisesRegex(ValueError, "differ"):
            MODULE.inject(self.source, self.source, self.package, MODULE.sha256(self.source))
        (self.package / "package.json").write_text('{"name":"unrelated"}')
        with self.assertRaisesRegex(ValueError, "identity"):
            MODULE.inject(self.source, self.output, self.package, MODULE.sha256(self.source))

    def test_rejects_package_symlinks(self):
        (self.package / "lib/linked.js").symlink_to("index.js")
        with self.assertRaisesRegex(ValueError, "symlink"):
            MODULE.inject(self.source, self.output, self.package, MODULE.sha256(self.source))


if __name__ == "__main__":
    unittest.main()
