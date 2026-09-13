/**
 * 「安卓调试授权」设置分区：三道人门状态 + 授权操作。
 *
 * 状态源：GET /api/android/privilege/status（宿主端只读 live 壳侧 SharedPreferences，
 * 3 秒轮询 + 每次变更后强制刷新）。
 * **授权变更例外——不设引擎 POST 端点**（Shizuku 对照：被提权方不得自改授权）：
 * 全部经 window.androidBridge 原生桥（setAdbAllow / setAdbPair / revokeAdbPair），
 * 由壳侧 AdbState 持久化 + 原生审计；桥不存在（桌面）时动作按钮禁用并提示。
 *
 * 安全边界（PRD F1.7）：
 *  - 门1 完全访问档位：应用不可程序化授予 → 按钮跳系统设置（androidBridge），授予后重启引擎生效；
 *  - 门2 允许访问开关：默认关闭；开启前危险明示 + 二次确认（确认后调原生桥）；
 *  - 门3 配对码：六位数字；码值绝不上审计（壳侧只记长度）；真实握手随通道接入；
 *  - 自动审批模式不构成开放条件（宿主端判定）。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AdbOperationError, EMPTY_PAIR_FORM, isIpLiteral, mergeDiscovery, pairFailText, runAdbOperation,
  type AdbOperationBridge, type PairForm, type PairOutcome,
} from './adb-model.js'

// Authorization mutations remain native-only. New APKs run pairing/discovery in bounded background jobs.
interface AndroidShellBridge extends AdbOperationBridge {
  setAdbAllow?: (enable: boolean) => void
  revokeAdbPair?: () => void
  requestAllFilesAccess?: () => void
  hasAllFilesAccess?: () => boolean
  getAdbState?: () => string
  a11yStatus?: () => string
  openA11ySettings?: () => void
  unlockRestrictedSettings?: () => string
}

/** 无障碍控制通道状态（壳侧 DeviceControlService.statusJson）。 */
interface A11yStatus {
  enabled?: boolean
  label?: string
  sdk?: number
  restrictedSettingsApplies?: boolean
  hint?: string
  tokenConfigured?: boolean
}

/** 宿主端点状态面（AdbStatus 的页面投影）。 */
interface AdbStatusView {
  tier: 'T0' | 'T1'
  fullAccess: boolean
  writeMode?: string
  wirelessDebugOn?: boolean
  wirelessDebugState?: 'on' | 'off' | 'unknown'
  allowSwitchOn?: boolean
  paired?: boolean
  connected?: boolean
  message?: string
}

/** DevSection 子槽 entry props（框架注入；本块不使用 owner share → unknown 即可）。 */
export type AdbAuthSectionProps = unknown

const STATUS_REFRESH_MS = 3000
const CONFIRM_ALLOW_TEXT = {
  on: {
    title: '开启「允许访问」？',
    desc: '开启后，安卓调试桥（ADB）授权通道由完全访问档位进入可授权状态：手机管理工具可执行输入事件、截图、包管理等 shell 级操作，且通行三段式授权门。此开关默认关闭，关闭即通道失败关闭（立即失效）。',
    ok: '开启',
  },
  off: {
    title: '关闭「允许访问」？',
    desc: '关闭后 ADB 授权通道立即失败关闭：所有需授权的手机管理操作会被拒绝，直到你再次开启并完成授权。',
    ok: '关闭',
  },
  revoke: {
    title: '回收配对？',
    desc: '断开调试连接并删除本机配对密钥，通道立即失败关闭。注：系统侧授权（adb 已配对名单）需在「无线调试」开关重新打开后才彻底清除。重启后配对本就需要重新进行（安全特性）。',
    ok: '回收',
  },
} as const

type ConfirmKind = keyof typeof CONFIRM_ALLOW_TEXT | null

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, init)
  const j = (await r.json().catch(() => null)) as { ok?: boolean; error?: string; status?: AdbStatusView } | null
  if (!r.ok || j === null || j.ok === false) {
    throw new Error(j?.error ?? `HTTP ${r.status}`)
  }
  return j as T
}

function statusFetch(): Promise<AdbStatusView> {
  return api<AdbStatusView>('/api/android/privilege/status')
}

/** 原生桥取用（壳 WebView 注入；桌面/非壳宿主为 undefined → 控件禁用）。 */
function nativeBridge(): AndroidShellBridge | undefined {
  try {
    return (window as unknown as { androidBridge?: AndroidShellBridge }).androidBridge
  } catch {
    return undefined
  }
}

/**
 * Render the ADB authorization block (DevSection child seat).
 * @param _props - composed slot props (contract: settings.dev.item entry share).
 * @returns the block element tree.
 */
export function AdbAuthSection(_props: AdbAuthSectionProps) {
  const [status, setStatus] = useState<AdbStatusView | null>(null)
  // 0.13.5 W4：无障碍通道状态（主入口；与 ADB 状态同轮询刷新）
  const [a11y, setA11y] = useState<A11yStatus | null>(null)
  // F4 双错误通道：pollError 由 3s 轮询独占（状态查询抖动），actionError 归操作动作所有
  // （此前轮询每 3 秒把操作报错一并抹掉——用户永远看不清红字就没了，2026-08-27 复盘实锤）。
  const [pollError, setPollError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const actionExpiryRef = useRef(0)
  const [okMsg, setOkMsg] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [scanning, setScanning] = useState(false)
  const [pairCode, setPairCode] = useState('')
  const [form, setForm] = useState<PairForm>({ ...EMPTY_PAIR_FORM })
  const { pairPort, connectPort, host } = form
  const [confirm, setConfirm] = useState<ConfirmKind>(null)
  const mounted = useRef(true)
  const actionGeneration = useRef(0)
  const actionRunning = useRef(false)
  const refreshGeneration = useRef(0)

  /** 操作类错误留存：15s 自动淡出（足够读完，又不必永久占屏）；新一轮操作即重置。 */
  const clearActionExpiry = useCallback(() => window.clearTimeout(actionExpiryRef.current), [])
  const raiseActionError = useCallback((text: string) => {
    clearActionExpiry()
    setActionError(text)
    actionExpiryRef.current = window.setTimeout(() => setActionError(null), 15_000)
  }, [clearActionExpiry])

  const refresh = useCallback(async () => {
    const generation = ++refreshGeneration.current
    const current = () => mounted.current && generation === refreshGeneration.current
    const bridge = nativeBridge()
    // New APK guarantees a quick cached snapshot and schedules native observation updates.
    // Do not merge a native legacy 'paired => wirelessOn' value into the engine's status projection.
    if (typeof bridge?.startAdbDiscovery === 'function') {
      try { bridge.getAdbState?.() } catch { /* HTTP remains the authoritative projection. */ }
    }
    try {
      const st = await statusFetch()
      if (current()) { setStatus(st); setPollError(null) }
    } catch {
      if (current()) { setStatus(null); setPollError('状态查询失败；暂不把上次状态当作当前事实') }
    }
    try {
      const raw = bridge?.a11yStatus?.()
      if (current() && typeof raw === 'string') setA11y(JSON.parse(raw) as A11yStatus)
    } catch { /* Native bridge unavailable; no authorization is inferred. */ }
  }, [])

  useEffect(() => {
    mounted.current = true
    void refresh()
    const timer = window.setInterval(() => void refresh(), STATUS_REFRESH_MS)
    return () => {
      mounted.current = false
      ++actionGeneration.current
      ++refreshGeneration.current
      actionRunning.current = false
      window.clearInterval(timer)
      window.clearTimeout(actionExpiryRef.current)
    }
  }, [refresh])

  const runMutation = useCallback(async (okMsgText: string, action: () => void) => {
    if (actionRunning.current || busy) return
    actionRunning.current = true
    const generation = ++actionGeneration.current
    const current = () => mounted.current && actionGeneration.current === generation
    setBusy(true)
    clearActionExpiry()
    setActionError(null)
    setOkMsg(null)
    try {
      action()
      if (current()) setOkMsg(okMsgText)
      await refresh()
    } catch {
      if (current()) raiseActionError('原生授权变更未确认；请刷新状态后核对')
    } finally {
      if (current()) { actionRunning.current = false; setBusy(false) }
    }
  }, [busy, refresh, raiseActionError, clearActionExpiry])

  const askAllow = useCallback((enabled: boolean) => setConfirm(enabled ? 'on' : 'off'), [])
  const askRevoke = useCallback(() => setConfirm('revoke'), [])
  const cancelConfirm = useCallback(() => setConfirm(null), [])

  const doConfirm = useCallback(async () => {
    if (confirm === null) return
    const kind = confirm
    setConfirm(null)
    const bridge = nativeBridge()
    if (kind === 'revoke') {
      if (typeof bridge?.revokeAdbPair !== 'function') { raiseActionError('原生回收配对功能不可用'); return }
      await runMutation('已提交回收配对，请核对刷新后的状态', () => bridge.revokeAdbPair!())
    } else {
      if (typeof bridge?.setAdbAllow !== 'function') { raiseActionError('原生允许访问开关不可用'); return }
      await runMutation('已提交开关变更，请核对刷新后的状态', () => bridge.setAdbAllow!(kind === 'on'))
    }
  }, [confirm, runMutation, raiseActionError])

  const scanPorts = useCallback(async () => {
    if (actionRunning.current || busy) return
    actionRunning.current = true
    const generation = ++actionGeneration.current
    const current = () => mounted.current && actionGeneration.current === generation
    setScanning(true)
    clearActionExpiry()
    setActionError(null)
    setOkMsg('正在发现本机配对/连接服务…')
    try {
      const result = await runAdbOperation(nativeBridge(), 'discovery', undefined, {
        isCurrent: current,
        onLegacy: () => setOkMsg('旧版应用使用同步扫描，期间页面可能暂时阻塞'),
      })
      if (!current()) return
      const next = mergeDiscovery(form, result)
      setForm(next.form)
      setOkMsg(next.message)
      await refresh()
    } catch (e) {
      if (current()) { setOkMsg(null); raiseActionError(e instanceof AdbOperationError ? e.message : '端口扫描未完成；请手动核对两个端口') }
    } finally {
      if (current()) { actionRunning.current = false; setScanning(false) }
    }
  }, [form, busy, refresh, raiseActionError, clearActionExpiry])

  const submitPair = useCallback(async () => {
    if (!/^\d{6}$/.test(pairCode)) {
      raiseActionError('配对码需为系统「无线调试」弹窗中的 6 位数字')
      return
    }
    const p = Number(pairPort)
    const c = Number(connectPort)
    if (pairPort === '' || connectPort === '') {
      raiseActionError('请先「自动扫描端口」，或手动填写系统「无线调试」弹窗中的配对端口与连接端口')
      return
    }
    if (!Number.isInteger(p) || !Number.isInteger(c) || p < 1 || p > 65535 || c < 1 || c > 65535) {
      raiseActionError('端口无效：请抄录「无线调试」弹窗中的配对端口与连接端口（1-65535）')
      return
    }
    if (!isIpLiteral(host)) {
      raiseActionError('请填写本机 IP 字面量（默认 127.0.0.1，不含端口），不可使用其他设备或主机名')
      return
    }
    if (actionRunning.current || busy) return
    actionRunning.current = true
    const generation = ++actionGeneration.current
    const current = () => mounted.current && actionGeneration.current === generation
    setBusy(true)
    clearActionExpiry()
    setActionError(null)
    setOkMsg('正在配对；请保持当前配对码窗口有效，不要重复提交')
    try {
      const result = await runAdbOperation(nativeBridge(), 'pair', { code: pairCode, pairPort: p, connectPort: c, host }, {
        isCurrent: current,
        onLegacy: () => setOkMsg('旧版应用使用同步配对，期间页面可能阻塞；请勿重复提交'),
      })
      if (!current()) return
      const j = result as unknown as PairOutcome
      if (!j.ok) { setOkMsg(null); raiseActionError(pairFailText(j)); return }
      setOkMsg(j.reason === 'paired-connect-unconfirmed'
        ? '配对成功，但连接尚未确认；请核对无线调试主页的连接端口'
        : '配对成功（不等同于后续设备命令执行成功）')
      setPairCode('')
      await refresh()
    } catch (e) {
      if (current()) { setOkMsg(null); raiseActionError(e instanceof AdbOperationError ? e.message : '配对结果未知；请先刷新状态，勿重复提交') }
    } finally {
      if (current()) { actionRunning.current = false; setBusy(false) }
    }
  }, [pairCode, pairPort, connectPort, host, busy, refresh, raiseActionError, clearActionExpiry])

  const requestAllFiles = useCallback(() => {
    try {
      ;(window as unknown as { androidBridge?: AndroidShellBridge }).androidBridge?.requestAllFilesAccess?.()
    } catch {
      /* bridge absent: desktop fallback no-op */
    }
  }, [])

  /** 0.13.5 W4：走官方 Intent 唤起系统无障碍设置页（ACTION_ACCESSIBILITY_SETTINGS）。 */
  const openA11y = useCallback(() => {
    const bridge = nativeBridge()
    if (!bridge?.openA11ySettings) {
      raiseActionError('需在安卓壳应用内开启（原生桥不可用）')
      return
    }
    setActionError(null)
    try {
      bridge.openA11ySettings()
      setOkMsg('已打开系统无障碍设置：找到「DSH 设备控制」并开启（开启后本页状态会自动刷新）')
    } catch (e) {
      raiseActionError('打开系统设置失败：' + String((e as Error).message))
    }
  }, [raiseActionError])

  /** 0.13.5 W4：Android 13+ 受限设置一键解锁（appops，走壳侧 ADB 通道）。 */
  const unlockRestricted = useCallback(async () => {
    const bridge = nativeBridge()
    if (!bridge?.unlockRestrictedSettings) {
      raiseActionError('需在安卓壳应用内解锁（原生桥不可用）')
      return
    }
    setBusy(true)
    setActionError(null)
    setOkMsg(null)
    try {
      const raw = bridge.unlockRestrictedSettings()
      const parsed = typeof raw === 'string' && raw.startsWith('{') ? (JSON.parse(raw) as { ok?: boolean; message?: string }) : null
      if (parsed?.ok === true) setOkMsg(parsed.message ?? '已解锁受限设置')
      else raiseActionError(parsed?.message ?? '解锁失败：可稍后重试，或在系统设置里手动允许')
      await refresh()
    } catch (e) {
      raiseActionError('解锁失败：' + String((e as Error).message))
    } finally {
      setBusy(false)
    }
  }, [refresh, raiseActionError])

  const onKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') cancelConfirm()
    },
    [cancelConfirm],
  )

  // 0.13.8 #172：通道就绪 = 引擎级三道门（完全访问 + 允许开关 + 配对/无线调试）——
  // 部署默认写面档位（tier）只是视图字段，不再决定「已授权」展示。
  const granted = status?.fullAccess === true && status?.allowSwitchOn === true &&
    status?.paired === true && status?.wirelessDebugOn === true
  const confirmText = confirm !== null ? CONFIRM_ALLOW_TEXT[confirm] : null

  return (
    <div className="adb-auth" data-plugin="adb-auth" onKeyDown={onKeyDown}>
      {/* 0.13.5 W4：无障碍通道是**主入口**（PRD-0.13.2 §3.3 B3——授权形式简化：一次系统开关） */}
      <div className={a11y?.enabled ? 'adb-auth-tier adb-auth-tier-ok' : 'adb-auth-tier adb-auth-tier-bad'}>
        <span>{a11y?.enabled ? '无障碍通道已开启（推荐）' : '无障碍通道未开启（推荐）'}</span>
        <span className="adb-auth-tier-sub">
          {a11y?.hint ?? '开启后 AI 可用语义方式读取界面并点击 / 输入 / 滚动'}
        </span>
      </div>
      <div className="adb-auth-actions">
        <button type="button" className="adb-auth-btn" disabled={busy || scanning} onClick={openA11y}>
          {a11y?.enabled ? '查看系统无障碍设置' : '去开启无障碍服务'}
        </button>
        {a11y?.restrictedSettingsApplies === true && (
          <button type="button" className="adb-auth-btn" disabled={busy || scanning} onClick={() => void unlockRestricted()}>
            一键解锁受限设置
          </button>
        )}
      </div>
      {a11y?.restrictedSettingsApplies === false && (
        <p className="adb-auth-note">
          当前系统（Android {a11y?.sdk ?? '?'}）没有「受限设置」限制：直接到 系统设置 → 无障碍 → 已下载的服务
          开启「DSH 设备控制」即可。
        </p>
      )}
      {a11y?.restrictedSettingsApplies === true && (
        <p className="adb-auth-note">
          Android 13 及以上：侧载应用的「无障碍」开关可能被系统「受限设置」挡住——先点「一键解锁受限设置」
          （走本机 ADB 通道执行 appops，需要已配对的 ADB 通道），再开启无障碍服务。
        </p>
      )}
      <p className="adb-auth-note">
        无障碍通道只需在系统设置里开启一次「DSH 设备控制」，即可使用 dump / click / input / scroll 语义操作
        （不依赖 uiautomator、不受窗口动画阻塞）。下方 ADB 通道是高级/脚本面（shell 执行、原图截图、
        pm / dumpsys 等系统面）。两条通道任一成立即可，会话档位仍需完全访问。
      </p>

      <details className="adb-auth-details">
        <summary>ADB 通道（高级/脚本面）：shell 执行、原图截图、系统面</summary>
        <p className="adb-auth-note">
        安卓调试授权三道门：完全访问档位（前置）→ 系统无线调试开启 → 应用内「允许访问」开关 → 输入配对码。
        配对码与配对端口来自「使用配对码配对」窗口；连接端口来自「无线调试」主页，两者不能混用。
        地址默认 127.0.0.1，也可填写本机 IP；原生桥会拒绝其他设备地址。配对码不进入诊断回显。
        新版应用在后台握手并轮询结果；旧版桥仍是同步调用，页面可能阻塞，升级应用可避免。
        等待超时不代表原生操作已取消：先刷新状态，不要重复提交。
      </p>

      <div className={granted ? 'adb-auth-tier adb-auth-tier-ok' : 'adb-auth-tier adb-auth-tier-bad'}>
        <span>{granted ? 'ADB 通道就绪（引擎级三道门已齐）' : 'ADB 通道未就绪（引擎级三道门未齐）'}</span>
        {status?.message ? <span className="adb-auth-tier-sub">{status.message}</span> : <span className="adb-auth-tier-sub">{status ? '按会话权限判定实际能力' : '状态待查询'}</span>}
      </div>

      <div className="adb-auth-gate">
        <div className="adb-auth-gate-main">
          <span className="adb-auth-gate-title">门1 · 完全访问档位（All Files Access）</span>
          <span className="adb-auth-gate-desc">在系统设置授予「所有文件访问」；授予后重启引擎生效</span>
        </div>
        {status?.fullAccess
          ? <span className="adb-auth-chip adb-auth-chip-ok">已授予</span>
          : (
            <>
              <span className="adb-auth-chip adb-auth-chip-bad">未授予</span>
              <button type="button" className="adb-auth-btn" onClick={requestAllFiles}>去授权</button>
            </>
          )}
      </div>

      <div className="adb-auth-gate">
        <div className="adb-auth-gate-main">
          <span className="adb-auth-gate-title">部署默认档位（不是当前会话档位）</span>
          <span className="adb-auth-gate-desc">此处仅显示部署默认值。实际设备能力按每个会话的 /permission 实时判定，要求 danger-full-access；无需为此修改全局默认值，自动审批不构成开放条件</span>
        </div>
        {status?.writeMode === 'danger-full-access'
          ? <span className="adb-auth-chip adb-auth-chip-ok">danger-full-access</span>
          : <span className="adb-auth-chip adb-auth-chip-bad">{status?.writeMode ?? '未知'}</span>}
      </div>

      <div className="adb-auth-gate">
        <div className="adb-auth-gate-main">
          <span className="adb-auth-gate-title">门2 · 系统无线调试</span>
          <span className="adb-auth-gate-desc">系统开关观测与历史配对分开；未发现服务、观测过期或旧壳不支持时显示未知</span>
        </div>
        {status?.wirelessDebugState === 'on'
          ? <span className="adb-auth-chip adb-auth-chip-ok">已开启</span>
          : <span className="adb-auth-chip adb-auth-chip-bad">{status?.wirelessDebugState === 'off' ? '已关闭' : '未知（待观测）'}</span>}
      </div>

      <label className="adb-auth-switch-row">
        <input
          type="checkbox"
          checked={status?.allowSwitchOn ?? false}
          disabled={busy || scanning}
          onChange={(e) => askAllow(e.target.checked)}
        />
        <span>门3 · 应用内「允许访问」开关（关闭即失败关闭）</span>
      </label>

      {status?.paired ? (
        <div className="adb-auth-pair">
          <span className="adb-auth-chip adb-auth-chip-ok">已配对</span>
          {status.connected === true
            ? <span className="adb-auth-chip adb-auth-chip-ok">已连接</span>
            : status.connected === false
              ? <span className="adb-auth-chip adb-auth-chip-bad">连接待确认</span>
              : null}
          <button type="button" className="adb-auth-btn adb-auth-btn-danger" disabled={busy || scanning} onClick={askRevoke}>
            回收配对
          </button>
        </div>
      ) : (
        <div className="adb-auth-pair">
          <input
            className="adb-auth-input"
            aria-label="本机 IP 地址"
            placeholder="本机 IP（默认 127.0.0.1）"
            value={host}
            disabled={busy || scanning}
            autoComplete="off"
            onChange={(e) => setForm((f) => ({ ...f, host: e.target.value.trim(), hostSource: 'manual' }))}
          />
          <input
            className="adb-auth-input"
            inputMode="numeric"
            type="password"
            autoComplete="off"
            aria-label="6 位配对码"
            disabled={busy || scanning}
            maxLength={6}
            placeholder="输入 6 位配对码"
            value={pairCode}
            onChange={(e) => setPairCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          />
          <input
            className="adb-auth-input adb-auth-input-port"
            inputMode="numeric"
            placeholder="配对码窗口端口"
            aria-label="配对端口"
            disabled={busy || scanning}
            value={pairPort}
            onChange={(e) => setForm((f) => ({ ...f, pairPort: e.target.value.replace(/\D/g, '').slice(0, 5), pairSource: 'manual' }))}
          />
          <input
            className="adb-auth-input adb-auth-input-port"
            inputMode="numeric"
            placeholder="无线调试主页连接端口"
            aria-label="连接端口"
            disabled={busy || scanning}
            value={connectPort}
            onChange={(e) => setForm((f) => ({ ...f, connectPort: e.target.value.replace(/\D/g, '').slice(0, 5), connectSource: 'manual' }))}
          />
          <button
            type="button"
            className="adb-auth-btn"
            disabled={busy || scanning}
            onClick={() => void scanPorts()}
            title="自动扫描系统无线调试的配对/连接端口（无需手动抄录）"
          >
            {scanning ? '扫描中…' : '自动扫描端口'}
          </button>
          <button
            type="button"
            className="adb-auth-btn"
            disabled={busy || scanning || pairCode.length !== 6}
            onClick={() => void submitPair()}
            title={pairCode.length !== 6 ? '先输入 6 位配对码' : '发起配对（可先自动扫描端口，或手动填写）'}
          >
            配对
          </button>
        </div>
      )}
      </details>

      {(actionError ?? pollError) !== null && <p className="adb-auth-error">{actionError ?? pollError}</p>}
      {okMsg !== null && <p className="adb-auth-ok">{okMsg}</p>}

      <div className="adb-auth-actions">
        <button type="button" className="adb-auth-btn" disabled={busy || scanning} onClick={() => void refresh()}>刷新状态</button>
      </div>

      {confirmText !== null && (
        <div
          className="adb-auth-modal-overlay"
          role="dialog"
          aria-modal="true"
          aria-label={confirmText.title}
          onClick={cancelConfirm}
        >
          <div
            className="adb-auth-modal"
            role="document"
            onClick={(e) => e.stopPropagation()}
          >
            <p className="adb-auth-modal-title">{confirmText.title}</p>
            <p className="adb-auth-modal-desc">{confirmText.desc}</p>
            <div className="adb-auth-modal-actions">
              <button type="button" className="adb-auth-btn" autoFocus onClick={cancelConfirm}>取消</button>
              <button
                type="button"
                className={confirm === 'revoke' ? 'adb-auth-btn adb-auth-btn-danger' : 'adb-auth-btn'}
                onClick={() => void doConfirm()}
              >{confirmText.ok}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
