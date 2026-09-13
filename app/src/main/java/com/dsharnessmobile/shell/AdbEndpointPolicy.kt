package com.dsharnessmobile.shell

import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface

/** Numeric, local-device endpoints only. Never resolve page/mDNS supplied hostnames. */
internal data class AdbEndpoint(val host: String, val port: Int) {
  val address: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

internal object AdbEndpointPolicy {
  fun validPort(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

  fun localHosts(): Set<String> = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
      .filter { it.isUp }.flatMap { it.inetAddresses.toList() }
      .mapNotNull { it.hostAddress }.toSet()
  } catch (_: Exception) { emptySet() }

  private fun literal(input: String): InetAddress? {
    if (input.length > 128) return null
    var value = input.removeSurrounding("[", "]")
    if (value.isEmpty() || value != value.trim()) return null
    val body = value.substringBefore('%')
    if (':' !in body) {
      val parts = body.split('.')
      if (parts.size != 4 || parts.any { part ->
          part.isEmpty() || part.any { it !in '0'..'9' } || (part.toIntOrNull() ?: -1) !in 0..255
        }) return null
      if ('%' in value) return null
      // Feed only canonical dotted decimal into InetAddress; no legacy shorthand/DNS fallback.
      value = parts.joinToString(".") { it.toInt().toString() }
    } else {
      if (!body.matches(Regex("[0-9a-fA-F:.]+"))) return null
      if ('%' in value && !value.substringAfter('%').matches(Regex("[a-zA-Z0-9_.-]+"))) return null
    }
    return try { InetAddress.getByName(value) } catch (_: Exception) { null }
  }

  fun localHost(input: String, localHosts: Set<String>): String? {
    val address = literal(input) ?: return null
    if (address.isAnyLocalAddress || address.isMulticastAddress) return null
    if (address.isLoopbackAddress) return address.hostAddress
    val local = localHosts.mapNotNull { literal(it) }.firstOrNull {
      it.address.contentEquals(address.address) &&
        (!address.isLinkLocalAddress || (address is Inet6Address && it is Inet6Address &&
          address.scopeId > 0 && address.scopeId == it.scopeId))
    } ?: return null
    return local.hostAddress
  }

  fun endpoint(host: String, port: Int, localHosts: Set<String>): AdbEndpoint? =
    if (port !in 1..65535) null else localHost(host, localHosts)?.let { AdbEndpoint(it, port) }
}

internal enum class AdbServiceType(val wire: String, val field: String) {
  PAIR("_adb-tls-pairing._tcp", "pair"), CONNECT("_adb-tls-connect._tcp", "connect"),
}

internal data class AdbDiscoveredEndpoint(
  val type: AdbServiceType,
  val endpoint: AdbEndpoint,
  val source: String,
  val serviceName: String? = null,
)

/** Completion is per requested type; a failed/foreign resolve cannot complete another type. */
internal class AdbDiscoveryResults(
  private val requested: Set<AdbServiceType>,
  private val localHosts: Set<String>,
) {
  private val results = linkedMapOf<AdbServiceType, AdbDiscoveredEndpoint>()
  private val failedStarts = mutableSetOf<AdbServiceType>()
  private var closed = false

  @Synchronized fun accept(result: AdbDiscoveredEndpoint): Boolean {
    if (closed || result.type !in requested || result.type in failedStarts) return false
    val endpoint = AdbEndpointPolicy.endpoint(result.endpoint.host, result.endpoint.port, localHosts) ?: return false
    results.putIfAbsent(result.type, result.copy(endpoint = endpoint))
    return true
  }
  @Synchronized fun startFailed(type: AdbServiceType) { if (!closed) failedStarts.add(type) }
  @Synchronized fun lost(type: AdbServiceType, serviceName: String) {
    if (!closed && results[type]?.serviceName == serviceName) results.remove(type)
  }
  @Synchronized fun needs(type: AdbServiceType): Boolean = !closed && type in requested && type !in results && type !in failedStarts
  @Synchronized fun done(): Boolean = requested.all { it in results || it in failedStarts }
  @Synchronized fun close(): List<AdbDiscoveredEndpoint> { closed = true; return results.values.toList() }
}

internal data class AdbWirelessObservation(
  val known: Boolean, val on: Boolean, val endpointReachable: Boolean, val observedAt: Long,
) {
  val state: String get() = if (!known) "unknown" else if (on) "on" else "off"
}

/** Serializes prefetch/explicit scans. Explicit refresh never consumes cached negatives or positives. */
internal class AdbDiscoveryCache<T>(private val ttlMs: Long, private val now: () -> Long) {
  private data class Entry<T>(val value: T, val at: Long)
  private var entry: Entry<T>? = null
  @Synchronized fun cached(): T? = entry?.takeIf { now() - it.at in 0 until ttlMs }?.value
  @Synchronized fun fresh(load: () -> Pair<T, Boolean>): T {
    entry = null // A failed explicit refresh must not revive the preceding snapshot.
    val (value, positive) = load()
    entry = if (positive) Entry(value, now()) else null
    return value
  }
  @Synchronized fun prefetch(load: () -> Pair<T, Boolean>): T = cached() ?: fresh(load)
  @Synchronized fun invalidate() { entry = null }
}

/** Missing preference / failed TCP is unknown, never proof that the system switch is off. */
internal fun observeWireless(switchValue: Int?, reachable: Boolean, now: Long): AdbWirelessObservation = when (switchValue) {
  0 -> AdbWirelessObservation(true, false, reachable, now)
  1 -> AdbWirelessObservation(true, true, reachable, now)
  else -> AdbWirelessObservation(reachable, reachable, reachable, now)
}
