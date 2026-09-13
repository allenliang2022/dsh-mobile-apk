package com.dsharnessmobile.shell

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** All tested entry points use java.nio, not Android Context/Log/runtime stubs. */
class NativeProotTest {
  private val base = Files.createTempDirectory("native-proot-test-").toFile()
  private val filesDir = File(base, "files").apply { mkdirs() }
  private val usrDir = File(filesDir, "usr").apply { mkdirs() }
  private val nativeDir = File(base, "apk installed 'with spaces'/lib/arm64").apply { mkdirs() }
  private val launcher = "#!/system/bin/sh\nprintf '%s\\n' launcher\n".toByteArray()

  @After fun cleanUp() { base.deleteRecursively() }

  private fun paths(dir: File = nativeDir) = NativeProot.resolve(dir.absolutePath, filesDir, usrDir)!!
  private fun payload(name: String, dir: File = nativeDir): File = File(dir, name).apply {
    parentFile.mkdirs()
    writeText("fake ELF fixture")
    Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwx------"))
  }
  private fun refresh(dir: File = nativeDir): Map<String, String> =
    NativeProot.prepareFiles(filesDir, usrDir, { dir.absolutePath }, { launcher })
  private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS))
  private fun attributes(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)

  private fun compatibilityWrapper(root: File = filesDir) = File(root, "native-proot-debian-compat.sh")
  private fun compatibilityEntry(usr: File = usrDir) = File(usr, "bin/debian")
  private fun refreshWithWarnings(warnings: MutableList<String>): Map<String, String> =
    NativeProot.prepareFiles(filesDir, usrDir, { nativeDir.absolutePath }, { launcher },
      onCompatibilityWarning = { warnings.add(it) })

  @Test fun `absent legacy entrance creates private wrapper and exact owned symlink`() {
    val warnings = mutableListOf<String>()
    val env = refreshWithWarnings(warnings)
    val wrapper = compatibilityWrapper()
    val entry = compatibilityEntry()
    assertTrue(Files.isSymbolicLink(entry.toPath()))
    assertEquals(wrapper.toPath(), Files.readSymbolicLink(entry.toPath()))
    assertEquals("#!/system/bin/sh\nexec /system/bin/sh " +
      NativeProot.shellQuote(paths().launcher.absolutePath) + " --bind-sdcard -- \"\$@\"\n",
      wrapper.readText())
    assertEquals("rwx------", mode(wrapper))
    assertFalse(wrapper.toPath().startsWith(usrDir.toPath()))
    assertFalse(wrapper.readText().contains(nativeDir.absolutePath))
    assertArrayEquals(launcher, paths().launcher.readBytes())
    assertEquals(paths().launcher.absolutePath, env["DSH_DEBIAN_LAUNCHER"])
    assertEquals("", env["DSH_NATIVE_PROOT_ERROR"])
    assertTrue(warnings.isEmpty())
  }

  @Test fun `repeated legacy preparation preserves wrapper and link inode and timestamps`() {
    val warnings = mutableListOf<String>()
    refreshWithWarnings(warnings)
    val wrapper = compatibilityWrapper()
    val entry = compatibilityEntry()
    val time = FileTime.fromMillis(1_600_000_000_000)
    Files.setLastModifiedTime(wrapper.toPath(), time)
    val beforeWrapper = attributes(wrapper)
    val beforeEntry = attributes(entry)
    Files.setPosixFilePermissions(wrapper.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
    repeat(3) { refreshWithWarnings(warnings) }
    assertEquals(beforeWrapper.fileKey(), attributes(wrapper).fileKey())
    assertEquals(time, attributes(wrapper).lastModifiedTime())
    assertEquals(beforeEntry.fileKey(), attributes(entry).fileKey())
    assertEquals(beforeEntry.lastModifiedTime(), attributes(entry).lastModifiedTime())
    assertEquals("rwx------", mode(wrapper))
    assertTrue(warnings.isEmpty())
  }

  @Test fun `usr replacement recreates legacy link without rewriting surviving wrapper`() {
    refresh()
    val wrapper = compatibilityWrapper()
    val before = attributes(wrapper)
    // Model only usr replacement, not SnapshotTransaction retention policy.
    Files.delete(compatibilityEntry().toPath())
    assertTrue(usrDir.deleteRecursively())
    assertTrue(usrDir.mkdirs())
    refresh(File(base, "replacement APK/lib"))
    assertEquals(wrapper.toPath(), Files.readSymbolicLink(compatibilityEntry().toPath()))
    assertEquals(before.fileKey(), attributes(wrapper).fileKey())
    assertEquals(before.lastModifiedTime(), attributes(wrapper).lastModifiedTime())
    assertArrayEquals(launcher, paths().launcher.readBytes())
  }

  @Test fun `custom regular directory and symlink legacy entries are preserved without opening them`() {
    val entry = compatibilityEntry()
    entry.parentFile.mkdirs()
    val custom = File(filesDir, "custom user launcher").apply { writeText("do not run or rewrite") }
    val missing = File(filesDir, "missing custom target")
    for (kind in listOf("regular", "directory", "symlink", "dangling", "relative-owned", "indirect-owned")) {
      val indirect = File(filesDir, "indirect")
      when (kind) {
        "regular" -> {
          entry.writeText("custom legacy command")
          Files.setPosixFilePermissions(entry.toPath(), PosixFilePermissions.fromString("---------"))
        }
        "directory" -> { entry.mkdir(); File(entry, "sentinel").writeText("preserve child") }
        "symlink" -> Files.createSymbolicLink(entry.toPath(), custom.toPath())
        "dangling" -> Files.createSymbolicLink(entry.toPath(), missing.toPath())
        "relative-owned" -> Files.createSymbolicLink(entry.toPath(),
          entry.parentFile.toPath().relativize(compatibilityWrapper().toPath()))
        "indirect-owned" -> {
          Files.createSymbolicLink(indirect.toPath(), compatibilityWrapper().toPath())
          Files.createSymbolicLink(entry.toPath(), indirect.toPath())
        }
      }
      val before = attributes(entry)
      val beforeMode = mode(entry)
      val beforeTarget = if (before.isSymbolicLink) Files.readSymbolicLink(entry.toPath()) else null
      val warnings = mutableListOf<String>()
      assertEquals("", refreshWithWarnings(warnings)["DSH_NATIVE_PROOT_ERROR"])
      assertEquals(kind, before.fileKey(), attributes(entry).fileKey())
      assertEquals(kind, before.lastModifiedTime(), attributes(entry).lastModifiedTime())
      assertEquals(kind, beforeMode, mode(entry))
      if (beforeTarget != null) assertEquals(beforeTarget, Files.readSymbolicLink(entry.toPath()))
      assertFalse("Foreign entrance must not trigger wrapper generation", compatibilityWrapper().exists())
      assertEquals(listOf("Existing legacy debian entrance is not managed; preserved unchanged"), warnings)
      assertEquals("do not run or rewrite", custom.readText())
      assertArrayEquals(launcher, paths().launcher.readBytes())
      if (kind == "regular") {
        Files.setPosixFilePermissions(entry.toPath(), PosixFilePermissions.fromString("rw-------"))
        assertEquals("custom legacy command", entry.readText())
      }
      if (kind == "directory") {
        val sentinel = File(entry, "sentinel")
        assertEquals("preserve child", sentinel.readText())
        Files.delete(sentinel.toPath())
      }
      Files.delete(entry.toPath())
      Files.deleteIfExists(indirect.toPath())
    }
  }

  @Test fun `managed link repairs missing or stale wrapper without replacing link`() {
    refresh()
    val entry = compatibilityEntry()
    val wrapper = compatibilityWrapper()
    val expected = wrapper.readBytes()
    val beforeEntry = attributes(entry)
    Files.delete(wrapper.toPath())
    assertFalse(entry.exists()) // Dangling, but the exact owned target is still recognized.
    val warnings = mutableListOf<String>()
    refreshWithWarnings(warnings)
    assertArrayEquals(expected, wrapper.readBytes())
    wrapper.writeText("outdated managed wrapper")
    refreshWithWarnings(warnings)
    assertArrayEquals(expected, wrapper.readBytes())
    assertEquals(beforeEntry.fileKey(), attributes(entry).fileKey())
    assertEquals(beforeEntry.lastModifiedTime(), attributes(entry).lastModifiedTime())
    assertTrue(warnings.isEmpty())
  }

  @Test fun `foreign alias also leaves a previously generated wrapper unchanged`() {
    refresh()
    Files.delete(compatibilityEntry().toPath())
    compatibilityEntry().writeText("custom replacement")
    compatibilityWrapper().writeText("managed but no longer referenced")
    val before = attributes(compatibilityWrapper())
    refresh()
    assertEquals("custom replacement", compatibilityEntry().readText())
    assertEquals("managed but no longer referenced", compatibilityWrapper().readText())
    assertEquals(before.fileKey(), attributes(compatibilityWrapper()).fileKey())
    assertEquals(before.lastModifiedTime(), attributes(compatibilityWrapper()).lastModifiedTime())
  }

  @Test fun `optional wrapper failure preserves foreign referent and maintained launcher availability`() {
    val wrapper = compatibilityWrapper()
    val victim = File(filesDir, "private user file").apply { writeText("secret sentinel") }
    for (kind in listOf("directory", "symlink")) {
      if (kind == "directory") wrapper.mkdir()
      else Files.createSymbolicLink(wrapper.toPath(), victim.toPath())
      val warnings = mutableListOf<String>()
      val env = refreshWithWarnings(warnings)
      assertEquals("", env["DSH_NATIVE_PROOT_ERROR"])
      assertEquals(paths().launcher.absolutePath, env["DSH_DEBIAN_LAUNCHER"])
      assertArrayEquals(launcher, paths().launcher.readBytes())
      assertArrayEquals(NativeProot.stateBytes(paths()), paths().stateFile.readBytes())
      assertFalse(Files.exists(compatibilityEntry().toPath(), NOFOLLOW_LINKS))
      assertEquals(listOf("Legacy debian compatibility entrance unavailable (IOException)"), warnings)
      assertEquals("secret sentinel", victim.readText())
      Files.delete(wrapper.toPath())
    }
  }

  @Test fun `legacy wrapper quotes stable launcher path and forwards sdcard contract and exact argv`() {
    val specialFiles = File(base, "files with 'quotes' \$HOME;`id`").apply { mkdirs() }
    val specialUsr = File(specialFiles, "usr with 'quotes'")
    val maintained = File(specialUsr, "bin/dsh-debian")
    val argvFixture = "#!/system/bin/sh\nprintf '%s\\000' \"\$@\"\n".toByteArray()
    NativeProot.prepareFiles(specialFiles, specialUsr, { nativeDir.absolutePath }, { argvFixture })
    val wrapper = compatibilityWrapper(specialFiles).readText()
    assertEquals("#!/system/bin/sh\nexec /system/bin/sh " +
      NativeProot.shellQuote(maintained.absolutePath) + " --bind-sdcard -- \"\$@\"\n", wrapper)
    assertEquals(compatibilityWrapper(specialFiles).toPath(),
      Files.readSymbolicLink(compatibilityEntry(specialUsr).toPath()))
    assertArrayEquals(argvFixture, maintained.readBytes())
    // A desktop JVM has no /system/bin/sh; adapt only that fixed interpreter token.
    val portableBody = wrapper.substringAfter('\n').replaceFirst("exec /system/bin/sh ", "exec sh ")
    for (args in listOf(emptyList(), listOf("", "space arg", "a'b", "\$HOME;`id`", "first\nsecond", "--help", "--"))) {
      val process = ProcessBuilder(listOf("sh", "-c", portableBody, "legacy-test") + args).start()
      val actual = process.inputStream.readBytes().toString(Charsets.UTF_8)
      assertEquals(process.errorStream.bufferedReader().readText(), 0, process.waitFor())
      assertEquals((listOf("--bind-sdcard", "--") + args).joinToString("\u0000", postfix = "\u0000"), actual)
    }
  }

  @Test fun `resolve uses only PackageManager path and snapshot-protected default rootfs`() {
    val proot = payload("libproot.so")
    val loader = payload("libproot-loader.so")
    val resolved = paths()
    assertEquals(proot, resolved.proot)
    assertEquals(loader, resolved.loader)
    assertEquals(File(filesDir, "tmp/proot-native"), resolved.tmpDir)
    assertEquals(File(filesDir, "native-proot.env"), resolved.stateFile)
    assertEquals(File(usrDir, "bin/dsh-debian"), resolved.launcher)
    assertEquals(File(filesDir, "home/.dsh/workspaces/debian-rootfs"), resolved.defaultRootfs)
    assertEquals(File(usrDir, "var/lib/proot-distro/containers/debian/rootfs"), resolved.legacyRootfs)
    assertTrue(resolved.available)
    assertNull(resolved.loader32)
  }

  @Test fun `resolve does not guess missing PackageManager directory`() {
    assertNull(NativeProot.resolve(null, filesDir, usrDir))
    assertNull(NativeProot.resolve("", filesDir, usrDir))
    assertNull(NativeProot.resolve("  ", filesDir, usrDir))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `resolve rejects relative PackageManager paths`() {
    NativeProot.resolve("relative/lib", filesDir, usrDir)
  }

  @Test fun `availability needs regular executable engine and loader`() {
    assertFalse(paths().available)
    payload("libproot.so")
    assertFalse(paths().available)
    val loader = payload("libproot-loader.so")
    assertTrue(paths().available)
    Files.setPosixFilePermissions(loader.toPath(), PosixFilePermissions.fromString("rw-------"))
    assertFalse(paths().available)
    loader.delete()
    loader.mkdir()
    assertFalse(paths().available)
  }

  @Test fun `loader32 is optional executable from exactly the same directory`() {
    payload("libproot-loader32.so", File(base, "other-abi"))
    assertNull(paths().loader32)
    val matching = payload("libproot-loader32.so")
    assertEquals(matching, paths().loader32)
    Files.setPosixFilePermissions(matching.toPath(), PosixFilePermissions.fromString("rw-------"))
    assertNull(paths().loader32)
  }

  @Test fun `missing payload still refreshes diagnostic state instead of retaining old native paths`() {
    payload("libproot.so")
    payload("libproot-loader.so")
    refresh()
    val next = File(base, "replacement-install/lib/arm64")
    val env = refresh(next)
    assertFalse(paths(next).available)
    assertEquals(File(next, "libproot.so").absolutePath, env["DSH_PROOT_BIN"])
    assertFalse(paths().stateFile.readText().contains(nativeDir.absolutePath))
    assertArrayEquals(NativeProot.stateBytes(paths(next)), paths().stateFile.readBytes())
  }

  @Test fun `missing rootfs is not created and existing legacy rootfs is untouched`() {
    val resolved = paths()
    val sentinel = File(resolved.legacyRootfs, "etc/user-data").apply {
      parentFile.mkdirs(); writeText("do not migrate")
    }
    refresh()
    assertFalse(resolved.defaultRootfs.exists())
    assertEquals("do not migrate", sentinel.readText())
    assertEquals(resolved.defaultRootfs.absolutePath, refresh()["DSH_DEBIAN_ROOTFS"])
  }

  @Test fun `state contains only generated paths and clears removed optional loader`() {
    val loader32 = payload("libproot-loader32.so")
    assertEquals(loader32.absolutePath, refresh()["DSH_PROOT_LOADER32"])
    loader32.delete()
    val env = refresh()
    assertEquals("", env["DSH_PROOT_LOADER32"])
    assertEquals("", env["DSH_NATIVE_PROOT_ERROR"])
    val text = paths().stateFile.readText()
    assertTrue(text.contains("DSH_PROOT_LOADER32=''\n"))
    assertFalse(text.contains("LD_PRELOAD="))
    assertEquals(setOf("DSH_NATIVE_PROOT_STATE_VERSION", "DSH_PROOT_BIN", "PROOT_LOADER",
      "DSH_PROOT_LOADER32", "PROOT_TMP_DIR", "DSH_DEBIAN_ROOTFS", "DSH_DEBIAN_LEGACY_ROOTFS"),
      text.lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }.map { it.substringBefore('=') }.toSet())
  }

  @Test fun `shell quoting round-trips metacharacters empty strings quotes and newlines`() {
    assertEquals("'a'\\''b'", NativeProot.shellQuote("a'b"))
    val values = listOf("", "ordinary", "space path", "a'b", "\$HOME;`id`\\\"*?", "first\nsecond")
    val command = "printf '%s\\000' " + values.joinToString(" ") { NativeProot.shellQuote(it) }
    val process = ProcessBuilder("sh", "-c", command).start()
    val actual = process.inputStream.readBytes().toString(Charsets.UTF_8)
    assertEquals(0, process.waitFor())
    assertEquals(values.joinToString("\u0000", postfix = "\u0000"), actual)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `shell quoting rejects unrepresentable NUL`() { NativeProot.shellQuote("a\u0000b") }

  @Test fun `generated state can be sourced without evaluating path text`() {
    val strange = File(base, "apk '\$HOME;`id`\nline/lib")
    val env = refresh(strange)
    val process = ProcessBuilder("sh", "-c",
      ". \"\$1\"; printf '%s\\000%s\\000' \"\$DSH_PROOT_BIN\" \"\$DSH_DEBIAN_ROOTFS\"",
      "state-test", paths().stateFile.absolutePath).start()
    val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
    assertEquals(0, process.waitFor())
    assertEquals(env["DSH_PROOT_BIN"] + "\u0000" + env["DSH_DEBIAN_ROOTFS"] + "\u0000", text)
  }

  @Test fun `state lock and launcher use owner-only permissions`() {
    refresh()
    assertEquals("rw-------", mode(paths().stateFile))
    assertEquals("rwx------", mode(paths().launcher))
    assertEquals("rwx------", mode(paths().tmpDir))
    assertEquals("rw-------", mode(File(filesDir, "native-proot.lock")))
  }

  @Test fun `idempotent refresh preserves content inode and timestamps while repairing mode`() {
    refresh()
    val installed = listOf(paths().stateFile, paths().launcher)
    val time = FileTime.fromMillis(1_600_000_000_000)
    installed.forEach { Files.setLastModifiedTime(it.toPath(), time) }
    val before = installed.map(::attributes)
    installed.forEach { Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rwxrwxrwx")) }
    Files.setPosixFilePermissions(paths().tmpDir.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
    refresh()
    installed.forEachIndexed { index, file ->
      assertEquals(before[index].fileKey(), attributes(file).fileKey())
      assertEquals(time, attributes(file).lastModifiedTime())
    }
    assertEquals("rw-------", mode(paths().stateFile))
    assertEquals("rwx------", mode(paths().launcher))
    assertEquals("rwx------", mode(paths().tmpDir))
  }

  @Test fun `APK path updates state but never rewrite unchanged stable launcher`() {
    refresh()
    val oldLauncher = attributes(paths().launcher)
    val oldState = attributes(paths().stateFile)
    refresh(File(base, "new APK/lib/arm64"))
    assertEquals(oldLauncher.fileKey(), attributes(paths().launcher).fileKey())
    assertEquals(oldLauncher.lastModifiedTime(), attributes(paths().launcher).lastModifiedTime())
    assertNotEquals(oldState.fileKey(), attributes(paths().stateFile).fileKey())
  }

  @Test fun `concurrent preparations are serialized and idempotent`() {
    val initial = refresh()
    val oldState = attributes(paths().stateFile)
    val oldLauncher = attributes(paths().launcher)
    val pool = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    try {
      val futures = (1..32).map { pool.submit(Callable { start.await(); refresh() }) }
      start.countDown()
      futures.forEach { assertEquals(initial, it.get(20, TimeUnit.SECONDS)) }
    } finally { pool.shutdownNow() }
    assertEquals(oldState.fileKey(), attributes(paths().stateFile).fileKey())
    assertEquals(oldState.lastModifiedTime(), attributes(paths().stateFile).lastModifiedTime())
    assertEquals(oldLauncher.fileKey(), attributes(paths().launcher).fileKey())
    assertFalse(base.walkTopDown().any { it.name.endsWith(".tmp") })
  }

  @Test fun `persistent file lock serializes another JVM process`() {
    refresh()
    val before = paths().stateFile.readBytes()
    val nextDir = File(base, "replacement-process/lib")
    val classpath = (System.getProperty("java.class.path").split(File.pathSeparator) +
      listOf(NativeProotLockWorker::class.java, NativeProot::class.java, Unit::class.java).map {
        File(it.protectionDomain.codeSource.location.toURI()).absolutePath
      }).distinct().joinToString(File.pathSeparator)
    val executor = Executors.newSingleThreadExecutor()
    var child: Process? = null
    try {
      FileChannel.open(File(filesDir, "native-proot.lock").toPath(), WRITE).use { channel ->
        channel.lock().use {
          val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath, "com.dsharnessmobile.shell.NativeProotLockWorker",
            filesDir.absolutePath, usrDir.absolutePath, nextDir.absolutePath).start()
          child = process
          val ready = executor.submit(Callable { process.inputStream.bufferedReader().readLine() })
          assertEquals("ready", ready.get(20, TimeUnit.SECONDS))
          assertFalse("Child bypassed held lock", process.waitFor(200, TimeUnit.MILLISECONDS))
          assertArrayEquals(before, paths().stateFile.readBytes())
        }
      }
      val process = child!!
      assertTrue("Child did not finish after lock release", process.waitFor(20, TimeUnit.SECONDS))
      assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
      assertArrayEquals(NativeProot.stateBytes(paths(nextDir)), paths().stateFile.readBytes())
      assertEquals("rw-------", mode(File(filesDir, "native-proot.lock")))
    } finally { child?.destroyForcibly(); executor.shutdownNow() }
  }

  @Test fun `concurrent atomic writes expose only complete old or new bytes`() {
    val target = File(filesDir, "atomic-state")
    val a = ByteArray(16_384) { 65 }
    val b = ByteArray(16_384) { 66 }
    NativeProot.writeIfChanged(target, a, false)
    val done = AtomicBoolean(false)
    val pool = Executors.newFixedThreadPool(5)
    val observed = CountDownLatch(1)
    try {
      val reader = pool.submit(Callable {
        var count = 0
        while (!done.get()) {
          val bytes = target.readBytes()
          assertTrue("Torn file observed", bytes.contentEquals(a) || bytes.contentEquals(b))
          count++
          observed.countDown()
          Thread.yield()
        }
        count
      })
      assertTrue(observed.await(10, TimeUnit.SECONDS))
      val writers = (0..3).map { writer -> pool.submit(Callable {
        repeat(15) { NativeProot.writeIfChanged(target, if ((it + writer) % 2 == 0) a else b, false) }
      }) }
      writers.forEach { it.get(20, TimeUnit.SECONDS) }
      done.set(true)
      assertTrue(reader.get(10, TimeUnit.SECONDS) > 0)
    } finally { done.set(true); pool.shutdownNow() }
    assertEquals("rw-------", mode(target))
    assertFalse(base.walkTopDown().any { it.name.endsWith(".tmp") })
  }

  @Test fun `non-regular target failure does not corrupt target or leave temp files`() {
    val directory = File(filesDir, "not-a-file").apply { mkdir() }
    try {
      NativeProot.writeIfChanged(directory, launcher, false)
      fail("Expected IOException")
    } catch (_: IOException) { /* Exact expected filesystem failure only. */ }
    assertTrue(directory.isDirectory)
    assertFalse(base.walkTopDown().any { it.name.endsWith(".tmp") })
  }

  @Test fun `symlink targets and temporary directories are rejected without touching referents`() {
    val victim = File(filesDir, "user-data").apply { writeText("untouched") }
    val link = File(filesDir, "state-link")
    Files.createSymbolicLink(link.toPath(), victim.toPath())
    try {
      NativeProot.writeIfChanged(link, launcher, false)
      fail("Expected IOException")
    } catch (_: IOException) { }
    assertEquals("untouched", victim.readText())
    val tmpParent = paths().tmpDir.parentFile.apply { mkdirs() }
    val victimDir = File(tmpParent, "real-dir").apply { mkdir() }
    Files.createSymbolicLink(paths().tmpDir.toPath(), victimDir.toPath())
    try { refresh(); fail("Expected IOException") } catch (_: IOException) { }
    assertFalse(paths().stateFile.exists())
  }

  @Test fun `I O and unrelated provider failures propagate from pure JVM seam`() {
    refresh()
    val state = paths().stateFile.readBytes()
    val exceptions = listOf(IOException("private detail"), SecurityException("private detail"), AssertionError("bug"))
    for (error in exceptions) {
      try {
        NativeProot.prepareFiles(filesDir, usrDir, { nativeDir.absolutePath }, { throw error })
        fail("Expected the original error")
      } catch (actual: Throwable) {
        assertSame(error, actual)
      }
      assertArrayEquals(state, paths().stateFile.readBytes())
    }
    assertEquals("", refresh()["DSH_NATIVE_PROOT_ERROR"])
  }

  @Test fun `missing PackageManager metadata fails rather than guessing or overwriting state`() {
    refresh()
    val before = paths().stateFile.readBytes()
    try {
      NativeProot.prepareFiles(filesDir, usrDir, { null }, { launcher })
      fail("Expected IOException")
    } catch (_: IOException) { }
    assertArrayEquals(before, paths().stateFile.readBytes())
  }
}

/** Separate process entry point: verifies the OS lock, not just the JVM monitor. */
object NativeProotLockWorker {
  @JvmStatic fun main(args: Array<String>) {
    println("ready")
    System.out.flush()
    NativeProot.prepareFiles(File(args[0]), File(args[1]), { args[2] },
      { "#!/system/bin/sh\nprintf '%s\\n' launcher\n".toByteArray() })
  }
}
