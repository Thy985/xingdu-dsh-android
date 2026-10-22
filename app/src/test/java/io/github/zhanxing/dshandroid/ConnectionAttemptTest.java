package io.github.zhanxing.dshandroid;

import static org.junit.Assert.*;
import org.junit.Test;

public class ConnectionAttemptTest {
  @Test public void retryWithSameUrlInvalidatesOldCallback() {
    ConnectionAttempt attempts = new ConnectionAttempt();
    long first = attempts.begin();
    long retry = attempts.begin();
    assertFalse(attempts.current(first));
    assertTrue(attempts.current(retry));
  }
  @Test public void stopOrDestroyInvalidatesPendingProbe() {
    ConnectionAttempt attempts = new ConnectionAttempt();
    long pending = attempts.begin();
    attempts.invalidate();
    assertFalse(attempts.current(pending));
    assertTrue(attempts.current(attempts.begin()));
  }
  @Test public void readinessDoesNotRequireConsumingAuthenticationToken() {
    for (int code : new int[] {200, 204, 301, 302, 304, 401, 403}) assertTrue(ConnectionAttempt.ready(code));
    for (int code : new int[] {-1, 0, 100, 400, 404, 429, 500, 503}) assertFalse(ConnectionAttempt.ready(code));
    assertEquals(4, ConnectionAttempt.MAX_PROBES);
  }
}
