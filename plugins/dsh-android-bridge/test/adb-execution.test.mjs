// All process calls are fakes; this suite NEVER launches adb or changes real device state.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { AndroidPrivilegeService, localAdbEndpoint } from '../lib/index.js'

const saved = process.env.DSH_ADB_PREFS_PATH
const dir = mkdtempSync(join(tmpdir(), 'dsh-adb-exec-test-'))
const file = join(dir, 'prefs.xml')
after(() => {
  if (saved === undefined) delete process.env.DSH_ADB_PREFS_PATH
  else process.env.DSH_ADB_PREFS_PATH = saved
  rmSync(dir, { recursive: true, force: true })
})
function prefs(host = '127.0.0.1', port = '37951') {
  writeFileSync(file, `<map><boolean name="allowSwitch" value="true"/><boolean name="fullAccess" value="true"/>
    <boolean name="paired" value="true"/><boolean name="wirelessKnown" value="true"/><boolean name="wirelessOn" value="true"/>
    <long name="wirelessObservedAt" value="${Date.now()}"/><string name="connectHost">${host}</string><string name="connectPort">${port}</string></map>`)
  process.env.DSH_ADB_PREFS_PATH = file
}
const ok = (stdout = '') => ({ exitCode: 0, signal: null, timedOut: false, aborted: false, stdout, stderr: '' })
function fake(results) {
  prefs()
  const commands = []
  const shell = {
    resolve: (spec) => { assert.equal(spec.workdir, '/'); return spec },
    run: async (spec) => {
      commands.push(spec.command)
      const result = results.shift()
      if (!result) throw Error('Unexpected mocked process invocation')
      return typeof result === 'function' ? result(spec) : result
    },
  }
  return { svc: new AndroidPrivilegeService({}, () => 'danger-full-access', { resolve: () => ({ mode: 'danger-full-access' }) }, shell), commands }
}
const connected = () => ok('connected to 127.0.0.1:37951\n')

test('persisted endpoints reject remote hosts and shell interpolation; no guessing', () => {
  assert.equal(localAdbEndpoint({ connectPort: '37951' }, []), '127.0.0.1:37951')
  assert.equal(localAdbEndpoint({ connectHost: '::1', connectPort: '37951' }, []), '[::1]:37951')
  assert.equal(localAdbEndpoint({ connectHost: '192.0.2.10', connectPort: '37951' }, ['192.0.2.10']), '192.0.2.10:37951')
  assert.equal(localAdbEndpoint({ connectHost: '192.0.2.11', connectPort: '37951' }, ['192.0.2.10']), undefined)
  for (const connectHost of ['localhost', '', "127.0.0.1';echo pwn", '127.0.0.1\n', '0.0.0.0', '::', 'fe80::1%wlan0;echo']) {
    assert.equal(localAdbEndpoint({ connectHost, connectPort: '37951' }, []), undefined)
  }
  for (const connectPort of [undefined, '', '0', '65536', '37951;echo pwn', '37951\n', '1.5']) {
    assert.equal(localAdbEndpoint({ connectPort }, []), undefined)
  }
})
test('IPv6 canonicalization and link-local scopes match a currently local interface', () => {
  assert.equal(localAdbEndpoint({ connectHost: '0:0:0:0:0:0:0:1', connectPort: '37951' }, []), '[::1]:37951')
  assert.equal(localAdbEndpoint({ connectHost: '[::1]', connectPort: '37951' }, []), '[::1]:37951')
  assert.equal(localAdbEndpoint({ connectHost: '2001:0DB8:0:0:0:0:0:10', connectPort: '37951' }, ['2001:db8::10']), '[2001:db8::10]:37951')
  assert.equal(localAdbEndpoint({ connectHost: '::ffff:192.0.2.10', connectPort: '37951' }, ['192.0.2.10']), '192.0.2.10:37951')
  assert.equal(localAdbEndpoint({ connectHost: 'fe80::10%5', connectPort: '37951' }, ['fe80:0:0:0:0:0:0:10%5']), '[fe80::10%5]:37951')
  for (const host of ['fe80::10', 'fe80::10%6', 'fe80::10%bad;scope']) {
    assert.equal(localAdbEndpoint({ connectHost: host, connectPort: '37951' }, ['fe80::10%5']), undefined)
  }
  // Empty interface knowledge represents a denied/failed interface read.
  assert.equal(localAdbEndpoint({ connectHost: '192.0.2.10', connectPort: '37951' }, []), undefined)
})
test('only persisted endpoint is tried, never guessed5555', async () => {
  const { svc, commands } = fake([ok('failed to connect')])
  assert.equal((await svc.execAdbShell('id')).ok, false)
  assert.deepEqual(commands, ["adb connect '127.0.0.1:37951'"])
})
test('unsafe/missing endpoint runs no process', async () => {
  const { svc, commands } = fake([])
  prefs('127.0.0.1', '37951;echo injected')
  assert.equal((await svc.execAdbShell('id')).ok, false)
  assert.deepEqual(commands, [])
})
for (const [name, outcome] of [
  ['nonzero', { exitCode: 1 }], ['timeout', { timedOut: true }],
  ['signal', { signal: 'SIGTERM' }], ['aborted', { aborted: true }],
  ['denial', { sandbox: { denied: true } }], ['runner failure', { sandbox: { runnerFailed: true } }],
  ['missing exit', { exitCode: undefined }],
]) test(`resolved process ${name} is NOT success`, async () => {
  const { svc, commands } = fake([{ ...connected(), ...outcome }])
  assert.equal((await svc.execAdbShell('id')).ok, false)
  assert.equal(commands.length, 1)
})
test('model probe failure refuses requested command and clears old model', async () => {
  const { svc, commands } = fake([connected(), { ...ok('old-model'), exitCode: 1 }])
  svc.liveModel = 'cached-model'
  assert.equal((await svc.execAdbShell('id')).ok, false)
  assert.equal(svc.boundModel(), '')
  assert.equal(commands.length, 2)
})
test('endpoint changes during connect/model awaits prevent subsequent device commands', async () => {
  const first = fake([() => { prefs('127.0.0.1', '40001'); return connected() }])
  assert.equal((await first.svc.execAdbShell('id')).ok, false)
  assert.equal(first.commands.length, 1)
  const second = fake([connected(), () => { prefs('127.0.0.1', '40001'); return ok('model') }])
  assert.equal((await second.svc.execAdbShell('id')).ok, false)
  assert.equal(second.commands.length, 2)
  assert.equal(second.svc.boundModel(), '')
})
test('successful pairing/transport does not turn command exit failure into success', async () => {
  const { svc, commands } = fake([connected(), ok('test-model'), { ...ok('Permission denied'), exitCode: 1 }])
  assert.equal((await svc.execAdbShell('id')).ok, false)
  assert.equal(commands.length, 3)
})
test('execAdbLine also propagates structured command failure', async () => {
  const { svc } = fake([connected(), ok('test-model'), { ...ok(''), timedOut: true }])
  assert.equal((await svc.execAdbLine('adb shell id')).ok, false)
})
test('real success uses verified endpoint and endpoint changes invalidate cached model', async () => {
  const { svc, commands } = fake([connected(), ok('test-model'), ok('uid=2000(shell)')])
  const out = await svc.execAdbShell('id')
  assert.equal(out.ok, true)
  assert.equal(out.stdout, 'uid=2000(shell)')
  assert.equal(svc.boundModel(), 'test-model')
  assert.ok(commands.every((c) => c.includes("'127.0.0.1:37951'")))
  prefs('127.0.0.1', '40001')
  assert.equal(svc.boundModel(), '')
})
