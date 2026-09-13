package com.dsharnessmobile.shell

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Behavioral tests use fixture bytes only; no retained executable is ever run. */
class SnapshotRetentionTest {
  private val files = Files.createTempDirectory("snapshot-retention-").toFile()
  private val usr = File(files, "usr")
  private val home = File(files, "home")
  private val stage = SnapshotTransaction.stageRoot(files)
  private val previous = SnapshotTransaction.previousRoot(files)

  @After fun cleanup() = SnapshotFs.deletePath(files)

  @Test fun successfulUpdateRetainsCustomExecutableConfigRootfsAndProfileBackup() {
    seed()
    val custom = write(usr, "bin/custom-tool", "original executable fixture\u0000\u0001\n")
    Files.setPosixFilePermissions(custom.toPath(), PosixFilePermissions.fromString("rwx------"))
    write(usr, "etc/custom.conf", "original config fixture\n")
    write(usr, "var/lib/proot-distro/containers/debian/rootfs/etc/os-release", "ID=legacy-fixture\n")
    val profileLink = File(home, ".dsh/profiles/web/user-link")
    Files.createSymbolicLink(profileLink.toPath(), File("factory.txt").toPath())
    val beforeIdentity = identity(custom)
    val beforeBytes = custom.readBytes()
    val link = File(usr, "bin/custom-link")
    Files.createSymbolicLink(link.toPath(), File("custom-tool").toPath())

    swap()
    val slot = requireNotNull(SnapshotTransaction.finish(files))
    val retained = File(slot, "previous")

    assertArrayEquals(beforeBytes, File(retained, "usr/bin/custom-tool").readBytes())
    assertEquals(beforeIdentity, identity(File(retained, "usr/bin/custom-tool")))
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(File(retained, "usr/bin/custom-tool").toPath()))
    assertEquals("original config fixture\n", File(retained, "usr/etc/custom.conf").readText())
    assertEquals("ID=legacy-fixture\n", File(retained, "usr/var/lib/proot-distro/containers/debian/rootfs/etc/os-release").readText())
    assertEquals("custom-tool", Files.readSymbolicLink(File(retained, "usr/bin/custom-link").toPath()).toString())
    assertEquals("old-profile", File(retained, "home/.dsh/profiles/web/factory.txt").readText())
    assertEquals(File("factory.txt").toPath(), Files.readSymbolicLink(File(retained, "home/.dsh/profiles/web/user-link").toPath()))
    assertEquals("new-profile", File(home, ".dsh/profiles/web/factory.txt").readText())
    assertEquals("new-node", File(usr, "bin/node").readText())
    assertFalse(File(usr, "bin/custom-tool").exists())
    assertFalse(File(home, ".dsh/workspaces/debian-rootfs").exists())
    val receipt = File(slot, "receipt.txt").readText()
    assertTrue(receipt.contains("cleanup=explicit-user-only"))
    assertTrue(receipt.contains("auto_restore=false"))
    assertFalse(receipt.contains("original config"))
    assertFalse(receipt.contains("old-profile"))
    assertFalse(receipt.contains("fixture-target-fingerprint"))
    for (dir in listOf(slot, SnapshotRetention.root(files))) {
      assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir.toPath()))
    }
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(File(slot, "receipt.txt").toPath()))
    assertFinished()
  }

  @Test fun secondUpdateAndExistingRecoverySlotNeverDeleteEarlierGenerations() {
    seed()
    val recoveryRoot = SnapshotRetention.prepare(files)
    val occupied = write(recoveryRoot, "runtime-1/previous/usr/irreplaceable", "preexisting-recovery")
    write(usr, "custom-generation", "first")
    swap()
    val first = requireNotNull(SnapshotTransaction.finish(files))
    write(usr, "custom-generation", "second")
    write(stage, "usr/bin/node", "third-node")
    write(stage, "home/.dsh/profiles/web/factory.txt", "third-profile")
    // Same timestamp and fingerprint deliberately exercise non-time-based allocation.
    swap()
    val second = requireNotNull(SnapshotTransaction.finish(files))

    assertNotEquals(first, second)
    assertEquals("first", File(first, "previous/usr/custom-generation").readText())
    assertEquals("second", File(second, "previous/usr/custom-generation").readText())
    assertEquals("preexisting-recovery", occupied.readText())
    assertEquals(3, requireNotNull(recoveryRoot.listFiles()).size)
    assertEquals("third-node", File(usr, "bin/node").readText())
    assertNull(SnapshotTransaction.finish(files))
    assertEquals(3, requireNotNull(recoveryRoot.listFiles()).size)
  }

  @Test fun interruptedSwapRollsBackOriginalRuntimeAndCustomBytes() {
    seed()
    write(usr, "bin/custom-tool", "original custom bytes")
    val before = identity(usr)
    expectIo { swap { if (it == "usr") throw IOException("fixture interruption") } }
    assertEquals("original custom bytes", File(previous, "usr/bin/custom-tool").readText())
    val result = SnapshotTransaction.recover(files, stage, usr, home, "old-fingerprint")
    assertEquals(SnapshotTransaction.Outcome.ROLLED_BACK, result.outcome)
    assertEquals("old-node", File(usr, "bin/node").readText())
    assertEquals("original custom bytes", File(usr, "bin/custom-tool").readText())
    assertEquals(before, identity(usr))
    assertEquals("old-profile", File(home, ".dsh/profiles/web/factory.txt").readText())
    assertFinished()
  }

  @Test fun retentionFailureLeavesOriginalPreviousAndPreventsDangerousCatchRollback() {
    seed()
    write(usr, "custom", "only original")
    swap()
    val recoveryRoot = SnapshotRetention.root(files)
    SnapshotFs.deletePath(recoveryRoot) // empty, prepared boundary only
    recoveryRoot.writeText("fixture collision: not a directory")

    expectIo { SnapshotTransaction.finish(files) }
    val marker = requireNotNull(SnapshotTransaction.readMarker(files))
    assertEquals(SnapshotTransaction.Phase.RETAINING, marker.phase)
    assertEquals("only original", File(previous, "usr/custom").readText())
    expectIo { SnapshotTransaction.rollback(files, stage, usr, home, marker) }
    assertEquals("new-node", File(usr, "bin/node").readText())
    assertEquals("only original", File(previous, "usr/custom").readText())
    assertEquals("fixture collision: not a directory", recoveryRoot.readText())

    Files.delete(recoveryRoot.toPath())
    assertEquals(SnapshotTransaction.Outcome.ROLLED_FORWARD,
      SnapshotTransaction.recover(files, stage, usr, home, "fixture-target-fingerprint").outcome)
    val retained = requireNotNull(SnapshotTransaction.finish(files))
    assertEquals("only original", File(retained, "previous/usr/custom").readText())
    assertFinished()
  }

  @Test fun crashAfterRetentionRenameCanOnlyRollForwardAndCleanupIsIdempotent() {
    seed()
    swap()
    val marker = requireNotNull(SnapshotTransaction.readMarker(files)).copy(phase = SnapshotTransaction.Phase.RETAINING)
    SnapshotTransaction.writeMarker(files, marker)
    val slot = requireNotNull(SnapshotRetention.retain(files, previous))
    // Simulate a kill after the real payload rename but before stage/marker cleanup.
    expectIo { SnapshotTransaction.rollback(files, stage, usr, home, marker) }
    assertEquals(SnapshotTransaction.Outcome.ROLLED_FORWARD,
      SnapshotTransaction.recover(files, stage, usr, home, "old-fingerprint").outcome)
    assertNull(SnapshotTransaction.finish(files))
    assertNull(SnapshotTransaction.finish(files))
    assertEquals("old-node", File(slot, "previous/usr/bin/node").readText())
    assertEquals("new-node", File(usr, "bin/node").readText())
    assertFinished()
  }

  @Test fun missingAndEmptyPreviousAreSafe() {
    write(usr, "bin/node", "untouched")
    assertNull(SnapshotTransaction.finish(files))
    assertFalse(SnapshotRetention.root(files).exists())
    previous.mkdirs()
    val slot = requireNotNull(SnapshotTransaction.finish(files))
    assertTrue(File(slot, "previous").isDirectory)
    assertTrue(requireNotNull(File(slot, "previous").list()).isEmpty())
    assertEquals("untouched", File(usr, "bin/node").readText())
    assertFinished()
  }

  @Test fun orphanPreviousIsNotDeletedBySwapOrStagedRecoveryOrCatchRollback() {
    seed()
    write(previous, "usr/only-old-data", "irreplaceable orphan")
    val marker = SnapshotTransaction.Marker(SnapshotTransaction.Phase.STAGED, "next", 1L)
    SnapshotTransaction.writeMarker(files, marker)
    expectIo { swap() }
    expectIo { SnapshotTransaction.recover(files, stage, usr, home, "old") }
    SnapshotTransaction.rollback(files, stage, usr, home, marker)
    assertEquals("irreplaceable orphan", File(previous, "usr/only-old-data").readText())
    assertEquals("old-node", File(usr, "bin/node").readText())
    assertEquals("new-node", File(stage, "usr/bin/node").readText())
    assertEquals(marker, SnapshotTransaction.readMarker(files))
  }

  @Test fun everyRecoveryAncestorSymlinkIsRejectedBeforeLiveDisplacement() {
    for (boundary in listOf("home", "home/.dsh", "home/.dsh/workspaces", "home/.dsh/workspaces/upgrade-recovery")) {
      val fixture = File(files, boundary.replace('/', '_')).apply { mkdirs() }
      val target = File(fixture, "outside").apply { mkdirs() }
      write(target, "sentinel", "outside original")
      val link = File(fixture, boundary)
      link.parentFile.mkdirs()
      Files.createSymbolicLink(link.toPath(), target.toPath())
      write(fixture, "usr/bin/node", "old")
      val staged = SnapshotTransaction.stageRoot(fixture)
      write(staged, "usr/bin/node", "new")
      expectIo {
        SnapshotTransaction.swap(fixture, staged, File(fixture, "usr"), File(fixture, "home"), emptySet(), "next", 1L)
      }
      assertEquals("old", File(fixture, "usr/bin/node").readText())
      assertEquals("new", File(staged, "usr/bin/node").readText())
      assertEquals("outside original", File(target, "sentinel").readText())
      assertEquals(listOf("sentinel"), requireNotNull(target.list()).toList())
      assertFalse(SnapshotTransaction.previousRoot(fixture).exists())
    }
  }

  @Test fun symlinksInsideRetainedUsrRemainOpaqueIncludingExternalAndDanglingTargets() {
    seed()
    val outside = write(files, "outside/sentinel", "outside bytes")
    Files.createSymbolicLink(File(usr, "external").toPath(), outside.parentFile.toPath())
    Files.createSymbolicLink(File(usr, "dangling").toPath(), File("absent/target").toPath())
    swap()
    val slot = requireNotNull(SnapshotTransaction.finish(files))
    assertEquals(outside.parentFile.toPath(), Files.readSymbolicLink(File(slot, "previous/usr/external").toPath()))
    assertEquals(File("absent/target").toPath(), Files.readSymbolicLink(File(slot, "previous/usr/dangling").toPath()))
    assertEquals("outside bytes", outside.readText())
    assertEquals(listOf("sentinel"), requireNotNull(outside.parentFile.list()).toList())
  }

  @Test fun symlinkedPreviousBoundaryIsNeverTraversedByFinishOrRollback() {
    seed()
    val outside = File(files, "outside").apply { mkdirs() }
    write(outside, "usr/only-original", "outside original")
    Files.createSymbolicLink(previous.toPath(), outside.toPath())
    expectIo { swap() }
    expectIo { SnapshotTransaction.finish(files) }
    expectIo {
      SnapshotTransaction.rollback(files, stage, usr, home,
        SnapshotTransaction.Marker(SnapshotTransaction.Phase.SWAPPING, "next", 1L, listOf("usr")))
    }
    assertEquals("outside original", File(outside, "usr/only-original").readText())
    assertEquals("old-node", File(usr, "bin/node").readText())
    assertTrue(Files.isSymbolicLink(previous.toPath()))
  }

  @Test fun factoryLeafLinkIsReplacedWithoutFollowingItsTargetAndBackupKeepsTheLink() {
    seed()
    val external = write(files, "outside/data", "external config")
    val live = File(home, ".dsh/profiles/web/factory.txt")
    Files.delete(live.toPath())
    Files.createSymbolicLink(live.toPath(), external.toPath())
    swap()
    assertFalse(Files.isSymbolicLink(live.toPath()))
    assertEquals("new-profile", live.readText())
    assertEquals("external config", external.readText())
    val slot = requireNotNull(SnapshotTransaction.finish(files))
    val retained = File(slot, "previous/home/.dsh/profiles/web/factory.txt")
    assertTrue(Files.isSymbolicLink(retained.toPath()))
    assertEquals(external.toPath(), Files.readSymbolicLink(retained.toPath()))
  }

  @Test fun userManifestSymlinkCannotBeReadAndMergedThrough() {
    seed()
    val external = write(files, "outside/config.json", "{\"dependencies\":{\"old\":\"1\"}}")
    val live = File(home, ".dsh/profiles/web/package.json")
    Files.createSymbolicLink(live.toPath(), external.toPath())
    write(stage, "home/.dsh/profiles/web/package.json", "{\"dependencies\":{\"new\":\"2\"}}")
    expectIo { swap() }
    SnapshotTransaction.recover(files, stage, usr, home, "old-fingerprint")
    assertTrue(Files.isSymbolicLink(live.toPath()))
    assertEquals(external.toPath(), Files.readSymbolicLink(live.toPath()))
    assertEquals("{\"dependencies\":{\"old\":\"1\"}}", external.readText())
    assertEquals("old-node", File(usr, "bin/node").readText())
  }

  @Test fun profileDirectorySymlinkCannotBeTraversed() {
    seed()
    val outside = File(files, "outside-dir").apply { mkdirs() }
    write(outside, "sentinel", "external bytes")
    val live = File(home, ".dsh/profiles/web/nested")
    Files.createSymbolicLink(live.toPath(), outside.toPath())
    write(stage, "home/.dsh/profiles/web/nested/factory", "must not escape")
    expectIo { swap() }
    SnapshotTransaction.recover(files, stage, usr, home, "old-fingerprint")
    assertTrue(Files.isSymbolicLink(live.toPath()))
    assertEquals(listOf("sentinel"), requireNotNull(outside.list()).toList())
    assertEquals("external bytes", File(outside, "sentinel").readText())
  }

  @Test fun profileBackupCollisionFailsWithoutReplacingOriginalWithPartialBackup() {
    seed()
    write(stage, ".profiles-backup-pending/partial", "incomplete backup")
    expectIo { swap() }
    assertEquals("old-profile", File(home, ".dsh/profiles/web/factory.txt").readText())
    assertFalse(File(previous, "home/.dsh/profiles").exists())
    SnapshotTransaction.recover(files, stage, usr, home, "old-fingerprint")
    assertEquals("old-profile", File(home, ".dsh/profiles/web/factory.txt").readText())
    assertEquals("old-node", File(usr, "bin/node").readText())
  }

  @Test fun rollbackRetryAfterRestoringUsrDoesNotDeleteRestoredOriginal() {
    seed()
    expectIo { swap { if (it == "usr") throw IOException("fixture interruption") } }
    val marker = requireNotNull(SnapshotTransaction.readMarker(files))
    // Model rollback's two real renames and a kill before the completion marker.
    SnapshotFs.move(usr, File(stage, "usr"))
    SnapshotFs.move(File(previous, "usr"), usr)
    SnapshotTransaction.rollback(files, stage, usr, home, marker)
    assertEquals("old-node", File(usr, "bin/node").readText())
    val completed = requireNotNull(SnapshotTransaction.readMarker(files))
    assertEquals(SnapshotTransaction.Phase.ROLLED_BACK, completed.phase)
    SnapshotTransaction.rollback(files, stage, usr, home, completed)
    assertEquals("old-node", File(usr, "bin/node").readText())
  }

  @Test fun journalTraversalCannotDeleteOutsideData() {
    seed()
    val outside = write(files, "outside/sentinel", "outside bytes")
    expectIo {
      SnapshotTransaction.rollback(files, stage, usr, home,
        SnapshotTransaction.Marker(SnapshotTransaction.Phase.SWAPPING, "next", 1L, listOf("home/../../outside")))
    }
    assertEquals("outside bytes", outside.readText())
    assertEquals("old-node", File(usr, "bin/node").readText())
  }

  private fun seed() {
    write(usr, "bin/node", "old-node")
    write(home, ".dsh/profiles/web/factory.txt", "old-profile")
    write(stage, "usr/bin/node", "new-node")
    write(stage, "home/.dsh/profiles/web/factory.txt", "new-profile")
  }

  private fun swap(onEntry: (String) -> Unit = {}) = SnapshotTransaction.swap(
    files, stage, usr, home, SnapshotUserData.preservedNames.toSet(), "fixture-target-fingerprint", 1L, onEntry,
  )

  private fun assertFinished() {
    assertFalse(SnapshotFs.exists(previous))
    assertFalse(SnapshotFs.exists(stage))
    assertNull(SnapshotTransaction.readMarker(files))
  }

  private fun write(root: File, name: String, text: String): File = File(root, name).apply {
    parentFile.mkdirs()
    writeText(text)
  }

  private fun identity(file: File): Any = requireNotNull(
    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey(),
  )

  private fun expectIo(block: () -> Unit) {
    try {
      block()
      fail("Expected fail-closed IOException")
    } catch (_: IOException) {
      // Expected; callers assert originals and transaction state separately.
    }
  }
}
