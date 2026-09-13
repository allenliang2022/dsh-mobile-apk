package com.dsharnessmobile.shell

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android PM/assets, java.nio and bounded subprocess checks, API 26+.
 * API 26/27 asserts prepare's explicit degradation; API 28+ asserts preparation.
 * x86_64 asserts absent optional payload; ARM64 verifies only the PRoot engine.
 * These tests neither provision nor certify a Debian guest, apt, DNS or signals.
 */
@RunWith(AndroidJUnit4::class)
class NativeProotAcceptanceTest : NativeProotAndroidFixture() {
  @Test fun prepareUsesRealPackageManagerAndStablePrivateLauncher() {
    val paths = pmPaths()
    val env = NativeProot.prepare(context, usr)
    if (Build.VERSION.SDK_INT < 28) {
      assertEquals(mapOf("DSH_NATIVE_PROOT_ERROR" to "Native PRoot requires Android API 28 or newer"), env)
      assertFalse(paths.stateFile.exists())
      assertFalse(paths.launcher.exists())
      assertFalse(paths.tmpDir.exists())
      assertFalse(File(context.filesDir, "native-proot.lock").exists())
    } else {
      assertEquals("", env["DSH_NATIVE_PROOT_ERROR"])
      assertEquals(paths.proot.absolutePath, env["DSH_PROOT_BIN"])
      assertEquals(paths.loader.absolutePath, env["PROOT_LOADER"])
      assertEquals(paths.stateFile.absolutePath, env["DSH_NATIVE_PROOT_STATE"])
      assertEquals(paths.launcher.absolutePath, env["DSH_DEBIAN_LAUNCHER"])
      assertEquals(paths.defaultRootfs.absolutePath, env["DSH_DEBIAN_ROOTFS"])
      assertArrayEquals(launcherAsset(), paths.launcher.readBytes())
      assertArrayEquals(NativeProot.stateBytes(paths), paths.stateFile.readBytes())
      assertEquals(setOf("DSH_NATIVE_PROOT_STATE_VERSION", "DSH_PROOT_BIN", "PROOT_LOADER",
        "DSH_PROOT_LOADER32", "PROOT_TMP_DIR", "DSH_DEBIAN_ROOTFS", "DSH_DEBIAN_LEGACY_ROOTFS"),
        paths.stateFile.readLines().filter { it.isNotBlank() && !it.startsWith('#') }
          .map { it.substringBefore('=') }.toSet())
      assertMode(paths.stateFile, "rw-------")
      assertMode(paths.launcher, "rwx------")
      assertMode(paths.tmpDir, "rwx------")
      assertMode(File(context.filesDir, "native-proot.lock"), "rw-------")
      assertTrue(paths.launcher.canExecute())
      assertFalse(paths.stateFile.canExecute())
      val launcherBefore = attributes(paths.launcher)
      val stateBefore = attributes(paths.stateFile)
      assertEquals(env, NativeProot.prepare(context, usr))
      assertEquals(launcherBefore.fileKey(), attributes(paths.launcher).fileKey())
      assertEquals(launcherBefore.lastModifiedTime(), attributes(paths.launcher).lastModifiedTime())
      assertEquals(stateBefore.fileKey(), attributes(paths.stateFile).fileKey())
    }
    assertEquals(File(context.filesDir, "home/.dsh/workspaces/debian-rootfs"), paths.defaultRootfs)
    assertEquals(File(usr, "var/lib/proot-distro/containers/debian/rootfs"), paths.legacyRootfs)
    assertFalse(paths.defaultRootfs.exists())
    assertFalse(paths.legacyRootfs.exists())
  }

  @Test fun installedAbiHasAnExplicitPayloadContractNotADebianAcceptanceClaim() {
    val env = NativeProot.prepare(context, usr)
    val paths = pmPaths()
    when (Build.SUPPORTED_ABIS.first()) {
      "x86_64" -> {
        assertFalse("x86_64 deliberately ships no native PRoot", paths.available)
        for (name in PAYLOAD_HASHES.keys) {
          assertFalse("Unexpected x86_64 native payload: $name",
            Files.exists(File(paths.proot.parentFile, name).toPath(), NOFOLLOW_LINKS))
        }
        assertNull(paths.loader32)
        if (Build.VERSION.SDK_INT >= 28) {
          assertEquals("", env["DSH_NATIVE_PROOT_ERROR"])
          val missing = launcher(env)
          assertEquals(126, missing.code)
          assertTrue(missing.output.contains("Native PRoot payload is unavailable"))
        } else {
          assertEquals("Native PRoot requires Android API 28 or newer", env["DSH_NATIVE_PROOT_ERROR"])
          assertFalse(paths.launcher.exists())
        }
      }
      "arm64-v8a" -> {
        assertTrue("ARM64 APK must extract its engine and loader", paths.available)
        for ((name, expected) in PAYLOAD_HASHES) {
          val payload = File(paths.proot.parentFile, name)
          assertTrue("Missing executable APK payload: $name", payload.isFile && payload.canExecute())
          assertEquals("Packaged payload digest: $name", expected, sha256(payload))
        }
        assertNotNull(paths.loader32)
        if (Build.VERSION.SDK_INT >= 28) {
          assertEquals("", env["DSH_NATIVE_PROOT_ERROR"])
          // Engine only: no rootfs, guest shell or QEMU is involved.
          val version = spawn(listOf(paths.proot.absolutePath, "--version"), mapOf(
            "PROOT_LOADER" to paths.loader.absolutePath, "PROOT_TMP_DIR" to paths.tmpDir.absolutePath))
          assertEquals(0, version.code)
          assertTrue("Expected the PRoot engine version, not a guest banner",
            version.output.contains("PRoot", ignoreCase = true))
        } else {
          assertEquals("Native PRoot requires Android API 28 or newer", env["DSH_NATIVE_PROOT_ERROR"])
          assertFalse(paths.launcher.exists())
        }
      }
      else -> fail("Unsupported packaged ABI")
    }
    assertFalse(paths.defaultRootfs.exists())
  }

  @Test fun androidAtomicReplacementRepairsModeAndRejectsSymlinkTargets() {
    // Exercise java.nio on all supported Android APIs, including the API26/27
    // prepare-disabled contract. PM/assets are real; this seam bypasses only its API gate.
    val paths = pmPaths()
    fun refresh() = NativeProot.prepareFiles(context.filesDir, usr,
      { paths.proot.parentFile.absolutePath }, ::launcherAsset)
    val env = refresh()
    val originalLauncher = attributes(paths.launcher)
    paths.stateFile.writeText("obsolete-install-path-fixture\n")
    val obsolete = attributes(paths.stateFile)
    assertNotNull("Android filesystem must expose file identity", obsolete.fileKey())
    Files.setPosixFilePermissions(paths.launcher.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
    assertEquals(env, refresh())
    assertNotEquals(obsolete.fileKey(), attributes(paths.stateFile).fileKey())
    assertEquals(originalLauncher.fileKey(), attributes(paths.launcher).fileKey())
    assertMode(paths.launcher, "rwx------")
    assertArrayEquals(NativeProot.stateBytes(paths), paths.stateFile.readBytes())
    val victim = File(fixture, "untouched-data").apply { writeText("fixture sentinel") }
    val link = File(fixture, "atomic-link")
    Files.createSymbolicLink(link.toPath(), victim.toPath())
    try {
      NativeProot.writeIfChanged(link, byteArrayOf(1), executable = false)
      fail("Replacing a symlink target must fail")
    } catch (_: IOException) { }
    assertEquals("fixture sentinel", victim.readText())
    assertTrue(Files.isSymbolicLink(link.toPath()))
    assertNoTemporaryFiles()
  }

  @Test fun prepareIoFailureReturnsDiagnosticWithoutUsingStaleState() {
    val paths = pmPaths()
    // A non-regular state target forces the real Android prepare catch path.
    Files.createDirectories(paths.stateFile.toPath())
    val env = NativeProot.prepare(context, usr)
    if (Build.VERSION.SDK_INT < 28) {
      assertEquals(mapOf("DSH_NATIVE_PROOT_ERROR" to "Native PRoot requires Android API 28 or newer"), env)
      assertFalse(paths.launcher.exists())
    } else {
      assertEquals(mapOf("DSH_NATIVE_PROOT_ERROR" to "IOException"), env)
      assertTrue(paths.launcher.isFile)
      val result = launcher(env + mapOf(
        "DSH_DEBIAN_LAUNCHER" to paths.launcher.absolutePath,
        "DSH_NATIVE_PROOT_STATE" to paths.stateFile.absolutePath))
      assertEquals(126, result.code)
      assertTrue(result.output.contains("stale state will not be used"))
    }
    assertTrue(paths.stateFile.isDirectory)
    assertFalse(paths.defaultRootfs.exists())
    assertNoTemporaryFiles()
  }

  @Test fun androidConcurrentReadersSeeOnlyCompleteAtomicFiles() {
    val file = File(fixture, "atomic-data")
    val a = ByteArray(16_384) { 65 }
    val b = ByteArray(16_384) { 66 }
    NativeProot.writeIfChanged(file, a, executable = false)
    val done = AtomicBoolean(false)
    val observed = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val reader = pool.submit(Callable {
        var count = 0
        while (!done.get() && !Thread.currentThread().isInterrupted) {
          val bytes = file.readBytes()
          assertTrue("Torn atomic file", bytes.contentEquals(a) || bytes.contentEquals(b))
          count++
          observed.countDown()
          Thread.yield()
        }
        count
      })
      assertTrue("Reader never observed a file", observed.await(5, TimeUnit.SECONDS))
      val writer = pool.submit(Callable {
        repeat(24) { NativeProot.writeIfChanged(file, if (it % 2 == 0) b else a, false) }
      })
      writer.get(20, TimeUnit.SECONDS)
      done.set(true)
      assertTrue(reader.get(5, TimeUnit.SECONDS) > 0)
    } finally {
      done.set(true)
      pool.shutdownNow()
      assertTrue("Atomic workers leaked", pool.awaitTermination(5, TimeUnit.SECONDS))
    }
    assertMode(file, "rw-------")
    assertNoTemporaryFiles()
  }

  @Test fun prepareNeverCreatesMigratesOrDeletesEitherRootfs() {
    val paths = pmPaths()
    val legacyData = fixtureFile(paths.legacyRootfs, "etc/sentinel", "legacy fixture")
    val legacyLink = File(paths.legacyRootfs, "sentinel-link")
    Files.createSymbolicLink(legacyLink.toPath(), File("etc/sentinel").toPath())
    val legacyHash = sha256(legacyData)
    val expectedError = if (Build.VERSION.SDK_INT >= 28) "" else "Native PRoot requires Android API 28 or newer"
    assertEquals(expectedError, NativeProot.prepare(context, usr)["DSH_NATIVE_PROOT_ERROR"])
    assertFalse(paths.defaultRootfs.exists())
    assertEquals(legacyHash, sha256(legacyData))
    assertEquals(File("etc/sentinel").toPath(), Files.readSymbolicLink(legacyLink.toPath()))
    val current = fixtureFile(paths.defaultRootfs, "etc/sentinel", "workspace fixture")
    val currentHash = sha256(current)
    val currentIdentity = attributes(current).fileKey()
    repeat(2) { assertEquals(expectedError, NativeProot.prepare(context, usr)["DSH_NATIVE_PROOT_ERROR"]) }
    assertEquals(currentHash, sha256(current))
    assertEquals(currentIdentity, attributes(current).fileKey())
    assertEquals(legacyHash, sha256(legacyData))
    assertFalse(File(paths.defaultRootfs, "bin/sh").exists())
    assertFalse(File(paths.legacyRootfs, "bin/sh").exists())
  }

  @Test fun launcherHelpMissingPayloadAndLegacySelectionUseBoundedSystemShell() {
    // Missing-payload control-flow fixture is independent of the installed ABI.
    val emptyNative = File(fixture, "empty-native").apply { mkdirs() }
    val env = NativeProot.prepareFiles(context.filesDir, usr, { emptyNative.absolutePath }, ::launcherAsset)
    val help = launcher(env, "--help")
    assertEquals(0, help.code)
    assertTrue(help.output.contains("usage: dsh-debian"))
    assertTrue(help.output.contains("workspaces/debian-rootfs"))
    assertEquals(2, launcher(env, "--rootfs", "relative").code)
    assertEquals(2, launcher(env, "--unknown").code)
    assertEquals(2, launcher(env, "--rootfs").code)
    val missing = launcher(env)
    assertEquals(126, missing.code)
    assertTrue(missing.output.contains("Native PRoot payload is unavailable"))
    val disabled = launcher(env + ("DSH_NATIVE_PROOT_ERROR" to "IOException"))
    assertEquals(126, disabled.code)
    assertTrue(disabled.output.contains("stale state will not be used"))

    // System executables satisfy only the launcher's -f/-x preflight. Both rootfs
    // fixtures intentionally lack bin/sh, so these are NEVER exec'd as PRoot.
    val paths = requireNotNull(NativeProot.resolve(emptyNative.absolutePath, context.filesDir, usr))
      .copy(proot = File("/system/bin/sh"), loader = File("/system/bin/sh"))
    val data = fixtureFile(paths.legacyRootfs, "etc/sentinel", "legacy selection fixture")
    val before = sha256(data)
    NativeProot.writeIfChanged(paths.stateFile, NativeProot.stateBytes(paths), executable = false)
    val default = launcher(env)
    assertEquals(1, default.code)
    assertTrue(default.output.contains("Debian rootfs not found or incomplete: ${paths.defaultRootfs}"))
    assertTrue(default.output.contains("Select it explicitly with --rootfs PATH"))
    val explicit = launcher(env, "--rootfs", paths.legacyRootfs.absolutePath)
    assertEquals(1, explicit.code)
    assertTrue(explicit.output.contains("Debian rootfs not found or incomplete: ${paths.legacyRootfs}"))
    assertFalse(paths.defaultRootfs.exists())
    assertEquals(before, sha256(data))
  }

  companion object {
    // Pinned engine payload digests from scripts/native-proot.json, not guest evidence.
    private val PAYLOAD_HASHES = mapOf(
      "libproot.so" to "7ac60a8fd3627bdf73c1bfa05dfe9a505b521fa77001f735563cf104b38b5ee6",
      "libproot-loader.so" to "b5b856b8b0d00d881d44d9fabfd6386499a607ceb9e8a5b95df94a3a2b7b451e",
      "libproot-loader32.so" to "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34",
    )
  }
}

/** Shared test-only isolation and process cleanup; no production lifecycle calls. */
abstract class NativeProotAndroidFixture {
  protected lateinit var target: Context
  protected lateinit var fixture: File
  protected lateinit var context: Context
  protected lateinit var usr: File

  @Before fun setUpNativeProotFixture() {
    target = InstrumentationRegistry.getInstrumentation().targetContext
    fixture = Files.createTempDirectory(target.cacheDir.toPath(), "native-proot-acceptance-").toFile()
    assertTrue("Android filesystem contract requires API 26+", Build.VERSION.SDK_INT >= 26)
    assertTrue("Only the two packaged 64-bit ABI contracts are covered",
      Build.SUPPORTED_ABIS.first() in setOf("x86_64", "arm64-v8a"))
    context = FixtureContext(target, fixture)
    usr = File(context.filesDir, "usr")
    Files.createDirectories(usr.toPath())
  }

  @After fun cleanUpNativeProotFixture() {
    if (::fixture.isInitialized) {
      // Do not follow the read-only links to the installed snapshot.
      SnapshotFs.deletePath(fixture)
      assertFalse(Files.exists(fixture.toPath(), NOFOLLOW_LINKS))
    }
  }

  internal fun pmPaths(): NativeProot.Paths {
    @Suppress("DEPRECATION")
    val info = target.packageManager.getApplicationInfo(target.packageName, 0)
    assertEquals(target.applicationInfo.nativeLibraryDir, info.nativeLibraryDir)
    assertTrue("PM must supply an absolute native library directory", File(info.nativeLibraryDir).isAbsolute)
    return requireNotNull(NativeProot.resolve(info.nativeLibraryDir, context.filesDir, usr))
  }

  protected fun launcher(env: Map<String, String>, vararg args: String): Result =
    spawn(listOf("/system/bin/sh", requireNotNull(env["DSH_DEBIAN_LAUNCHER"])) + args, env)

  protected data class Result(val code: Int, val output: String)

  /** No inherited environment, unbounded capture, stdin, daemon or background shell. */
  protected fun spawn(argv: List<String>, env: Map<String, String>): Result {
    val builder = ProcessBuilder(argv).directory(fixture).redirectErrorStream(true)
    builder.environment().apply {
      clear()
      put("PATH", "/system/bin")
      put("HOME", context.filesDir.absolutePath)
      put("TMPDIR", context.cacheDir.absolutePath)
      putAll(env)
    }
    val process = builder.start()
    val reader = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "native-proot-output").apply { isDaemon = true }
    }
    try {
      process.outputStream.close()
      val output = reader.submit(Callable {
        process.inputStream.use { input ->
          val bytes = ByteArrayOutputStream()
          val buffer = ByteArray(2048)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > 32_768) {
              process.destroyForcibly()
              throw AssertionError("Subprocess exceeded the 32 KiB output limit")
            }
            bytes.write(buffer, 0, count)
          }
          bytes.toString("UTF-8")
        }
      })
      assertTrue("Subprocess exceeded 15 seconds", process.waitFor(15, TimeUnit.SECONDS))
      return Result(process.exitValue(), output.get(5, TimeUnit.SECONDS))
    } finally {
      process.destroyForcibly()
      val exited = process.waitFor(5, TimeUnit.SECONDS)
      process.inputStream.close()
      process.errorStream.close()
      process.outputStream.close()
      reader.shutdownNow()
      assertTrue("Output reader leaked", reader.awaitTermination(5, TimeUnit.SECONDS))
      assertTrue("Subprocess leaked", exited)
    }
  }

  protected fun launcherAsset() = target.assets.open("native-proot/dsh-debian").use { it.readBytes() }
  protected fun attributes(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  protected fun assertMode(file: File, expected: String) = assertEquals(expected,
    PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS)))
  protected fun assertNoTemporaryFiles() {
    Files.walk(fixture.toPath()).use { paths ->
      assertFalse("Atomic write left temporary files", paths.anyMatch { it.fileName.toString().endsWith(".tmp") })
    }
  }
  protected fun fixtureFile(root: File, name: String, text: String) = File(root, name).apply {
    Files.createDirectories(parentFile.toPath())
    writeText(text)
  }
  protected fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
  }

  /** PM and AssetManager stay real; no live private settings or key files are read. */
  private class FixtureContext(base: Context, root: File) : ContextWrapper(base) {
    private val privateFiles = File(root, "files").apply { mkdirs() }
    private val privateCache = File(root, "cache").apply { mkdirs() }
    override fun getFilesDir(): File = privateFiles
    override fun getCacheDir(): File = privateCache
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = emptyPreferences
    // shellEnv only reads booleans; reject new preference APIs instead of reading live data.
    private val emptyPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
      arrayOf(SharedPreferences::class.java)) { _, method, args ->
      when (method.name) {
        "getBoolean" -> args!![1]
        else -> throw AssertionError("Unexpected preference access: ${method.name}")
      }
    } as SharedPreferences
  }
}
