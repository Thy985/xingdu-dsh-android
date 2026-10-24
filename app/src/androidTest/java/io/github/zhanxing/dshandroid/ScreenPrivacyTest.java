package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

/** End-to-end checks for the native screen-capture preference and window policy. */
@RunWith(AndroidJUnit4.class)
public final class ScreenPrivacyTest {
  private ActivityScenario<MainActivity> scenario;
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

  @Before public void clearBeforeTest() {
    setPersisted(false);
  }

  @After public void clearAfterTest() {
    if (scenario != null) scenario.close();
    setPersisted(false);
  }

  @Test public void defaultIsFalseAndActivityStartsSecure() {
    clearPersistedPrefs();
    assertFalse("default test must begin with no persisted choice", context
        .getSharedPreferences(ScreenPrivacyProvider.PREFS, Context.MODE_PRIVATE)
        .contains(ScreenPrivacyProvider.ALLOWED));
    scenario = ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity -> {
      ScreenPrivacy privacy = new ScreenPrivacy(activity);
      try {
        assertFalse(privacy.allowed());
        assertTrue("default must protect the window", hasSecureFlag(activity));
      } finally {
        privacy.destroy();
      }
    });
  }

  /** Observes the actual window in :remote; ActivityScenario cannot represent that process. */
  @Test public void remoteActivityWindowSynchronizesSecureFlag() throws Exception {
    Assume.assumeTrue("remote Activity requires Android 9+", Build.VERSION.SDK_INT >= 28);
    scenario = ActivityScenario.launch(MainActivity.class);
    try {
      scenario.onActivity(activity -> activity.startActivity(
          new Intent(activity, RemoteActivity.class)));
      waitForRemoteSecureFlag(true);
      assertTrue("provider set must commit", setPersistedResult(true));
      waitForRemoteSecureFlag(false);
      assertTrue("provider set must commit", setPersistedResult(false));
      waitForRemoteSecureFlag(true);
    } finally {
      injectBack();
      setPersisted(false);
    }
  }

  @Test public void explicitEnableRemovesSecureFlag() {
    scenario = ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity -> {
      ScreenPrivacy privacy = new ScreenPrivacy(activity);
      try {
        assertTrue("provider set must commit", privacy.setAllowed(true));
        assertTrue(privacy.allowed());
        assertFalse("enabled capture must clear FLAG_SECURE", hasSecureFlag(activity));
      } finally {
        privacy.destroy();
      }
    });
  }

  @Test public void disablingRestoresSecureFlag() {
    setPersisted(true);
    scenario = ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity -> {
      ScreenPrivacy privacy = new ScreenPrivacy(activity);
      try {
        assertFalse(hasSecureFlag(activity));
        assertTrue(privacy.setAllowed(false));
        assertFalse(privacy.allowed());
        assertTrue("disabled capture must restore FLAG_SECURE", hasSecureFlag(activity));
      } finally {
        privacy.destroy();
      }
    });
  }

  @Test public void unrelatedWindowFlagsRemainUntouched() {
    scenario = ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity -> {
      final int unrelated = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
      activity.getWindow().addFlags(unrelated);
      ScreenPrivacy privacy = new ScreenPrivacy(activity);
      try {
        assertTrue(privacy.setAllowed(true));
        assertFalse(hasSecureFlag(activity));
        assertTrue((windowFlags(activity) & unrelated) != 0);
        assertTrue(privacy.setAllowed(false));
        assertTrue(hasSecureFlag(activity));
        assertTrue((windowFlags(activity) & unrelated) != 0);
      } finally {
        privacy.destroy();
      }
    });
  }

  @Test public void preferenceSurvivesMainActivityRecreate() {
    scenario = ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity -> {
      ScreenPrivacy privacy = new ScreenPrivacy(activity);
      try {
        assertTrue(privacy.setAllowed(true));
      } finally {
        privacy.destroy();
      }
    });
    scenario.recreate();
    scenario.onActivity(activity -> {
      assertFalse("recreated activity must retain enabled choice", hasSecureFlag(activity));
      ScreenPrivacy privacy = new ScreenPrivacy(activity);
      try {
        assertTrue(privacy.allowed());
      } finally {
        privacy.destroy();
      }
    });
  }

  @Test public void resumedLifecycleRepairsTamperedSecureFlag() {
    scenario = ActivityScenario.launch(MainActivity.class);
    final AtomicReference<MainActivity> activityRef = new AtomicReference<>();
    scenario.onActivity(activityRef::set);
    scenario.moveToState(Lifecycle.State.CREATED);
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
        activityRef.get().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
    assertFalse("test must actually tamper with the flag", hasSecureFlag(activityRef.get()));
    scenario.moveToState(Lifecycle.State.RESUMED);
    scenario.onActivity(activity -> assertTrue(
        "onStart/onResume must repair a tampered window", hasSecureFlag(activity)));
  }

  @Test public void providerIsNotExported() throws Exception {
    ProviderInfo info = context.getPackageManager().getProviderInfo(
        new android.content.ComponentName(context, ScreenPrivacyProvider.class), 0);
    assertNotNull(info);
    assertFalse("screen privacy provider must be private", info.exported);
  }

  @Test public void multipleControllersAndObserversSynchronize() throws Exception {
    scenario = ActivityScenario.launch(MainActivity.class);
    final AtomicReference<ScreenPrivacy> firstRef = new AtomicReference<>();
    final AtomicReference<ScreenPrivacy> secondRef = new AtomicReference<>();
    scenario.onActivity(activity -> {
      ScreenPrivacy first = new ScreenPrivacy(activity);
      ScreenPrivacy second = new ScreenPrivacy(activity);
      firstRef.set(first);
      secondRef.set(second);
    });
    assertTrue(setPersistedResult(true));
    waitForMainLooper();
    scenario.onActivity(activity -> {
      assertTrue("second controller must observe enable", secondRef.get().allowed());
      assertFalse(hasSecureFlag(activity));
    });
    assertTrue(setPersistedResult(false));
    waitForMainLooper();
    scenario.onActivity(activity -> {
      assertFalse("first controller must observe disable", firstRef.get().allowed());
      assertTrue(hasSecureFlag(activity));
      firstRef.get().destroy();
      secondRef.get().destroy();
    });
  }

  private void setPersisted(boolean allowed) {
    assertTrue(setPersistedResult(allowed));
  }

  private void clearPersistedPrefs() {
    assertTrue(context.getSharedPreferences(ScreenPrivacyProvider.PREFS, Context.MODE_PRIVATE)
        .edit().clear().commit());
  }

  private boolean setPersistedResult(boolean allowed) {
    Bundle args = new Bundle();
    args.putBoolean(ScreenPrivacyProvider.ALLOWED, allowed);
    Bundle result = context.getContentResolver().call(
        ScreenPrivacyProvider.uri(context), "set", null, args);
    assertNotNull("screen privacy provider must be available", result);
    return result.getBoolean(ScreenPrivacyProvider.OK);
  }

  private static boolean hasSecureFlag(MainActivity activity) {
    return (windowFlags(activity) & WindowManager.LayoutParams.FLAG_SECURE) != 0;
  }

  private static int windowFlags(MainActivity activity) {
    return activity.getWindow().getAttributes().flags;
  }

  private static void waitForMainLooper() {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(50);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }
  private void waitForRemoteSecureFlag(boolean expected) throws Exception {
    final long deadline = SystemClock.uptimeMillis() + 8_000L;
    String lastDump = "";
    while (SystemClock.uptimeMillis() < deadline) {
      lastDump = windowDump();
      Boolean actual = remoteSecureFlag(lastDump, context.getPackageName());
      if (actual != null && actual == expected) return;
      SystemClock.sleep(100L);
    }
    throw new AssertionError("remote Activity did not reach FLAG_SECURE=" + expected
        + " within timeout; last dumpsys window windows was:\n" + lastDump);
  }

  private String windowDump() throws IOException {
    ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation().executeShellCommand("dumpsys window windows");
    try (ParcelFileDescriptor.AutoCloseInputStream stream =
        new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      // InputStream.readAllBytes requires API 33; the app's floor is API 26.
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int count; (count = stream.read(buffer)) != -1; ) out.write(buffer, 0, count);
      return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private void injectBack() throws IOException {
    ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation().executeShellCommand("input keyevent KEYCODE_BACK");
    descriptor.close();
  }

  /** Parses only the remote window's mAttrs fl= field, never arbitrary SECURE words. */
  private static Boolean remoteSecureFlag(String dump, String packageName) {
    boolean remoteWindow = false;
    for (String line : dump.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("Window #")) {
        remoteWindow = line.contains(packageName + "/.RemoteActivity")
            || line.contains(packageName + "/io.github.zhanxing.dshandroid.RemoteActivity");
      }
      if (!remoteWindow) continue;
      // EMUI dumps put fl= on its own line below mAttrs=, so match anywhere in the section.
      // \bfl= also refuses pfl=/hwFlags= (no word boundary before fl).
      Matcher matcher = Pattern.compile("\\bfl=(.+)").matcher(line);
      if (matcher.find()) return hasSecureFlagValue(matcher.group(1));
    }
    return null;
  }

  private static boolean hasSecureFlagValue(String value) {
    // Symbolic lists are space- or pipe-separated depending on the vendor dump format.
    for (String token : value.split("[\\s\\|]+")) {
      if ("FLAG_SECURE".equals(token) || "SECURE".equals(token)) return true;
      try {
        if ((Long.decode(token) & WindowManager.LayoutParams.FLAG_SECURE) != 0) return true;
      } catch (NumberFormatException ignored) {
        // Unknown symbolic flags are not secure by default.
      }
    }
    return false;
  }

}
