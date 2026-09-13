#!/usr/bin/env node
// Source: pinned bytes + publishable provenance, without requiring a snapshot.
// APK: source checks, then streamed ZIP/AXML/XZ/tar checks; no name-only success.
// Usage: node scripts/check-native-proot.mjs [--abi arm64|arm64-v8a|x86_64] [--apk file] [--root apk-repo]
import { createHash } from 'node:crypto'
import { createReadStream, existsSync, readFileSync, readdirSync, lstatSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { ABI_ALIASES, ABI_MACHINES, validateElf } from './lib/native-proot-elf.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = dirname(HERE)
const argv = process.argv.slice(2)
const opts = {}
try {
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i]
    if (!['--abi', '--apk', '--root'].includes(key) || !argv[i + 1] || argv[i + 1].startsWith('--') || key in opts) throw new Error('invalid argument: ' + key)
    opts[key] = argv[++i]
  }
  const root = resolve(opts['--root'] || process.env.DSH_APK_DIR || (existsSync(join(ROOT, 'dsh-mobile-apk', 'app')) ? join(ROOT, 'dsh-mobile-apk') : ROOT))
  const abi = opts['--abi'] && ABI_ALIASES[opts['--abi']]
  if (opts['--abi'] && !abi) throw new Error('unsupported ABI: ' + opts['--abi'])
  if (opts['--apk'] && !abi) throw new Error('--apk requires an explicit --abi')
  const metadata = JSON.parse(readFileSync(join(root, 'scripts', 'native-proot.json'), 'utf8'))
  const supported = Object.keys(metadata.artifacts || {})
  const unsupported = Object.keys(metadata.unsupportedAbis || {})
  if (metadata.schemaVersion !== 1 || supported.length === 0 || supported.some((name) => unsupported.includes(name)) || [...supported, ...unsupported].sort().join(',') !== Object.keys(ABI_MACHINES).sort().join(',')) throw new Error('metadata must explicitly partition supported/unsupported ABIs with schemaVersion=1')
  for (const name of supported) if (!metadata.artifacts[name] || typeof metadata.artifacts[name] !== 'object' || Array.isArray(metadata.artifacts[name])) throw new Error('invalid supported artifact record: ' + name)
  for (const name of unsupported) if (typeof metadata.unsupportedAbis[name] !== 'string' || !metadata.unsupportedAbis[name].trim()) throw new Error('unsupported ABI needs a reason: ' + name)
  const failures = []
  const nativeRoot = join(root, 'app', 'src', 'main', 'jniLibs')
  if (existsSync(nativeRoot) && readdirSync(nativeRoot).some((name) => !(name in ABI_MACHINES))) throw new Error('unregistered native ABI directory')
  for (const selected of abi ? [abi] : Object.keys(ABI_MACHINES)) {
    try {
      const record = metadata.artifacts[selected]
      const dir = join(root, 'app', 'src', 'main', 'jniLibs', selected)
      if (unsupported.includes(selected)) {
        if (existsSync(dir) && (!lstatSync(dir).isDirectory() || readdirSync(dir).length)) throw new Error('unsupported ABI must have no native payload')
        console.log(`PASS  ${selected}: explicitly unsupported PRoot; no native payload (not a skipped test)`)
        continue
      }
      if (record.validation?.publishable !== true || record.validation?.sourceVerified !== true) failures.push(selected + ': provenance validation requires publishable=true and sourceVerified=true; unverified artifacts cannot ship')
      const files = record.files
      if (!files || !files['libproot.so'] || !files['libproot-loader.so']) throw new Error('metadata missing required engine/native loader')
      if (!lstatSync(dir).isDirectory()) throw new Error('native source directory must not be a symlink')
      const actual = readdirSync(dir).sort()
      if (actual.join(',') !== Object.keys(files).sort().join(',')) throw new Error('source native file set differs from metadata (missing/unpinned file)')
      for (const [name, digest] of Object.entries(files)) {
        if (!/^lib[\w.-]+\.so$/.test(name) || !/^[a-f0-9]{64}$/.test(digest)) throw new Error('invalid metadata filename/hash: ' + name)
        const path = join(dir, name)
        if (!lstatSync(path).isFile()) throw new Error('source native library is not a regular file: ' + name)
        const hash = createHash('sha256')
        let header = Buffer.alloc(0)
        for await (const bytes of createReadStream(path)) {
          if (header.length < 64) header = Buffer.concat([header, bytes.subarray(0, 64 - header.length)])
          hash.update(bytes)
        }
        validateElf(header, selected, name)
        if (hash.digest('hex') !== digest) throw new Error('SHA-256 mismatch: ' + name)
        console.log(`PASS  ${selected}/${name}: ELF and pinned SHA-256`)
      }
      if (!/^vendor\/native-proot\/assets\/native-proot-source\/[\w.-]+\.tar\.gz$/.test(record.sourceArchive || '') || !/^[a-f0-9]{64}$/.test(record.sourceArchiveSha256 || '')) throw new Error('metadata needs sourceArchive and pinned sourceArchiveSha256')
      const archive = join(root, record.sourceArchive)
      if (!lstatSync(archive).isFile()) throw new Error('source archive must be a regular file')
      const archiveHash = createHash('sha256')
      for await (const bytes of createReadStream(archive)) archiveHash.update(bytes)
      if (archiveHash.digest('hex') !== record.sourceArchiveSha256) throw new Error('source archive SHA-256 mismatch')
      const sourceAssets = dirname(archive)
      for (const name of ['COPYING.proot', 'GPL-3.0.txt', 'LGPL-3.0.txt', 'rebuild-arm64.sh', 'string-header.patch', 'talloc-answers.txt']) {
        const file = join(sourceAssets, name)
        if (!lstatSync(file).isFile() || lstatSync(file).size === 0) throw new Error('source license/rebuild asset missing/empty: ' + name)
      }
      console.log(`PASS  ${selected}: pinned corresponding source archive and license/rebuild assets`)

    } catch (e) { failures.push(selected + ': ' + e.message) }
  }
  // Static source policy; binary APK policy is independently decoded below.
  const manifest = readFileSync(join(root, 'app', 'src', 'main', 'AndroidManifest.xml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '')
  if (!/<application\b[^>]*\bandroid:extractNativeLibs\s*=\s*["']true["']/.test(manifest)) failures.push('source Manifest must set application android:extractNativeLibs=true')
  const gradle = readFileSync(join(root, 'app', 'build.gradle.kts'), 'utf8')
  if (!/useLegacyPackaging\s*=\s*true/.test(gradle) || !gradle.includes('gradleProperty("targetAbi")') || !/abiFilters\s*\+=\s*targetAbi/.test(gradle)) failures.push('Gradle must enable legacy JNI extraction and targetAbi filtering')
  if (failures.length) throw new Error(failures.join('; '))
  if (opts['--apk']) {
    const result = spawnSync(process.env.PYTHON || 'python', [join(HERE, 'lib', 'check-native-proot-apk.py'), '--root', root, '--abi', abi, '--apk', resolve(opts['--apk'])], { stdio: 'inherit' })
    if (result.error || result.status !== 0) throw new Error('APK integrity check failed: ' + (result.error?.message || result.status))
  }
  console.log('CHECK-NATIVE-PROOT PASSED (SKIP=0)')
} catch (e) {
  console.error('CHECK-NATIVE-PROOT FAILED: ' + e.message)
  process.exitCode = 1
}
