package io.github.zhanxing.dshandroid;

import java.util.regex.*;

public final class LogParser {
  private static final Pattern P =
      Pattern.compile("dsh web:\\s+(http://127\\.0\\.0\\.1(?::\\d+)?/[^\\s]*)", 2);
  // Headers contain spaces and multiple cookies. Hide the remainder of that line.
  private static final Pattern HEADERS = Pattern.compile(
      "(?im)\\b((?:proxy-)?authorization|(?:set-)?cookie)([\\\"']?\\s*[:=]\\s*)[^\\r\\n]+");
  private static final Pattern VALUES = Pattern.compile(
      "(?i)\\b(access[_-]?token|refresh[_-]?token|token|secret|api[_-]?key|password)"
          + "([\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^&\\s,;}]+)");
  private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");

  private LogParser() {}

  public static String findLaunchUrl(String s) {
    if (s == null || s.indexOf((char) 27) >= 0) return null;
    Matcher m = P.matcher(s);
    return m.find() ? m.group(1) : null;
  }

  public static String redact(String s) {
    if (s == null) return "";
    s = s.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "");
    s = HEADERS.matcher(s).replaceAll("$1$2[redacted]");
    s = VALUES.matcher(s).replaceAll("$1$2[redacted]");
    return BEARER.matcher(s).replaceAll("Bearer [redacted]");
  }
}
