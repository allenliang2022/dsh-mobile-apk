package com.dsharnessmobile.shell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API30+ disposable-emulator host regression, separate from the native helper suite.
 * Requires an already extracted matching snapshot and no shared-storage write grants.
 * Private files/preferences are fixtures, but shellEnv uses static Environment for
 * public export metadata: scoped storage does not guarantee those writes are denied.
 * Therefore this class is restricted to disposable ranchu/goldfish emulators; it
 * does not promise fixture-only public IO and is not a physical-device test entry.
 * Only host Node/bash are executed, not the DSH server or a Debian guest.
 */
@RunWith(AndroidJUnit4::class)
class NativeProotHostAcceptanceTest : NativeProotAndroidFixture() {
  @Test fun shellEnvAndExtractedHostNodeBashWorkWithoutOptionalGuest() {
    assertTrue("Host test requires a disposable ranchu/goldfish emulator",
      Build.HARDWARE in setOf("ranchu", "goldfish"))
    // Reduce shared-storage exposure, but do not treat these flags as an IO sandbox:
    // MediaProvider/FUSE may still permit writes to previously app-owned public files.
    assertTrue("Host emulator contract requires API30+", Build.VERSION.SDK_INT >= 30)
    assertEquals("Run without a shared-storage write grant", PackageManager.PERMISSION_DENIED,
      target.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    assertFalse("Scoped storage is required", Environment.isExternalStorageLegacy())
    assertFalse("Run without all-files access", Environment.isExternalStorageManager())
    // This marker prevents examination/migration of any public legacy layout.
    fixtureFile(context.filesDir, "home/.dsh/.private-layout", "private")
    val installedUsr = File(target.filesDir, "usr")
    for (name in listOf("bin/node", "bin/bash", "lib/libtermux-exec-ld-preload.so")) {
      assertTrue("The matching snapshot must already be extracted: $name", File(installedUsr, name).isFile)
    }
    Files.createDirectories(File(usr, "bin").toPath())
    for (name in listOf("node", "bash")) {
      Files.createSymbolicLink(File(usr, "bin/$name").toPath(), File(installedUsr, "bin/$name").toPath())
    }
    Files.createSymbolicLink(File(usr, "lib").toPath(), File(installedUsr, "lib").toPath())
    Files.createDirectories(File(usr, "etc").toPath())
    Files.createSymbolicLink(File(usr, "etc/tls").toPath(), File(installedUsr, "etc/tls").toPath())
    val env = EngineManager(context).shellEnv()
    assertEquals("", env["DSH_NATIVE_PROOT_ERROR"])
    assertEquals(File(context.filesDir, "home").absolutePath, env["HOME"])
    assertEquals(File(context.filesDir, "home/.dsh").absolutePath, env["DSH_HOME"])
    assertEquals(usr.absolutePath + "/bin:/system/bin", env["PATH"])
    assertEquals("", env["DASHSCOPE_API_KEY"])
    assertEquals("", env["DEEPSEEK_API_KEY"])
    assertEquals("", env["DSH_PICK_TOKEN"])
    val paths = pmPaths()
    assertEquals(paths.proot.absolutePath, env["DSH_PROOT_BIN"])
    if (Build.SUPPORTED_ABIS.first() == "x86_64") {
      assertFalse("x86_64 remains explicitly without native PRoot", paths.available)
      assertEquals(126, launcher(env).code)
    } else {
      assertTrue("ARM64 must provide the engine even without a guest", paths.available)
    }
    // Match EngineManager's supported Android linker path; no shell profiles are sourced.
    val node = spawn(listOf("/system/bin/linker64", File(usr, "bin/node").absolutePath, "--version"), env)
    assertEquals(0, node.code)
    assertTrue("Host Node version expected", Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(node.output.trim()))
    val bash = spawn(listOf("/system/bin/linker64", File(usr, "bin/bash").absolutePath,
      "--noprofile", "--norc", "-c", "printf '%s\\n' host-bash-ok"), env)
    assertEquals(0, bash.code)
    assertEquals("host-bash-ok", bash.output.trim())
    assertFalse(paths.defaultRootfs.exists())
    assertFalse(paths.legacyRootfs.exists())
  }
}
