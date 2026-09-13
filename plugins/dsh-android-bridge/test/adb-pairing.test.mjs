import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EMPTY_PAIR_FORM, mergeDiscovery, runAdbOperation, pairFailText } from '../lib/client/adb-model.js'

const empty = () => ({ ...EMPTY_PAIR_FORM })
const input = { code: '123456', pairPort: 40001, connectPort: 37951, host: '127.0.0.1' }
const encode = JSON.stringify
const tick = async () => {}
const errorReason = (reason) => (e) => e.reason === reason

for (const [name, result, pair, connect] of [
  ['connect-only property', { pair: null, connect: 37951, candidates: [] }, '', '37951'],
  ['connect-only NSD', { pair: null, connect: 37951, candidates: [37951] }, '', '37951'],
  ['pair-only', { pair: 40001, connect: null, candidates: [40001] }, '40001', ''],
  ['duplicate untyped', { candidates: [40001, 40001] }, '', ''],
  ['mixed candidates', { pair: 40001, candidates: [37951, 40001] }, '40001', ''],
  ['typed complete', { pair: 40001, connect: 37951, candidates: [1, 2] }, '40001', '37951'],
  ['bad ports', { pair: 0, connect: 65536, candidates: [40001, 37951] }, '', ''],
  ['strings/fractions', { pair: '40001', connect: 37951.5 }, '', ''],
]) test(`typed discovery: ${name}`, () => {
  const out = mergeDiscovery(empty(), result)
  assert.equal(out.form.pairPort, pair)
  assert.equal(out.form.connectPort, connect)
  assert.doesNotMatch(out.message, /请确认.*已开启/)
})
test('missing roles preserve manual values but clear stale scan values', () => {
  const manual = { ...empty(), pairPort: '40002', connectPort: '37952' }
  assert.deepEqual(mergeDiscovery(manual, {}).form, manual)
  const scanned = mergeDiscovery(empty(), { pair: 40001, connect: 37951 }).form
  assert.equal(mergeDiscovery(scanned, { connect: 37952 }).form.pairPort, '')
  assert.equal(mergeDiscovery(scanned, { pair: 40002 }).form.connectPort, '')
  assert.match(mergeDiscovery(empty(), { connect: 37951 }).message, /使用配对码配对/)
  assert.match(mergeDiscovery(empty(), {}).message, /不代表无线调试已关闭/)
})
test('do not mix hosts, override manual hosts, or consume arbitrary candidate diagnostics', () => {
  const manual = { ...empty(), host: '192.0.2.10', hostSource: 'manual', pairPort: '40002' }
  assert.deepEqual(mergeDiscovery(manual, { pair: 40001, host: '127.0.0.1' }).form, manual)
  assert.deepEqual(mergeDiscovery(empty(), { pair: 40001, connect: 37951, pairHost: '127.0.0.1', connectHost: '192.0.2.10' }).form, empty())
  assert.equal(mergeDiscovery(empty(), { pair: 40001, host: '192.0.2.10' }).form.host, '192.0.2.10')
  assert.deepEqual(mergeDiscovery(manual, { ok: false, message: input.code }).form, manual)
  assert.doesNotMatch(mergeDiscovery(manual, { ok: false, message: input.code }).message, /123456/)
})
test('pair errors never echo raw credentials or claim protocol-fault repair success', () => {
  for (const reason of ['unknown', undefined, 'protocol-fault', 'window-closed']) {
    const text = pairFailText({ ok: false, reason, message: `raw pairing code ${input.code}` })
    assert.doesNotMatch(text, /123456|已自动重建|窗口已关闭/)
  }
  assert.match(pairFailText({ ok: false, reason: 'protocol-fault' }), /不能单独证明/)
})

test('new pair tickets poll once running then complete, with correct host and no legacy call', async () => {
  const calls = []
  let polls = 0
  const bridge = {
    startAdbPair: (...args) => { calls.push(args); return encode({ ok: true, requestId: 'p1' }) },
    setAdbPair: () => { throw Error('must not fall back') },
    getAdbOperation: (id) => {
      assert.equal(id, 'p1')
      return encode(++polls === 1 ? { ok: true, requestId: 'p1', state: 'running', kind: 'pair' }
        : { ok: true, requestId: 'p1', state: 'complete', kind: 'pair', result: { ok: true, reason: 'paired-connect-unconfirmed' } })
    },
  }
  const result = await runAdbOperation(bridge, 'pair', input, { sleep: tick })
  assert.deepEqual(calls, [[input.code, 40001, 37951, '127.0.0.1']])
  assert.equal(result.reason, 'paired-connect-unconfirmed')
  assert.equal(polls, 2)
})
test('new discovery result is typed and partial, not fabricated', async () => {
  const result = await runAdbOperation({
    startAdbDiscovery: () => encode({ ok: true, requestId: 'd1' }),
    getAdbOperation: () => encode({ ok: true, requestId: 'd1', state: 'complete', kind: 'discovery', result: { connect: 37951, pair: null } }),
  }, 'discovery')
  assert.equal(result.pair, null)
  assert.equal(result.connect, 37951)
})
test('legacy fallback is explicit, loopback-only and demands structured pair success', async () => {
  let legacy = 0
  const bridge = { setAdbPair: () => encode({ ok: true, reason: 'paired' }) }
  assert.equal((await runAdbOperation(bridge, 'pair', input, { onLegacy: () => legacy++ })).ok, true)
  assert.equal(legacy, 1)
  await assert.rejects(runAdbOperation(bridge, 'pair', { ...input, host: '192.0.2.10' }), errorReason('legacy-host'))
  for (const raw of [undefined, true, false, 'true', '{', '{}', '{"ok":"true"}']) {
    await assert.rejects(runAdbOperation({ setAdbPair: () => raw }, 'pair', input), errorReason('invalid'))
  }
  assert.equal((await runAdbOperation({ discoverAdbPorts: () => '{"connect":37951}' }, 'discovery')).connect, 37951)
  await assert.rejects(runAdbOperation(undefined, 'pair', input), errorReason('unavailable'))
  await assert.rejects(runAdbOperation({}, 'discovery'), errorReason('unavailable'))
})
test('failed or malformed new ticket never triggers legacy or automatic retry', async () => {
  for (const ticket of ['{}', '{', encode({ ok: false, message: input.code }), encode({ ok: true, requestId: '' })]) {
    let starts = 0
    await assert.rejects(runAdbOperation({
      startAdbPair: () => { starts++; return ticket },
      getAdbOperation: () => { throw Error('should not poll') },
      setAdbPair: () => { throw Error('must not use legacy') },
    }, 'pair', input), (e) => e.reason === 'start' && !e.message.includes(input.code))
    assert.equal(starts, 1)
  }
})
test('poll errors and invalid kinds/results fail closed without raw exception leakage', async () => {
  for (const response of [
    { ok: false, message: input.code }, { ok: true, requestId: 'p1', state: 'complete', kind: 'discovery', result: {} },
    { ok: true, requestId: 'p1', state: 'complete', kind: 'pair', result: {} }, { ok: true, requestId: 'p1', state: 'pending', kind: 'pair' },
    { ok: true, requestId: 'wrong-id', state: 'complete', kind: 'pair', result: { ok: true } },
    { ok: true, state: 'complete', kind: 'pair', result: { ok: true } },
  ]) await assert.rejects(runAdbOperation({
    startAdbPair: () => '{"ok":true,"requestId":"p1"}', getAdbOperation: () => encode(response),
  }, 'pair', input), (e) => ['poll', 'invalid'].includes(e.reason) && !e.message.includes(input.code))
})
test('bounded timeout does not claim native cancellation or resubmit', async () => {
  let time = 0, starts = 0
  await assert.rejects(runAdbOperation({
    startAdbPair: () => { starts++; return '{"ok":true,"requestId":"p1"}' },
    getAdbOperation: () => '{"ok":true,"requestId":"p1","state":"running","kind":"pair"}',
  }, 'pair', input, { now: () => time, timeoutMs: 500, sleep: async (ms) => { time += ms } }),
  (e) => e.reason === 'timeout' && /不代表原生操作已取消/.test(e.message))
  assert.equal(starts, 1)
})
test('unmount/new-generation cancels polling and does not return stale completion', async () => {
  let current = true, polls = 0
  await assert.rejects(runAdbOperation({
    startAdbDiscovery: () => '{"ok":true,"requestId":"d1"}',
    getAdbOperation: () => { polls++; return '{"ok":true,"requestId":"d1","state":"running","kind":"discovery"}' },
  }, 'discovery', undefined, { isCurrent: () => current, sleep: async () => { current = false } }), errorReason('stale'))
  assert.equal(polls, 1)
  await assert.rejects(runAdbOperation({}, 'discovery', undefined, { isCurrent: () => false }), errorReason('stale'))
})
