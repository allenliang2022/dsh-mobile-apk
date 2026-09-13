package com.dsharnessmobile.shell

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

/**
 * Append-only recovery storage in the preserved app-private workspace. No pruning,
 * restore, executable discovery, or execution is performed here. The caller owns
 * transaction serialization (as with the snapshot stage and marker).
 *
 * Rename the entire displaced tree, not selected files: this preserves unknown
 * custom tools, guest rootfs contents, file identity, permissions and symlinks.
 * Every directory below the trusted filesDir anchor is checked without following
 * links. Links *inside* the payload are opaque and are never traversed.
 */
internal object SnapshotRetention {
  private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
  private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

  fun root(filesDir: File): File = File(filesDir, "home/.dsh/workspaces/upgrade-recovery")

  /** Check/create the boundary before the live runtime is displaced. */
  fun prepare(filesDir: File): File {
    requireDirectory(filesDir)
    var directory = filesDir
    for (name in listOf("home", ".dsh", "workspaces", "upgrade-recovery")) {
      directory = File(directory, name)
      if (!SnapshotFs.exists(directory)) Files.createDirectory(directory.toPath(), privateDirectory)
      requireDirectory(directory)
    }
    // Do not silently chmod an existing path owned by the user. Refuse an unsafe
    // recovery root instead; newly created roots are 0700 independent of umask.
    val permissions = Files.getPosixFilePermissions(directory.toPath(), NOFOLLOW_LINKS)
    if (permissions != PosixFilePermissions.fromString("rwx------")) {
      throw IOException("Snapshot update blocked: upgrade-recovery must be a private 0700 directory")
    }
    return directory
  }

  /** Returns the new slot, or null when no previous tree exists. Never deletes old slots. */
  fun retain(filesDir: File, previous: File): File? {
    requireDirectory(filesDir)
    if (!SnapshotFs.exists(previous)) return null
    requireDirectory(previous)
    val root = prepare(filesDir)
    // CREATE_DIRECTORY is the allocation authority, not timestamps/fingerprints.
    // Equal update timestamps and collisions cannot reuse or replace an old slot.
    val slot = Files.createTempDirectory(root.toPath(), "runtime-", privateDirectory).toFile()
    val receipt = File(slot, "receipt.txt")
    Files.newByteChannel(receipt.toPath(), setOf(CREATE_NEW, WRITE), privateFile).use { channel ->
      val text = "format=1\nkind=displaced-snapshot\npayload=previous\n" +
        "created_at_ms=${System.currentTimeMillis()}\n" +
        "payload_presence=directory-indicates-completed-retention\n" +
        "cleanup=explicit-user-only\nauto_restore=false\n"
      val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
      while (bytes.hasRemaining()) channel.write(bytes)
      if (channel is java.nio.channels.FileChannel) channel.force(true)
    }
    // No ATOMIC_MOVE: Java permits it to replace an existing target even without
    // REPLACE_EXISTING. A normal same-filesystem rename must instead fail closed
    // on collision. Never fall back to copy/delete on space/mount/rename failure.
    Files.move(previous.toPath(), File(slot, "previous").toPath())
    return slot
  }

  fun requireDirectory(directory: File) {
    if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
      throw IOException("Snapshot update blocked: recovery boundary is not a real directory: ${directory.name}")
    }
  }
}
