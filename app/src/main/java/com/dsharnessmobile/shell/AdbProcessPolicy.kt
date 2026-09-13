package com.dsharnessmobile.shell

/** Values crossing into UI/audit must already be redacted; never include argv or a pairing code. */
internal fun redactAdbText(text: String, secrets: List<String>): String =
  secrets.filter { it.isNotEmpty() }.fold(text) { value, secret -> value.replace(secret, "[REDACTED]") }

internal fun classifyAdbFailure(text: String, phase: String = "pair"): String {
  val t = text.lowercase()
  return when {
    t.contains("not found in snapshot") -> "adb-missing"
    phase == "server-readiness" || phase == "server-start" ||
      t.contains("cannot connect to daemon") || t.contains("daemon not running") ||
      t.contains("server 启动") || t.contains("server 未就绪") ||
      (Regex(":5037(?:\\D|$)").containsMatchIn(t) && (t.contains("cannot connect") || t.contains("failed to connect"))) -> "server-not-ready"
    t.contains("timeout") || t.contains("timed out") -> if (phase == "pair") "handshake-timeout" else "command-timeout"
    t.contains("protocol fault") -> "protocol-fault"
    t.contains("connection refused") -> "endpoint-unreachable"
    t.contains("failed to connect") || t.contains("cannot connect") -> "connect-failed"
    t.contains("wrong password") || t.contains("incorrect") || t.contains("authentication failed") || t.contains("failed to authenticate") -> "authentication-failed"
    else -> "unknown"
  }
}

internal data class AdbCommandResult(
  val text: String,
  val exitCode: Int? = null,
  val phase: String,
  val exitTimedOut: Boolean = false,
  val drainTimedOut: Boolean = false,
  val reasonOverride: String? = null,
  val recovery: String = "none",
  val firstReason: String? = null,
) {
  val timedOut: Boolean get() = exitTimedOut || drainTimedOut
  val ok: Boolean get() = exitCode == 0 && !timedOut && reasonOverride == null
  val reason: String get() = reasonOverride ?: if (ok) "ok" else if (timedOut) {
    if (phase == "pair") "handshake-timeout" else "command-timeout"
  } else classifyAdbFailure(text, phase)
  fun paired(): Boolean = ok && text.lineSequence().any {
    it.startsWith("Successfully paired") || it.startsWith("成功配对") || it.startsWith("已成功配对")
  }
  fun connected(): Boolean = ok && text.lineSequence().any {
    it.startsWith("connected to ") || it.startsWith("already connected to ")
  }
}

internal interface AdbOwnedProcess {
  fun alive(): Boolean
  fun destroy()
  fun awaitExit(timeoutMs: Long): Boolean
}

internal interface AdbServerOps {
  fun socketUp(): Boolean
  fun ping(): AdbCommandResult
  fun spawnOwned(): AdbOwnedProcess
  fun nowMs(): Long
  fun pause(ms: Long)
}

/** One lock covers ownership, ready checks, commands and recovery. No kill-server / foreign PID kill. */
internal class AdbServerLifecycle {
  private var owned: AdbOwnedProcess? = null
  @Volatile var ready: Boolean = false
    private set

  private fun failure(message: String, phase: String = "server-readiness") =
    AdbCommandResult(message, phase = phase, reasonOverride = "server-not-ready")

  private fun stopOwned(ops: AdbServerOps): String? {
    val process = owned ?: return "unowned-not-restarted"
    if (!process.alive()) {
      owned = null
      return if (ops.socketUp()) "unowned-not-restarted" else null
    }
    process.destroy()
    if (!process.awaitExit(1_500)) return "owned-stop-timeout"
    owned = null
    val deadline = ops.nowMs() + 1_500
    while (ops.socketUp() && ops.nowMs() < deadline) ops.pause(50)
    // Socket may now belong to a different process. Never terminate that listener.
    return if (ops.socketUp()) "socket-still-occupied" else null
  }

  private fun ensure(ops: AdbServerOps): AdbCommandResult? {
    ready = false // Readiness is per operation, not an indefinite cached assertion.
    if (owned?.alive() == false) owned = null
    if (!ops.socketUp()) {
      if (owned != null) {
        val stopped = stopOwned(ops)
        if (stopped != null) return failure(stopped)
      }
      owned = ops.spawnOwned()
      val deadline = ops.nowMs() + 3_000
      while (owned?.alive() == true && !ops.socketUp() && ops.nowMs() < deadline) ops.pause(50)
      if (!ops.socketUp()) return failure("adb server did not start listening", "server-start")
    }
    val ping = ops.ping()
    if (!ping.ok || !ping.text.contains("List of devices") || ping.text.contains("protocol fault")) {
      return ping.copy(phase = "server-readiness", reasonOverride = "server-not-ready")
    }
    ready = true
    return null
  }

  @Synchronized fun prewarm(ops: AdbServerOps): AdbCommandResult? = try {
    ensure(ops)
  } catch (_: Exception) { ready = false; failure("adb server readiness exception") }

  @Synchronized fun run(ops: AdbServerOps, retryProtocolFault: Boolean, command: () -> AdbCommandResult): AdbCommandResult {
    try {
      ensure(ops)?.let { return it }
      val first = command()
      if (!first.ok) ready = false
      if (first.reason != "protocol-fault" || !retryProtocolFault || first.ok) return first
      val stopped = stopOwned(ops)
      if (stopped != null) return first.copy(recovery = stopped, firstReason = first.reason)
      ensure(ops)?.let { return it.copy(recovery = "owned-restart-unconfirmed", firstReason = first.reason) }
      val second = command()
      if (!second.ok) ready = false
      return second.copy(recovery = "owned-restarted", firstReason = first.reason)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      ready = false
      return failure("adb operation interrupted")
    } catch (_: Exception) {
      ready = false
      return failure("adb lifecycle exception")
    }
  }
}
