package com.dsharnessmobile.shell

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled-binary LOCAL bootstrap only; not real-phone pairing acceptance. */
@RunWith(AndroidJUnit4::class)
class AdbServerStartupInstrumentedTest {
  @Test fun bundledAdbStartsIsolatedServerAndListsNoDevices() {
    assertTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val fixture = Files.createTempDirectory(context.cacheDir.toPath(), "adb-startup-").toFile()
    val log = File(fixture, "server.log")
    var owned: Process? = null
    try {
      extractRuntime(fixture)
      val usr = File(fixture, "usr")
      val adb = File(usr, "bin/adb")
      assertTrue("Bundled adb missing", adb.isFile)
      check(adb.setExecutable(true, true))
      val home = File(fixture, "home").apply { check(mkdir()) }
      val androidHome = File(fixture, "android-home").apply { check(mkdir()) }
      val tmp = File(fixture, "tmp").apply { check(mkdir()) }
      val loopback = InetAddress.getByName("127.0.0.1")
      val port = ServerSocket(0, 1, loopback).use { it.localPort }
      val serial = "dsh-nonexistent-usb-${UUID.randomUUID()}"
      val builder = ProcessBuilder("/system/bin/linker64", adb.path,
        "--one-device", serial, "server", "nodaemon")
        .directory(fixture).redirectErrorStream(true).redirectOutput(log)
      builder.environment().apply {
        clear() // No inherited vendor keys, SDK home, trace, server address, or live app env.
        putAll(mapOf(
          "PATH" to "${usr.path}/bin:/system/bin", "HOME" to home.path,
          "ANDROID_USER_HOME" to androidHome.path, "TMPDIR" to tmp.path,
          "LD_LIBRARY_PATH" to "${usr.path}/lib",
          "OPENSSL_CONF" to "${usr.path}/etc/tls/openssl.cnf",
          "SSL_CERT_FILE" to "${usr.path}/etc/tls/cert.pem",
          "SSL_CERT_DIR" to "${usr.path}/etc/tls/certs",
          "ADB_MDNS_AUTO_CONNECT" to "0", "ADB_LIBUSB" to "0",
          "ADB_SERVER_SOCKET" to adbLocalServerSocket(port), // Production regression hook.
          "TERMUX__ROOTFS" to fixture.path, "TERMUX__PREFIX" to usr.path,
          "TERMUX_APP__DATA_DIR" to fixture.path, "TERMUX_APP__LEGACY_DATA_DIR" to fixture.path,
          "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
          "TERMUX_EXEC__EXECVE_CALL__INTERCEPT" to "1",
        ))
        val preload = File(usr, "lib/libtermux-exec-ld-preload.so")
        if (preload.isFile) put("LD_PRELOAD", preload.path)
      }
      val server = builder.start().also { owned = it; it.outputStream.close() }
      fun listening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(loopback, port), 100) }; true
      } catch (_: java.io.IOException) { false }
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      var ready = false
      while (server.isAlive && System.nanoTime() < deadline) {
        if (listening()) { ready = true; break }
        Thread.sleep(50)
      }
      assertTrue("Bundled server failed to listen: ${evidence(log)}", ready && server.isAlive)
      // Real host:devices RPC, not an adbd/device command. No CLI fallback can spawn
      // an unowned daemon if the server dies; OKAY + zero bytes is the empty list.
      Socket().use { socket ->
        socket.connect(InetSocketAddress(loopback, port), 500)
        socket.soTimeout = 3_000
        socket.getOutputStream().apply { write("000chost:devices".toByteArray(Charsets.US_ASCII)); flush() }
        val input = DataInputStream(socket.getInputStream())
        fun field(): String = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        assertEquals("Local devices RPC failed: ${evidence(log)}", "OKAY", field())
        assertEquals("Expected no transports", "0000", field())
      }
      assertTrue("Owned server must remain alive: ${evidence(log)}", server.isAlive)
    } finally {
      // Only this Process handle is ours. Never adb kill-server or a PID search.
      val stopped = owned?.let { child ->
        runCatching {
          child.destroy()
          if (!child.waitFor(2, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(2, TimeUnit.SECONDS)
          } else true
        }.getOrDefault(false)
      } ?: true
      if (stopped) Files.walk(fixture.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } // Never follow links.
      }
      assertTrue("Owned child did not stop; private fixture retained", stopped)
    }
  }

  private fun evidence(log: File): String = if (!log.isFile) "(no output)" else
    log.inputStream().use { input ->
      val bytes = ByteArray(4096)
      val count = input.read(bytes)
      if (count < 0) "" else String(bytes, 0, count, Charsets.UTF_8)
    } // Only our subprocess log, never generated keys or live app files.

  private fun selected(name: String): Boolean = name == "usr/bin/adb" ||
    (name.startsWith("usr/lib/") && '/' !in name.removePrefix("usr/lib/")) ||
    name == "usr/etc/tls" || name.startsWith("usr/etc/tls/")

  private fun extractRuntime(root: File) {
    val base = root.toPath().toRealPath()
    val links = mutableListOf<Pair<Path, Path>>()
    var bytes = 0L
    val asset = InstrumentationRegistry.getInstrumentation().targetContext.assets.open("snapshot.tar.xz")
    TarArchiveInputStream(XZCompressorInputStream(asset)).use { tar ->
      while (true) {
        val entry = tar.nextEntry ?: break
        val name = entry.name.removePrefix("./").trimEnd('/')
        if (!selected(name) || (entry.isDirectory && !name.startsWith("usr/etc/tls"))) continue
        require(!name.startsWith('/') && name.split('/').none { it == ".." || it.isEmpty() })
        val target = base.resolve(name).normalize()
        require(target.startsWith(base) && target != base)
        Files.createDirectories(target.parent)
        when {
          entry.isDirectory -> Files.createDirectories(target)
          entry.isSymbolicLink -> {
            val link = Paths.get(entry.linkName)
            require(!link.isAbsolute && entry.linkName.isNotEmpty()) { "Absolute/empty fixture link rejected" }
            val resolved = target.parent.resolve(link).normalize()
            require(resolved.startsWith(base))
            // The full runtime also has unrelated aliases into omitted trees (e.g.
            // terminfo/perl). Never create an outside-minimal-runtime link. If adb
            // actually requires an omitted library, the real startup assertion fails.
            if (!selected(base.relativize(resolved).toString())) continue
            links.add(target to link) // Defer links so extraction never writes through one.
          }
          entry.isFile -> {
            require(entry.size >= 0 && entry.size <= 512L * 1024 * 1024 - bytes)
            bytes += entry.size
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
              check(tar.copyTo(out) == entry.size)
            }
          }
          else -> error("Unsupported selected archive entry") // No hardlinks/devices/FIFOs.
        }
      }
    }
    links.forEach { (target, link) -> Files.createSymbolicLink(target, link) }
    links.forEach { (target, _) -> require(target.toRealPath().startsWith(base)) }
  }
}
