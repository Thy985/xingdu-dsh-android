package io.github.zhanxing.dshandroid;

import android.net.Uri;

public final class LocalOriginPolicy {
  private LocalOriginPolicy() {}

  public static boolean allowed(Uri base, Uri next) {
    return base != null && next != null && UrlPolicy.allowed(base.toString(), next.toString());
  }
}
