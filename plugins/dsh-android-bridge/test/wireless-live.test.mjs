// Wireless observations are independent of pairing; unknown/legacy/stale must fail closed.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { AndroidPrivilegeService, parseAdbPrefsXml, wirelessState, WIRELESS_FRESH_MS } from '../lib/index.js'

const keys = ['DSH_ADB_PREFS_PATH', 'DSH_ADB_FULLACCESS', 'DSH_ADB_WIRELESS', 'DSH_ADB_PAIRED']
const saved = Object.fromEntries(keys.map((key) => [key, process.env[key]]))
const dir = mkdtempSync(join(tmpdir(), 'dsh-wireless-test-'))
const file = join(dir, 'prefs.xml')
after(() => {
  rmSync(dir, { recursive: true, force: true })
  for (const [key, value] of Object.entries(saved)) {
    if (value === undefined) delete process.env[key]
    else process.env[key] = value
  }
})
function service(paired, wireless, extra = '', defaultMode = 'danger-full-access', sessionMode = 'danger-full-access') {
  writeFileSync(file, `<map>
    <boolean name="allowSwitch" value="true"/><boolean name="fullAccess" value="true"/>
    <boolean name="paired" value="${paired}"/><boolean name="connected" value="true"/>
    ${wireless === undefined ? '' : `<boolean name="wirelessOn" value="${wireless}"/>`}
    ${extra}</map>`)
  process.env.DSH_ADB_PREFS_PATH = file
  return new AndroidPrivilegeService({}, () => defaultMode, { resolve: () => ({ mode: sessionMode }) })
}
const observation = () => `<boolean name="wirelessKnown" value="true"/><long name="wirelessObservedAt" value="${Date.now()}"/>`

for (const paired of [false, true]) for (const on of [false, true]) {
  test(`status/real gate matrix paired=${paired}, wireless=${on}`, () => {
    const svc = service(paired, on, observation())
    const st = svc.status()
    assert.equal(st.paired, paired)
    assert.equal(st.wirelessDebugOn, on)
    assert.equal(st.wirelessDebugState, on ? 'on' : 'off')
    assert.equal(st.wirelessKnown, true)
    assert.equal(svc.gateFacts().adbReady, paired && on)
    assert.equal(svc.gateFor({}).ok, paired && on)
    assert.equal(st.tier, paired && on ? 'T1' : 'T0')
  })
}
test('independent wireless-only keys are admitted by parser', () => {
  const p = parseAdbPrefsXml(`<map><boolean name="wirelessOn" value="true"/>${observation()}</map>`)
  assert.equal(p.wirelessOn, true)
  assert.equal(p.wirelessKnown, true)
  assert.equal(p.paired, false)
  assert.equal(wirelessState(p), 'on')
  assert.ok(parseAdbPrefsXml('<map><boolean name="wirelessOn" value="false"/></map>'))
  assert.ok(parseAdbPrefsXml('<map><boolean name="wirelessKnown" value="false"/></map>'))
})
test('connected requires fresh wireless observation AND current endpoint reachability', () => {
  assert.equal(service(true, true, observation() + '<boolean name="endpointReachable" value="true"/>').status().connected, true)
  assert.equal(service(true, true, observation() + '<boolean name="endpointReachable" value="false"/>').status().connected, false)
  assert.equal(service(true, true, observation()).status().connected, false)
  assert.equal(service(true, true, '<boolean name="endpointReachable" value="true"/>').status().connected, false)
})
test('missing/legacy keys do not make historical pairing into live state', () => {
  for (const on of [undefined, true, false]) {
    const svc = service(true, on)
    assert.equal(svc.status().wirelessDebugState, 'unknown')
    assert.equal(svc.status().wirelessDebugOn, false)
    assert.equal(svc.gateFor({}).ok, false)
  }
})
test('freshness boundaries, future stamps, failed observations are unknown', () => {
  const now = 100_000
  const base = { wirelessKnown: true, wirelessOn: true, wirelessObservedAt: now }
  assert.equal(wirelessState(base, now), 'on')
  for (const p of [
    { ...base, wirelessKnown: false }, { ...base, wirelessObservedAt: undefined },
    { ...base, wirelessOn: undefined }, { ...base, wirelessObservedAt: now + 1 },
    { ...base, wirelessObservedAt: now - WIRELESS_FRESH_MS },
    { ...base, wirelessObservedAt: NaN }, { ...base, wirelessObservedAt: 0 },
  ]) assert.equal(wirelessState(p, now), 'unknown')
})
test('legacy env wireless derived from pairing cannot open the gate without observations', () => {
  process.env.DSH_ADB_PREFS_PATH = join(dir, 'missing.xml')
  process.env.DSH_ADB_WIRELESS = '1'
  process.env.DSH_ADB_PAIRED = '1'
  const svc = new AndroidPrivilegeService({}, () => 'danger-full-access')
  assert.equal(svc.status().wirelessDebugState, 'unknown')
  assert.equal(svc.gateFacts().adbReady, false)
})
for (const [defaultMode, sessionMode, allowed] of [
  ['workspace-write', 'danger-full-access', true],
  ['danger-full-access', 'read-only', false],
]) test(`deployment ${defaultMode} is NOT session ${sessionMode}`, () => {
  const svc = service(true, true, observation(), defaultMode, sessionMode)
  assert.equal(svc.status().writeMode, defaultMode)
  assert.equal(svc.gateFacts().adbReady, true)
  assert.equal(svc.gateFor({}).ok, allowed)
})
