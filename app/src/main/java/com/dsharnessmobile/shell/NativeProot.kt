package com.dsharnessmobile.shell

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Uses only PackageManager's extracted nativeLibraryDir for the executable payload.
 * Never copies ELF files into writable app data. PRoot is not a security sandbox.
 *
 * State contains an allowlist of paths, never the engine environment or credentials.
 * The generated launcher is stable across APK updates; only state names the current
 * install directory. Each file is atomically replaced (not a two-file transaction).
 */
object NativeProot {
  private const val LAUNCHER_ASSET = "native-proot/dsh-debian"
  private val FILE_MODE = PosixFilePermissions.fromString("rw-------")
  private val EXEC_MODE = PosixFilePermissions.fromString("rwx------")

  data class Paths(
    val proot: File,
    val loader: File,
    val loader32: File?,
    val tmpDir: File,
    val stateFile: File,
    val launcher: File,
    val defaultRootfs: File,
    val legacyRootfs: File,
  ) {
    val available: Boolean
      get() = proot.isFile && proot.canExecute() && loader.isFile && loader.canExecute()
  }

  internal fun resolve(nativeLibraryDir: String?, filesDir: File, usrDir: File): Paths? {
    if (nativeLibraryDir.isNullOrBlank()) return null
    val nativeDir = File(nativeLibraryDir)
    require(nativeDir.isAbsolute) { "PackageManager nativeLibraryDir must be absolute" }
    return Paths(
      proot = File(nativeDir, "libproot.so"),
      loader = File(nativeDir, "libproot-loader.so"),
      // Packaging verifies the ELF architecture; never search another ABI directory.
      loader32 = File(nativeDir, "libproot-loader32.so").takeIf { it.isFile && it.canExecute() },
      tmpDir = File(filesDir, "tmp/proot-native"),
      stateFile = File(filesDir, "native-proot.env"),
      launcher = File(usrDir, "bin/dsh-debian"),
      defaultRootfs = File(filesDir, "home/.dsh/workspaces/debian-rootfs"),
      legacyRootfs = File(usrDir, "var/lib/proot-distro/containers/debian/rootfs"),
    )
  }

  /**
   * Query PackageManager on every refresh, including after replacement installation.
   * Missing payload/rootfs is diagnosed by the launcher, not a reason to retain an
   * obsolete /data/app path. The optional runtime cannot block engine startup on I/O/access failure.
   * Programming errors still propagate; failed refreshes explicitly disable launch.
   */
  fun prepare(context: Context, usrDir: File): Map<String, String> {
    if (Build.VERSION.SDK_INT < 28) {
      return mapOf("DSH_NATIVE_PROOT_ERROR" to "Native PRoot requires Android API 28 or newer")
    }
    return try {
      prepareFiles(
        filesDir = context.filesDir,
        usrDir = usrDir,
        nativeDirectory = {
          @Suppress("DEPRECATION")
          context.packageManager.getApplicationInfo(context.packageName, 0).nativeLibraryDir
        },
        launcherBytes = { context.assets.open(LAUNCHER_ASSET).use { it.readBytes() } },
      )
    } catch (e: IOException) {
      unavailable(e)
    } catch (e: SecurityException) {
      unavailable(e)
    }
  }

  private fun unavailable(error: Exception): Map<String, String> {
    // Do not log arbitrary exception messages (they may include user data).
    val kind = error.javaClass.simpleName
    Log.w("dsh-native-proot", "Native PRoot preparation failed ($kind)")
    return mapOf("DSH_NATIVE_PROOT_ERROR" to kind)
  }

  /** Pure JVM seam: both providers are evaluated inside the cross-process lock. */
  @Synchronized
  internal fun prepareFiles(
    filesDir: File,
    usrDir: File,
    nativeDirectory: () -> String?,
    launcherBytes: () -> ByteArray,
  ): Map<String, String> {
    ensureDirectory(filesDir.toPath(), restrictMode = false)
    // Persistent inode: deleting the lock after use would allow two lock domains.
    // The JVM monitor prevents OverlappingFileLockException between local threads.
    val lockPath = File(filesDir, "native-proot.lock").toPath()
    requireRegularOrAbsent(lockPath)
    FileChannel.open(lockPath, setOf(CREATE, WRITE, NOFOLLOW_LINKS),
      PosixFilePermissions.asFileAttribute(FILE_MODE)).use { channel ->
      channel.lock().use {
        setModeIfNeeded(lockPath, FILE_MODE)
        val paths = resolve(nativeDirectory(), filesDir, usrDir)
          ?: throw IOException("PackageManager did not supply nativeLibraryDir")
        val launcher = launcherBytes() // Read before changing either installed file.
        ensureDirectory(paths.tmpDir.toPath(), restrictMode = true)
        writeIfChanged(paths.launcher, launcher, executable = true)
        writeIfChanged(paths.stateFile, stateBytes(paths), executable = false)
        return environment(paths)
      }
    }
  }

  internal fun stateBytes(paths: Paths): ByteArray = buildString {
    append("# Generated paths only; do not store credentials here.\n")
    append("DSH_NATIVE_PROOT_STATE_VERSION=1\n")
    val values = linkedMapOf(
      "DSH_PROOT_BIN" to paths.proot.absolutePath,
      "PROOT_LOADER" to paths.loader.absolutePath,
      "DSH_PROOT_LOADER32" to (paths.loader32?.absolutePath ?: ""),
      "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
      "DSH_DEBIAN_ROOTFS" to paths.defaultRootfs.absolutePath,
      "DSH_DEBIAN_LEGACY_ROOTFS" to paths.legacyRootfs.absolutePath,
    )
    for ((key, value) in values) append(key).append('=').append(shellQuote(value)).append('\n')
  }.toByteArray(Charsets.UTF_8)

  private fun environment(paths: Paths): Map<String, String> = buildMap {
    put("DSH_NATIVE_PROOT_ERROR", "")
    put("DSH_PROOT_BIN", paths.proot.absolutePath)
    put("PROOT_LOADER", paths.loader.absolutePath)
    // Empty explicitly clears a loader32 inherited from an older preparation.
    put("DSH_PROOT_LOADER32", paths.loader32?.absolutePath ?: "")
    put("PROOT_TMP_DIR", paths.tmpDir.absolutePath)
    put("DSH_NATIVE_PROOT_STATE", paths.stateFile.absolutePath)
    put("DSH_DEBIAN_LAUNCHER", paths.launcher.absolutePath)
    put("DSH_DEBIAN_ROOTFS", paths.defaultRootfs.absolutePath)
  }

  /**
   * Unique, owner-only temp file in the destination directory, fsync, atomic rename.
   * No non-atomic fallback: unsupported atomic replacement and unrelated errors
   * propagate, leaving the previous complete target intact. Same bytes preserve its
   * inode/mtime, but overly broad permissions are still repaired.
   */
  @Synchronized
  internal fun writeIfChanged(target: File, bytes: ByteArray, executable: Boolean) {
    val path = target.toPath()
    val parent = path.toAbsolutePath().parent
    ensureDirectory(parent, restrictMode = false)
    val mode = if (executable) EXEC_MODE else FILE_MODE
    val existing = requireRegularOrAbsent(path)
    if (existing != null && Files.readAllBytes(path).contentEquals(bytes)) {
      setModeIfNeeded(path, mode)
      return
    }
    val temp = Files.createTempFile(parent, ".${target.name}.", ".tmp",
      PosixFilePermissions.asFileAttribute(mode))
    try {
      FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
      }
      Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  private fun ensureDirectory(path: Path, restrictMode: Boolean) {
    Files.createDirectories(path, PosixFilePermissions.asFileAttribute(EXEC_MODE))
    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw IOException("Not a real directory: $path")
    if (restrictMode) setModeIfNeeded(path, EXEC_MODE)
  }

  private fun requireRegularOrAbsent(path: Path): BasicFileAttributes? {
    val attributes = try {
      Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
      return null
    }
    if (!attributes.isRegularFile) throw IOException("Not a regular file: $path")
    return attributes
  }

  private fun setModeIfNeeded(path: Path, mode: Set<PosixFilePermission>) {
    if (Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != mode) {
      Files.setPosixFilePermissions(path, mode)
    }
  }

  internal fun shellQuote(value: String): String {
    require('\u0000' !in value) { "Shell values cannot contain NUL" }
    return "'" + value.replace("'", "'\\''") + "'"
  }
}
