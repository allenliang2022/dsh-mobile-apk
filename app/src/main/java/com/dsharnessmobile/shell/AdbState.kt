package com.dsharnessmobile.shell

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Native authority for local-device ADB pairing. Probes/discovery never grant pairing. */
object AdbState {
  private const val PREFS = "dsh-adb"
  private const val KEY_ALLOW = "allowSwitch"
  private const val KEY_PAIRED = "paired"
  private const val KEY_PAIR_PORT = "pairPort"
  private const val KEY_CONNECT_PORT = "connectPort"
  private const val KEY_CONNECT_HOST = "connectHost"
  private const val KEY_CONNECTED = "connected"
  private const val KEY_FULLACCESS = "fullAccess"
  private const val KEY_WIRELESS_ON = "wirelessOn"
  private const val KEY_WIRELESS_KNOWN = "wirelessKnown"
  private const val KEY_WIRELESS_OBSERVED_AT = "wirelessObservedAt"
  private const val KEY_ENDPOINT_REACHABLE = "endpointReachable"
  private const val NSD_TIMEOUT_MS = 2_000L
  private const val WIRELESS_PROBE_TTL_MS = 2_000L
  private const val WIRELESS_PROBE_TIMEOUT_MS = 400
  private val PAIR_CODE = Regex("^\\d{6}$")
  private val authorizationRevision = AtomicLong(0)
  private val authorizationLock = Any()

  data class PairResult(
    val ok: Boolean, val paired: Boolean, val guidance: String?, val reason: String,
    val phase: String = "validation", val recovery: String = "none",
    val exitCode: Int? = null, val timedOut: Boolean = false,
  )

  /** Legacy five-argument calls retain loopback; an explicit host is never silently substituted. */
  fun pairWithCodeJson(context: Context, engine: EngineManager, code: String, pairPort: Int, connectPort: Int, host: String = "127.0.0.1"): String {
    val r = pairWithCode(context, engine, code, pairPort, connectPort, host)
    return JSONObject().put("ok", r.ok).put("paired", r.paired)
      .put("reason", r.reason).put("phase", r.phase).put("recovery", r.recovery)
      .put("exitCode", r.exitCode ?: JSONObject.NULL).put("timedOut", r.timedOut)
      .put("message", r.guidance ?: JSONObject.NULL).toString()
  }

  fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  fun fullAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

  fun syncFullAccess(context: Context) {
    val value = fullAccess()
    val p = prefs(context)
    val previous = p.getBoolean(KEY_FULLACCESS, false)
    p.edit().putBoolean(KEY_FULLACCESS, value).apply()
    if (previous != value) LogCollector.log("dsh-adb", "full-access synced: $previous -> $value")
  }

  fun allowSwitch(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW, false)
  fun setAllowSwitch(context: Context, enable: Boolean) {
    synchronized(authorizationLock) {
      authorizationRevision.incrementAndGet()
      prefs(context).edit().putBoolean(KEY_ALLOW, enable).apply()
    }
    syncFullAccess(context)
    AdbAudit.log(context, "adb-allow-switch", mapOf("allow" to enable))
  }
  fun paired(context: Context): Boolean = prefs(context).getBoolean(KEY_PAIRED, false)
  fun setPaired(context: Context, value: Boolean) {
    // Compatibility clear-only API: no Boolean setter may manufacture a pairing grant.
    require(!value) { "A pairing grant requires pairWithCode" }
    synchronized(authorizationLock) {
      authorizationRevision.incrementAndGet()
      prefs(context).edit().putBoolean(KEY_PAIRED, false).apply()
    }
    requestStatusRefresh(context)
    syncFullAccess(context)
  }
  fun pairPort(context: Context): String? = prefs(context).getString(KEY_PAIR_PORT, null)
  fun connectPort(context: Context): String? = prefs(context).getString(KEY_CONNECT_PORT, null)
  fun connectHost(context: Context): String = prefs(context).getString(KEY_CONNECT_HOST, "127.0.0.1") ?: "127.0.0.1"
  fun connected(context: Context): Boolean = prefs(context).getBoolean(KEY_CONNECTED, false)
  fun authorized(context: Context): Boolean = fullAccess() && allowSwitch(context) && paired(context)

  private fun savedEndpoint(context: Context): AdbEndpoint? {
    val port = AdbEndpointPolicy.validPort(connectPort(context)) ?: return null
    return AdbEndpointPolicy.endpoint(connectHost(context), port, AdbEndpointPolicy.localHosts())
  }

  private val statusWorker = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "dsh-adb-status").apply { isDaemon = true }
  }
  private val monitorStarted = AtomicBoolean(false)
  private val refreshQueued = AtomicBoolean(false)
  @Volatile private var wireless = observeWireless(null, false, 0)
  @Volatile private var discoveredConnect: AdbEndpoint? = null
  @Volatile private var discoveredAt = 0L

  /** Process-lifetime producer; retains applicationContext only, independent of WebView polling. */
  fun startStatusMonitor(context: Context) {
    val app = context.applicationContext
    if (monitorStarted.compareAndSet(false, true)) {
      statusWorker.scheduleWithFixedDelay({ refreshWireless(app) }, 0, WIRELESS_PROBE_TTL_MS, TimeUnit.MILLISECONDS)
    }
  }

  private fun requestStatusRefresh(context: Context) {
    val app = context.applicationContext
    startStatusMonitor(app)
    if (refreshQueued.compareAndSet(false, true)) statusWorker.execute {
      try { refreshWireless(app) } finally { refreshQueued.set(false) }
    }
  }

  private fun refreshWireless(context: Context) {
    try {
      val switchValue = try { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") } catch (_: Exception) { null }
      val candidate = savedEndpoint(context) ?: discoveredConnect?.takeIf {
        SystemClock.elapsedRealtime() - discoveredAt in 0..15_000L
      }?.let { AdbEndpointPolicy.endpoint(it.host, it.port, AdbEndpointPolicy.localHosts()) }
      val reachable = candidate?.let { tcpProbe(it.host, it.port, WIRELESS_PROBE_TIMEOUT_MS) } ?: false
      val next = observeWireless(switchValue, reachable, System.currentTimeMillis())
      wireless = next
      // Refresh timestamp even when booleans agree: the engine can reject stale observations.
      prefs(context).edit().putBoolean(KEY_WIRELESS_KNOWN, next.known)
        .putBoolean(KEY_WIRELESS_ON, next.on).putLong(KEY_WIRELESS_OBSERVED_AT, next.observedAt)
        .putBoolean(KEY_ENDPOINT_REACHABLE, next.endpointReachable).apply()
    } catch (_: Exception) {
      wireless = observeWireless(null, false, System.currentTimeMillis())
      // A failed producer cannot leave a fresh successful observation in memory.
    }
  }

  private fun wirelessSnapshot(): AdbWirelessObservation = wireless.let {
    if (System.currentTimeMillis() - it.observedAt in 0..15_000L) it else observeWireless(null, false, it.observedAt)
  }

  /** Compatibility boolean: false can mean UNKNOWN. Inspect wirelessKnown/wirelessDebugState. No TCP here. */
  fun wirelessDebugLive(context: Context): Boolean {
    requestStatusRefresh(context)
    return wirelessSnapshot().let { it.known && it.on }
  }

  private val ports = AdbDiscoveryCache<AdbPortDiscovery.Result>(2_000L) { SystemClock.elapsedRealtime() }
  private fun scanPorts(context: Context): Pair<AdbPortDiscovery.Result, Boolean> {
    val result = AdbPortDiscovery.discover(context.applicationContext, NSD_TIMEOUT_MS)
    discoveredConnect = result.connect
    discoveredAt = SystemClock.elapsedRealtime()
    requestStatusRefresh(context)
    return result to result.positive
  }
  /** Prefetch alone may consume a brief positive cache; negatives are never cached. */
  @Suppress("UNUSED_PARAMETER")
  fun prefetchPorts(context: Context, engine: EngineManager) { ports.prefetch { scanPorts(context) } }
  fun cachedPorts(): String? = ports.cached()?.json
  /** Explicit user discovery is ALWAYS fresh, including while a prefetch is finishing. */
  @Suppress("UNUSED_PARAMETER")
  fun discoverPorts(context: Context, engine: EngineManager): String = ports.fresh { scanPorts(context) }.json

  private fun adbBin(context: Context): File? = File(File(context.filesDir, "usr"), "bin/adb").takeIf { it.exists() }

  fun pairWithCode(context: Context, engine: EngineManager, code: String, pairPort: Int, connectPort: Int, host: String = "127.0.0.1"): PairResult {
    if (!fullAccess() || !allowSwitch(context)) return PairResult(false, false, "请先授予所有文件访问并开启应用内允许访问", "not-authorized")
    val revision = authorizationRevision.get()
    if (!PAIR_CODE.matches(code)) return PairResult(false, false, "配对码必须为 6 位数字", "invalid-code")
    if (pairPort !in 1..65535 || connectPort !in 1..65535) return PairResult(false, false, "端口必须是 1-65535", "invalid-port")
    val endpoint = AdbEndpointPolicy.endpoint(host, pairPort, AdbEndpointPolicy.localHosts())
      ?: return PairResult(false, false, "IP 必须是本机的数字地址（回环或当前网络地址），不能是远程设备", "invalid-host")
    val connection = endpoint.copy(port = connectPort)
    if (adbBin(context) == null) return PairResult(false, false, "ADB 客户端未就绪", "adb-missing")
    // Secrets are redacted immediately after process collection, before any audit or UI result.
    val result = runAdb(engine, listOf("pair", endpoint.address, code), 60, "pair", retry = true, secrets = listOf(code))
    if (!result.paired()) {
      AdbAudit.log(context, "adb-pair", commandAudit(result) + mapOf("codeLength" to code.length, "result" to "fail", "pairPort" to pairPort))
      return PairResult(false, false, failureGuidance(result), result.reason, result.phase, result.recovery, result.exitCode, result.timedOut)
    }
    // Serialize the final grant with app switch/revoke mutations; no late grant after revoke.
    synchronized(authorizationLock) {
      if (revision != authorizationRevision.get() || !fullAccess() || !allowSwitch(context)) {
        return PairResult(false, false, "配对期间授权已变化，请核对应用授权后重试", "authorization-changed", "pair")
      }
      prefs(context).edit().putBoolean(KEY_PAIRED, true).putString(KEY_PAIR_PORT, pairPort.toString())
        .putString(KEY_CONNECT_PORT, connection.port.toString()).putString(KEY_CONNECT_HOST, connection.host)
        .putBoolean(KEY_CONNECTED, false).apply()
    }
    ports.invalidate()
    requestStatusRefresh(context)
    // No guessed 5555 or other endpoint. The explicit local host/port is the sole connect target.
    val connectedResult = runAdb(engine, listOf("connect", connection.address), 25, "connect", retry = true, secrets = listOf(code))
    val online = connectedResult.connected()
    synchronized(authorizationLock) {
      if (revision != authorizationRevision.get() || !fullAccess() || !allowSwitch(context)) {
        return PairResult(false, paired(context), "连接期间授权已变化，请核对应用授权", "authorization-changed", "connect")
      }
      prefs(context).edit().putBoolean(KEY_CONNECTED, online).apply()
    }
    requestStatusRefresh(context)
    AdbAudit.log(context, "adb-pair", commandAudit(connectedResult) + mapOf("codeLength" to code.length, "pairPort" to pairPort, "connected" to online))
    return if (online) PairResult(true, true, null, "paired", "connect", connectedResult.recovery, connectedResult.exitCode)
    else PairResult(true, true, "已配对；连接未确认。请核对独立的连接端口。" + failureGuidance(connectedResult), "paired-connect-unconfirmed", connectedResult.phase, connectedResult.recovery, connectedResult.exitCode, connectedResult.timedOut)
  }

  private fun commandAudit(result: AdbCommandResult): Map<String, Any?> = mapOf(
    "reason" to result.reason, "phase" to result.phase, "recovery" to result.recovery,
    "firstReason" to result.firstReason, "exitCode" to result.exitCode, "timedOut" to result.timedOut,
    "error" to if (result.ok) "" else result.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(512),
  )

  private fun failureGuidance(result: AdbCommandResult): String = when (result.reason) {
    "protocol-fault" -> "ADB 协议应答异常（尚不能确定是本地服务还是配对端点）；恢复状态：${result.recovery}。请检查诊断后重试"
    "server-not-ready" -> "本地 ADB 服务未就绪；未强制终止共享服务。请稍后重试并检查诊断"
    "handshake-timeout", "command-timeout" -> "ADB 操作超时；请核对本机地址、当前端口与配对码窗口是否仍有效"
    "endpoint-unreachable", "connect-failed" -> "目标端点不可达；可能是地址或端口已变化，也可能配对窗口已关闭，请核对系统显示"
    "authentication-failed" -> "认证失败；请核对当前配对码和对应的配对端口"
    else -> "ADB 操作失败：" + result.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(512).orEmpty().ifBlank { result.reason }
  }

  /** Revoke this app's gate/endpoint only. Do not kill shared servers or delete shared key material. */
  fun revokePair(context: Context, engine: EngineManager) {
    val endpoint = synchronized(authorizationLock) {
      authorizationRevision.incrementAndGet()
      val previous = savedEndpoint(context)
      prefs(context).edit().putBoolean(KEY_PAIRED, false).putBoolean(KEY_CONNECTED, false)
        .remove(KEY_PAIR_PORT).remove(KEY_CONNECT_PORT).remove(KEY_CONNECT_HOST).apply()
      previous
    }
    ports.invalidate()
    requestStatusRefresh(context)
    syncFullAccess(context)
    if (endpoint != null) runAdb(engine, listOf("disconnect", endpoint.address), 15, "disconnect")
    AdbAudit.log(context, "adb-pair-revoke", emptyMap<String, Any>())
  }

  /** Real ADB shell execution. API29 SAF retains its explicit allow-switch-only bootstrap gate. */
  fun adbShellExecute(context: Context, engine: EngineManager, cmd: String, requireFullAccess: Boolean = true): String {
    fun failure(message: String, result: AdbCommandResult? = null): String = JSONObject().put("ok", false)
      .put("guidance", message).put("reason", result?.reason ?: "not-authorized")
      .put("phase", result?.phase ?: "validation").put("exitCode", result?.exitCode ?: JSONObject.NULL)
      .put("timedOut", result?.timedOut ?: false).put("recovery", result?.recovery ?: "none")
      .put("stderr", result?.text?.take(2048) ?: JSONObject.NULL).toString()
    if (if (requireFullAccess) !authorized(context) else !allowSwitch(context)) {
      return failure("未授权：请完成此通道要求的应用内授权和真实配对")
    }
    if (adbBin(context) == null) return failure("ADB 客户端未就绪")
    val endpoint = savedEndpoint(context) ?: return failure("缺少有效的本机连接地址/端口，请核对系统当前端点")
    val connect = runAdb(engine, listOf("connect", endpoint.address), 20, "connect", retry = true)
    if (!connect.connected()) {
      prefs(context).edit().putBoolean(KEY_CONNECTED, false).apply()
      return failure(failureGuidance(connect), connect)
    }
    // Never retry a shell payload: its remote side effects may already have happened.
    val result = runAdb(engine, listOf("-s", endpoint.address, "shell", "export PATH=/system/bin:/system/xbin; " + cmd), 30, "shell")
    prefs(context).edit().putBoolean(KEY_CONNECTED, result.ok).apply()
    requestStatusRefresh(context)
    if (!result.ok) return failure(failureGuidance(result), result)
    return JSONObject().put("ok", true).put("stdout", result.text.take(64 * 1024))
      .put("exitCode", result.exitCode).put("timedOut", false).put("phase", "shell").toString()
  }

  /** Android13+ restricted-settings unlock; same native authorization as other ADB actions. */
  fun unlockRestrictedSettings(context: Context, engine: EngineManager): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return JSONObject().put("ok", true)
      .put("message", "Android 13 以下没有受限设置限制，请直接在系统设置开启 DSH 设备控制").toString()
    if (!authorized(context)) return JSONObject().put("ok", false)
      .put("message", "需要先完成 ADB 授权（完全访问 → 允许访问 → 配对）").toString()
    val pkg = context.packageName
    val raw = adbShellExecute(context, engine, "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
    val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("ok", false).put("guidance", "返回结果无效") }
    val ok = json.optBoolean("ok", false)
    AdbAudit.log(context, "a11y-unlock-restricted", mapOf("tool" to "shell-native", "pkg" to pkg, "result" to if (ok) "ok" else "failed"))
    return JSONObject().put("ok", ok).put("message", if (ok) "已解锁受限设置，请在系统设置开启 DSH 设备控制"
      else json.optString("guidance", "解锁失败，可稍后重试或在系统设置中手动允许")).toString()
  }

  /** Quick snapshot only. No socket, process, NSD wait or main-thread work in this bridge read. */
  fun stateJson(context: Context): String {
    requestStatusRefresh(context)
    val allow = allowSwitch(context)
    val pair = paired(context)
    val full = fullAccess()
    val observation = wirelessSnapshot()
    val wirelessOn = observation.known && observation.on
    val conn = connected(context) && observation.endpointReachable && wirelessOn
    val authorized = full && allow && pair && wirelessOn
    val message = when {
      !full -> "未授权：请授予所有文件访问"
      !allow -> "未授权：应用内允许访问开关未开启"
      !pair -> "未配对：请使用系统当前配对码和独立的配对/连接端口"
      !observation.known -> "无线调试状态未知：尚无可靠系统开关或可达端点证据，不代表已关闭"
      !wirelessOn -> "系统无线调试开关已关闭"
      !conn -> "已配对，连接尚未确认；请核对当前连接端口"
      else -> null
    }
    return JSONObject().put("tier", if (authorized && conn) "T1" else if (authorized) "T1-connecting" else "T0")
      .put("fullAccess", full).put("allowSwitch", allow).put("paired", pair)
      .put("wirelessDebugOn", wirelessOn).put("wirelessKnown", observation.known)
      .put("wirelessDebugState", observation.state).put("wirelessObservedAt", observation.observedAt)
      .put("endpointReachable", observation.endpointReachable).put("connected", conn)
      .put("authorized", authorized).put("message", message ?: JSONObject.NULL).toString()
  }

  fun env(context: Context): Map<String, String> = mapOf(
    "DSH_ADB_ALLOW" to if (allowSwitch(context)) "1" else "0",
    "DSH_ADB_PAIRED" to if (paired(context)) "1" else "0",
    "DSH_ADB_WIRELESS" to if (wirelessSnapshot().let { it.known && it.on }) "1" else "0",
  )

  private val lifecycle = AdbServerLifecycle()
  @Volatile private var lastPrewarmAt = Long.MIN_VALUE
  private const val PREWARM_THROTTLE_MS = 60_000L
  private val prewarmLock = Any()
  fun prewarmDue(): Boolean = lastPrewarmAt == Long.MIN_VALUE || SystemClock.elapsedRealtime() - lastPrewarmAt >= PREWARM_THROTTLE_MS
  fun prewarm(engine: EngineManager) {
    if (!prewarmDue()) return
    synchronized(prewarmLock) {
      if (!prewarmDue()) return
      lastPrewarmAt = SystemClock.elapsedRealtime()
      lifecycle.prewarm(serverOps(engine))?.let {
        LogCollector.log("dsh-adb", "prewarm phase=${it.phase} reason=${it.reason}")
      }
    }
  }

  private fun serverOps(engine: EngineManager): AdbServerOps = object : AdbServerOps {
    override fun socketUp(): Boolean = tcpProbe("127.0.0.1", 5037, 300)
    override fun ping(): AdbCommandResult = executeAdb(engine, listOf("devices"), 6, "server-readiness")
    override fun spawnOwned(): AdbOwnedProcess {
      // Never stream an uncontrolled daemon trace (which could contain pair payloads) into logs.
      val proc = spawnAdb(engine, listOf("server", "nodaemon"), File("/dev/null"))
      return object : AdbOwnedProcess {
        override fun alive(): Boolean = proc.isAlive
        override fun destroy() { proc.destroy() }
        override fun awaitExit(timeoutMs: Long): Boolean = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
      }
    }
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
    override fun pause(ms: Long) { Thread.sleep(ms) }
  }

  private fun spawnAdb(engine: EngineManager, args: List<String>, out: File? = null): Process {
    val adb = File(engine.usrDir, "bin/adb")
    fun build(argv: List<String>): ProcessBuilder = ProcessBuilder(argv).apply {
      environment().putAll(engine.shellEnv())
      environment()["OPENSSL_CONF"] = File(engine.usrDir, "etc/tls/openssl.cnf").absolutePath
      // Readiness and all clients must address the SAME local server, irrespective of ambient env.
      environment().remove("ANDROID_ADB_SERVER_ADDRESS")
      environment().remove("ANDROID_ADB_SERVER_PORT")
      environment().remove("ADB_TRACE")
      // The Android adb daemon rejects numeric listen hostnames (even 127.0.0.1).
      // Hostless tcp:5037 is loopback-only without -a and also works for local clients.
      environment()["ADB_SERVER_SOCKET"] = adbLocalServerSocket()
      redirectErrorStream(true)
      if (out != null) redirectOutput(out)
    }
    return try { build(listOf(adb.absolutePath) + args).start() } catch (e: java.io.IOException) {
      if (e.message?.contains("Permission denied") != true) throw e
      build(listOf("/system/bin/linker64", adb.absolutePath) + args).start()
    }
  }

  private fun executeAdb(engine: EngineManager, args: List<String>, timeoutS: Long, phase: String, secrets: List<String> = emptyList()): AdbCommandResult {
    return try {
      if (!File(engine.usrDir, "bin/adb").exists()) return AdbCommandResult("adb not found in snapshot runtime", phase = phase, reasonOverride = "adb-missing")
      val proc = spawnAdb(engine, args)
      val io = ProcIo.readBounded(proc, timeoutS)
      val exit = try { proc.exitValue() } catch (_: IllegalThreadStateException) { null }
      val result = AdbCommandResult(redactAdbText(io.textWithMarkers(), secrets), exit, phase, io.exitTimedOut, io.drainTimedOut)
      // adb connect can print a failure with exit 0; require its positive protocol acknowledgement.
      if (result.ok && ((phase == "pair" && !result.paired()) || (phase == "connect" && !result.connected()))) {
        result.copy(reasonOverride = classifyAdbFailure(result.text, phase))
      } else result
    } catch (e: Exception) {
      AdbCommandResult(redactAdbText("adb failed: " + (e.message ?: e.javaClass.simpleName), secrets), phase = phase)
    }
  }

  private fun runAdb(engine: EngineManager, args: List<String>, timeoutS: Long, phase: String, retry: Boolean = false, secrets: List<String> = emptyList()): AdbCommandResult =
    lifecycle.run(serverOps(engine), retry) { executeAdb(engine, args, timeoutS, phase, secrets) }
}

/** Audit contains only sanitized result metadata, never pairing codes/argv/key material. */
object AdbAudit {
  private val TS = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
  @Synchronized fun log(context: Context, action: String, args: Map<String, Any?>) {
    try {
      val dir = File(context.filesDir, "audit")
      dir.mkdirs()
      val entry = JSONObject().put("ts", TS.format(Date())).put("action", action).put("tool", "shell-native")
        .put("args", JSONObject(args as Map<*, *>)).put("result", args["result"] ?: "ok")
      File(dir, "audit.ndjson").appendText(entry.toString() + "\n")
    } catch (_: Exception) { /* Audit storage failure does not create an authorization grant. */ }
  }
}
