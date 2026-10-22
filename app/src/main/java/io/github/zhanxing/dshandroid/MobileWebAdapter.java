package io.github.zhanxing.dshandroid;

import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Presentation-only adapter. No bridge, network interception, or runtime mutations. */
final class MobileWebAdapter {
  private MobileWebAdapter() {}

  static void install(WebView view) {
    if (!local(view)) return;
    try {
      String css = asset(view, "mobile/mobile.css");
      String js = asset(view, "mobile/mobile.js");
      view.evaluateJavascript("(function(){if(location.origin!=='http://127.0.0.1:3080')return;"
          + "var s=document.getElementById('dsh-android-mobile-style');"
          + "if(!s){s=document.createElement('style');s.id='dsh-android-mobile-style';document.head.appendChild(s);}"
          + "s.textContent=" + JSONObject.quote(css) + ";" + js + "})()", null);
    } catch (Exception ignored) {
      // An unavailable adapter must never prevent the official UI from loading.
    }
  }

  static void setEnabled(WebView view, boolean enabled) {
    if (!local(view)) return;
    view.evaluateJavascript("(function(){if(location.origin!=='http://127.0.0.1:3080')return;"
        + "if(window.__dshAndroidMobile)window.__dshAndroidMobile.setEnabled(" + enabled + ");})()", null);
  }

  private static boolean local(WebView view) {
    return view != null && UrlPolicy.allowed("http://127.0.0.1:3080/", view.getUrl());
  }

  private static String asset(WebView view, String name) throws Exception {
    try (InputStream in = view.getContext().getAssets().open(name);
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
