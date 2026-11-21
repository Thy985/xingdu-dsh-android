package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * L3 evidence for the split runtime layout (tests/validation/0.2.0/ B1/B2/B4/B5).
 *
 * <p>Runs the REAL engine: real RuntimeInstaller.install, real ProcessBuilder
 * command identical to DshService.startInternal, real HTTP probe on
 * 127.0.0.1:3080. No model configuration is involved — `dsh web` serves its UI
 * without one — and the spawned process is always destroyed in finally.
 *
 * <p>Red line #6: this suite asserts it is running on a dedicated test device
 * by refusing to proceed when the app already carries user data
 * (files/workspaces non-empty before install).
 */
public class SplitRuntimeTest {
  private Context context;
  private Path runtime;
  private RuntimeLayoutPolicy.Layout layout;
  private String nativeDir;
  private Process engine;

  @Before public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Path files = context.getFilesDir().toPath();
    // Red line #6 guard: a pre-existing workspace means real user data on this
    // device — the suite must not run against it.
    Path workspace = files.resolve("workspaces/default");
    if (Files.isDirectory(workspace)) {
      try (java.util.stream.Stream<Path> entries = Files.list(workspace)) {
        assertTrue("设备存在既有工作区数据，拒绝在非专用测试设备执行（AGENTS.md 红线 6）",
            entries.findAny().isEmpty());
      }
    }
    layout = RuntimeLayoutPolicy.fromManifests(
        readAsset(RuntimeInstaller.MANIFEST), readAsset(RuntimeLayoutPolicy.NATIVE_MANIFEST));
    assertTrue("被测 APK 必须携带 split 布局 manifest", layout.isSplit());
    nativeDir = context.getApplicationInfo().nativeLibraryDir;
    runtime = RuntimeInstaller.install(context, () -> false).runtime;
  }

  @After public void tearDown() {
    if (engine != null) engine.destroyForcibly();
  }

  /** B1: PackageManager extracted the five jniLibs components; size + SHA-256 match the manifest. */
  @Test public void b1_nativeLibsExtractedAndHashVerified() throws Exception {
    for (RuntimeLayoutPolicy.NativeLib lib : layout.nativeLibs) {
      Path p = Paths.get(nativeDir, lib.name);
      assertTrue("nativeLibraryDir 缺少组件: " + lib.name, Files.isRegularFile(p));
      assertEquals("组件大小不符: " + lib.name, lib.bytes, Files.size(p));
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new BufferedInputStream(Files.newInputStream(p))) {
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) md.update(b, 0, n);
      }
      assertEquals("组件 SHA-256 不符: " + lib.name, lib.sha256, hex(md.digest()));
    }
  }

  /** B1b: installer committed the data tree and recreated every historical exec-site symlink. */
  @Test public void b1b_installCommittedDataTreeWithSymlinks() throws Exception {
    assertTrue("安装器未产出数据树", Files.isRegularFile(runtime.resolve(
        "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")));
    assertTrue("安装标记缺失", Files.isRegularFile(
        context.getFilesDir().toPath().resolve("runtime.sha256")));
    for (Map.Entry<String, String> site : RuntimeLayoutPolicy.splitSymlinkSites(layout.abi).entrySet()) {
      Path link = runtime.resolve(site.getKey());
      assertTrue("符号链接缺失: " + site.getKey(), Files.exists(link, LinkOption.NOFOLLOW_LINKS));
      assertTrue("应为符号链接: " + site.getKey(), Files.isSymbolicLink(link));
      assertEquals("符号链接目标错误: " + site.getKey(),
          Paths.get(nativeDir, site.getValue()), Files.readSymbolicLink(link));
    }
  }

  /**
   * B2: the engine starts with node from nativeLibraryDir (via the data-tree
   * symlink) and serves the official web UI on 127.0.0.1:3080.
   */
  @Test public void b2_engineServesWebFromNativeLibraryDir() throws Exception {
    startEngine();
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(120);
    while (System.currentTimeMillis() < deadline) {
      if (engine != null && !engine.isAlive()) {
        String out = engineOutput == null ? "" : String.valueOf(engineOutput.get());
        fail("引擎进程提前退出, exit=" + engine.exitValue() + " 输出: " + out);
      }
      if (httpProbe()) return;
      Thread.sleep(2000);
    }
    String out = engineOutput == null ? "" : String.valueOf(engineOutput.get());
    fail("120 秒内 127.0.0.1:3080 未就绪; 引擎输出: " + out);
  }

  /** B4: the engine's ripgrep resolver path (data-tree symlink) execs the nativeLibraryDir binary. */
  @Test public void b4_ripgrepExecsThroughSymlink() throws Exception {
    Process p = spawnWithEngineEnv(runtime.resolve("bin/rg").toString(), "--version");
    assertTrue("rg --version 未在 30 秒内退出", p.waitFor(30, TimeUnit.SECONDS));
    String out = readAll(p);
    assertEquals("rg 退出码异常, 输出: " + out, 0, p.exitValue());
    assertTrue("rg 版本输出异常: " + out, out.contains("ripgrep"));
  }

  /** B5: the PTY helper resolves and runs through the data-tree symlink (no ENOENT). */
  @Test public void b5_ptyHelperResolvesThroughSymlink() throws Exception {
    Path pty = runtime.resolve("bin/dsh_pty_exec");
    assertTrue("PTY 符号链接缺失", Files.isSymbolicLink(pty));
    Process p = spawnWithEngineEnv(pty.toString());
    assertTrue("PTY 助手未在 30 秒内退出", p.waitFor(30, TimeUnit.SECONDS));
    String out = readAll(p).toLowerCase(Locale.US);
    assertFalseLikeEnoent(out);
  }

  /** Spawn a helper exactly the way the engine environment would (LD_LIBRARY_PATH etc.). */
  private Process spawnWithEngineEnv(String... command) throws Exception {
    RuntimeLayoutPolicy.EnginePaths paths =
        RuntimeLayoutPolicy.enginePaths(layout, runtime.toString(), nativeDir);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.put("LD_LIBRARY_PATH", paths.ldLibraryPath);
    env.put("PATH", paths.pathEnv);
    env.put("PREFIX", runtime.toString());
    env.put("HOME", context.getFilesDir().toPath().resolve("home").toString());
    env.put("OPENSSL_CONF", paths.opensslConf);
    return builder.start();
  }

  private static void assertFalseLikeEnoent(String out) {
    assertTrue("PTY 助手解析失败（ENOENT 意味着符号链接/解包失效）: " + out,
        !out.contains("no such file") && !out.contains("enoent"));
  }

  // ---- engine spawn, byte-identical to DshService.startInternal for this layout ----

  private void startEngine() throws Exception {
    Path home = context.getFilesDir().toPath().resolve("home");
    Path workspace = context.getFilesDir().toPath().resolve("workspaces/default");
    Files.createDirectories(home);
    Files.createDirectories(workspace);
    RuntimeLayoutPolicy.EnginePaths paths =
        RuntimeLayoutPolicy.enginePaths(layout, runtime.toString(), nativeDir);
    List<String> command = new ArrayList<>(Arrays.asList(
        paths.node, "--expose-internals",
        runtime.resolve("lib/node_modules/@deepseek-ai/dsh/lib/bin.js").toString(), "web",
        "--no-open", "--host", "127.0.0.1", "--port", "3080"));
    ProcessBuilder builder = new ProcessBuilder(command)
        .directory(workspace.toFile()).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.put("HOME", home.toString());
    env.put("DSH_HOME", home.resolve(".dsh").toString());
    env.put("PREFIX", runtime.toString());
    env.put("LD_LIBRARY_PATH", paths.ldLibraryPath);
    env.put("PATH", paths.pathEnv);
    env.put("TMPDIR", context.getCacheDir().toString());
    env.put("SSL_CERT_FILE", runtime.resolve("etc/tls/cert.pem").toString());
    env.put("NODE_EXTRA_CA_CERTS", runtime.resolve("etc/tls/cert.pem").toString());
    env.put("OPENSSL_CONF", paths.opensslConf);
    env.put("BASH", paths.bash);
    env.put("SHELL", paths.bash);
    env.put("TERM", "xterm-256color");
    if (paths.nativeDirEnv != null) env.put("DSH_ANDROID_NATIVE_DIR", paths.nativeDirEnv);
    engine = builder.start();
    final AtomicReference<String> firstLines = new AtomicReference<>("");
    Thread reader = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(engine.getInputStream()))) {
        StringBuilder sb = new StringBuilder();
        String line;
        for (int i = 0; i < 40 && (line = r.readLine()) != null; i++) {
          sb.append(line).append('\n');
        }
        firstLines.set(LogParser.redact(sb.toString()));
      } catch (Exception ignored) { /* engine killed */ }
    });
    reader.setDaemon(true);
    reader.start();
    engineOutput = firstLines;
  }

  private AtomicReference<String> engineOutput;

  private boolean httpProbe() throws Exception {
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL("http://127.0.0.1:3080/").openConnection();
      c.setConnectTimeout(3000);
      c.setReadTimeout(3000);
      return c.getResponseCode() > 0;
    } catch (java.io.IOException e) {
      return false;
    } finally {
      if (c != null) c.disconnect();
    }
  }

  private static String readAll(Process p) throws Exception {
    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(p.getInputStream()))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = r.readLine()) != null) sb.append(line).append('\n');
      return sb.toString();
    }
  }

  private String readAsset(String name) throws Exception {
    try (InputStream in = context.getAssets().open(name)) {
      java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[4096]; int n;
      while ((n = in.read(b)) != -1) o.write(b, 0, n);
      return o.toString("UTF-8");
    }
  }

  private static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
    return s.toString();
  }
}
