package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.net.HttpURLConnection;
import static org.junit.Assume.assumeTrue;
import java.net.URL;
import org.junit.Test;

/**
 * B6 step 1/2: seed a REAL 0.1.9 first-run via the actual UI flow, driven
 * cross-app with UiAutomator (FLAG_SECURE blocks screen capture, not the
 * accessibility tree or input injection).
 *
 * <p>Run against the 0.1.9 install with: adb shell am instrument -e class
 * B6SeedTest … (orchestrated from the host; see the validation record).
 * Must not reference 0.2.0-only classes (RuntimeLayoutPolicy et al.) so it
 * survives class loading inside the 0.1.9 process.
 */
public class B6SeedTest {
  private static final String PKG = "io.github.zhanxing.dshandroid";

  @Test public void seedFirstRunAndStartEngine() throws Exception {
    // Orchestration-only test: skipped unless run with -e b6 true (it drives
    // the 0.1.9 first-run via cross-app UI automation, not a clean-suite case).
    assumeTrue("B6 编排测试需 -e b6 true 运行",
        "true".equals(androidx.test.platform.app.InstrumentationRegistry
            .getArguments().getString("b6", "false")));
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    // The test device sleeps between runs; a locked screen empties the
    // accessibility tree and hides windows from am start.
    device.wakeUp();
    device.executeShellCommand("wm dismiss-keyguard");
    Thread.sleep(1500);

    // Launch the 0.1.9 home and wait for the window.
    device.executeShellCommand("am start -n " + PKG + "/.MainActivity");
    assertTrue("0.1.9 主窗口未出现",
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), 20_000));

    // First run shows the engine card; tap 启动, then walk any confirmation
    // dialog (风险确认) until the engine install begins.
    long dialogDeadline = System.currentTimeMillis() + 30_000;
    boolean tapped = false;
    while (System.currentTimeMillis() < dialogDeadline && !tapped) {
      UiObject2 start = findClickableByText(device, "启动", "本机引擎 启动", "重新启动");
      if (start != null) { start.click(); tapped = true; break; }
      UiObject2 confirm = findClickableByText(device, "确认", "理解风险并开启", "继续", "确定");
      if (confirm != null) { confirm.click(); }
      Thread.sleep(1000);
    }
    assertTrue("未找到可点击的「启动」按钮", tapped);

    // Wait for the engine to serve the official web UI (runtime install of the
    // legacy 211MB zip takes a while on device).
    long deadline = System.currentTimeMillis() + 300_000;
    while (System.currentTimeMillis() < deadline) {
      if (httpProbe()) {
        // Engine serving: marker + home data now exist in the 0.1.9 data dir.
        String marker = readMarker();
        assertNotNull("runtime.sha256 标记缺失", marker);
        assertTrue("标记格式异常", marker.matches("[0-9a-f]{64}"));
        return;
      }
      Thread.sleep(3000);
    }
    fail("300 秒内 0.1.9 引擎未在 127.0.0.1:3080 就绪");
  }

  private static UiObject2 findClickableByText(UiDevice device, String... texts) {
    for (String text : texts) {
      for (UiObject2 o : device.findObjects(By.text(text))) {
        if (o.isClickable()) return o;
      }
      UiObject2 any = device.findObject(By.text(text));
      if (any != null && any.isClickable()) return any;
    }
    return null;
  }

  private static boolean httpProbe() throws Exception {
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

  /** Reads files/runtime.sha256 from the target app's data dir (same uid). */
  private static String readMarker() throws Exception {
    android.content.Context ctx =
        InstrumentationRegistry.getInstrumentation().getTargetContext();
    java.io.File f = new java.io.File(ctx.getFilesDir(), "runtime.sha256");
    if (!f.isFile()) return null;
    byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
    return new String(b, "UTF-8").trim();
  }
}
