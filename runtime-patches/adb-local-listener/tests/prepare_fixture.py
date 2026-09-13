#!/usr/bin/env python3
"""Create a self-contained, flattened test runtime from a pinned factory snapshot.
No live application runtime, configuration or key is read. This output is CI-only.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import struct
import tarfile
import zipfile

ORIGINALS = {
    'arm64-v8a': ('28c4569e95d5c0f69a64e004ae0e684d64551bf1f86236cf2ffd5235f6eeca16', 183),
    'x86_64': ('8f3a3cd6970112ade0d8886528326ad6b6285ed316c5663a59d40d1df6974c9d', 62),
}
PREFIXES = ('/data/data/com.termux/files/usr/',
            '/data/data/com.dsharnessmobile.shell/files/usr/',
            '/data/user/0/com.dsharnessmobile.shell/files/usr/')


def selected(name):
    return (name == 'usr/bin/adb' or
            (name.startswith('usr/lib/') and '/' not in name[len('usr/lib/'):]) or
            name.startswith('usr/etc/tls/'))


def elf(data, machine):
    assert len(data) >= 24 and data[:4] == b'\x7fELF' and data[4:6] == b'\x02\x01'
    assert struct.unpack('<H', data[18:20])[0] == machine


def prepare(snapshot, expected_sha, abi, package, fake, output):
    with Path(snapshot).open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected_sha
    data, links = {}, {}
    total = 0
    with tarfile.open(snapshot, 'r:xz') as tar:
        for member in tar:
            name = member.name.removeprefix('./').rstrip('/')
            if not selected(name) or member.isdir():
                continue
            path = PurePosixPath(name)
            assert not path.is_absolute() and '..' not in path.parts
            if member.isfile():
                total += member.size
                assert 0 <= member.size and total <= 512 * 1024 * 1024
                data[name] = tar.extractfile(member).read()
                assert len(data[name]) == member.size
            elif member.issym():
                target = member.linkname
                if target.startswith('/'):
                    prefix = next((p for p in PREFIXES if target.startswith(p)), None)
                    assert prefix is not None, 'Unknown absolute factory link'
                    target = 'usr/' + target[len(prefix):]
                else:
                    target = posixpath.join(posixpath.dirname(name), target)
                target = posixpath.normpath(target)
                assert target.startswith('usr/')
                if selected(target):
                    links[name] = target
                # Omitted terminfo/perl trees are unrelated to the minimal runtime.
            else:
                raise ValueError('Unsupported selected archive member')
    def resolve(name, visited=None):
        visited = set() if visited is None else visited
        assert name not in visited, 'Factory link cycle'
        if name in data:
            return data[name]
        assert name in links, 'Missing selected link target'
        return resolve(links[name], visited | {name})
    for name in links:
        data[name] = resolve(name)
    expected, machine = ORIGINALS[abi]
    assert hashlib.sha256(data['usr/bin/adb']).hexdigest() == expected
    elf(data['usr/bin/adb'], machine)
    manifest = json.loads((Path(package) / 'manifest.json').read_text())
    item = manifest['artifacts'][abi]
    shim = (Path(package) / item['file']).read_bytes()
    assert hashlib.sha256(shim).hexdigest() == item['sha256']
    assert item['originalSha256'] == expected
    elf(shim, machine)
    probe = Path(fake).read_bytes()
    elf(probe, machine)
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for name, content in sorted(data.items()):
            archive.writestr('runtime/' + name, content)
        archive.writestr('payload/shim', shim)
        archive.writestr('payload/fake-original', probe)
        archive.writestr('payload/manifest.json', json.dumps({'abi': abi, 'originalSha256': expected, 'shimSha256': item['sha256']}))
    print(f'Prepared isolated {abi} fixture: {len(data)} regular runtime entries; no symlinks')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', required=True)
    parser.add_argument('--snapshot-sha256', required=True)
    parser.add_argument('--abi', choices=ORIGINALS, required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--fake-original', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    prepare(args.snapshot, args.snapshot_sha256, args.abi, args.package, args.fake_original, args.output)
