package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-activity mobile checks. The HTTP page below is a test fixture, not the official DSH UI;
 * it only implements the documented data attributes needed to exercise the presentation adapter.
 */
@RunWith(AndroidJUnit4.class)
public final class MobileLayoutTest {
  private static final String FIXTURE_URL = "http://127.0.0.1:3080/";
  private static final String FIXTURE = "<!doctype html><html><head>"
      + "<meta name=viewport content=\"width=device-width, initial-scale=1\">"
      + "<title>instrumentation fixture</title></head><body>"
      + "<main><div class=_sidebarCol_fixture_></div>"
      + "<div class=_centerCol_fixture_></div><div data-rightbar-col></div><div data-shell-overlay></div></main>"
      + "<header><div data-conversation-header-leading>fixture</div></header>"
      + "<div data-conversation-content><div data-composer-seat><div data-composer-card>"
      + "<div class=_row_fixture_><div class=_scroll_fixture_>"
      + "<input id=\"fixture-input\" aria-label=\"fixture input\"></div></div>"
      + "<div contenteditable id=\"draft\" data-testid=\"composer-input\"></div>"
      + "</div></div></div>"
      + "<iframe id=\"foreign-frame\" src=\"about:blank\"></iframe>"
      + "</body></html>";

  private ActivityScenario<MainActivity> scenario;
  private FixtureServer server;

  @Before public void setUp() throws Exception {
    server = new FixtureServer();
    server.start();
    scenario = ActivityScenario.launch(MainActivity.class);
    long end = SystemClock.uptimeMillis() + 30000;
    AtomicReference<Boolean> ready = new AtomicReference<>(false);
    while (!ready.get() && SystemClock.uptimeMillis() < end) {
      scenario.onActivity(activity -> {
        try {
          Field f = MainActivity.class.getDeclaredField("bound");
          f.setAccessible(true);
          ready.set(f.getBoolean(activity));
        } catch (Exception e) { throw new AssertionError(e); }
      });
      SystemClock.sleep(100);
    }
    assertTrue("Local Binder must settle before loading fixture", ready.get());
  }

  @After public void tearDown() throws Exception {
    if (scenario != null) scenario.close();
    if (server != null) server.close();
  }

  @Test public void nativeChromeAndWelcomeScreenAreRealAndTouchable() {
    scenario.onActivity(activity -> {
      int flags = activity.getWindow().getAttributes().flags;
      assertTrue("FLAG_SECURE must remain set", (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
      assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
          activity.getWindow().getAttributes().softInputMode
              & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
      LinearLayout root = (LinearLayout) ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
      assertEquals("top bar is 48dp", dp(activity, 48), root.getChildAt(0).getLayoutParams().height);
      ScrollView scroll = (ScrollView) ((LinearLayout) root.getChildAt(1)).getChildAt(0);
      assertNotNull("native card homepage must be scrollable", scroll);
      assertTrue("homepage must expose the engine card", containsText(scroll, "本机引擎"));
      assertTrue("homepage must expose truthful runtime status", containsText(scroll, "运行时与安装"));
      assertTrue("homepage must state that official tools require launch", containsText(scroll, "必须在引擎启动后"));
      assertTrue("homepage must expose native file transfers", containsText(scroll, "手机文件"));
      assertTrue("homepage must expose separate remote mode", containsText(scroll, "远程连接"));
      assertNotNull("remote entry must remain reachable without starting local runtime",
          findButton(scroll, "连接远程主机"));
      for (String label : new String[]{"查看脱敏日志", "只读诊断", "插件使用说明", "终端使用说明", "导入 / 导出手机文件"}) {
        Button shortcut = findButton(scroll, label);
        assertNotNull("real shortcut: " + label, shortcut);
        assertTrue(shortcut.isClickable());
      }
      Button start = findButton(scroll, "启动");
      Button stop = findButton(scroll, "停止引擎");
      assertNotNull(start);
      assertNotNull(stop);
      assertTrue(start.isClickable());
      assertTrue(stop.isClickable());
      Button menu = (Button) ((LinearLayout) root.getChildAt(0)).getChildAt(2);
      assertEquals(dp(activity, 48), menu.getLayoutParams().width);
      assertEquals(dp(activity, 48), menu.getLayoutParams().height);
      assertTrue("menu must accept a touch", menu.performClick());
      // The menu is a real AlertDialog; dismiss it without starting the engine.
      InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(
          android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
    });
  }

  @Test public void nativeHomeStatusUpdatesKeepScrollAndConservativeControls() {
    scenario.onActivity(activity -> {
      LinearLayout root = (LinearLayout) ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
      LinearLayout content = (LinearLayout) root.getChildAt(1);
      ScrollView scroll = (ScrollView) content.getChildAt(0);
      try {
        Method apply = MainActivity.class.getDeclaredMethod("apply", DshService.Snapshot.class);
        apply.setAccessible(true);
        apply.invoke(activity, new DshService.Snapshot("正在校验与安装本机运行时…", null, ""));
        assertSame("snapshot must not rebuild scroll", scroll, content.getChildAt(0));
        assertFalse(findButton(scroll, "启动").isEnabled());
        assertTrue(findButton(scroll, "停止引擎").isEnabled());
        assertFalse(findButton(scroll, "重试").isEnabled());
        apply.invoke(activity, new DshService.Snapshot("已停止", null, ""));
        assertSame(scroll, content.getChildAt(0));
        assertTrue(findButton(scroll, "启动").isEnabled());
        assertFalse(findButton(scroll, "停止引擎").isEnabled());
      } catch (Exception error) { throw new AssertionError(error); }
    });
  }

  @Test public void secureWebViewLoadsFixtureAndAdapterTogglePreservesDraft() throws Exception {
    WebView web = loadFixture();
    scenario.onActivity(activity -> {
      WebSettings s = web.getSettings();
      assertTrue(s.getJavaScriptEnabled());
      assertTrue(s.getDomStorageEnabled());
      assertFalse(s.getAllowFileAccess());
      assertFalse(s.getAllowContentAccess());
      assertFalse(s.getAllowFileAccessFromFileURLs());
      assertFalse(s.getAllowUniversalAccessFromFileURLs());
      assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.getMixedContentMode());
      assertTrue(s.supportZoom());
      assertTrue(s.getBuiltInZoomControls());
      assertEquals(Math.round(activity.getResources().getConfiguration().fontScale * 100), s.getTextZoom());
    });
    assertEquals("true", javascript(web, "String(!!window.__dshAndroidMobile)"));
    assertEquals("true", javascript(web, "String(document.documentElement.hasAttribute('data-dsh-android-mobile'))"));
    assertEquals("true", javascript(web, "String(!!document.querySelector('[data-dsh-mobile-frame]'))"));
    assertEquals("about:blank", javascript(web,
        "document.getElementById('foreign-frame').getAttribute('src')"));
    assertEquals("true", javascript(web, "String(!!document.querySelector('[contenteditable]'))"));
    assertEquals("true", javascript(web, "String(!!document.querySelector('#fixture-input'))"));

    assertEquals("ok", javascript(web,
        "document.getElementById('draft').textContent='draft survives';'ok'"));
    final AtomicReference<WebView> before = new AtomicReference<>(web);
    invokeToggle(false);
    assertSame(before.get(), getWeb());
    assertEquals("true", javascript(web, "String(!document.documentElement.hasAttribute('data-dsh-android-mobile'))"));
    assertEquals("draft survives", javascript(web, "document.getElementById('draft').textContent"));
    invokeToggle(true);
    assertSame(before.get(), getWeb());
    assertEquals("true", javascript(web, "String(document.documentElement.hasAttribute('data-dsh-android-mobile'))"));
    assertEquals("draft survives", javascript(web, "document.getElementById('draft').textContent"));
  }

  @Test public void imeResizeIsObservedAndRestored() throws Exception {
    WebView web = loadFixture();
    final AtomicReference<Integer> initial = new AtomicReference<>();
    scenario.onActivity(a -> initial.set(web.getHeight()));
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      web.requestFocus();
      web.evaluateJavascript("document.getElementById('fixture-input').focus()", null);
      InputMethodManager imm = (InputMethodManager) web.getContext().getSystemService(InputMethodManager.class);
      imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
    });
    int reduced = waitForHeightBelow(web, initial.get() - Math.max(24, initial.get() / 10), 8000);
    assertTrue("IME did not resize WebView; initial=" + initial.get() + ", measured=" + reduced
        + ". This emulator must provide a visible soft keyboard for this test.", reduced > 0 && reduced <= initial.get() - Math.max(24, initial.get() / 10));
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      InputMethodManager imm = (InputMethodManager) web.getContext().getSystemService(InputMethodManager.class);
      imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
    });
    int restored = waitForHeightAtLeast(web, initial.get() - 8, 8000);
    assertTrue("WebView did not restore after IME hide: initial=" + initial.get()
        + ", restored=" + restored, restored >= initial.get() - 8);
  }

  @Test public void orientationChangeKeepsActivityWebViewAndDraft() throws Exception {
    WebView web = loadFixture();
    assertEquals("ok", javascript(web, "document.getElementById('draft').textContent='rotate draft';'ok'"));
    final AtomicReference<MainActivity> activityRef = new AtomicReference<>();
    scenario.onActivity(activityRef::set);
    MainActivity original = activityRef.get();
    scenario.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
    waitForOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE);
    assertSame(original, currentActivity());
    assertSame(web, getWeb());
    scenario.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
    waitForOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT);
    assertSame(original, currentActivity());
    assertSame(web, getWeb());
    assertEquals("rotate draft", javascript(web, "document.getElementById('draft').textContent"));
  }

  @Test public void mainFrameHttpErrorIsNotReportedConnectedAndRetryRecovers() throws Exception {
    WebView web = loadFixture();
    server.responseCode = 503;
    scenario.onActivity(a -> web.loadUrl(FIXTURE_URL + "unavailable"));
    waitForNativeState("pageError", true);
    scenario.onActivity(a -> {
      try {
        Field loaded = MainActivity.class.getDeclaredField("loadedUrl");
        loaded.setAccessible(true);
        assertEquals("failed document must not become connected", null, loaded.get(a));
      } catch (Exception e) { throw new AssertionError(e); }
    });
    server.responseCode = 200;
    // Keep the existing WebView to also test that a successful navigation clears its failure flag.
    scenario.onActivity(a -> web.loadUrl(FIXTURE_URL));
    waitForNativeState("loadedUrl", true);
    waitForNativeState("pageError", false);
    assertSame(web, getWeb());
  }

  @Test public void remoteManifestIsPrivateAndLocalHttpGateRemainsExact() throws Exception {
    WebView web = loadFixture();
    scenario.onActivity(activity -> {
      try {
        ActivityInfo remote = activity.getPackageManager().getActivityInfo(
            new android.content.ComponentName(activity, RemoteActivity.class), 0);
        assertFalse("remote activity must not accept external launch intents", remote.exported);
        assertEquals(activity.getPackageName() + ":remote", remote.processName);
        assertEquals("星渡", activity.getApplicationInfo().loadLabel(activity.getPackageManager()).toString());
        android.webkit.WebResourceResponse response = web.getWebViewClient().shouldInterceptRequest(
            web, resourceRequest("http://192.168.1.9:3080/should-not-leak"));
        assertNotNull("platform LAN HTTP permission must not widen local HTTP origins", response);
        assertEquals(403, response.getStatusCode());
        assertEquals(null, web.getWebViewClient().shouldInterceptRequest(
            web, resourceRequest(FIXTURE_URL + "app.js")));
      } catch (Exception error) { throw new AssertionError(error); }
    });
  }

  private static android.webkit.WebResourceRequest resourceRequest(String address) {
    return new android.webkit.WebResourceRequest() {
      public android.net.Uri getUrl() { return android.net.Uri.parse(address); }
      public boolean isForMainFrame() { return false; }
      public boolean isRedirect() { return false; }
      public boolean hasGesture() { return false; }
      public String getMethod() { return "GET"; }
      public java.util.Map<String, String> getRequestHeaders() { return java.util.Collections.emptyMap(); }
    };
  }

  private static boolean containsText(android.view.View view, String expected) {
    if (view instanceof android.widget.TextView
        && ((android.widget.TextView) view).getText().toString().contains(expected)) return true;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), expected)) return true;
    }
    return false;
  }

  private static Button findButton(android.view.View view, String expected) {
    if (view instanceof Button && ((Button) view).getText().toString().equals(expected)) return (Button) view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = findButton(group.getChildAt(i), expected);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void waitForNativeState(String field, boolean present) {
    long end = SystemClock.uptimeMillis() + 10000;
    AtomicReference<Boolean> matched = new AtomicReference<>(false);
    while (!matched.get() && SystemClock.uptimeMillis() < end) {
      scenario.onActivity(a -> {
        try {
          Field f = MainActivity.class.getDeclaredField(field);
          f.setAccessible(true);
          matched.set((f.get(a) != null) == present);
        } catch (Exception e) { throw new AssertionError(e); }
      });
      SystemClock.sleep(50);
    }
    assertTrue(field + " presence must become " + present, matched.get());
  }

  private WebView loadFixture() throws Exception {
    final AtomicReference<WebView> result = new AtomicReference<>();
    scenario.onActivity(activity -> {
      try {
        Field desired = MainActivity.class.getDeclaredField("desiredUrl");
        desired.setAccessible(true);
        desired.set(activity, FIXTURE_URL);
        Method load = MainActivity.class.getDeclaredMethod("load", String.class);
        load.setAccessible(true);
        load.invoke(activity, FIXTURE_URL);
      } catch (Exception e) { throw new AssertionError(e); }
    });
    long end = SystemClock.uptimeMillis() + 8000;
    while (result.get() == null && SystemClock.uptimeMillis() < end) {
      scenario.onActivity(activity -> result.set(getWeb(activity)));
      SystemClock.sleep(50);
    }
    assertNotNull("private load must create a real WebView", result.get());
    waitForJavascript(result.get(), "String(!!window.__dshAndroidMobile)", "true", 10000);
    return result.get();
  }

  private void invokeToggle(boolean enabled) throws Exception {
    scenario.onActivity(activity -> {
      try {
        Method m = MainActivity.class.getDeclaredMethod("toggleMobileLayout", boolean.class);
        m.setAccessible(true);
        m.invoke(activity, enabled);
      } catch (Exception e) { throw new AssertionError(e); }
    });
    SystemClock.sleep(250);
  }

  private void waitForOrientation(int orientation) {
    long end = SystemClock.uptimeMillis() + 30000;
    AtomicReference<Integer> value = new AtomicReference<>(0);
    while (SystemClock.uptimeMillis() < end) {
      scenario.onActivity(a -> value.set(a.getResources().getConfiguration().orientation));
      if (value.get() == orientation) return;
      SystemClock.sleep(100);
    }
    assertEquals("Actual device orientation must change", orientation, value.get().intValue());
  }

  private MainActivity currentActivity() {
    final AtomicReference<MainActivity> ref = new AtomicReference<>();
    scenario.onActivity(ref::set);
    return ref.get();
  }

  private WebView getWeb() { return getWeb(currentActivity()); }
  private static WebView getWeb(MainActivity a) {
    try {
      Field f = MainActivity.class.getDeclaredField("web");
      f.setAccessible(true);
      return (WebView) f.get(a);
    } catch (Exception e) { throw new AssertionError(e); }
  }

  private static int dp(Activity a, int value) {
    return (int) (value * a.getResources().getDisplayMetrics().density + .5f);
  }

  private static String javascript(WebView web, String expression) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> value = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> web.evaluateJavascript(expression,
        s -> { value.set(s); latch.countDown(); }));
    assertTrue("JavaScript callback timed out", latch.await(8, TimeUnit.SECONDS));
    Object parsed = new JSONTokener(value.get()).nextValue();
    return parsed == null ? null : parsed.toString();
  }

  private static void waitForJavascript(WebView web, String expression, String expected, long timeout) throws Exception {
    long end = SystemClock.uptimeMillis() + timeout;
    String actual = "";
    while (SystemClock.uptimeMillis() < end) {
      actual = javascript(web, expression);
      if (expected.equals(actual)) return;
      SystemClock.sleep(100);
    }
    assertEquals("WebView JavaScript condition timed out", expected, actual);
  }

  private static int waitForHeightBelow(WebView web, int target, long timeout) {
    long end = SystemClock.uptimeMillis() + timeout;
    int measured = web.getHeight();
    while (SystemClock.uptimeMillis() < end) {
      AtomicReference<Integer> height = new AtomicReference<>();
      InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> height.set(web.getHeight()));
      measured = height.get();
      if (measured <= target) return measured;
      SystemClock.sleep(100);
    }
    return measured;
  }

  private static int waitForHeightAtLeast(WebView web, int target, long timeout) {
    long end = SystemClock.uptimeMillis() + timeout;
    int measured = web.getHeight();
    while (SystemClock.uptimeMillis() < end) {
      AtomicReference<Integer> height = new AtomicReference<>();
      InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> height.set(web.getHeight()));
      measured = height.get();
      if (measured >= target) return measured;
      SystemClock.sleep(100);
    }
    return measured;
  }

  private static final class FixtureServer implements AutoCloseable {
    private final ServerSocket socket;
    private volatile boolean running = true;
    volatile int responseCode = 200;
    private Thread thread;
    FixtureServer() throws IOException {
      socket = new ServerSocket(3080, 20, InetAddress.getByName("127.0.0.1"));
    }
    void start() {
      thread = new Thread(() -> {
        while (running) try (Socket client = socket.accept()) {
          BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
          String line;
          while ((line = in.readLine()) != null && !line.isEmpty()) { /* consume request */ }
          BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
          byte[] body = FIXTURE.getBytes(StandardCharsets.UTF_8);
          out.write("HTTP/1.1 " + responseCode + (responseCode == 200 ? " OK" : " Service Unavailable")
              + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
              + body.length + "\r\nConnection: close\r\n\r\n");
          out.flush();
          client.getOutputStream().write(body);
          client.getOutputStream().flush();
        } catch (IOException ignored) { if (running) throw new AssertionError(ignored); }
      }, "mobile-layout-fixture");
      thread.start();
    }
    @Override public void close() throws Exception {
      running = false;
      socket.close();
      if (thread != null) thread.join(2000);
    }
  }
}
