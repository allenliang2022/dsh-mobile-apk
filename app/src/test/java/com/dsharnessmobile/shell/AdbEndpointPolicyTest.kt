package com.dsharnessmobile.shell

import org.junit.Assert.*
import org.junit.Test

class AdbEndpointPolicyTest {
  private val local = setOf("192.0.2.10", "2001:db8::3")

  @Test fun acceptsOnlyNumericLocalAddressesAndLoopback() {
    assertEquals("127.0.0.1", AdbEndpointPolicy.localHost("127.0.0.1", emptySet()))
    assertEquals("192.0.2.10", AdbEndpointPolicy.localHost("192.0.2.10", local))
    assertNotNull(AdbEndpointPolicy.localHost("::1", emptySet()))
    assertNotNull(AdbEndpointPolicy.localHost("[2001:db8::3]", local))
    assertNotNull(AdbEndpointPolicy.localHost("fe80:0:0:0:0:0:0:3%2", setOf("fe80::3%2")))
    assertNull(AdbEndpointPolicy.localHost("fe80::3%3", setOf("fe80::3%2")))
    assertNull(AdbEndpointPolicy.localHost("fe80::3", setOf("fe80::3%2")))
    for (host in listOf("192.0.2.11", "example.com", "localhost", "127.1", "2130706433", "0.0.0.0", "::", "224.0.0.1", "127.0.0.1:5037", "127.0.0.1;id", " 127.0.0.1", "999.1.1.1")) {
      assertNull(host, AdbEndpointPolicy.localHost(host, local))
    }
  }

  @Test fun validatesPortsAndFormatsIpv6WithoutChangingHost() {
    for (port in listOf(null, "", "0", "-1", "65536", "abc", "1:2")) assertNull(AdbEndpointPolicy.validPort(port))
    assertEquals(1, AdbEndpointPolicy.validPort("1"))
    assertEquals(65535, AdbEndpointPolicy.validPort("65535"))
    assertEquals("192.0.2.10:37951", AdbEndpointPolicy.endpoint("192.0.2.10", 37951, local)?.address)
    val v6 = AdbEndpointPolicy.endpoint("2001:db8::3", 37951, local)!!
    assertTrue(v6.address.startsWith("["))
    assertTrue(v6.address.endsWith("]:37951"))
    assertNull(AdbEndpointPolicy.endpoint("192.0.2.10", 0, local))
    assertNull("Old network address cannot become a remote endpoint after WiFi changes", AdbEndpointPolicy.endpoint("192.0.2.10", 37951, setOf("192.168.4.4")))
  }

  @Test fun unknownIsNotOffAndPairingIsNotAnInputToWirelessObservation() {
    assertEquals("unknown", observeWireless(null, false, 123).state)
    assertEquals("unknown", observeWireless(-1, false, 123).state)
    assertEquals("on", observeWireless(1, false, 123).state)
    assertEquals("off", observeWireless(0, false, 123).state)
    assertEquals("on", observeWireless(null, true, 123).state)
    assertEquals("off", observeWireless(0, true, 123).state)
    assertEquals(123L, observeWireless(null, true, 123).observedAt)
  }
}
