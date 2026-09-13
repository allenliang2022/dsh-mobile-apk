#!/usr/bin/env python3
"""Assemble a small runtime patch bundle; never edits an installed application."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct

ORIGINALS = {
    'arm64-v8a': ('28c4569e95d5c0f69a64e004ae0e684d64551bf1f86236cf2ffd5235f6eeca16', 183),
    'x86_64': ('8f3a3cd6970112ade0d8886528326ad6b6285ed316c5663a59d40d1df6974c9d', 62),
}
ROOT = Path(__file__).resolve().parent


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def build(native_dir, output):
    native_dir, output = Path(native_dir), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    artifacts = {}
    for abi, (original, machine) in ORIGINALS.items():
        name = 'adb-shim-' + abi
        source = native_dir / name
        if source.is_symlink() or not source.is_file():
            raise ValueError('Missing regular built shim: ' + name)
        header = source.read_bytes()[:24]
        if len(header) < 24 or header[:6] != b'\x7fELF\x02\x01' or struct.unpack('<HH', header[16:20]) != (3, machine):
            raise ValueError('Expected correct-ABI PIE ELF: ' + name)
        shutil.copyfile(source, output / name)
        (output / name).chmod(0o755)
        artifacts[abi] = {'file': name, 'sha256': digest(source), 'originalSha256': original, 'elfMachine': machine}
    for name in ('apply_patch.py', 'README.md'):
        source = ROOT / name
        if not source.is_file() or source.stat().st_size == 0:
            raise ValueError('Missing delivery file: ' + name)
        shutil.copyfile(source, output / name)
    shutil.copyfile(ROOT.parents[1] / 'LICENSE', output / 'LICENSE')
    (output / 'manifest.json').write_text(json.dumps({'schema': 1, 'patchId': 'adb-local-listener-v1', 'artifacts': artifacts}, indent=2) + '\n')
    names = ['LICENSE', 'README.md', 'apply_patch.py', 'manifest.json'] + [a['file'] for a in artifacts.values()]
    (output / 'SHA256SUMS').write_text(''.join(digest(output / name) + '  ' + name + '\n' for name in sorted(names)))
    print(json.dumps({'output': str(output), 'files': sorted(names), 'artifacts': artifacts}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-dir', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    build(args.native_dir, args.output)
