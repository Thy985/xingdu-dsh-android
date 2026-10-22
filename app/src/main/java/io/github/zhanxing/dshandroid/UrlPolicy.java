package io.github.zhanxing.dshandroid;

import java.net.URI;

/** Pure-Java exact local-origin policy used by Android adapter and unit tests. */
public final class UrlPolicy {
  private UrlPolicy() {}

  public static boolean allowed(String base, String next) {
    try {
      URI b = new URI(base), n = new URI(next);
      return "http".equalsIgnoreCase(n.getScheme())
          && "127.0.0.1".equals(n.getHost())
          && n.getUserInfo() == null
          && n.getPort() == b.getPort()
          && "http".equalsIgnoreCase(b.getScheme())
          && "127.0.0.1".equals(b.getHost())
          && b.getUserInfo() == null;
    } catch (Exception e) {
      return false;
    }
  }
}
