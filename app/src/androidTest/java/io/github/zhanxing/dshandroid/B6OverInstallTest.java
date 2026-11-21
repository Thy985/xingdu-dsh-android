package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/**
 * B6 step 2/2: verify the 0.1.9 -> 0.2.0 over-install (targetSdk 28 -> 36,
 * legacy -> split layout) preserves user data and reinstalls the runtime.
 *
 * <p>Preconditions orchestrated from the host: 0.1.9 (vc 10) installed and
 * seeded via B6SeedTest (real UI first-run), then `adb install -r` of the
 * 0.2.0 build (vc 11). Deliberately has NO red-line-6 data guard —
 * pre-existing data IS the scenario under test.
 *
 * <p>State-tolerant: if a previous run already performed the reinstall
 * (marker = split sha), the legacy-precondition block is skipped and the
 * post-install assertions still run. Leaves the engine RUNNING for 90s at
 * the end so the host can capture dumpsys evidence before force-stop.
 *
 * <p>Orchestration-only: skipped unless run with `-e b6 true` so the clean
 * suite never executes it.
 */
public class B6OverInstallTest {
  /** SHA-256 of the bundled legacy runtime.zip (v0.1.9, see git history). */
  private static final String LEGACY_ZIP_SHA =
      "2706569fee0514dc2c51af2bf90e5847fc4846ba431a969b3c4dc78762bc27e4";

  private Context context;
  private ServiceConnection connection;
  private DshService.LocalBinder binder;
  private CountDownLatch ready;
  private AtomicReference<DshService.Snapshot> last;

  @Test public void b6_dataSurvivesAndRuntimeReinstalls() throws Exception {
    assumeTrue("B6 编排测试需 -e b6 true 运行", b6Enabled());
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Path files = context.getFilesDir().toPath();
    Path marker = files.resolve("runtime.sha256");
    Path homeDsh = files.resolve("home/.dsh");
    Path workspace = files.resolve("workspaces/default");

    // ---- version + data-presence preconditions ----
    assertEquals("versionCode 应为 11（0.2.0 覆盖安装后）", 11,
        context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode);
    assertTrue("覆盖安装后应存在安装标记", Files.isRegularFile(marker));
    String markerSha = new String(Files.readAllBytes(marker), "UTF-8").trim();
    boolean preReinstall = LEGACY_ZIP_SHA.equals(markerSha);
    if (preReinstall) {
      // 0.1.9 seeded state: home/.dsh is created BY THE ENGINE on first boot;
      // the seed engine died in init (Termux OpenSSL conflict), so only the
      // app-created home/ is guaranteed at this point.
      assertTrue("0.1.9 首启的 home/ 应在覆盖安装后保留",
          Files.isDirectory(files.resolve("home")));
      assertTrue("0.1.9 首启的 legacy runtime 树应保留（bin/node 为常规文件）",
          Files.isRegularFile(files.resolve("runtime/bin/node")));
    } else {
      assertEquals("标记应为 split 数据 zip 的 sha（前一轮已重装）",
          bundledZipSha(), markerSha);
      assertTrue("重装后 bin/node 应为符号链接（split 布局）",
          Files.isSymbolicLink(files.resolve("runtime/bin/node")));
    }
    assertTrue("工作区目录应保留", Files.isDirectory(workspace));

    // ---- start the engine through the real service path ----
    ready = new CountDownLatch(1);
    last = new AtomicReference<>();
    connection = new ServiceConnection() {
      @Override public void onServiceConnected(ComponentName name, IBinder service) {
        DshService.LocalBinder local = (DshService.LocalBinder) service;
        binder = local;
        local.addListener(snapshot -> {
          last.set(snapshot);
          if (snapshot.state == EngineState.URL_READY) ready.countDown();
        });
      }
      @Override public void onServiceDisconnected(ComponentName name) { binder = null; }
    };
    assertTrue(context.bindService(new Intent(context, DshService.class),
        connection, Context.BIND_AUTO_CREATE));
    context.startForegroundService(new Intent(context, DshService.class)
        .setAction(DshService.START));
    assertTrue("300 秒内引擎未就绪: " + state(),
        ready.await(300, TimeUnit.SECONDS));
    assertEquals("引擎应经 split 布局就绪", EngineState.URL_READY, last.get().state);

    // ---- postconditions: transaction replaced the runtime, data preserved ----
    assertEquals("安装标记应为 split 数据 zip 的 sha",
        bundledZipSha(), new String(Files.readAllBytes(marker), "UTF-8").trim());
    assertTrue("重装后 bin/node 应为符号链接（split 布局）",
        Files.isSymbolicLink(files.resolve("runtime/bin/node")));
    assertTrue("工作区目录应保留", Files.isDirectory(workspace));
    assertTrue("引擎就绪后 home/.dsh 应存在（由 0.2.0 引擎首启创建）",
        Files.isDirectory(homeDsh));

    // Evidence window: the host captures dumpsys notification/FGS records now.
    System.out.println("[B6OverInstallTest] EVIDENCE-WINDOW-OPEN 90s");
    Thread.sleep(90_000);
    // Engine intentionally left running; the host force-stops afterwards.
  }

  private String state() {
    DshService.Snapshot s = last.get();
    return s == null ? "无快照" : s.state + "/" + s.detail;
  }

  private String bundledZipSha() throws Exception {
    try (java.io.InputStream in = context.getAssets().open(RuntimeInstaller.MANIFEST)) {
      java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[4096]; int n;
      while ((n = in.read(b)) != -1) o.write(b, 0, n);
      return new JSONObject(o.toString("UTF-8")).optString("sha256", "");
    }
  }

  private static boolean b6Enabled() {
    return "true".equals(InstrumentationRegistry.getArguments().getString("b6", "false"));
  }
}
