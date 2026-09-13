package com.dsharnessmobile.shell

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Bounded async bridge receipts. Tickets are not pairing/authentication receipts.
 * Only one discovery/pair operation runs at a time; a second submission is rejected
 * instead of retaining another pairing code in a queue. Completed results contain
 * only the native operation's sanitized JSON, never its arguments or closure.
 * Polling expiry does not cancel an operation or revoke a successful pairing.
 */
internal class AdbOperationRegistry(
  private val executor: Executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "dsh-adb-operation").apply { isDaemon = true }
  },
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val ttlMs: Long = 5 * 60_000L,
  private val maxRetained: Int = 8,
) {
  private data class Entry(
    val kind: String,
    var completedAt: Long? = null,
    var result: JSONObject? = null,
  )

  private val entries = linkedMapOf<String, Entry>()
  private var activeId: String? = null

  init {
    require(ttlMs > 0 && maxRetained > 0)
  }

  @Synchronized
  fun submit(kind: String, action: () -> String): String {
    if (kind != "discovery" && kind != "pair") return error("不支持的调试操作")
    prune()
    if (activeId != null) return error("已有调试操作正在执行，请等待其结果，不要重复提交")
    val id = UUID.randomUUID().toString()
    entries[id] = Entry(kind)
    activeId = id
    trim()
    try {
      executor.execute {
        val result = try {
          JSONObject(action())
        } catch (_: Throwable) {
          // Exception messages may contain argv or a pairing code. Never publish them.
          JSONObject().put("ok", false).put("reason", "operation-failed")
            .put("message", "调试操作未能完成；请查看不含配对码的阶段诊断")
        }
        synchronized(this) {
          entries[id]?.apply {
            this.result = result
            completedAt = clock()
          }
          if (activeId == id) activeId = null
          prune()
          trim()
        }
      }
    } catch (_: Throwable) {
      entries.remove(id)
      if (activeId == id) activeId = null
      return error("无法启动调试操作，请稍后重试")
    }
    return JSONObject().put("ok", true).put("requestId", id).toString()
  }

  /** Legacy calls remain synchronous, but share admission with ticket operations. */
  fun runLegacy(kind: String, action: () -> String): String {
    val id = synchronized(this) {
      if (kind != "discovery" && kind != "pair") return error("不支持的调试操作")
      prune()
      if (activeId != null) return error("已有调试操作正在执行，请等待其结果，不要重复提交")
      UUID.randomUUID().toString().also {
        entries[it] = Entry(kind)
        activeId = it
        trim()
      }
    }
    // Do not hold the admission lock while performing a potentially slow legacy call:
    // another caller must promptly receive busy, not block until it can start again.
    val result = try { JSONObject(action()) } catch (_: Throwable) {
      JSONObject().put("ok", false).put("reason", "operation-failed")
        .put("message", "调试操作未能完成；请查看不含配对码的阶段诊断")
    }
    synchronized(this) {
      entries[id]?.apply { this.result = result; completedAt = clock() }
      if (activeId == id) activeId = null
      prune()
      trim()
    }
    return result.toString()
  }

  @Synchronized
  fun get(id: String): String {
    prune()
    val entry = entries[id] ?: return error("调试操作不存在或结果已过期；这不表示操作已取消，请先刷新状态")
    return JSONObject()
      .put("ok", true)
      .put("requestId", id)
      .put("kind", entry.kind)
      .put("state", if (entry.completedAt == null) "running" else "complete")
      .apply { entry.result?.let { put("result", it) } }
      .toString()
  }

  private fun prune() {
    val now = clock()
    val it = entries.iterator()
    while (it.hasNext()) {
      val item = it.next()
      val done = item.value.completedAt
      if (done != null && now - done >= ttlMs) it.remove()
    }
  }

  private fun trim() {
    while (entries.size > maxRetained) {
      val key = entries.entries.firstOrNull { it.key != activeId }?.key ?: break
      entries.remove(key)
    }
  }

  private fun error(message: String): String =
    JSONObject().put("ok", false).put("message", message).toString()
}

/** Process lifetime, not Activity lifetime: rotation cannot lose a live operation ticket. */
internal object AdbOperations {
  val registry = AdbOperationRegistry()
}
