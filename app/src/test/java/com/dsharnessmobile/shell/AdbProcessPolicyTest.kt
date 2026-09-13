package com.dsharnessmobile.shell

import org.junit.Assert.*
import org.junit.Test

class AdbProcessPolicyTest {
  private fun success(phase: String = "pair") = AdbCommandResult("Successfully paired to local", 0, phase)
  private fun fault() = AdbCommandResult("error: protocol fault (couldn't read status message): Success", 1, "pair")

  @Test fun localServerSocketUsesAndroidCompatibleHostlessListenSyntax() {
    assertEquals("tcp:5037", adbLocalServerSocket())
    assertEquals("tcp:15037", adbLocalServerSocket(15037))
    assertFalse(adbLocalServerSocket().contains("127.0.0.1"))
    for (port in listOf(0, -1, 65536)) {
      try { adbLocalServerSocket(port); fail("Invalid port accepted") }
      catch (_: IllegalArgumentException) { }
    }
    val source = listOf(
      java.io.File("src/main/java/com/dsharnessmobile/shell/AdbState.kt"),
      java.io.File("app/src/main/java/com/dsharnessmobile/shell/AdbState.kt"),
    ).first { it.isFile }.readText()
    assertTrue(source.contains("environment()[\"ADB_SERVER_SOCKET\"] = adbLocalServerSocket()"))
    assertFalse(source.contains("environment()[\"ADB_SERVER_SOCKET\"] = \"tcp:127.0.0.1:5037\""))
  }

  @Test fun failuresAreNotFalselyBlamedOnPairingWindow() {
    assertEquals("handshake-timeout", classifyAdbFailure("failed to connect: timed out"))
    assertEquals("server-not-ready", classifyAdbFailure("cannot connect to daemon at tcp:5037"))
    assertEquals("server-not-ready", classifyAdbFailure("failed to connect to 127.0.0.1:5037: Connection refused"))
    assertEquals("endpoint-unreachable", classifyAdbFailure("failed to connect to 127.0.0.1:50371: Connection refused"))
    assertEquals("handshake-timeout", classifyAdbFailure("failed to connect to 127.0.0.1:50371: timed out"))
    assertEquals("ok", success().reason)
    assertEquals("endpoint-unreachable", classifyAdbFailure("Connection refused"))
    assertEquals("protocol-fault", classifyAdbFailure(fault().text))
    assertEquals("command-timeout", classifyAdbFailure("adb timeout", "shell"))
    assertFalse(AdbCommandResult("Successfully paired", 1, "pair").paired())
    assertFalse(AdbCommandResult("Successfully paired", 0, "pair", exitTimedOut = true).paired())
    assertFalse(AdbCommandResult("", phase = "shell").ok)
    assertTrue(AdbCommandResult("a warning\nconnected to 127.0.0.1:37951", 0, "connect").connected())
    assertFalse(AdbCommandResult("failed to connect", 0, "connect").connected())
  }

  @Test fun redactsEveryOccurrenceBeforeReturningText() {
    val code = "123456"
    val redacted = redactAdbText("pair failed $code\nargv=$code", listOf(code))
    assertFalse(redacted.contains(code))
    assertEquals("pair failed [REDACTED]\nargv=[REDACTED]", redacted)
  }

  @Test fun unownedListenerIsRecheckedButNeverKilledOrClaimedRebuilt() {
    val ops = FakeOps().apply { listening = true }
    val lifecycle = AdbServerLifecycle()
    var calls = 0
    val result = lifecycle.run(ops, true) { calls++; fault() }
    assertEquals("unowned-not-restarted", result.recovery)
    assertEquals(1, calls)
    assertEquals(0, ops.spawns)
    assertEquals(1, ops.pings)
    assertFalse(lifecycle.ready)
    lifecycle.run(ops, false) { success() }
    assertEquals(2, ops.pings)
  }

  @Test fun ownedRecoveryWaitsForExitAndSocketReleaseBeforeSpawning() {
    val ops = FakeOps()
    val lifecycle = AdbServerLifecycle()
    var calls = 0
    val result = lifecycle.run(ops, true) { if (++calls == 1) fault() else success() }
    assertTrue(result.paired())
    assertEquals("owned-restarted", result.recovery)
    assertEquals("protocol-fault", result.firstReason)
    assertEquals(2, ops.spawns)
    assertEquals(1, ops.stops)
    assertEquals(1, ops.waits)
    assertEquals(2, ops.pings)
    assertTrue(ops.events.indexOf("wait") < ops.events.lastIndexOf("spawn"))
  }

  @Test fun delayedOwnedDeathAndReplacedSocketFailWithoutKillingForeignListener() {
    val blocked = FakeOps().apply { exits = false }
    val first = AdbServerLifecycle().run(blocked, true) { fault() }
    assertEquals("owned-stop-timeout", first.recovery)
    assertEquals(1, blocked.spawns)
    val replaced = FakeOps().apply { socketReplaced = true }
    val second = AdbServerLifecycle().run(replaced, true) { fault() }
    assertEquals("socket-still-occupied", second.recovery)
    assertEquals(1, replaced.spawns)
    assertEquals(1, replaced.stops)
    assertTrue(replaced.time >= 1500)
  }

  @Test fun deadOwnedHandleDoesNotValidateReplacementAndShellIsNeverRetried() {
    val ops = FakeOps()
    val lifecycle = AdbServerLifecycle()
    lifecycle.run(ops, false) { success() }
    ops.processAlive = false // another process retains the socket
    ops.pingGood = false
    val result = lifecycle.run(ops, true) { fail("Unverified server must not execute command"); success() }
    assertEquals("server-not-ready", result.reason)
    assertEquals(2, ops.pings)
    assertEquals(0, ops.stops)
    val shellOps = FakeOps()
    var shellCalls = 0
    val shellResult = AdbServerLifecycle().run(shellOps, false) { shellCalls++; fault().copy(phase = "shell") }
    assertEquals(1, shellCalls)
    assertEquals("none", shellResult.recovery)
    assertEquals(0, shellOps.stops)
  }

  private class FakeOps : AdbServerOps {
    var time = 0L
    var listening = false
    var processAlive = true
    var exits = true
    var socketReplaced = false
    var pingGood = true
    var spawns = 0
    var pings = 0
    var stops = 0
    var waits = 0
    val events = mutableListOf<String>()
    override fun socketUp(): Boolean = listening
    override fun nowMs(): Long = time
    override fun pause(ms: Long) { time += ms }
    override fun ping(): AdbCommandResult {
      pings++
      return AdbCommandResult(if (pingGood) "List of devices attached" else "protocol fault", if (pingGood) 0 else 1, "server-readiness")
    }
    override fun spawnOwned(): AdbOwnedProcess {
      events.add("spawn")
      spawns++
      listening = true
      processAlive = true
      return object : AdbOwnedProcess {
        override fun alive(): Boolean = processAlive
        override fun destroy() { events.add("stop"); stops++ }
        override fun awaitExit(timeoutMs: Long): Boolean {
          events.add("wait")
          waits++
          if (exits) { processAlive = false; listening = socketReplaced }
          return exits
        }
      }
    }
  }
}
