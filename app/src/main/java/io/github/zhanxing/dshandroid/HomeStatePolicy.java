package io.github.zhanxing.dshandroid;

/** Pure-Java policy for the native home controls; unknown states are never treated as running. */
public final class HomeStatePolicy {
  public enum State { NOT_STARTED, INSTALLING, WAITING_WEB, STOPPING, FAILED, EXITED, DISCONNECTED, UNKNOWN }

  private HomeStatePolicy() {}

  public static State classify(String raw) {
    if ("未启动".equals(raw)) return State.NOT_STARTED;
    if (raw == null) return State.UNKNOWN;
    if ("服务已断开".equals(raw) || "本机服务已断开，可重新启动".equals(raw)) return State.DISCONNECTED;
    if ("正在停止…".equals(raw)) return State.STOPPING;
    if ("已停止".equals(raw)) return State.NOT_STARTED;
    if ("正在校验与安装本机运行时…".equals(raw)) return State.INSTALLING;
    if ("引擎已启动，等待官方 Web 服务…".equals(raw)
        || "本机 Web 服务已输出认证地址，等待连接验证…".equals(raw)) return State.WAITING_WEB;
    if (raw.startsWith("启动失败：")) return State.FAILED;
    if (raw.startsWith("引擎已退出（")) return State.EXITED;
    return State.UNKNOWN;
  }

  public static boolean canStart(String raw) {
    State state = classify(raw);
    return state == State.NOT_STARTED || state == State.FAILED || state == State.EXITED
        || state == State.DISCONNECTED || state == State.UNKNOWN;
  }

  public static boolean canStop(String raw) {
    State state = classify(raw);
    return state == State.INSTALLING || state == State.WAITING_WEB || state == State.STOPPING;
  }

  public static boolean canRetry(String raw) {
    return classify(raw) != State.INSTALLING && classify(raw) != State.STOPPING;
  }
}
