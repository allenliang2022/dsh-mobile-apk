package com.dsharnessmobile.adbpatchtest;

import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.*;
import org.json.JSONObject;

/** Separate CI-only application/UID; never installs a replacement DSH application. */
@RunWith(AndroidJUnit4.class)
public class ShimIntegrationTest {
  private static final String ORIGINAL = "adb.dsh-original-v1";

  private static Path fixture() throws Exception {
    assertTrue("Disposable emulator only", Arrays.asList("ranchu", "goldfish").contains(Build.HARDWARE));
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Path root = Files.createTempDirectory(context.getCacheDir().toPath(), "adb-shim-").toRealPath();
    long copied = 0;
    try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("fixture.zip"))) {
      ZipEntry entry;
      byte[] buffer = new byte[65536];
      while ((entry = zip.getNextEntry()) != null) {
        Path target = root.resolve(entry.getName()).normalize();
        if (!target.startsWith(root) || target.equals(root) || entry.getName().startsWith("/")) throw new IOException("Unsafe fixture entry");
        if (entry.isDirectory()) { Files.createDirectories(target); continue; }
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
          int count;
          while ((count = zip.read(buffer)) != -1) {
            copied += count;
            if (copied > 700L * 1024 * 1024) throw new IOException("Fixture size exceeded");
            out.write(buffer, 0, count);
          }
        }
      }
    }
    Files.createDirectories(root.resolve("home/.android"));
    Files.createDirectories(root.resolve("tmp"));
    return root;
  }

  private static void executable(Path path) { assertTrue(path.toFile().setExecutable(true, true)); }
  private static String hex(byte[] data) {
    StringBuilder out = new StringBuilder(data.length * 2);
    for (byte value : data) out.append(String.format(Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }
  private static String sha(Path path) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(path)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) md.update(b, 0, n); }
    return hex(md.digest());
  }
  private static void layout(Path root, boolean fake) throws Exception {
    Path bin = root.resolve("runtime/usr/bin");
    Path original = bin.resolve(ORIGINAL);
    if (fake) { Files.delete(bin.resolve("adb")); Files.copy(root.resolve("payload/fake-original"), original); }
    else Files.move(bin.resolve("adb"), original);
    Files.copy(root.resolve("payload/shim"), bin.resolve("adb"));
    executable(original); executable(bin.resolve("adb"));
  }
  private static ProcessBuilder command(Path root, boolean preload, String... arguments) {
    Path usr = root.resolve("runtime/usr");
    List<String> argv = new ArrayList<>();
    argv.add("/system/bin/linker64"); argv.add(usr.resolve("bin/adb").toString());
    argv.addAll(Arrays.asList(arguments));
    ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile());
    Map<String,String> env = builder.environment(); env.clear();
    env.put("PATH", usr.resolve("bin") + ":/system/bin");
    env.put("HOME", root.resolve("home").toString());
    env.put("ANDROID_USER_HOME", root.resolve("home/.android").toString());
    env.put("TMPDIR", root.resolve("tmp").toString());
    env.put("LD_LIBRARY_PATH", usr.resolve("lib").toString());
    env.put("OPENSSL_CONF", usr.resolve("etc/tls/openssl.cnf").toString());
    env.put("ADB_MDNS_AUTO_CONNECT", "0"); env.put("ADB_LIBUSB", "0");
    env.put("ADB_LOCAL_TRANSPORT_MAX_PORT", "0");
    if (preload) {
      env.put("LD_PRELOAD", usr.resolve("lib/libtermux-exec-ld-preload.so").toString());
      env.put("TERMUX__ROOTFS", root.resolve("runtime").toString()); env.put("TERMUX__PREFIX", usr.toString());
      env.put("TERMUX_APP__DATA_DIR", root.resolve("runtime").toString());
      env.put("TERMUX_APP__LEGACY_DATA_DIR", root.resolve("runtime").toString());
      env.put("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE", "force");
      env.put("TERMUX_EXEC__EXECVE_CALL__INTERCEPT", "1");
    }
    return builder;
  }
  private static String text(InputStream input) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
    while ((n = input.read(buffer)) != -1) { if (result.size() + n > 32768) throw new IOException("Fixture output exceeded"); result.write(buffer, 0, n); }
    return result.toString("UTF-8");
  }
  private static void stop(Process process) throws Exception {
    if (process != null && process.isAlive()) {
      process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(2, TimeUnit.SECONDS)); }
    }
  }
  private static void cleanup(Path root) throws Exception {
    try (Stream<Path> paths = Files.walk(root)) { Iterator<Path> items = paths.sorted(Comparator.reverseOrder()).iterator(); while (items.hasNext()) Files.delete(items.next()); }
  }

  @Test public void realAdbBootsThroughShimUsingOldNativeSocketValue() throws Exception {
    Path root = fixture(); Process process = null;
    try {
      JSONObject meta = new JSONObject(new String(Files.readAllBytes(root.resolve("payload/manifest.json")), StandardCharsets.UTF_8));
      assertEquals(meta.getString("originalSha256"), sha(root.resolve("runtime/usr/bin/adb")));
      assertEquals(meta.getString("shimSha256"), sha(root.resolve("payload/shim")));
      layout(root, false);
      int port; try (ServerSocket reserve = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reserve.getLocalPort(); }
      Path log = root.resolve("server.log");
      ProcessBuilder builder = command(root, true, "--one-device", "dsh-no-usb-" + UUID.randomUUID(), "server", "nodaemon");
      builder.environment().put("ADB_SERVER_SOCKET", "tcp:127.0.0.1:" + port);
      process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start(); process.getOutputStream().close();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); boolean ready = false;
      while (process.isAlive() && System.nanoTime() < deadline) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 100); ready = true; break; }
        catch (IOException ignored) { Thread.sleep(50); }
      }
      String evidence; try (InputStream in = Files.newInputStream(log)) { evidence = text(in); }
      assertTrue("Real wrapped server did not stay alive: " + evidence, ready && process.isAlive());
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 500); socket.setSoTimeout(3000);
        socket.getOutputStream().write("000chost:devices".getBytes(StandardCharsets.US_ASCII));
        DataInputStream input = new DataInputStream(socket.getInputStream()); byte[] reply = new byte[8]; input.readFully(reply);
        assertEquals("OKAY0000", new String(reply, StandardCharsets.US_ASCII));
      }
      assertTrue("exec handoff must retain the tracked process", process.isAlive());
    } finally { stop(process); cleanup(root); }
  }

  @Test public void argsStdioExitAndOnlyMatchingEnvironmentAreForwarded() throws Exception {
    Path root = fixture();
    try {
      layout(root, true);
      String[] arguments = {"with spaces", "", "中文", "literal;not-a-shell"};
      byte[] input = "stdin\nwith\u0000bytes".getBytes(StandardCharsets.UTF_8);
      for (String socketValue : new String[]{"tcp:192.0.2.10:45001", "tcp:127.0.0.1:45001"}) {
        Process process = null;
        try {
          ProcessBuilder builder = command(root, false, arguments);
          builder.environment().put("ADB_SERVER_SOCKET", socketValue); builder.environment().put("PATCH_SENTINEL", "keep me");
          process = builder.start(); process.getOutputStream().write(input); process.getOutputStream().close();
          assertTrue("Fake original must exit", process.waitFor(5, TimeUnit.SECONDS));
          String output = text(process.getInputStream()), error = text(process.getErrorStream());
          assertEquals(37, process.exitValue()); assertEquals("fake-original stderr\n", error);
          assertTrue(output.contains("DSH_FAKE_ORIGINAL_V1\n"));
          assertTrue(output.contains("argc=5\n"));
          // linker64 names the preserved executable in argv[0]; this limitation is explicit.
          assertTrue(output.contains("arg0=" + hex(root.resolve("runtime/usr/bin/" + ORIGINAL).toString().getBytes(StandardCharsets.UTF_8)) + "\n"));
          for (int i = 0; i < arguments.length; i++) assertTrue(output.contains("arg" + (i + 1) + "=" + hex(arguments[i].getBytes(StandardCharsets.UTF_8)) + "\n"));
          assertTrue(output.contains("stdin=" + hex(input) + "\n"));
          String expected = socketValue.startsWith("tcp:127.0.0.1:") ? "tcp:45001" : socketValue;
          assertTrue(output.contains(hex(("ADB_SERVER_SOCKET=" + expected).getBytes(StandardCharsets.UTF_8)) + "\n"));
          assertTrue(output.contains(hex("PATCH_SENTINEL=keep me".getBytes(StandardCharsets.UTF_8)) + "\n"));
        } finally { stop(process); }
      }
    } finally { cleanup(root); }
  }

  @Test public void missingAndSymlinkOriginalFailWithoutFallback() throws Exception {
    Path root = fixture();
    try {
      layout(root, true); Path original = root.resolve("runtime/usr/bin/" + ORIGINAL); Files.delete(original);
      executable(root.resolve("payload/fake-original"));
      for (int variant = 0; variant < 2; variant++) {
        if (variant == 1) Files.createSymbolicLink(original, root.resolve("payload/fake-original"));
        Process process = null;
        try {
          process = command(root, false, "version").start(); process.getOutputStream().close();
          assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue());
          assertFalse(text(process.getInputStream()).contains("DSH_FAKE_ORIGINAL_V1"));
        } finally { stop(process); }
      }
    } finally { cleanup(root); }
  }
}
