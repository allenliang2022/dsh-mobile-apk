/** Pure, testable settings/bridge contract. No React, device actions, or raw diagnostics. */
export interface PairOutcome {
  ok: boolean
  reason?: string
  message?: string | null
}
export interface AdbOperationBridge {
  discoverAdbPorts?: () => string
  setAdbPair?: (code: string, pairPort: number, connectPort: number) => unknown
  startAdbDiscovery?: () => string
  startAdbPair?: (code: string, pairPort: number, connectPort: number, host: string) => string
  getAdbOperation?: (requestId: string) => string
}
export interface PairInput { code: string; pairPort: number; connectPort: number; host: string }
export interface PairForm {
  host: string
  pairPort: string
  connectPort: string
  pairSource: 'manual' | 'scan'
  connectSource: 'manual' | 'scan'
  hostSource: 'manual' | 'scan'
}
export const EMPTY_PAIR_FORM: PairForm = {
  host: '127.0.0.1', pairPort: '', connectPort: '', pairSource: 'manual', connectSource: 'manual', hostSource: 'scan',
}

/** Syntax only: native and engine additionally verify LOCAL interface membership. */
export function isIpLiteral(host: string): boolean {
  if (!host || host.length > 128 || host !== host.trim()) return false
  const plain = host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host
  if (/^(?:\d{1,3}\.){3}\d{1,3}$/.test(plain)) {
    return plain.split('.').every((s) => String(Number(s)) === s && Number(s) <= 255)
  }
  const [body, scope, ...extra] = plain.split('%')
  if (extra.length || (scope !== undefined && !/^[a-zA-Z0-9_.-]{1,64}$/.test(scope))) return false
  if (!body.includes(':') || !/^[0-9a-f:.]+$/i.test(body)) return false
  try { return new URL(`http://[${body}]/`).hostname.startsWith('[') } catch { return false }
}
function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}
function port(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 65535 ? value : undefined
}

/** Candidates are diagnostics, NEVER a source of port roles. Clear obsolete scan-derived fields only. */
export function mergeDiscovery(previous: PairForm, value: unknown): { form: PairForm; message: string } {
  const j = object(value)
  if (!j) throw new Error('扫描结果格式无效；请手动填写两个端口')
  if (j.ok === false) return { form: { ...previous }, message: '端口发现未完成；请稍后重试或手动填写。未将其视为无线调试关闭' }
  const pair = port(j.pair)
  const connect = port(j.connect)
  const hosts = [pair !== undefined ? j.pairHost : undefined, connect !== undefined ? j.connectHost : undefined]
    .filter((x): x is string => typeof x === 'string')
  if (hosts.some((h) => !isIpLiteral(h)) || (hosts.length > 1 && hosts[0] !== hosts[1])) {
    return { form: { ...previous }, message: '发现的配对/连接地址不一致或无效；未自动填入，请核对本机 IP 与两个端口' }
  }
  const discoveredHost = typeof j.host === 'string' && isIpLiteral(j.host) ? j.host : hosts[0]
  if (discoveredHost && previous.hostSource === 'manual' && previous.host !== discoveredHost) {
    return { form: { ...previous }, message: '发现地址与手动选择的 IP 不同；已保留手填值，请核对地址与对应端口后再配对' }
  }
  const form = { ...previous }
  if (discoveredHost) { form.host = discoveredHost; form.hostSource = 'scan' }
  if (pair !== undefined) { form.pairPort = String(pair); form.pairSource = 'scan' }
  else if (previous.pairSource === 'scan') form.pairPort = ''
  if (connect !== undefined) { form.connectPort = String(connect); form.connectSource = 'scan' }
  else if (previous.connectSource === 'scan') form.connectPort = ''
  const message = pair !== undefined && connect !== undefined
    ? `已发现配对端口 ${pair}、连接端口 ${connect}；请核对当前系统页面`
    : pair !== undefined
      ? `已发现配对端口 ${pair}；连接端口尚未发现，请从「无线调试」主页填写（保留手填值）`
      : connect !== undefined
        ? `已发现连接端口 ${connect}；请打开「使用配对码配对」窗口取得配对端口（保留手填值）`
        : '本轮未发现可识别的配对/连接服务（不代表无线调试已关闭）；请打开配对码窗口重扫或手动填写。手填值已保留'
  return { form, message }
}

/** Never echo native message/stdout: old APKs may put the submitted code in diagnostics. */
export function pairFailText(j: PairOutcome): string {
  switch (j.reason) {
    case 'invalid-code': return '配对码需为当前系统弹窗中的 6 位数字'
    case 'invalid-port': return '端口无效；请分别核对配对码弹窗端口与无线调试主页连接端口'
    case 'invalid-host':
    case 'non-local-host': return '地址必须是本机 IP 或 127.0.0.1，不能连接其他设备'
    case 'adb-missing': return 'ADB 客户端未就绪；请检查应用安装的运行环境'
    case 'window-closed': return '无法连接配对端口；请核对本机 IP、当前配对端口，并保持配对码窗口打开'
    case 'protocol-fault': return 'ADB 协议握手失败；这不能单独证明配对码或 IP 错误。请核对端口类型，并检查本地调试服务'
    case 'server-not-ready': return '本地调试服务未就绪；请稍后重试'
    case 'handshake-timeout': return '配对握手超时；请确认当前配对码窗口仍打开，再手动重试'
    default: return '配对失败；请核对当前配对码、本机 IP 与两个端口。原始诊断未回显以保护配对码'
  }
}
export class AdbOperationError extends Error {
  constructor(readonly reason: 'unavailable' | 'legacy-host' | 'start' | 'poll' | 'invalid' | 'timeout' | 'stale') {
    super({
      unavailable: '当前应用没有所需原生桥；请在安卓壳内操作或升级应用',
      'legacy-host': '旧版应用仅支持 127.0.0.1；使用本机其他 IP 需升级应用',
      start: '原生操作未启动（可能已有操作进行中）；请稍后查看状态，勿连续提交',
      poll: '原生操作状态查询失败；操作可能仍在运行，请先刷新状态，勿自动重复提交',
      invalid: '原生桥返回格式不兼容；未将其视为成功，请升级应用或核对状态',
      timeout: '等待原生操作超时，但不代表原生操作已取消；请先刷新状态，勿连续重复配对',
      stale: '操作结果已过期',
    }[reason])
  }
}
interface WaitOptions {
  isCurrent?: () => boolean
  sleep?: (ms: number) => Promise<void>
  now?: () => number
  timeoutMs?: number
  onLegacy?: () => void
}
function json(raw: unknown): Record<string, unknown> {
  try {
    const j = typeof raw === 'string' ? object(JSON.parse(raw)) : null
    if (j) return j
  } catch { /* Do not expose exception text, which may contain credentials. */ }
  throw new AdbOperationError('invalid')
}

/** Bounded ticket polling; generation guards; never re-submit automatically after an error. */
export async function runAdbOperation(
  bridge: AdbOperationBridge | undefined,
  kind: 'discovery' | 'pair',
  input?: PairInput,
  options: WaitOptions = {},
): Promise<Record<string, unknown>> {
  const current = options.isCurrent ?? (() => true)
  const check = () => { if (!current()) throw new AdbOperationError('stale') }
  check()
  if (!bridge) throw new AdbOperationError('unavailable')
  const asyncStart = kind === 'discovery' ? bridge.startAdbDiscovery : bridge.startAdbPair
  if (typeof asyncStart !== 'function' || typeof bridge.getAdbOperation !== 'function') {
    // Only fall back when capability is absent, never after a failed ticket/handshake.
    if (kind === 'pair' && input?.host !== '127.0.0.1') throw new AdbOperationError('legacy-host')
    const legacy = kind === 'discovery' ? bridge.discoverAdbPorts : bridge.setAdbPair
    if (typeof legacy !== 'function') throw new AdbOperationError('unavailable')
    options.onLegacy?.()
    check()
    let raw: unknown
    try {
      raw = kind === 'discovery' ? bridge.discoverAdbPorts!()
        : bridge.setAdbPair!(input!.code, input!.pairPort, input!.connectPort)
    } catch { throw new AdbOperationError('start') }
    check()
    const result = json(raw)
    if (kind === 'pair' && typeof result.ok !== 'boolean') throw new AdbOperationError('invalid')
    return result
  }
  let ticket: Record<string, unknown>
  try {
    ticket = json(kind === 'discovery' ? bridge.startAdbDiscovery!()
      : bridge.startAdbPair!(input!.code, input!.pairPort, input!.connectPort, input!.host))
  } catch { throw new AdbOperationError('start') }
  if (ticket.ok !== true || typeof ticket.requestId !== 'string' || !ticket.requestId) throw new AdbOperationError('start')
  const now = options.now ?? Date.now
  const deadline = now() + (options.timeoutMs ?? (kind === 'pair' ? 210_000 : 20_000))
  const sleep = options.sleep ?? ((ms) => new Promise<void>((resolve) => setTimeout(resolve, ms)))
  for (;;) {
    check()
    if (now() >= deadline) throw new AdbOperationError('timeout')
    let poll: Record<string, unknown>
    try { poll = json(bridge.getAdbOperation(ticket.requestId)) } catch { throw new AdbOperationError('poll') }
    check()
    if (poll.ok !== true) throw new AdbOperationError('poll')
    if (poll.kind !== kind || poll.requestId !== ticket.requestId) throw new AdbOperationError('invalid')
    if (poll.state === 'complete') {
      const result = object(poll.result)
      if (!result || (kind === 'pair' && typeof result.ok !== 'boolean')) throw new AdbOperationError('invalid')
      return result
    }
    if (poll.state !== 'running') throw new AdbOperationError('invalid')
    await sleep(Math.min(350, Math.max(1, deadline - now())))
  }
}
