// Synthetic, offline CLI fixtures. These do NOT establish real cross-repository
// synchronization. Only the gate implementation/allowlist are copied; all peer
// build inputs are independently generated fixture text, never a clone of ROOT.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const ROOT = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
const mirrorScript = readFileSync(join(ROOT, 'scripts/check-patch-mirror.mjs'), 'utf8')
const releaseScript = readFileSync(join(ROOT, 'scripts/check-release-gates.mjs'), 'utf8')
const nativeManifest = readFileSync(join(ROOT, 'scripts/native-proot-mirror-files.json'), 'utf8')
const nativeFiles = JSON.parse(nativeManifest).files
const legacyBlock = mirrorScript.slice(mirrorScript.indexOf('const MIRROR_TOP ='), mirrorScript.indexOf('/** 递归列出'))
const legacyFiles = [...legacyBlock.matchAll(/'(scripts\/[^']+|plugins\/[^']+)'/g)].map((m) => m[1])
assert.ok(legacyFiles.length > 20, 'legacy mirror fixture surfaces must be discoverable')
const gates = [...releaseScript.matchAll(/\{ script: '([^']+)'/g)].map((m) => m[1])
assert.ok(gates.length > 10, 'release fixture gate set must be discoverable')

function put(root, rel, bytes) {
  const file = join(root, rel)
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, bytes)
}
function temporary(t, label) {
  const root = mkdtempSync(join(tmpdir(), label))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  return root
}
function cli(root, script, args = [], extraEnv = {}) {
  const env = { ...process.env, ...extraEnv }
  if (!Object.hasOwn(extraEnv, 'DSH_MIRROR_PEER')) delete env.DSH_MIRROR_PEER
  // Android linker-launched Node can report linker64 as process.execPath.
  const result = spawnSync(process.env.NODE || 'node', [join(root, 'scripts', script), ...args], {
    cwd: root, env, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024,
  })
  assert.ifError(result.error)
  return { code: result.status, output: result.stdout + result.stderr }
}
function expect(result, code, pattern) {
  assert.equal(result.code, code, result.output)
  if (pattern) assert.match(result.output, pattern)
}
function mirrorTree(root) {
  for (const rel of new Set([...nativeFiles, ...legacyFiles])) {
    if (rel.startsWith('plugins/')) put(root, rel + '/src/fixture.mjs', 'synthetic plugin ' + rel + '\n')
    else put(root, rel, 'synthetic file ' + rel + '\n')
  }
  put(root, 'scripts/check-patch-mirror.mjs', mirrorScript)
  put(root, 'scripts/native-proot-mirror-files.json', nativeManifest)
  put(root, 'scripts/patches/registry.json', JSON.stringify({ patches: [{ id: 'synthetic' }] }))
  put(root, 'scripts/patches/apply-patches.mjs', "  'synthetic': {\n")
  put(root, 'scripts/patches/README.md', 'synthetic patch registry\n')
  put(root, 'scripts/patches/tests/plain.test.mjs', 'synthetic root test\n')
  put(root, 'scripts/patches/tests/nested/deep/fixture.test.mjs', 'synthetic nested test\n')
}
function mirrors(t) {
  const coordinator = temporary(t, 'synthetic-native-mirror-')
  const peer = join(coordinator, 'dsh-mobile-apk')
  mirrorTree(coordinator)
  mirrorTree(peer)
  return { coordinator, peer, run: (args = ['--peer', peer], env) => cli(coordinator, 'check-patch-mirror.mjs', args, env) }
}

test('synthetic independent peer passes unchanged legacy and required native mirrors', t => {
  expect(mirrors(t).run(), 0, /SKIP=0/)
})
for (const rel of nativeFiles) test('synthetic peer missing required closure member fails: ' + rel, t => {
  const f = mirrors(t)
  rmSync(join(f.peer, rel))
  expect(f.run(), 1, /native 门禁镜像在场/)
})
test('mirror implementation itself is byte-compared, not only its native helpers', t => {
  const f = mirrors(t)
  put(f.peer, 'scripts/check-patch-mirror.mjs', mirrorScript + '\n// synthetic drift\n')
  expect(f.run(), 1, /FAIL  镜像一致: scripts\/check-patch-mirror.mjs/)
})
test('recursive patch test content drift is rejected', t => {
  const f = mirrors(t)
  put(f.peer, 'scripts/patches/tests/nested/deep/fixture.test.mjs', 'changed nested fixture\n')
  expect(f.run(), 1, /tests\/ 共有文件逐字节一致/)
})
test('recursive patch test file-set drift is rejected in either direction', t => {
  const f = mirrors(t)
  put(f.peer, 'scripts/patches/tests/nested/deep/extra.test.mjs', 'peer-only fixture\n')
  expect(f.run(), 1, /tests\/ 文件清单一致/)
  expect(cli(f.peer, 'check-patch-mirror.mjs', ['--peer', f.coordinator]), 1, /tests\/ 文件清单一致/)
})
test('invalid explicit peer cannot fall back to a valid auto-discovered synthetic peer', t => {
  const f = mirrors(t)
  expect(f.run(['--peer', join(f.coordinator, 'missing')]), 1, /显式镜像对端有效且独立/)
  expect(f.run([], { DSH_MIRROR_PEER: join(f.coordinator, 'missing') }), 1, /显式镜像对端有效且独立/)
})
test('relative and normalized self-peer aliases fail instead of producing mirror evidence', t => {
  const f = mirrors(t)
  expect(f.run(['--peer', '.']), 1, /same tree/)
  expect(f.run(['--peer', f.coordinator + '/.']), 1, /same tree/)
})
test('missing explicit peer argument and contradictory self mode fail closed', t => {
  const f = mirrors(t)
  expect(f.run(['--peer']), 1, /missing peer path/)
  expect(f.run(['--self', '--peer', f.peer]), 1, /--self 与显式 peer 不能并用/)
})
test('allowlist cannot remove its own checker', t => {
  const f = mirrors(t)
  const manifest = JSON.parse(nativeManifest)
  manifest.files = manifest.files.filter((p) => p !== 'scripts/check-patch-mirror.mjs')
  put(f.coordinator, 'scripts/native-proot-mirror-files.json', JSON.stringify(manifest))
  expect(f.run(), 1, /native 同步清单可解析且包含镜像门禁自身/)
})

const snapshotWorkflow = './gradlew :app:assembleDebug -PtargetAbi="$ABI"\nnode scripts/check-native-proot.mjs --abi "$ABI" --apk app-debug.apk\ncp app/build/outputs/apk/debug/app-debug.apk delivered.apk\n'
function releaseLayout(t, nested) {
  const root = temporary(t, 'synthetic-native-release-layout-')
  const apk = nested ? join(root, 'dsh-mobile-apk') : root
  const ps1 = gates.map((g) => 'node scripts\\' + g).join('\n') + '\n& .\\gradlew :app:assembleDebug -PtargetAbi="$androidAbi"\nnode scripts\\check-native-proot.mjs --apk app-debug.apk\nCopy-Item "app\\build\\outputs\\apk" target\n'
  const mjs = 'const GATE_SCRIPTS = [' + gates.map((g) => "'" + g + "'").join(', ') + ']\nspawnSync(gradleCmd, ["-PtargetAbi=arm64-v8a"])\ncheck-native-proot.mjs --apk app-debug.apk\ncopyFileSync(join(apkDir, "app-debug.apk"), target)\n'
  const release = '$pluginSrcs = @(\'plugins/fixture\')\nnode scripts\\check-release-gates.mjs --run --require\n& $Gradle assembleDebug -PtargetAbi=$($abi.n)\nnode scripts\\check-native-proot.mjs --apk $apk.FullName\nCopy-Item $apk.FullName target\n'
  for (const tree of new Set([root, apk])) {
    put(tree, 'scripts/build-apk-013.ps1', ps1)
    put(tree, 'scripts/build-apk.mjs', mjs)
    put(tree, 'scripts/build-release.ps1', release)
    put(tree, '.github/workflows/pr-gate.yml', gates.join('\n'))
  }
  put(root, 'scripts/check-release-gates.mjs', releaseScript)
  put(root, 'scripts/plugin-dirs.json', JSON.stringify({ dirs: ['plugins/fixture'] }))
  put(apk, 'app/build.gradle.kts', '// synthetic APK layout marker\n')
  put(apk, '.github/workflows/build-snapshot.yml', snapshotWorkflow)
  put(apk, '.github/workflows/native-proot-validation.yml', './gradlew :app:testDebugUnitTest -PtargetAbi=arm64-v8a\nnode scripts/check-native-proot.mjs --apk app-debug.apk\n- name: Upload validated dev APK\n')
  return { root, apk, run: () => cli(root, 'check-release-gates.mjs') }
}
for (const nested of [false, true]) test(`release gate CLI resolves ${nested ? 'coordinator nested APK' : 'standalone APK'} workflows`, t => {
  expect(releaseLayout(t, nested).run(), 0, /CHECK-RELEASE-GATES PASSED/)
})
test('coordinator APK workflow cannot be replaced by a valid root workflow when missing', t => {
  const f = releaseLayout(t, true)
  put(f.root, '.github/workflows/build-snapshot.yml', snapshotWorkflow)
  rmSync(join(f.apk, '.github/workflows/build-snapshot.yml'))
  expect(f.run(), 1, /接线面存在: dsh-mobile-apk\/\.github\/workflows\/build-snapshot.yml/)
})
test('nested APK workflow remains checked when a valid root copy exists', t => {
  const f = releaseLayout(t, true)
  put(f.root, '.github/workflows/build-snapshot.yml', snapshotWorkflow)
  put(f.apk, '.github/workflows/build-snapshot.yml', snapshotWorkflow.replace('-PtargetAbi=', '-Pwrong='))
  expect(f.run(), 1, /dsh-mobile-apk\/\.github\/workflows\/build-snapshot.yml targetAbi/)
})
test('a separate coordinator direct Gradle workflow cannot bypass its postbuild gate', t => {
  const f = releaseLayout(t, true)
  put(f.root, '.github/workflows/build-snapshot.yml', snapshotWorkflow.replace('check-native-proot.mjs', 'not-the-native-gate.mjs'))
  expect(f.run(), 1, /FAIL  \.github\/workflows\/build-snapshot.yml targetAbi/)
})
test('multiline coordinator Gradle entry is detected rather than silently omitted', t => {
  const f = releaseLayout(t, true)
  put(f.root, '.github/workflows/build-snapshot.yml', snapshotWorkflow.replace('./gradlew :app:assembleDebug', './gradlew \\\n  :app:assembleDebug').replace('check-native-proot.mjs', 'not-the-native-gate.mjs'))
  expect(f.run(), 1, /FAIL  \.github\/workflows\/build-snapshot.yml targetAbi/)
})
test('coordinator delegation-only workflow does not shadow the real APK build entry', t => {
  const f = releaseLayout(t, true)
  put(f.root, '.github/workflows/build-snapshot.yml', 'jobs:\n  delegate:\n    uses: ./nested-build.yml\n')
  expect(f.run(), 0, /CHECK-RELEASE-GATES PASSED/)
})
