package com.dsharnessmobile.shell

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Real snapshot transaction calls, not a reimplementation of the preservation list
 * or swap algorithm. Every runtime/rootfs is a fresh local fixture, never a system
 * /usr or a provisioned Debian. This covers snapshot persistence, not APK installation.
 */
class NativeProotUpgradeTest {
  private val files = Files.createTempDirectory("native-proot-upgrade-").toFile()
  private val usr = File(files, "usr")
  private val home = File(files, "home")
  private val stage = SnapshotTransaction.stageRoot(files)
  private val defaultRootfs = File(home, ".dsh/workspaces/debian-rootfs")
  private val legacyRootfs = File(usr, "var/lib/proot-distro/containers/debian/rootfs")
  // Production authority, deliberately not a test-owned list.
  private val preserved = SnapshotUserData.preservedNames.toSet()

  @After fun cleanUp() {
    SnapshotFs.deletePath(files)
    assertFalse(Files.exists(files.toPath(), NOFOLLOW_LINKS))
  }

  @Test fun successfulSwapAndFinishKeepWorkspaceRootfsBytesLinksAndIdentity() {
    seedRuntimes()
    val before = seedRootfs(defaultRootfs, "workspace")
    assertTrue("The default rootfs must lie inside the real preservation boundary", "workspaces" in preserved)
    assertFalse("usr must not become a preserved DSH_HOME entry", "usr" in preserved)
    val stagedRootfs = File(stage, "home/.dsh/workspaces/debian-rootfs")
    assertNotEquals(sha256(File(defaultRootfs, "usr/bin/bash")), sha256(File(stagedRootfs, "usr/bin/bash")))

    swap()

    assertEquals("new-node", File(usr, "bin/node").readText())
    assertEquals("new-factory", File(home, ".dsh/factory-version").readText())
    assertEquals(SnapshotTransaction.Phase.SWAPPED, SnapshotTransaction.readMarker(files)?.phase)
    assertFalse(SnapshotTransaction.readMarker(files)!!.moved.any { it.startsWith("home/.dsh/workspaces") })
    assertFalse(SnapshotFs.exists(File(SnapshotTransaction.previousRoot(files), "home/.dsh/workspaces")))
    assertRootfsUnchanged(defaultRootfs, before)
    assertFalse(File(defaultRootfs, "factory-only").exists())
    assertTrue("The conflicting staged workspace was not activated", stagedRootfs.isDirectory)

    SnapshotTransaction.finish(files)

    assertRootfsUnchanged(defaultRootfs, before)
    assertFinished()
  }

  @Test fun interruptedRealSwapRecoversOldUsrWithoutTouchingWorkspaceRootfs() {
    seedRuntimes()
    val workspaceBefore = seedRootfs(defaultRootfs, "workspace")
    val legacyBefore = seedRootfs(legacyRootfs, "legacy")
    val interrupted = SwapInterrupted()
    try {
      swap { entry -> if (entry == "home/.dsh/factory-version") throw interrupted }
      fail("Expected interruption after real factory replacement")
    } catch (actual: SwapInterrupted) {
      assertSame(interrupted, actual)
    }
    val marker = requireNotNull(SnapshotTransaction.readMarker(files))
    assertEquals(SnapshotTransaction.Phase.SWAPPING, marker.phase)
    assertTrue(marker.moved.contains("usr"))
    assertTrue(marker.moved.contains("home/.dsh/factory-version"))
    assertEquals("new-node", File(usr, "bin/node").readText())
    assertFalse(legacyRootfs.exists())
    assertRootfsUnchanged(defaultRootfs, workspaceBefore)
    assertRootfsUnchanged(File(SnapshotTransaction.previousRoot(files),
      "usr/var/lib/proot-distro/containers/debian/rootfs"), legacyBefore)

    val recovered = recover("old-fingerprint")

    assertEquals(SnapshotTransaction.Outcome.ROLLED_BACK, recovered.outcome)
    assertNull(recovered.fingerprintToCommit)
    assertEquals("old-node", File(usr, "bin/node").readText())
    assertEquals("old-factory", File(home, ".dsh/factory-version").readText())
    assertRootfsUnchanged(defaultRootfs, workspaceBefore)
    assertRootfsUnchanged(legacyRootfs, legacyBefore)
    SnapshotTransaction.finish(files)
    assertFinished()
    assertEquals(SnapshotTransaction.Outcome.NONE, recover("old-fingerprint").outcome)
    assertRootfsUnchanged(defaultRootfs, workspaceBefore)
  }

  @Test fun swappedRecoveryRollsForwardThenFinishKeepsWorkspaceRootfs() {
    seedRuntimes()
    val before = seedRootfs(defaultRootfs, "workspace")
    swap()

    // A completed swap with the previous fingerprint models interruption before commit.
    val recovered = recover("old-fingerprint")

    assertEquals(SnapshotTransaction.Outcome.ROLLED_FORWARD, recovered.outcome)
    assertEquals("new-fingerprint", recovered.fingerprintToCommit)
    assertEquals("new-node", File(usr, "bin/node").readText())
    assertRootfsUnchanged(defaultRootfs, before)
    assertTrue("Roll-forward still requires explicit finish", SnapshotTransaction.markerFile(files).isFile)
    SnapshotTransaction.finish(files)
    assertFinished()
    assertEquals(SnapshotTransaction.Outcome.NONE, recover("new-fingerprint").outcome)
    assertRootfsUnchanged(defaultRootfs, before)
  }

  @Test fun committedFingerprintRecoversAnOlderPhaseWithoutRevertingWorkspaceRootfs() {
    seedRuntimes()
    val before = seedRootfs(defaultRootfs, "workspace")
    swap()
    val swapped = requireNotNull(SnapshotTransaction.readMarker(files))
    // Only the persisted phase is stale; all replacements came from the real swap.
    SnapshotTransaction.writeMarker(files, swapped.copy(phase = SnapshotTransaction.Phase.SWAPPING))

    val recovered = recover("new-fingerprint")

    assertEquals(SnapshotTransaction.Outcome.ROLLED_FORWARD, recovered.outcome)
    assertEquals("new-fingerprint", recovered.fingerprintToCommit)
    assertEquals("new-node", File(usr, "bin/node").readText())
    assertRootfsUnchanged(defaultRootfs, before)
    SnapshotTransaction.finish(files)
    assertFinished()
    assertRootfsUnchanged(defaultRootfs, before)
  }

  @Test fun stagedRecoveryDiscardsOnlyTheNewSnapshotNotEitherLiveRootfs() {
    seedRuntimes()
    val workspaceBefore = seedRootfs(defaultRootfs, "workspace")
    val legacyBefore = seedRootfs(legacyRootfs, "legacy")
    SnapshotTransaction.writeMarker(files, SnapshotTransaction.Marker(
      SnapshotTransaction.Phase.STAGED, "new-fingerprint", 1L))

    val recovered = recover("old-fingerprint")

    assertEquals(SnapshotTransaction.Outcome.DISCARDED_STAGE, recovered.outcome)
    assertNull(recovered.fingerprintToCommit)
    assertEquals("old-node", File(usr, "bin/node").readText())
    assertRootfsUnchanged(defaultRootfs, workspaceBefore)
    assertRootfsUnchanged(legacyRootfs, legacyBefore)
    SnapshotTransaction.finish(files)
    assertFinished()
    assertRootfsUnchanged(defaultRootfs, workspaceBefore)
  }

  @Test fun legacyUsrRootfsIsRetainedForExplicitRecoveryWithoutAutomaticMigration() {
    seedRuntimes()
    val before = seedRootfs(legacyRootfs, "legacy-only")
    // Real snapshots do not ship a Debian rootfs. Keep only an empty workspace seed.
    SnapshotFs.deletePath(File(stage, "home/.dsh/workspaces/debian-rootfs"))
    assertFalse(defaultRootfs.exists())

    swap()

    assertFalse("Upgrade must not migrate legacy rootfs into workspaces", defaultRootfs.exists())
    assertFalse("Legacy usr is replaced as factory runtime, not preserved user data", legacyRootfs.exists())
    val displacedLegacy = File(SnapshotTransaction.previousRoot(files),
      "usr/var/lib/proot-distro/containers/debian/rootfs")
    assertRootfsUnchanged(displacedLegacy, before)
    assertEquals("new-node", File(usr, "bin/node").readText())
    val recovered = recover("old-fingerprint")
    assertEquals(SnapshotTransaction.Outcome.ROLLED_FORWARD, recovered.outcome)
    assertFalse(defaultRootfs.exists())

    val retained = requireNotNull(SnapshotTransaction.finish(files))

    assertFinished()
    assertFalse(displacedLegacy.exists())
    assertFalse(legacyRootfs.exists())
    assertFalse(defaultRootfs.exists())
    assertRootfsUnchanged(File(retained,
      "previous/usr/var/lib/proot-distro/containers/debian/rootfs"), before)
  }

  private fun seedRuntimes() {
    write(files, "usr/bin/node", "old-node")
    write(files, "home/.dsh/factory-version", "old-factory")
    write(stage, "usr/bin/node", "new-node")
    write(stage, "home/.dsh/factory-version", "new-factory")
    // Conflicting factory data proves preservation rather than accidental equality.
    write(stage, "home/.dsh/workspaces/debian-rootfs/usr/bin/bash", "must not overwrite workspace")
    write(stage, "home/.dsh/workspaces/debian-rootfs/factory-only", "must not be installed")
  }

  private fun swap(onEntry: (String) -> Unit = {}) = SnapshotTransaction.swap(
    filesDir = files,
    stagedRoot = stage,
    usrDir = usr,
    homeDir = home,
    preservedNames = preserved,
    fingerprint = "new-fingerprint",
    startedAt = 1L,
    onEntry = onEntry,
  )

  private fun recover(fingerprint: String) = SnapshotTransaction.recover(files, stage, usr, home, fingerprint)

  private data class RootfsEvidence(
    val hashes: Map<String, String>,
    val links: Map<String, String>,
    val identities: Map<String, Any>,
  )

  private fun seedRootfs(root: File, label: String): RootfsEvidence {
    write(root, "usr/bin/bash", "$label binary fixture\n")
    write(root, "etc/os-release", "ID=fixture\nLABEL=$label\n")
    write(root, "var/lib/fixture/data", "$label mutable data\n")
    Files.createDirectories(File(root, "bin").toPath())
    Files.createSymbolicLink(File(root, "bin/sh").toPath(), File("../usr/bin/bash").toPath())
    // Deliberately dangling inside this fixture; never targets a host /usr path.
    Files.createSymbolicLink(File(root, "missing-link").toPath(), File("usr/lib/not-installed").toPath())
    return evidence(root)
  }

  private fun evidence(root: File): RootfsEvidence {
    val hashes = listOf("usr/bin/bash", "etc/os-release", "var/lib/fixture/data")
      .associateWith { sha256(File(root, it)) }
    val links = listOf("bin/sh", "missing-link").associateWith { name ->
      val path = File(root, name).toPath()
      assertTrue("Rootfs link must remain a link: $name", Files.isSymbolicLink(path))
      Files.readSymbolicLink(path).toString()
    }
    val identities = (listOf("") + hashes.keys + links.keys).associateWith { name ->
      val key = Files.readAttributes(File(root, name).toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
      assertNotNull("Filesystem must expose fixture identity: $name", key)
      requireNotNull(key)
    }
    return RootfsEvidence(hashes, links, identities)
  }

  private fun assertRootfsUnchanged(root: File, expected: RootfsEvidence) {
    assertTrue(root.isDirectory)
    assertEquals("Rootfs bytes, link targets and identities must survive", expected, evidence(root))
    assertEquals(sha256(File(root, "usr/bin/bash")), sha256(File(root, "bin/sh")))
    assertTrue(Files.exists(File(root, "missing-link").toPath(), NOFOLLOW_LINKS))
    assertFalse(Files.exists(File(root, "missing-link").toPath()))
  }

  private fun assertFinished() {
    assertFalse(SnapshotFs.exists(stage))
    assertFalse(SnapshotFs.exists(SnapshotTransaction.previousRoot(files)))
    assertFalse(SnapshotFs.exists(SnapshotTransaction.markerFile(files)))
    assertNull(SnapshotTransaction.readMarker(files))
  }

  private fun write(root: File, relative: String, text: String) {
    val file = File(root, relative)
    Files.createDirectories(file.parentFile.toPath())
    file.writeText(text)
  }

  private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

  private class SwapInterrupted : RuntimeException("test fixture swap interruption")
}
