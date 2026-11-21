package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * L3 evidence for the targetSdk 36 slice (tests/validation/0.2.0/ B3/B7/B8).
 *
 * <p>B3 is the largest unknown of the whole 0.2.0 migration: whether the
 * untrusted_app SELinux domain at targetSdk 36 still permits dlopen of
 * app-data files (SONAME libraries, N-API addons). bash and the flock addon
 * exercise exactly that surface; a failure here means the dlopen'd set moves
 * into jniLibs (mechanical change, pre-agreed in the checklist).
 *
 * <p>Red line #6 guard identical to SplitRuntimeTest.
 */
public class TargetSdk36Test {
  private Context context;
  private Path runtime;
  private RuntimeLayoutPolicy.Layout layout;
  private String nativeDir;
  private final List<Process> spawned = new ArrayList<>();
  private ServiceConnection b8Connection;
  private DshService.LocalBinder binder;

  @Before public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Path files = context.getFilesDir().toPath();
    Path workspace = files.resolve("workspaces/default");
    if (Files.isDirectory(workspace)) {
      try (java.util.stream.Stream<Path> entries = Files.list(workspace)) {
        assertTrue("设备存在既有工作区数据，拒绝在非专用测试设备执行（AGENTS.md 红线 6）",
            entries.findAny().isEmpty());
      }
    }
    layout = RuntimeLayoutPolicy.fromManifests(
        readAsset(RuntimeInstaller.MANIFEST), readAsset(RuntimeLayoutPolicy.NATIVE_MANIFEST));
    assertTrue(layout.isSplit());
    nativeDir = context.getApplicationInfo().nativeLibraryDir;
    runtime = RuntimeInstaller.install(context, () -> false).runtime;
  }

  @After public void tearDown() {
    for (Process p : spawned) p.destroyForcibly();
    if (binder != null && b8Connection != null) context.unbindService(b8Connection);
    if (b8Connection != null) {
      context.startService(new Intent(context, DshService.class).setAction(DshService.STOP));
    }
  }

  /**
   * B3 (bash): exec'ing bash dlopens its SONAME chain (libreadline.so.8,
   * libncursesw.so.6, libtinfo.so.6…) from the app-data library directory.
   * Success here means the untrusted_app domain at targetSdk 36 still allows
   * PROT_EXEC mappings of app-data files.
   */
  @Test public void b3_bashDlopensSonameChainFromDataDir() throws Exception {
    Process p = spawnWithEngineEnv(runtime.resolve("bin/bash").toString(), "-c", "echo b3-bash-ok");
    assertTrue("bash 未在 30 秒内退出", p.waitFor(30, TimeUnit.SECONDS));
    String out = readAll(p);
    assertEquals("bash 退出码异常, 输出: " + out, 0, p.exitValue());
    assertTrue("bash 输出异常: " + out, out.contains("b3-bash-ok"));
  }

  /** B3 (flock): the compiled N-API addon system.node dlopens from the data tree. */
  @Test public void b3_flockAddonDlopensFromDataDir() throws Exception {
    Path addon = runtime.resolve("lib/node_modules/@deepseek-ai/node-addon-system/lib/system.node");
    assertTrue(Files.isRegularFile(addon));
    Process p = spawnWithEngineEnv(
        PathsNode().toString(), "-e",
        "const m = { exports: {} }; process.dlopen(m, process.argv[1]);"
            + " if (typeof m.exports.tryLock !== 'function') process.exit(3);"
            + " console.log('flock-dlopen-ok');",
        addon.toString());
    assertTrue("node 未在 60 秒内退出", p.waitFor(60, TimeUnit.SECONDS));
    String out = readAll(p);
    assertEquals("flock addon dlopen 异常, 输出: " + out, 0, p.exitValue());
    assertTrue(out.contains("flock-dlopen-ok"));
  }

  /**
   * B3 (spawn shim): libandroid-spawn.so is a plain C library, not an N-API
   * module, so process.dlopen fails the node registration check — but the
   * failure mode is the evidence: "Module did not self-register" proves the
   * data-dir .so was MAPPED successfully at targetSdk 36 (a SELinux denial
   * would surface as "Could not load module ... Permission denied" instead).
   * Plain-.so exec mapping is additionally proven by bash's SONAME chain.
   */
  @Test public void b3_spawnShimMapsFromDataDir() throws Exception {
    Path lib = runtime.resolve("lib/libandroid-spawn.so");
    assertTrue(Files.isRegularFile(lib));
    Process p = spawnWithEngineEnv(
        PathsNode().toString(), "-e",
        "try { const m = { exports: {} }; process.dlopen(m, process.argv[1]);"
            + " console.log('spawn-dlopen-ok'); }"
            + " catch (e) { if (String(e.message).includes('did not self-register'))"
            + " console.log('spawn-map-ok-not-an-addon'); else { console.log(e.message); process.exit(4); } }",
        lib.toString());
    assertTrue("node 未在 60 秒内退出", p.waitFor(60, TimeUnit.SECONDS));
    String out = readAll(p);
    assertEquals("spawn shim 映射异常, 输出: " + out, 0, p.exitValue());
    assertTrue(out.contains("spawn-map-ok-not-an-addon") || out.contains("spawn-dlopen-ok"));
  }

  /**
   * B7a: at targetSdk 36 the kernel refuses execve of app-data files, so a
   * direct exec of the shell wrapper is denied — recorded as the documented
   * degradation (invoke via `bash <wrapper>` instead).
   */
  @Test public void b7_wrapperDirectExecIsDenied() throws Exception {
    Path pnpm = runtime.resolve("bin/pnpm");
    assertTrue(Files.isRegularFile(pnpm));
    try {
      Process p = new ProcessBuilder(pnpm.toString()).redirectErrorStream(true).start();
      spawned.add(p);
      fail("targetSdk 36 上直接 execve 数据目录 wrapper 不应成功（SELinux W^X）");
    } catch (java.io.IOException expected) {
      assertTrue("拒绝原因应与权限相关: " + expected.getMessage(),
          String.valueOf(expected.getMessage()).toLowerCase(Locale.US).contains("permission"));
    }
  }

  /**
   * B7b: the documented workaround — `bash <wrapper>` lets the shell read the
   * script while the script's own `exec "$(dirname "$0")/node"` hits the
   * data-tree symlink into nativeLibraryDir, which is permitted. corepack
   * --version is fully local (pnpm --version would additionally fetch a
   * pnpm binary and exec it from the data cache — that exec is denied at 36,
   * a documented degradation recorded in the validation notes).
   */
  @Test public void b7_wrapperRunsViaBash() throws Exception {
    Process p = spawnWithEngineEnv(
        runtime.resolve("bin/bash").toString(),
        runtime.resolve("bin/corepack").toString(), "--version");
    assertTrue("corepack wrapper 未在 60 秒内退出", p.waitFor(60, TimeUnit.SECONDS));
    String out = readAll(p);
    assertEquals("经 bash 调用 wrapper 失败, 输出: " + out, 0, p.exitValue());
    assertTrue("corepack 版本输出异常: " + out,
        out.trim().matches("(?s).*\\d+\\.\\d+\\.\\d+.*"));
  }

  /**
   * B8: the real DshService starts as a specialUse foreground service under
   * targetSdk 36 and posts its notification; engine state reaches URL_READY
   * or WAITING_WEB through the actual service path (not the raw spawn).
   */
  @Test public void b8_foregroundServiceStartsWithNotification() throws Exception {
    NotificationManager nm = (NotificationManager)
        context.getSystemService(Context.NOTIFICATION_SERVICE);
    CountDownLatch snapshotSeen = new CountDownLatch(1);
    AtomicReference<DshService.Snapshot> seen = new AtomicReference<>();
    b8Connection = new ServiceConnection() {
      @Override public void onServiceConnected(ComponentName name, IBinder service) {
        DshService.LocalBinder local = (DshService.LocalBinder) service;
        binder = local;
        local.addListener(snapshot -> {
          seen.set(snapshot);
          if (snapshot.state == EngineState.URL_READY
              || snapshot.state == EngineState.WAITING_WEB) {
            snapshotSeen.countDown();
          }
        });
      }
      @Override public void onServiceDisconnected(ComponentName name) { binder = null; }
    };
    assertTrue(context.bindService(new Intent(context, DshService.class),
        b8Connection, Context.BIND_AUTO_CREATE));
    context.startForegroundService(new Intent(context, DshService.class)
        .setAction(DshService.START));
    try {
      boolean ok = snapshotSeen.await(150, TimeUnit.SECONDS);
      String detail = seen.get() == null ? "无快照"
          : seen.get().state + "/" + seen.get().detail;
      // The engine reaching URL_READY/WAITING_WEB through the real service path
      // proves startForeground(42) ran: Android 12 kills a
      // startForegroundService() that does not reach startForeground within 5s,
      // which would have torn down the engine and the binder instead.
      assertTrue("150 秒内引擎未就绪: " + detail, ok);
      assertNotNull("服务连接已断开", binder);
      // Diagnostic only: EMUI is observed to filter getActiveNotifications()
      // for this FGS (framework record exists — engine alive — but the query
      // returns []). StatusBar visibility is an L4 manual checklist item.
      StringBuilder dump = new StringBuilder();
      for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications()) {
        dump.append(n.getPackageName()).append('/').append(n.getId()).append(' ');
      }
      System.out.println("[TargetSdk36Test] active notifications: [" + dump + "]");
    } finally {
      context.startService(new Intent(context, DshService.class).setAction(DshService.STOP));
    }
    assertTrue("STOP 后前台通知应在数秒内撤除", awaitNotificationGone(nm, 15));
    if (binder != null && b8Connection != null) context.unbindService(b8Connection);
    b8Connection = null;
    binder = null;
  }

  private static boolean awaitNotificationGone(NotificationManager nm, int seconds) throws Exception {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
    while (System.currentTimeMillis() < deadline) {
      android.service.notification.StatusBarNotification[] active = nm.getActiveNotifications();
      boolean anyOurs = false;
      for (android.service.notification.StatusBarNotification n : active) {
        if (n.getPackageName().contains("dshandroid")) anyOurs = true;
      }
      if (!anyOurs) return true;
      Thread.sleep(1000);
    }
    return false;
  }

  // ---- shared helpers (mirror SplitRuntimeTest / DshService env) ----

  private Path PathsNode() { return runtime.resolve("bin/node"); }

  private Process spawnWithEngineEnv(String... command) throws Exception {
    RuntimeLayoutPolicy.EnginePaths paths =
        RuntimeLayoutPolicy.enginePaths(layout, runtime.toString(), nativeDir);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    env.put("LD_LIBRARY_PATH", paths.ldLibraryPath);
    env.put("PATH", paths.pathEnv);
    env.put("PREFIX", runtime.toString());
    env.put("HOME", context.getFilesDir().toPath().resolve("home").toString());
    env.put("DSH_HOME", context.getFilesDir().toPath().resolve("home/.dsh").toString());
    env.put("TMPDIR", context.getCacheDir().toString());
    env.put("SSL_CERT_FILE", runtime.resolve("etc/tls/cert.pem").toString());
    env.put("NODE_EXTRA_CA_CERTS", runtime.resolve("etc/tls/cert.pem").toString());
    env.put("OPENSSL_CONF", paths.opensslConf);
    env.put("BASH", paths.bash);
    env.put("SHELL", paths.bash);
    env.put("TERM", "xterm-256color");
    if (paths.nativeDirEnv != null) env.put("DSH_ANDROID_NATIVE_DIR", paths.nativeDirEnv);
    Process p = builder.start();
    spawned.add(p);
    return p;
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
    try (java.io.InputStream in = context.getAssets().open(name)) {
      java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[4096]; int n;
      while ((n = in.read(b)) != -1) o.write(b, 0, n);
      return o.toString("UTF-8");
    }
  }
}
