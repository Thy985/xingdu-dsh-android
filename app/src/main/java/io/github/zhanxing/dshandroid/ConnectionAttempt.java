package io.github.zhanxing.dshandroid;

/** Invalidates callbacks from an older retry/stop, even when its URL is identical. */
final class ConnectionAttempt {
  static final int MAX_PROBES = 4;
  private volatile long revision;
  long begin() { return ++revision; }
  void invalidate() { ++revision; }
  boolean current(long request) { return revision == request; }
  static boolean ready(int status) {
    return (status >= 200 && status < 400) || status == 401 || status == 403;
  }
}
