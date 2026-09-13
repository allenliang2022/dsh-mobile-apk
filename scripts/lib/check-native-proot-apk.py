#!/usr/bin/env python3
"""Bounded-memory APK verifier (stdlib only). Never extracts APK/tar into a runtime.
ZIP members are read to EOF (CRC/local-header integrity); XZ is read to EOF too.
Only the small manifest, fingerprint and ELF headers are retained in memory.
"""
import argparse
import collections
import hashlib
import json
import lzma
import pathlib
import posixpath
import re
import struct
import sys
import tarfile
import zipfile

CHUNK = 1024 * 1024
ANDROID_NS = "http://schemas.android.com/apk/res/android"
EXTRACT_NATIVE_LIBS = 0x010104EA
MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def elf(header, abi, name=""):
    loader32 = name == "libproot-loader32.so"
    require(not loader32 or abi == "arm64-v8a", "loader32 is supported only for ARM")
    cls, machine = (1, 40) if loader32 else (2, MACHINES[abi])
    size = 52 if cls == 1 else 64
    require(len(header) >= size, "truncated ELF header: " + name)
    require(header[:4] == b"\x7fELF", "invalid ELF magic: " + name)
    require(header[4] == cls, "wrong ELF class: " + name)
    require(header[5] == 1, "wrong ELF endianness: " + name)
    require(header[6] == 1 and struct.unpack_from("<I", header, 20)[0] == 1, "invalid ELF version")
    require(struct.unpack_from("<H", header, 18)[0] == machine, "wrong ELF e_machine: " + name)
    require(struct.unpack_from("<H", header, 40 if cls == 1 else 52)[0] == size, "invalid ELF header size")
    require(struct.unpack_from("<H", header, 16)[0] in (2, 3), "ELF is not executable/shared object")


def string_pool(chunk, header_size):
    require(header_size >= 28, "AXML short string-pool header")
    count, styles, flags, start, styles_start = struct.unpack_from("<IIIII", chunk, 8)
    require(header_size + 4 * (count + styles) <= start <= len(chunk), "AXML invalid string offsets")
    limit = styles_start or len(chunk)
    require(start <= limit <= len(chunk), "AXML invalid style offsets")
    strings = []

    def length(pos, utf8):
        unit, high = (1, 0x80) if utf8 else (2, 0x8000)
        require(pos + unit <= limit, "AXML truncated string length")
        value = chunk[pos] if utf8 else struct.unpack_from("<H", chunk, pos)[0]
        pos += unit
        if value & high:
            require(pos + unit <= limit, "AXML truncated long string length")
            tail = chunk[pos] if utf8 else struct.unpack_from("<H", chunk, pos)[0]
            value = ((value & ~high) << (8 if utf8 else 16)) | tail
            pos += unit
        return value, pos

    for i in range(count):
        pos = start + struct.unpack_from("<I", chunk, header_size + 4 * i)[0]
        require(start <= pos < limit, "AXML string offset outside pool")
        units, pos = length(pos, bool(flags & 0x100))
        if flags & 0x100:
            size, pos = length(pos, True)
            require(pos + size < limit and chunk[pos + size] == 0, "AXML unterminated UTF-8")
            value = chunk[pos:pos + size].decode("utf-8")
            require(len(value.encode("utf-16-le")) // 2 == units, "AXML wrong UTF-8 character count")
        else:
            size = units * 2
            require(pos + size + 2 <= limit and chunk[pos + size:pos + size + 2] == b"\0\0", "AXML unterminated UTF-16")
            value = chunk[pos:pos + size].decode("utf-16-le")
        strings.append(value)
    return strings


def manifest_extracts_native(data):
    # ResXMLTree/ResStringPool/ResXMLTree_attrExt per Android ResourceTypes.h.
    require(len(data) >= 8, "AXML missing header")
    kind, header_size, total = struct.unpack_from("<HHI", data)
    require(kind == 3 and header_size == 8 and total == len(data), "manifest is not a complete binary AXML document")
    strings, resources, stack = None, None, []
    applications = roots = 0
    enabled = False

    def string(index):
        if index == 0xFFFFFFFF:
            return None
        require(strings is not None and index < len(strings), "AXML invalid string index")
        return strings[index]

    pos = header_size
    while pos < total:
        require(pos + 8 <= total, "AXML truncated chunk header")
        kind, hs, size = struct.unpack_from("<HHI", data, pos)
        require(8 <= hs <= size and pos + size <= total and size % 4 == 0 and pos % 4 == 0, "AXML invalid chunk extent/alignment")
        chunk = data[pos:pos + size]
        if kind == 1:
            require(strings is None, "AXML duplicate string pool")
            strings = string_pool(chunk, hs)
        elif kind == 0x180:
            require(resources is None and hs == 8 and (size - hs) % 4 == 0, "AXML invalid resource map")
            resources = struct.unpack_from("<" + "I" * ((size - hs) // 4), chunk, hs)
        elif kind == 0x102:
            require(hs == 16 and size >= 36, "AXML invalid start element")
            ns, name, attr_start, attr_size, count = struct.unpack_from("<IIHHH", chunk, hs)
            tag = (string(ns), string(name))
            require(attr_size >= 20 and attr_start >= 20 and hs + attr_start + attr_size * count <= size, "AXML invalid attribute extent")
            if not stack:
                roots += 1
                require(tag == (None, "manifest") and roots == 1, "AXML invalid manifest root")
            application = stack == [(None, "manifest")] and tag == (None, "application")
            if application:
                applications += 1
            attributes = set()
            attribute_ids = set()
            for i in range(count):
                off = hs + attr_start + attr_size * i
                ans, aname, raw, value_size, res0, dtype, value = struct.unpack_from("<IIIHBBI", chunk, off)
                attribute = (string(ans), string(aname))
                require(attribute not in attributes, "AXML duplicate attribute")
                attributes.add(attribute)
                resource_id = resources[aname] if resources is not None and aname < len(resources) else 0
                if resource_id:
                    require(resource_id not in attribute_ids, "AXML duplicate attribute resource ID")
                    attribute_ids.add(resource_id)
                if application and resource_id == EXTRACT_NATIVE_LIBS:
                    require(attribute == (ANDROID_NS, "extractNativeLibs"), "AXML spoofed extractNativeLibs resource ID/name")
                string(raw)  # raw string indexes must also be structurally valid
                require(value_size == 8 and res0 == 0, "AXML invalid typed value")
                if application and attribute == (ANDROID_NS, "extractNativeLibs"):
                    require(resources is not None and aname < len(resources) and resources[aname] == EXTRACT_NATIVE_LIBS, "AXML extractNativeLibs resource ID mismatch")
                    require(dtype == 0x12 and value in (1, 0xFFFFFFFF), "AXML extractNativeLibs must be typed boolean true")
                    enabled = True
            stack.append(tag)
        elif kind == 0x103:
            require(hs == 16 and size == 24 and stack, "AXML invalid end element")
            ns, name = struct.unpack_from("<II", chunk, hs)
            require(stack.pop() == (string(ns), string(name)), "AXML mismatched element")
        elif kind in (0x100, 0x101):
            require(hs == 16 and size == 24, "AXML invalid namespace node")
            prefix, uri = struct.unpack_from("<II", chunk, hs)
            string(prefix)
            string(uri)
        elif kind == 0x104:
            require(hs == 16 and size == 28, "AXML invalid CDATA node")
        else:
            raise ValueError("AXML unexpected chunk: " + hex(kind))
        pos += size
    require(not stack and roots == 1 and applications == 1 and enabled, "AXML application extractNativeLibs=true missing")


class HashReader:
    def __init__(self, stream):
        self.stream = stream
        self.hash = hashlib.sha256()

    def read(self, size):
        require(0 <= size <= CHUNK, "unbounded snapshot read requested")
        data = self.stream.read(size)
        self.hash.update(data)
        return data


class CheckedTarInfo(tarfile.TarInfo):
    @classmethod
    def fromtarfile(cls, archive):
        try:
            return super().fromtarfile(archive)
        except (tarfile.InvalidHeaderError, tarfile.TruncatedHeaderError) as error:
            # tarfile ignore_zeros otherwise silently ignores corrupt nonzero headers.
            raise ValueError("invalid snapshot tar header: " + str(error)) from error


def snapshot(stream, abi):
    hashed = HashReader(stream)
    nodes = 0
    # LZMAFile and tar stream mode retain buffers, not the complete ZIP or snapshot.
    # Reading beyond tar EOF is important: a valid Node header in a truncated XZ is not success.
    with lzma.LZMAFile(hashed, "rb") as decoded:
        with tarfile.open(fileobj=decoded, mode="r|", ignore_zeros=True, tarinfo=CheckedTarInfo) as archive:
            for member in archive:
                archive.members.clear()  # tarfile otherwise caches every TarInfo even in stream mode
                require(not member.name.startswith("/") and ".." not in member.name.split("/"), "unsafe snapshot member path")
                name = posixpath.normpath(member.name)
                if name in ("usr", "usr/bin"):
                    require(member.isdir(), "snapshot Node ancestor is not a directory")
                if name == "usr/bin/node":
                    nodes += 1
                    require(nodes == 1 and member.isfile(), "snapshot usr/bin/node must be one regular file (not duplicate/link)")
                    require(member.mode & 0o111, "snapshot usr/bin/node is not executable")
                    with archive.extractfile(member) as node:
                        elf(node.read(64), abi, "snapshot usr/bin/node")
        while decoded.read(CHUNK):
            pass
    while hashed.read(CHUNK):
        pass
    require(nodes == 1, "snapshot usr/bin/node missing")
    return hashed.hash.hexdigest()


def scan(stream, capture_limit=0):
    digest = hashlib.sha256()
    captured = bytearray()
    header = bytearray()
    total = 0
    while True:
        data = stream.read(CHUNK)
        if not data:
            break
        total += len(data)
        digest.update(data)
        if len(header) < 64:
            header.extend(data[:64 - len(header)])
        if capture_limit:
            require(total <= capture_limit, "oversized manifest/fingerprint")
            captured.extend(data)
    return digest.hexdigest(), bytes(header), bytes(captured), total


def verify(root, apk, abi):
    metadata = json.loads((root / "scripts/native-proot.json").read_text())
    record = metadata["artifacts"].get(abi)
    if record is None:
        require(isinstance(metadata.get("unsupportedAbis", {}).get(abi), str) and metadata["unsupportedAbis"][abi].strip(), "undeclared unsupported ABI")
        native_source = root / "app/src/main/jniLibs" / abi
        require(not native_source.exists() or (native_source.is_dir() and not any(native_source.iterdir())), "unsupported ABI has native source payload")
        expected = {}
    else:
        require(record.get("validation", {}).get("publishable") is True and record.get("validation", {}).get("sourceVerified") is True, "provenance validation does not permit publishing")
        expected = {f"lib/{abi}/{name}": digest for name, digest in record["files"].items()}
        require(f"lib/{abi}/libproot.so" in expected and f"lib/{abi}/libproot-loader.so" in expected, "metadata missing required native files")
    source_assets = {}
    if record is not None:
        require(re.fullmatch(r"vendor/native-proot/assets/native-proot-source/[\w.-]+\.(?:tar\.gz|tgz)", record.get("sourceArchive", "")), "invalid sourceArchive path")
        source_archive = root / record["sourceArchive"]
        with source_archive.open("rb") as file:
            archive_hash, _, _, _ = scan(file)
        require(archive_hash == record.get("sourceArchiveSha256"), "source archive SHA-256 mismatch")
        directory = source_archive.parent
        required = {source_archive.name, "COPYING.proot", "GPL-3.0.txt", "LGPL-3.0.txt", "rebuild-arm64.sh", "string-header.patch", "talloc-answers.txt"}
        require(required <= {p.name for p in directory.iterdir()}, "required license/rebuild asset missing")
        for file in directory.iterdir():
            require(file.is_file() and not file.is_symlink() and file.stat().st_size > 0, "invalid source license/rebuild asset")
            with file.open("rb") as stream:
                source_assets["assets/native-proot-source/" + file.name] = scan(stream)[0]
    manifest = fingerprint = snapshot_hash = None
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        counts = collections.Counter(entry.filename for entry in entries)
        require(all(count == 1 for count in counts.values()), "duplicate ZIP entries: " + ", ".join(name for name, count in counts.items() if count != 1))
        missing_source_assets = sorted(source_assets.keys() - counts.keys())
        if missing_source_assets:
            actual_source_members = sorted(name for name in counts if name.startswith("assets/native-proot-source/"))
            raise ValueError("APK corresponding source/license/rebuild assets missing: " + str(missing_source_assets)
                             + "; actual assets/native-proot-source members: " + str(actual_source_members))
        native = {name for name in counts if name.startswith("lib/") and not name.endswith("/")}
        require(native == set(expected), "APK native entry set mismatch (missing/unpinned/wrong ABI): " + str(sorted(native ^ set(expected))))
        for entry in entries:
            name = entry.filename
            require("\\" not in name and "\0" not in name and not name.startswith("/") and all(part not in (".", "..") for part in name.rstrip("/").split("/")), "noncanonical ZIP path")
            require(not entry.flag_bits & 1, "encrypted ZIP member")
            mode = entry.external_attr >> 16
            require(mode & 0o170000 not in (0o120000, 0o060000, 0o020000), "ZIP links/devices are forbidden")
            with archive.open(entry) as stream:
                if name == "assets/snapshot.tar.xz":
                    snapshot_hash = snapshot(stream, abi)
                    continue
                limit = 4 * CHUNK if name == "AndroidManifest.xml" else 256 if name == "assets/snapshot.sha256" else 0
                digest, header, captured, total = scan(stream, limit)
                require(total == entry.file_size, "ZIP member size mismatch: " + name)
                if name in source_assets:
                    require(digest == source_assets[name], "APK source/license/rebuild asset SHA-256 mismatch: " + name)
                if name in expected:
                    elf(header, abi, pathlib.PurePosixPath(name).name)
                    require(digest == expected[name], "APK native SHA-256 mismatch: " + name)
                    source = root / "app/src/main/jniLibs" / abi / pathlib.PurePosixPath(name).name
                    with source.open("rb") as file:
                        source_hash, _, _, _ = scan(file)
                    require(digest == source_hash, "APK native bytes differ from source: " + name)
                elif name == "AndroidManifest.xml":
                    manifest = captured
                elif name == "assets/snapshot.sha256":
                    fingerprint = captured.decode("ascii").strip()
        require(manifest is not None, "APK binary manifest missing")
        manifest_extracts_native(manifest)
        require(fingerprint is not None and re.fullmatch(r"[0-9a-fA-F]{64}", fingerprint), "snapshot.sha256 missing/invalid")
        require(snapshot_hash is not None and snapshot_hash == fingerprint.lower(), "snapshot SHA-256 mismatch/missing")
    print(f"PASS  APK {abi}: all ZIP CRCs, exact native entry set/ELF/source hashes, binary manifest, snapshot SHA-256/Node ELF")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, required=True)
    parser.add_argument("--abi", choices=MACHINES, required=True)
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        verify(args.root, args.apk, args.abi)
    except (ValueError, OSError, EOFError, KeyError, struct.error, zipfile.BadZipFile, tarfile.TarError, lzma.LZMAError) as error:
        print("APK-NATIVE-PROOT FAILED: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
