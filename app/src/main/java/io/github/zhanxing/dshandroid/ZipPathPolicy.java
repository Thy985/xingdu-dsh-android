package io.github.zhanxing.dshandroid;

/** Pure validation for ZIP entry names; no filesystem access. */
public final class ZipPathPolicy {
  private ZipPathPolicy() {}

  public static boolean isSafe(String name) {
    return name != null
        && !name.startsWith("/")
        && name.indexOf('\\') < 0
        && name.indexOf('\0') < 0
        && !name.equals("..")
        && !name.contains("../");
  }
}
