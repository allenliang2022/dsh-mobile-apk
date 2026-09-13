package com.dsharnessmobile.shell

import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdbOperationRegistryTest {
  private class DeferredExecutor : Executor {
    val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }
    fun runNext() = tasks.removeFirst().run()
  }

  @Test fun ticketReturnsBeforeWorkAndOnlyItsResultIsPolled() {
    val worker = DeferredExecutor()
    val registry = AdbOperationRegistry(worker)
    var calls = 0
    val ticket = JSONObject(registry.submit("discovery") {
      calls++
      """{"pair":40001,"connect":37951}"""
    })
    assertTrue(ticket.getBoolean("ok"))
    assertEquals(0, calls)
    val id = ticket.getString("requestId")
    assertEquals("running", JSONObject(registry.get(id)).getString("state"))
    assertFalse(JSONObject(registry.get(id)).has("result"))
    assertFalse(JSONObject(registry.get("unrelated")).getBoolean("ok"))
    worker.runNext()
    val done = JSONObject(registry.get(id))
    assertEquals(1, calls)
    assertEquals("complete", done.getString("state"))
    assertEquals("discovery", done.getString("kind"))
    assertEquals(40001, done.getJSONObject("result").getInt("pair"))
    assertEquals(37951, done.getJSONObject("result").getInt("connect"))
  }

  @Test fun duplicateSubmissionIsRejectedWithoutQueueingAnotherCode() {
    val worker = DeferredExecutor()
    val registry = AdbOperationRegistry(worker)
    registry.submit("pair") { """{"ok":true,"paired":true}""" }
    val duplicate = JSONObject(registry.submit("pair") { fail("Must not run"); "{}" })
    assertFalse(duplicate.getBoolean("ok"))
    assertEquals(1, worker.tasks.size)
    worker.runNext()
    assertTrue(JSONObject(registry.submit("discovery") { "{}" }).getBoolean("ok"))
  }

  @Test fun legacyAndTicketCallsShareTheSameAdmission() {
    val worker = DeferredExecutor()
    val registry = AdbOperationRegistry(worker)
    registry.submit("pair") { """{"ok":true}""" }
    assertFalse(JSONObject(registry.runLegacy("discovery") { fail("Must not interleave"); "{}" }).getBoolean("ok"))
    worker.runNext()
    val legacy = JSONObject(registry.runLegacy("pair") {
      assertFalse(JSONObject(registry.submit("pair") { fail("Must not queue"); "{}" }).getBoolean("ok"))
      assertFalse(JSONObject(registry.runLegacy("pair") { fail("Must not interleave"); "{}" }).getBoolean("ok"))
      """{"ok":true,"paired":true}"""
    })
    assertTrue(legacy.getBoolean("ok"))
    assertTrue(JSONObject(registry.submit("discovery") { "{}" }).getBoolean("ok"))
  }

  @Test fun legacyFailureDoesNotLeakOrLeaveAdmissionBusy() {
    val registry = AdbOperationRegistry(DeferredExecutor())
    val failed = registry.runLegacy("pair") { error("private code 123456") }
    assertFalse(failed.contains("123456"))
    assertFalse(JSONObject(failed).getBoolean("ok"))
    assertTrue(JSONObject(registry.runLegacy("discovery") { """{"ok":true}""" }).getBoolean("ok"))
  }

  @Test fun failuresDoNotPublishExceptionMessagesOrBecomeSuccess() {
    val worker = DeferredExecutor()
    val registry = AdbOperationRegistry(worker)
    val id = JSONObject(registry.submit("pair") { error("secret pairing argv 123456") }).getString("requestId")
    worker.runNext()
    val raw = registry.get(id)
    assertFalse(raw.contains("123456"))
    assertFalse(raw.contains("argv"))
    assertFalse(JSONObject(raw).getJSONObject("result").getBoolean("ok"))
    val malformed = JSONObject(registry.submit("pair") { "not JSON: 654321" }).getString("requestId")
    worker.runNext()
    assertFalse(registry.get(malformed).contains("654321"))
    assertFalse(JSONObject(registry.get(malformed)).getJSONObject("result").getBoolean("ok"))
  }

  @Test fun expiredReceiptDoesNotPretendToCancelARunningOperation() {
    val worker = DeferredExecutor()
    var now = 100L
    val registry = AdbOperationRegistry(worker, { now }, ttlMs = 500)
    val id = JSONObject(registry.submit("pair") { """{"ok":true}""" }).getString("requestId")
    now = 10_000L
    assertEquals("running", JSONObject(registry.get(id)).getString("state"))
    worker.runNext()
    now += 499
    assertTrue(JSONObject(registry.get(id)).getBoolean("ok"))
    now++
    val expired = JSONObject(registry.get(id))
    assertFalse(expired.getBoolean("ok"))
    assertTrue(expired.getString("message").contains("不表示操作已取消"))
  }

  @Test fun completedResultRetentionIsBounded() {
    val worker = DeferredExecutor()
    val registry = AdbOperationRegistry(worker, maxRetained = 2)
    val ids = (1..3).map {
      val id = JSONObject(registry.submit("discovery") { "{}" }).getString("requestId")
      worker.runNext()
      id
    }
    assertFalse(JSONObject(registry.get(ids[0])).getBoolean("ok"))
    assertTrue(JSONObject(registry.get(ids[1])).getBoolean("ok"))
    assertTrue(JSONObject(registry.get(ids[2])).getBoolean("ok"))
  }

  @Test fun rejectedExecutorDoesNotLeaveRegistryPermanentlyBusy() {
    var reject = true
    val registry = AdbOperationRegistry(Executor {
      if (reject) throw java.util.concurrent.RejectedExecutionException("private details")
      it.run()
    })
    val failure = registry.submit("pair") { "{}" }
    assertFalse(JSONObject(failure).getBoolean("ok"))
    assertFalse(failure.contains("private details"))
    reject = false
    assertTrue(JSONObject(registry.submit("pair") { "{}" }).getBoolean("ok"))
    assertFalse(JSONObject(registry.submit("unsupported") { "{}" }).getBoolean("ok"))
  }
}
