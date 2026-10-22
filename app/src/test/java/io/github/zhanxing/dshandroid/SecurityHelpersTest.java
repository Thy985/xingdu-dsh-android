package io.github.zhanxing.dshandroid;

import static org.junit.Assert.*;

import org.junit.Test;

public class SecurityHelpersTest {
  @Test
  public void zipSlipRejected() {
    assertFalse(ZipPathPolicy.isSafe("../escape"));
    assertFalse(ZipPathPolicy.isSafe("a/../../escape"));
    assertFalse(ZipPathPolicy.isSafe("/absolute"));
    assertFalse(ZipPathPolicy.isSafe("a\\escape"));
    assertTrue(ZipPathPolicy.isSafe("bin/node"));
  }

  @Test
  public void originIsExactLoopbackPortAndScheme() {
    String base = "http://127.0.0.1:3080/?token=x";
    assertTrue(UrlPolicy.allowed(base, "http://127.0.0.1:3080/app"));
    assertFalse(UrlPolicy.allowed(base, "https://127.0.0.1:3080/app"));
    assertFalse(UrlPolicy.allowed(base, "http://127.0.0.1.evil:3080/app"));
    assertFalse(UrlPolicy.allowed(base, "http://user@127.0.0.1:3080/app"));
    assertFalse(UrlPolicy.allowed(base, "http://127.0.0.1:80/app"));
  }

  @Test
  public void ansiAndSplitTokenAreNotDisplayed() {
    String line = "dsh web: http://127.0.0.1:3080/?to\u001b[31mken=secret";
    assertNull(LogParser.findLaunchUrl(line));
    assertFalse(LogParser.redact("token=secret\u001b[0m").contains("secret"));
  }
}
