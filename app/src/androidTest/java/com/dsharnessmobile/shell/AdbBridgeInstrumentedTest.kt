package com.dsharnessmobile.shell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disposable emulator only. Tests the REAL WebView/Java bridge and Android NSD
 * listener contract; no production ADB command, pairing code, key or grant is used.
 * Passing this suite is NOT a real-device wireless pairing acceptance receipt.
 */
@RunWith(AndroidJUnit4::class)
class AdbBridgeInstrumentedTest {
  private fun emulatorOnly() {
    assertTrue("Run only on a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
  }

  @Test fun webViewReceivesTicketWhileNativeWorkerIsStillBlocked() {
    emulatorOnly()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    val registry = AdbOperationRegistry(executor)
    val viewRef = AtomicReference<WebView>()
    val pageReady = CountDownLatch(1)
    try {
      instrumentation.runOnMainSync {
        val bridge = AndroidBridge(
          onPickRequest = {}, onKeepScreen = {}, onNotify = { _, _ -> },
          onStartAdbDiscovery = {
            registry.submit("discovery") {
              try {
                check(release.await(20, TimeUnit.SECONDS))
                """{"pair":40001,"connect":37951,"host":"127.0.0.1"}"""
              } finally { finished.countDown() }
            }
          },
          onGetAdbOperation = registry::get,
        )
        val view = WebView(instrumentation.targetContext)
        viewRef.set(view)
        view.settings.javaScriptEnabled = true
        view.addJavascriptInterface(bridge, "androidBridge")
        view.webViewClient = object : WebViewClient() {
          override fun onPageFinished(view: WebView, url: String) { pageReady.countDown() }
        }
        view.loadDataWithBaseURL("https://dsh-test.invalid/", "<html><body>bridge fixture</body></html>", "text/html", "UTF-8", null)
      }
      assertTrue("Fixture page must load", pageReady.await(15, TimeUnit.SECONDS))
      fun evaluate(script: String): String {
        val value = AtomicReference<String>()
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
          viewRef.get().evaluateJavascript(script) { result -> value.set(result); completed.countDown() }
        }
        assertTrue("JS bridge must return before the blocked worker is released", completed.await(5, TimeUnit.SECONDS))
        return JSONTokener(value.get()).nextValue() as String
      }
      val ticket = JSONObject(evaluate("androidBridge.startAdbDiscovery()"))
      assertTrue(ticket.getBoolean("ok"))
      val id = ticket.getString("requestId")
      assertEquals(1L, release.count)
      val pending = JSONObject(evaluate("androidBridge.getAdbOperation('${id}')"))
      assertEquals("running", pending.getString("state"))
      val duplicate = JSONObject(evaluate("androidBridge.startAdbDiscovery()"))
      assertFalse("A second request must not queue another operation", duplicate.getBoolean("ok"))
      release.countDown()
      assertTrue(finished.await(5, TimeUnit.SECONDS))
      // Registry completion happens just after the action's finally; bounded polling
      // checks the actual async hand-off rather than assuming a task latch equals commit.
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      var result: JSONObject
      do {
        result = JSONObject(evaluate("androidBridge.getAdbOperation('${id}')"))
        if (result.getString("state") == "complete") break
        Thread.sleep(20)
      } while (System.nanoTime() < deadline)
      assertEquals("complete", result.getString("state"))
      assertEquals(40001, result.getJSONObject("result").getInt("pair"))
      assertEquals(37951, result.getJSONObject("result").getInt("connect"))
    } finally {
      release.countDown()
      executor.shutdownNow()
      instrumentation.runOnMainSync { viewRef.get()?.destroy() }
    }
  }

  @Test fun androidAcceptsIndependentListenersAndProductionAdapterFindsBothFixtures() {
    emulatorOnly()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val discoveryError = AtomicReference<String>()
    fun listener(start: CountDownLatch, stop: CountDownLatch) = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(type: String) { start.countDown() }
      override fun onDiscoveryStopped(type: String) { stop.countDown() }
      override fun onStartDiscoveryFailed(type: String, code: Int) { discoveryError.set("start:$type:$code"); start.countDown() }
      override fun onStopDiscoveryFailed(type: String, code: Int) { discoveryError.set("stop:$type:$code"); stop.countDown() }
      override fun onServiceFound(info: NsdServiceInfo) {}
      override fun onServiceLost(info: NsdServiceInfo) {}
    }
    val pairStarted = CountDownLatch(1)
    val connectStarted = CountDownLatch(1)
    val pairStopped = CountDownLatch(1)
    val connectStopped = CountDownLatch(1)
    val pair = listener(pairStarted, pairStopped)
    val connect = listener(connectStarted, connectStopped)
    val started = mutableListOf<Pair<NsdManager.DiscoveryListener, CountDownLatch>>()
    try {
      manager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pair)
      started.add(pair to pairStopped)
      assertTrue(pairStarted.await(5, TimeUnit.SECONDS))
      assertNull(discoveryError.get())
      var reuseRejected = false
      try { manager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, pair) }
      catch (_: IllegalArgumentException) { reuseRejected = true }
      assertTrue("Negative control: an active discovery listener cannot be reused", reuseRejected)
      manager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connect)
      started.add(connect to connectStopped)
      assertTrue(connectStarted.await(5, TimeUnit.SECONDS))
      assertNull(discoveryError.get())
    } finally {
      started.forEach { (listener, stopped) ->
        manager.stopServiceDiscovery(listener)
        assertTrue("Discovery cleanup must complete", stopped.await(5, TimeUnit.SECONDS))
      }
    }
    assertNull(discoveryError.get())

    // Publish two controlled LOCAL services using the actual Android NSD service.
    // The adapter must resolve both known ports; an all-null/error response cannot pass.
    val registrations = mutableListOf<NsdManager.RegistrationListener>()
    val fixtureNames = mutableSetOf<String>()
    fun advertise(type: String, port: Int) {
      val completed = CountDownLatch(1)
      val error = AtomicReference<String>()
      val name = AtomicReference<String>()
      val info = NsdServiceInfo().apply {
        serviceName = "dsh-adb-fixture-" + java.util.UUID.randomUUID().toString().take(8)
        serviceType = type
        this.port = port
      }
      val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(value: NsdServiceInfo) { name.set(value.serviceName); completed.countDown() }
        override fun onRegistrationFailed(value: NsdServiceInfo, code: Int) { error.set("register:$code"); completed.countDown() }
        override fun onServiceUnregistered(value: NsdServiceInfo) {}
        override fun onUnregistrationFailed(value: NsdServiceInfo, code: Int) {}
      }
      manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
      registrations.add(listener)
      assertTrue("Fixture service must register", completed.await(10, TimeUnit.SECONDS))
      assertNull(error.get())
      fixtureNames.add(name.get())
    }
    java.net.ServerSocket(0).use { pairSocket ->
      java.net.ServerSocket(0).use { connectSocket ->
        try {
          advertise("_adb-tls-pairing._tcp", pairSocket.localPort)
          advertise("_adb-tls-connect._tcp", connectSocket.localPort)
          val result = JSONObject(AdbPortDiscovery.discover(context, 10_000,
            propertyReader = { null }, serviceFilter = { it.serviceName in fixtureNames }).json)
          assertEquals("Production adapter must find the pairing fixture", pairSocket.localPort, result.getInt("pair"))
          assertEquals("Production adapter must find the connection fixture", connectSocket.localPort, result.getInt("connect"))
          assertEquals(2, result.getJSONArray("endpoints").length())
          val diagnostics = result.getJSONArray("diagnostics")
          for (i in 0 until diagnostics.length()) {
            assertFalse("No listener-start failure may masquerade as successful discovery",
              diagnostics.getJSONObject(i).getString("reason") in setOf("start-exception", "start-failed"))
          }
        } finally {
          registrations.forEach { try { manager.unregisterService(it) } catch (_: Exception) {} }
        }
      }
    }
  }
}
