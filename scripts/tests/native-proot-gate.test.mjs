#!/usr/bin/env node
// Fully synthetic, offline fixtures; no snapshot, SDK, installed runtime or emulator required.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { validateElf } from '../lib/native-proot-elf.mjs'

const ROOT = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
const PYTHON = process.env.PYTHON || 'python'
const GENERATE = String.raw`
import gzip, hashlib, io, json, lzma, pathlib, struct, sys, tarfile, zipfile, warnings
warnings.simplefilter('ignore', UserWarning)
root = pathlib.Path(sys.argv[1]); spec = json.loads(sys.argv[2])
def elf(abi, loader32=False):
    cls, machine = (1,40) if loader32 else (2,183 if abi == 'arm64-v8a' else 62)
    b = bytearray(96); b[:7] = b'\x7fELF' + bytes([cls,1,1])
    struct.pack_into('<HHI', b,16,3,machine,1)
    struct.pack_into('<H', b,40 if cls == 1 else 52,52 if cls == 1 else 64)
    return bytes(b)
metadata = {'schemaVersion':1, 'artifacts':{}}
payloads = {}
for abi in ['arm64-v8a','x86_64']:
    if spec.get('unsupported') and abi == 'x86_64':
        metadata['unsupportedAbis'] = {'x86_64':'No source-verified PRoot build available.'}
        continue
    record = {'validation':{'publishable':True,'sourceVerified':True},'files':{}}
    names = ['libproot.so','libproot-loader.so'] + (['libproot-loader32.so'] if abi == 'arm64-v8a' else [])
    directory = root / 'app/src/main/jniLibs' / abi; directory.mkdir(parents=True)
    for name in names:
        data = elf(abi, name == 'libproot-loader32.so')
        (directory / name).write_bytes(data)
        record['files'][name] = hashlib.sha256(data).hexdigest()
        payloads['lib/'+abi+'/'+name] = data
    metadata['artifacts'][abi] = record
sources = root/'vendor/native-proot/assets/native-proot-source'; sources.mkdir(parents=True)
source_data = gzip.compress(b'synthetic corresponding source archive',mtime=0)
(sources/'fixture-source.tar.gz').write_bytes(source_data)
for name in ['COPYING.proot','GPL-3.0.txt','LGPL-3.0.txt','rebuild-arm64.sh','string-header.patch','talloc-answers.txt']:
    (sources/name).write_text('synthetic fixture '+name)
for record in metadata['artifacts'].values():
    record['sourceArchive'] = 'vendor/native-proot/assets/native-proot-source/fixture-source.tar.gz'
    record['sourceArchiveSha256'] = hashlib.sha256(source_data).hexdigest()
(root/'scripts').mkdir()
(root/'scripts/native-proot.json').write_text(json.dumps(metadata))
(root/'app/src/main/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:extractNativeLibs="true"/></manifest>')
(root/'app/build.gradle.kts').write_text('val targetAbi = providers.gradleProperty("targetAbi"); abiFilters += targetAbi; useLegacyPackaging = true')
abi = spec.get('abi','arm64-v8a')
def chunk(kind, hs, payload):
    return struct.pack('<HHI', kind, hs, 8+len(payload)) + payload
strings = ['manifest','application','http://schemas.android.com/apk/res/android','extractNativeLibs','android','true','spoof']
encoded = []; offsets = []
for s in strings:
    offsets.append(sum(map(len,encoded)))
    b = s.encode('utf-16-le' if spec.get('utf16') else 'utf-8')
    encoded.append(struct.pack('<H',len(s)) + b + b'\0\0' if spec.get('utf16') else bytes([len(s),len(b)]) + b + b'\0')
string_data = b''.join(encoded); string_data += b'\0'*(-len(string_data)%4)
pool = chunk(1,28, struct.pack('<IIIII',len(strings),0,0 if spec.get('utf16') else 0x100,28+len(strings)*4,0) + struct.pack('<'+'I'*len(strings),*offsets) + string_data)
resources = chunk(0x180,8,struct.pack('<IIIIIII',0,0,0,0x010104eb if spec.get('wrongResource') else 0x010104ea,0,0,0x010104ea if spec.get('spoofResource') else 0))
none = 0xffffffff
ns_start = chunk(0x100,16,struct.pack('<IIII',1,none,4,2))
ns_end = chunk(0x101,16,struct.pack('<IIII',1,none,4,2))
def start(name, attrs=b''):
    return chunk(0x102,16,struct.pack('<IIIIHHHHHH',1,none,none,name,20,20,len(attrs)//20,0,0,0)+attrs)
def end(name):
    return chunk(0x103,16,struct.pack('<IIII',1,none,none,name))
attr = struct.pack('<IIIHBBI',none if spec.get('wrongNamespace') else 2,3,5,8,0,3 if spec.get('stringBoolean') else 0x12,0 if spec.get('manifestFalse') else 0xffffffff)
if spec.get('spoofResource'): attr = struct.pack('<IIIHBBI',2,6,5,8,0,0x12,0)+attr
body = pool+resources+ns_start+start(0)+start(1,b'' if spec.get('missingAttribute') else attr)+end(1)+end(0)+ns_end
manifest = chunk(3,8,body)
if spec.get('truncatedManifest'): manifest = manifest[:-1]
if spec.get('textManifest'): manifest = b'<application android:extractNativeLibs="true"/>'
buffer = io.BytesIO()
with tarfile.open(fileobj=buffer,mode='w') as tar:
    if not spec.get('missingNode'):
        node = bytearray(elf(spec.get('nodeAbi',abi)))
        if spec.get('badNodeMagic'): node[0] = 0
        info = tarfile.TarInfo('./usr/bin/node'); info.mode = 0o755; info.size = len(node)
        if spec.get('linkNode'):
            info.type = tarfile.SYMTYPE; info.linkname = '/tmp/node'; info.size = 0
        tar.addfile(info,io.BytesIO(node))
        if spec.get('duplicateNode'): tar.addfile(info,io.BytesIO(node))
        if spec.get('aliasNode'):
            info.name = spec['aliasNode']; tar.addfile(info,io.BytesIO(elf('x86_64')))
        if spec.get('ancestorLink'):
            info=tarfile.TarInfo('usr/bin'); info.type=tarfile.SYMTYPE; info.linkname='wrong-abi-bin'; tar.addfile(info)
raw_tar = buffer.getvalue()
if spec.get('badTarChecksum'):
    raw_tar = raw_tar[:5120] + b'X'*512 + raw_tar[5632:]
snapshot = lzma.compress(raw_tar,preset=0)
if spec.get('large'):
    class Zeroes:
        def read(self,n): return b'0'*n
    with lzma.open(root/'large.tar.xz','wb',preset=0) as output:
        with tarfile.open(fileobj=output,mode='w|') as tar:
            data=elf(abi); info=tarfile.TarInfo('usr/bin/node'); info.size=len(data); info.mode=0o755
            tar.addfile(info,io.BytesIO(data))
            info=tarfile.TarInfo('usr/large-zero-fixture'); info.size=128*1024*1024
            tar.addfile(info,Zeroes())
    snapshot=(root/'large.tar.xz').read_bytes()
if spec.get('truncatedSnapshot'): snapshot = snapshot[:-16]
entries = {'AndroidManifest.xml':manifest,'assets/snapshot.tar.xz':snapshot,'assets/snapshot.sha256':hashlib.sha256(snapshot).hexdigest().encode(),'assets/unrelated.bin':b'CRC negative control'}
entries.update({name:data for name,data in payloads.items() if name.startswith('lib/'+abi+'/')})
entries.update({'assets/native-proot-source/'+p.name:p.read_bytes() for p in sources.iterdir()})
if spec.get('changedSourceAsset'): entries['assets/native-proot-source/fixture-source.tar.gz'] += b'x'
if spec.get('fingerprintMismatch'): entries['assets/snapshot.sha256'] = b'0'*64
if spec.get('wrongLibraryAbi'):
    entries['lib/'+abi+'/libproot.so'] = elf('x86_64' if abi == 'arm64-v8a' else 'arm64-v8a')
if spec.get('changedLibraryBytes'):
    name='lib/'+abi+'/libproot.so'; entries[name] = entries[name][:-1] + b'x'
if spec.get('extraAbi'): entries['lib/x86_64/libunrelated.so'] = elf('x86_64')
if spec.get('unsupportedPayload'): entries['lib/x86_64/libproot.so'] = elf('x86_64')
if spec.get('omit'): entries.pop(spec['omit'])
with zipfile.ZipFile(root/'test.apk','w',compression=zipfile.ZIP_STORED) as apk:
    for name,data in entries.items(): apk.writestr(name,data)
    if spec.get('duplicate'): apk.writestr(spec['duplicate'],entries[spec['duplicate']])
    if spec.get('large'):
        with apk.open('assets/large-zero-fixture','w') as stream:
            for i in range(128): stream.write(b'0'*(1024*1024))
if spec.get('corruptCrc'):
    path=root/'test.apk'; raw=bytearray(path.read_bytes()); off=raw.index(b'CRC negative control'); raw[off] ^= 1; path.write_bytes(raw)
`

function fixture(t, spec = {}) {
  const root = mkdtempSync(join(tmpdir(), 'native-proot-gate-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const result = spawnSync(PYTHON, ['-c', GENERATE, root, JSON.stringify(spec)], { encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr || result.error?.message)
  return root
}
function run(root, abi, apk = false) {
  const result = spawnSync(process.env.NODE || 'node', [join(ROOT, 'scripts/check-native-proot.mjs'), '--root', root, ...(abi ? ['--abi', abi] : []), ...(apk ? ['--apk', join(root, 'test.apk')] : [])], { encoding: 'utf8' })
  assert.ifError(result.error)
  return { code: result.status, output: result.stdout + result.stderr }
}
function expect(result, code, pattern) {
  assert.equal(result.code, code, result.output)
  if (pattern) assert.match(result.output, pattern)
  if (code === 0) assert.match(result.output, /SKIP=0/)
}
function changeMetadata(root, mutate) {
  const file = join(root, 'scripts/native-proot.json')
  const json = JSON.parse(readFileSync(file)); mutate(json); writeFileSync(file, JSON.stringify(json))
}
function changeLibrary(root, mutate) {
  const file = join(root, 'app/src/main/jniLibs/arm64-v8a/libproot.so')
  const data = readFileSync(file); mutate(data); writeFileSync(file, data)
}

test('source passes both ABIs and ARM ELF32 loader without any snapshot', t => {
  const root = fixture(t); unlinkSync(join(root, 'test.apk'))
  expect(run(root), 0, /libproot-loader32.so: ELF and pinned SHA-256/)
  assert.deepEqual(validateElf(readFileSync(join(root, 'app/src/main/jniLibs/arm64-v8a/libproot-loader32.so')), 'arm64-v8a', 'libproot-loader32.so'), { elfClass: 1, machine: 40 })
})
test('source missing file fails rather than skips', t => {
  const root = fixture(t); unlinkSync(join(root, 'app/src/main/jniLibs/arm64-v8a/libproot-loader32.so'))
  expect(run(root), 1, /file set differs/)
})
for (const [label, mutate, pattern] of [
  ['hash changed', b => { b[95] ^= 1 }, /SHA-256 mismatch/],
  ['magic invalid with correct machine', b => { b[0] = 0 }, /ELF magic/],
  ['big endian', b => { b[5] = 2 }, /endianness/],
  ['wrong ABI', b => b.writeUInt16LE(62, 18), /e_machine/],
  ['ELF32 engine forbidden', b => { b[4] = 1 }, /ELF class/],
  ['version invalid', b => { b[6] = 0 }, /ELF version/],
]) test('source rejects ' + label, t => { const root = fixture(t); changeLibrary(root, mutate); expect(run(root), 1, pattern) })
for (const field of ['publishable', 'sourceVerified']) test('provenance refuses ' + field + '=false', t => {
  const root = fixture(t); changeMetadata(root, m => { m.artifacts['arm64-v8a'].validation[field] = false })
  expect(run(root), 1, /provenance validation/)
})
test('provenance missing is fail-closed', t => {
  const root = fixture(t); changeMetadata(root, m => { delete m.artifacts['arm64-v8a'].validation })
  expect(run(root), 1, /provenance validation/)
})
test('unlisted source native bytes fail', t => {
  const root = fixture(t); writeFileSync(join(root, 'app/src/main/jniLibs/arm64-v8a/libextra.so'), 'bad')
  expect(run(root), 1, /file set differs/)
})
test('supported/unsupported partition cannot omit an ABI', t => {
  const root = fixture(t); changeMetadata(root, m => { delete m.artifacts.x86_64 })
  expect(run(root), 1, /partition/)
})
test('unsupported x86_64 explicitly verifies empty payload, not SKIP', t => {
  const root = fixture(t, { unsupported: true, abi: 'x86_64' })
  expect(run(root), 0, /explicitly unsupported/)
  expect(run(root, 'x86_64', true), 0, /snapshot SHA-256\/Node ELF/)
})
for (const abi of ['arm64-v8a', 'x86_64']) for (const utf16 of [false, true]) test(`APK passes ${abi}, AXML UTF-${utf16 ? '16' : '8'}`, t => {
  const root = fixture(t, { abi, utf16 }); expect(run(root, abi, true), 0, /all ZIP CRCs/)
})
for (const [label, spec, pattern] of [
  ['source archive bytes changed', { changedSourceAsset: true }, /APK source\/license\/rebuild asset SHA-256 mismatch/],
  ['native library changed', { changedLibraryBytes: true }, /APK native SHA-256 mismatch/],
  ['wrong ELF ABI', { wrongLibraryAbi: true }, /wrong ELF e_machine/],
  ['second ABI unrelated native library', { extraAbi: true }, /native entry set mismatch/],
  ['unsupported ABI with payload', { unsupported: true, abi: 'x86_64', unsupportedPayload: true }, /native entry set mismatch/],
  ['snapshot fingerprint mismatch', { fingerprintMismatch: true }, /snapshot SHA-256 mismatch/],
  ['wrong snapshot Node ABI', { nodeAbi: 'x86_64' }, /wrong ELF e_machine/],
  ['snapshot Node non ELF', { badNodeMagic: true }, /invalid ELF magic/],
  ['snapshot Node missing', { missingNode: true }, /node missing/],
  ['snapshot Node symlink', { linkNode: true }, /one regular file/],
  ['snapshot duplicate Node via double slash', { aliasNode: 'usr/bin//node' }, /one regular file/],
  ['snapshot duplicate Node via dot segment', { aliasNode: 'usr/./bin/node' }, /one regular file/],
  ['snapshot Node parent replaced by symlink', { ancestorLink: true }, /Node ancestor/],
  ['snapshot Node duplicate', { duplicateNode: true }, /one regular file/],
  ['corrupt tar header after EOF padding', { badTarChecksum: true }, /invalid snapshot tar header/],
  ['truncated snapshot even with matching fingerprint', { truncatedSnapshot: true }, /end-of-stream|Compressed file ended|truncated|end of data/],
  ['bad CRC in unrelated ZIP member', { corruptCrc: true }, /CRC/],
  ['extractNativeLibs false but raw string true', { manifestFalse: true }, /typed boolean true/],
  ['extractNativeLibs string not boolean', { stringBoolean: true }, /typed boolean true/],
  ['attribute missing', { missingAttribute: true }, /extractNativeLibs=true missing/],
  ['attribute wrong namespace', { wrongNamespace: true }, /spoofed extractNativeLibs/],
  ['alias attribute sharing Android resource ID', { spoofResource: true }, /spoofed extractNativeLibs|duplicate attribute resource ID/],
  ['attribute resource ID spoof', { wrongResource: true }, /resource ID mismatch/],
  ['truncated AXML', { truncatedManifest: true }, /complete binary AXML/],
  ['plain text masquerading as compiled manifest', { textManifest: true }, /complete binary AXML/],
]) test('APK rejects ' + label, t => { const root = fixture(t, spec); expect(run(root, spec.abi || 'arm64', true), 1, pattern) })
for (const name of ['lib/arm64-v8a/libproot.so', 'lib/arm64-v8a/libproot-loader.so', 'lib/arm64-v8a/libproot-loader32.so', 'assets/snapshot.tar.xz', 'assets/snapshot.sha256', 'AndroidManifest.xml', 'assets/native-proot-source/fixture-source.tar.gz', 'assets/native-proot-source/COPYING.proot', 'assets/native-proot-source/rebuild-arm64.sh']) test('APK rejects missing ' + name, t => {
  const root = fixture(t, { omit: name }); expect(run(root, 'arm64', true), 1, /missing|entry set mismatch/)
})
for (const name of ['lib/arm64-v8a/libproot.so', 'assets/snapshot.tar.xz', 'assets/snapshot.sha256', 'AndroidManifest.xml', 'assets/unrelated.bin']) test('APK rejects duplicate ZIP ' + name, t => {
  const root = fixture(t, { duplicate: name }); expect(run(root, 'arm64', true), 1, /duplicate ZIP/)
})
test('APK invocation must declare its ABI', t => { const root = fixture(t); expect(run(root, undefined, true), 1, /requires an explicit --abi/) })

test('source archive hash mismatch fails', t => {
  const root = fixture(t); writeFileSync(join(root, 'vendor/native-proot/assets/native-proot-source/fixture-source.tar.gz'), 'changed')
  expect(run(root), 1, /source archive SHA-256 mismatch/)
})
test('source archive missing fails', t => {
  const root = fixture(t); unlinkSync(join(root, 'vendor/native-proot/assets/native-proot-source/fixture-source.tar.gz'))
  expect(run(root), 1, /ENOENT/)
})

test('streaming: 128 MiB APK/snapshot members, bounded reads (Linux CI: 96 MiB cap)', t => {
  const root = fixture(t, { large: true })
  const code = String.raw`
import importlib.util, pathlib, sys
spec=importlib.util.spec_from_file_location('gate',sys.argv[1]); gate=importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)
# Android's linker/allocator reserves a very large virtual address space; Windows has no RLIMIT_AS.
# All hosts assert bounded reads; Linux CI additionally enforces the 96 MiB process cap.
if sys.platform == 'linux' and not hasattr(sys,'getandroidapilevel'):
    import resource
    resource.setrlimit(resource.RLIMIT_AS,(96*1024*1024,96*1024*1024))
original = gate.zipfile.ZipExtFile.read
def bounded(self,size=-1):
    assert 0 <= size <= gate.CHUNK, 'unbounded ZIP read: '+str(size)
    return original(self,size)
gate.zipfile.ZipExtFile.read = bounded
gate.verify(pathlib.Path(sys.argv[2]),pathlib.Path(sys.argv[2])/'test.apk','arm64-v8a')
print('bounded ZIP reads verified')
`
  const result = spawnSync(PYTHON, ['-c', code, join(ROOT, 'scripts/lib/check-native-proot-apk.py'), root], { encoding: 'utf8', env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } })
  assert.equal(result.status, 0, result.stderr || result.error?.message)
  assert.match(result.stdout, /bounded ZIP reads verified/)
})

test('null supported metadata cannot masquerade as explicitly unsupported', t => {
  const root = fixture(t)
  changeMetadata(root, m => { m.artifacts['arm64-v8a'] = null })
  rmSync(join(root, 'app/src/main/jniLibs/arm64-v8a'), { recursive: true })
  expect(run(root), 1, /invalid supported artifact record/)
})
