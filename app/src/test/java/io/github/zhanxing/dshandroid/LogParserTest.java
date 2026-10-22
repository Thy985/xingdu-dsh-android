package io.github.zhanxing.dshandroid;

import static org.junit.Assert.*;

import org.junit.Test;

public class LogParserTest {
  @Test
  public void url() {
    assertEquals(
        "http://127.0.0.1:3080/?token=abc",
        LogParser.findLaunchUrl("dsh web: http://127.0.0.1:3080/?token=abc"));
  }

  @Test
  public void redact() {
    assertFalse(LogParser.redact("token=secret").contains("secret"));
  }

  @Test public void authorizationDoesNotLeaveBearerOrBasicCredentialBehind() {
    for (String header : new String[] {"Authorization: Bearer private-value", "Authorization=Basic private-value",
        "Proxy-Authorization: Bearer private-value", "request failed Bearer private-value"}) {
      assertFalse(LogParser.redact(header), LogParser.redact(header).contains("private-value"));
    }
  }

  @Test public void cookiesAndQuotedKeysAreRedacted() {
    for (String log : new String[] {"Cookie: session=private-value; second=also-private",
        "Set-Cookie: session=private-value; Path=/", "{\"api_key\":\"private-value\"}",
        "password='private-value with spaces'", "access_token=private-value&ok=true",
        "X-Api-Key: private-value", "{\"authorization\":\"Bearer private-value\"}"}) {
      assertFalse(LogParser.redact(log), LogParser.redact(log).contains("private-value"));
      assertFalse(LogParser.redact(log), LogParser.redact(log).contains("also-private"));
    }
  }

  @Test public void multilineDiagnosticsSurviveConservativeHeaderRedaction() {
    String clean = LogParser.redact("Authorization: Bearer private-value\nconnection refused at 127.0.0.1:3080");
    assertFalse(clean.contains("private-value"));
    assertTrue(clean.contains("connection refused"));
    assertEquals("engine exited (1)", LogParser.redact("engine exited (1)"));
  }
}
