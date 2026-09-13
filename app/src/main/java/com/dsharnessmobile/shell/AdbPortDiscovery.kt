package com.dsharnessmobile.shell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Runs on a worker only. Each discovery owns its listeners and a single bounded resolve lane. */
internal object AdbPortDiscovery {
  data class Result(val json: String, val connect: AdbEndpoint?, val positive: Boolean)

  private fun systemProperty(name: String): String? {
    val cls = Class.forName("android.os.SystemProperties")
    return cls.getMethod("get", String::class.java).invoke(null, name) as? String
  }

  /** Optional internal seams isolate real NSD fixture advertisements from system adbd in tests. */
  @Suppress("DEPRECATION")
  fun discover(
    context: Context,
    timeoutMs: Long,
    propertyReader: (String) -> String? = ::systemProperty,
    serviceFilter: (NsdServiceInfo) -> Boolean = { true },
  ): Result {
    val localHosts = AdbEndpointPolicy.localHosts()
    val results = AdbDiscoveryResults(AdbServiceType.entries.toSet(), localHosts)
    val diagnostics = Collections.synchronizedList(mutableListOf<JSONObject>())
    fun diagnostic(phase: String, type: AdbServiceType?, reason: String, code: Int? = null) {
      diagnostics.add(JSONObject().put("phase", phase).put("type", type?.field ?: JSONObject.NULL)
        .put("reason", reason).put("code", code ?: JSONObject.NULL))
    }
    // Read independently: one inaccessible or absent property cannot suppress the other lookup.
    for ((type, property) in listOf(
      AdbServiceType.PAIR to "service.adb.tls.pairing_port",
      AdbServiceType.CONNECT to "service.adb.tls.port",
    )) {
      try {
        val raw = propertyReader(property)
        val port = AdbEndpointPolicy.validPort(raw)
        if (port != null) results.accept(AdbDiscoveredEndpoint(type, AdbEndpoint("127.0.0.1", port), "property"))
        else diagnostic("property", type, "missing-or-invalid")
      } catch (_: Exception) { diagnostic("property", type, "unavailable") }
    }
    val requested = AdbServiceType.entries.filter { results.needs(it) }
    val mgr = try { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }
      catch (_: Exception) { diagnostic("discovery", null, "nsd-service-unavailable"); null }
    val accepting = AtomicBoolean(true)
    val queue = LinkedBlockingQueue<Pair<AdbServiceType, NsdServiceInfo>>(64)
    val seen = Collections.synchronizedSet(mutableSetOf<String>())
    val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    val resolving = AtomicReference<NsdManager.ResolveListener?>(null)
    if (requested.isNotEmpty() && mgr == null) diagnostic("discovery", null, "nsd-unavailable")
    if (requested.isNotEmpty() && mgr != null) {
      val deadline = SystemClock.elapsedRealtime() + timeoutMs
      try {
        for (type in requested) {
          // A DIFFERENT listener per service type; NsdManager forbids active listener reuse.
          val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
              if (accepting.get()) { diagnostic("discovery", type, "start-failed", errorCode); results.startFailed(type) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
              diagnostic("cleanup", type, "stop-failed", errorCode)
            }
            override fun onServiceFound(info: NsdServiceInfo) {
              if (!accepting.get() || !results.needs(type)) return
              if (info.serviceType?.trimEnd('.') != type.wire || !serviceFilter(info)) return
              val identity = type.field + ":" + info.serviceName
              if (seen.add(identity) && !queue.offer(type to info)) diagnostic("discovery", type, "candidate-limit")
            }
            override fun onServiceLost(info: NsdServiceInfo) {
              if (accepting.get()) {
                seen.remove(type.field + ":" + info.serviceName)
                results.lost(type, info.serviceName)
              }
            }
          }
          try {
            mgr.discoverServices(type.wire, NsdManager.PROTOCOL_DNS_SD, listener)
            listeners.add(listener)
          } catch (_: Exception) {
            diagnostic("discovery", type, "start-exception")
            results.startFailed(type)
          }
        }
        while (!results.done()) {
          val remaining = deadline - SystemClock.elapsedRealtime()
          if (remaining <= 0) { diagnostic("discovery", null, "timeout"); break }
          val candidate = queue.poll(minOf(remaining, 100), TimeUnit.MILLISECONDS) ?: continue
          val (type, info) = candidate
          val identity = type.field + ":" + info.serviceName
          if (!results.needs(type) || !seen.contains(identity)) continue
          val resolved = AtomicReference<NsdServiceInfo?>(null)
          val finished = CountDownLatch(1)
          val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
              if (accepting.get()) diagnostic("resolve", type, "resolve-failed", errorCode)
              finished.countDown()
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
              if (accepting.get()) resolved.set(serviceInfo)
              finished.countDown()
            }
          }
          resolving.set(listener)
          try { mgr.resolveService(info, listener) } catch (_: Exception) {
            diagnostic("resolve", type, "resolve-exception")
            resolving.set(null)
            continue
          }
          // Never start a second resolver while an older one remains outstanding.
          if (!finished.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)) {
            diagnostic("resolve", type, "timeout")
            break
          }
          resolving.set(null)
          val value = resolved.get() ?: continue
          if (!seen.contains(identity)) continue // lost while resolving: never publish the stale endpoint
          val host = value.host?.hostAddress
          if (host == null || !results.accept(AdbDiscoveredEndpoint(type, AdbEndpoint(host, value.port), "nsd", value.serviceName))) {
            diagnostic("resolve", type, "non-local-or-invalid-endpoint")
          }
        }
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        diagnostic("discovery", null, "interrupted")
      } catch (_: Exception) { diagnostic("discovery", null, "unavailable") }
      finally {
        accepting.set(false)
        // New Android releases can cancel resolution; older APIs still ignore late results.
        resolving.get()?.let { listener ->
          try {
            mgr.javaClass.getMethod("stopServiceResolution", NsdManager.ResolveListener::class.java).invoke(mgr, listener)
          } catch (_: Exception) { diagnostic("cleanup", null, "resolve-cancel-unavailable") }
        }
        for (listener in listeners) {
          try { mgr.stopServiceDiscovery(listener) } catch (_: Exception) { diagnostic("cleanup", null, "stop-exception") }
        }
      }
    }
    accepting.set(false)
    val found = results.close()
    val pair = found.firstOrNull { it.type == AdbServiceType.PAIR }?.endpoint
    val connect = found.firstOrNull { it.type == AdbServiceType.CONNECT }?.endpoint
    val host = when {
      pair == null -> connect?.host
      connect == null || pair.host == connect.host -> pair.host
      else -> null
    }
    val endpoints = JSONArray()
    for (result in found) endpoints.put(JSONObject()
      .put("type", result.type.field).put("host", result.endpoint.host).put("port", result.endpoint.port)
      .put("source", result.source).put("serviceName", result.serviceName ?: JSONObject.NULL))
    val errors = synchronized(diagnostics) { JSONArray(diagnostics.toList()) }
    val json = JSONObject().put("pair", pair?.port ?: JSONObject.NULL).put("connect", connect?.port ?: JSONObject.NULL)
      .put("pairHost", pair?.host ?: JSONObject.NULL).put("connectHost", connect?.host ?: JSONObject.NULL)
      .put("host", host ?: JSONObject.NULL).put("candidates", JSONArray(found.map { it.endpoint.port }.distinct()))
      .put("endpoints", endpoints).put("diagnostics", errors).put("observedAt", System.currentTimeMillis()).toString()
    return Result(json, connect, found.isNotEmpty())
  }
}
