#!/usr/bin/env python3
"""Replace only the built ADB bridge package in a pinned factory snapshot.

No profile configuration, other plugin, key, or shell executable is rewritten.
This is a development-build input; it is not a live device installation tool.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile

PROFILES = ("web", "headless")
PACKAGE = "@dsh-android/dsh-android-bridge"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def package_files(package):
    package = Path(package)
    manifest = json.loads((package / "package.json").read_text())
    if manifest.get("name") != PACKAGE:
        raise ValueError("Unexpected package identity")
    files = {"package.json": (package / "package.json").read_bytes()}
    for file in sorted((package / "lib").rglob("*")):
        if file.is_symlink():
            raise ValueError("Built package cannot contain symlinks")
        if file.is_file() and file.suffix != ".map":
            files[file.relative_to(package).as_posix()] = file.read_bytes()
    if not {"lib/index.js", "lib/client.js"}.issubset(files):
        raise ValueError("Build the bridge before snapshot injection")
    return files


def prefix(profile):
    return f"home/.dsh/profiles/{profile}/node_modules/{PACKAGE}/"


def member_path(name):
    return name[2:] if name.startswith("./") else name


def package_member(name, profile):
    root = prefix(profile)
    return name == root + "package.json" or name.startswith(root + "lib/")


def verify(output, files):
    found = {profile: {} for profile in PROFILES}
    with tarfile.open(output, "r:xz") as archive:
        for item in archive:
            name = member_path(item.name)
            for profile in PROFILES:
                if package_member(name, profile) and not item.isdir():
                    relative = name[len(prefix(profile)):]
                    if not item.isfile() or relative in found[profile]:
                        raise ValueError("Unexpected link or duplicate package member")
                    found[profile][relative] = archive.extractfile(item).read()
    for profile in PROFILES:
        if found[profile] != files:
            raise ValueError("Snapshot package differs from built source: " + profile)


def inject(source, output, package, expected_sha):
    source, output = Path(source), Path(output)
    if source.resolve() == output.resolve():
        raise ValueError("Source and destination must differ")
    if sha256(source) != expected_sha:
        raise ValueError("Factory snapshot SHA-256 mismatch")
    files = package_files(package)
    seen = set()
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".adb-snapshot-", dir=output.parent)
    os.close(descriptor)
    try:
        with tarfile.open(source, "r:xz") as old, tarfile.open(temporary, "w:xz", preset=1) as new:
            for item in old:
                name = member_path(item.name)
                owner = next((p for p in PROFILES if package_member(name, p)), None)
                if owner is not None and not item.isdir():
                    if name == prefix(owner) + "package.json":
                        seen.add(owner)
                    # Prune stale package modules as well as replacing current members.
                    continue
                new.addfile(item, old.extractfile(item) if item.isfile() else None)
            if seen != set(PROFILES):
                raise ValueError("Expected bridge package absent from a factory profile")
            for profile in PROFILES:
                for relative, content in sorted(files.items()):
                    item = tarfile.TarInfo(prefix(profile) + relative)
                    item.size = len(content)
                    item.mode = 0o644
                    item.mtime = 0
                    new.addfile(item, io.BytesIO(content))
        verify(temporary, files)
        os.replace(temporary, output)
        return sha256(output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("output")
    parser.add_argument("package")
    parser.add_argument("--expected-sha256", required=True)
    args = parser.parse_args()
    print(inject(args.source, args.output, args.package, args.expected_sha256))
