package com.dsharnessmobile.shell

import org.junit.Assert.*
import org.junit.Test

class AdbDiscoveryPolicyTest {
  private fun result(type: AdbServiceType, port: Int, host: String = "192.0.2.10", name: String = "local") =
    AdbDiscoveredEndpoint(type, AdbEndpoint(host, port), "nsd", name)

  @Test fun twoPairResultsCannotCompleteConnectAndForeignResultsAreRejected() {
    val state = AdbDiscoveryResults(AdbServiceType.entries.toSet(), setOf("192.0.2.10"))
    assertFalse(state.accept(result(AdbServiceType.CONNECT, 12345, "192.0.2.11", "foreign")))
    assertTrue(state.accept(result(AdbServiceType.PAIR, 46871)))
    assertTrue(state.accept(result(AdbServiceType.PAIR, 46872, name = "duplicate")))
    assertFalse(state.done())
    assertTrue(state.needs(AdbServiceType.CONNECT))
    assertTrue(state.accept(result(AdbServiceType.CONNECT, 37951)))
    assertTrue(state.done())
    val results = state.close()
    assertEquals(2, results.size)
    assertEquals(46871, results.first { it.type == AdbServiceType.PAIR }.endpoint.port)
    assertEquals("192.0.2.10", results.first().endpoint.host)
  }

  @Test fun partialPropertiesLeaveOnlyTheMissingTypePending() {
    val state = AdbDiscoveryResults(AdbServiceType.entries.toSet(), emptySet())
    assertTrue(state.accept(AdbDiscoveredEndpoint(AdbServiceType.CONNECT, AdbEndpoint("127.0.0.1", 37951), "property")))
    assertFalse(state.needs(AdbServiceType.CONNECT))
    assertTrue(state.needs(AdbServiceType.PAIR))
    assertFalse(state.done())
    state.startFailed(AdbServiceType.PAIR)
    assertTrue(state.done())
    assertEquals(AdbServiceType.CONNECT, state.close().single().type)
  }

  @Test fun oneDiscoveryFailureDoesNotCompleteTheOtherAndLateCallbacksAreIgnored() {
    val state = AdbDiscoveryResults(AdbServiceType.entries.toSet(), setOf("192.0.2.10"))
    state.startFailed(AdbServiceType.PAIR)
    assertFalse(state.done())
    assertTrue(state.accept(result(AdbServiceType.CONNECT, 37951)))
    assertTrue(state.done())
    val finished = state.close()
    assertFalse(state.accept(result(AdbServiceType.PAIR, 46871)))
    assertFalse(state.accept(result(AdbServiceType.CONNECT, 40000)))
    assertEquals(finished, state.close())
  }

  @Test fun lostSelectedServiceReopensOnlyItsOwnType() {
    val state = AdbDiscoveryResults(AdbServiceType.entries.toSet(), setOf("192.0.2.10"))
    state.accept(result(AdbServiceType.PAIR, 46871))
    state.accept(result(AdbServiceType.CONNECT, 37951))
    state.lost(AdbServiceType.PAIR, "local")
    assertTrue(state.needs(AdbServiceType.PAIR))
    assertFalse(state.needs(AdbServiceType.CONNECT))
  }

  @Test fun explicitRefreshBypassesPositiveCacheAndNegativeResultsAreNeverReused() {
    var time = 10L
    var calls = 0
    val cache = AdbDiscoveryCache<String>(2000) { time }
    fun positive(): Pair<String, Boolean> { calls++; return "port-$calls" to true }
    assertEquals("port-1", cache.prefetch { positive() })
    assertEquals("port-1", cache.prefetch { positive() })
    assertEquals("port-2", cache.fresh { positive() })
    assertEquals(2, calls)
    assertEquals("empty", cache.fresh { "empty" to false })
    assertNull(cache.cached())
    assertEquals("port-3", cache.prefetch { positive() })
    time += 2000
    assertNull(cache.cached())
    assertEquals("port-4", cache.prefetch { positive() })
    try { cache.fresh { throw IllegalStateException("scan failed") }; fail("expected scan error") }
    catch (_: IllegalStateException) { assertNull("failed refresh must evict old snapshot", cache.cached()) }
    cache.invalidate()
    assertNull(cache.cached())
  }
}
