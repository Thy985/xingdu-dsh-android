package io.github.zhanxing.dshandroid;

/**
 * Pure-Java policy for the native home controls. Service states come in typed
 * ({@link EngineState}); {@link State#DISCONNECTED} is an activity-side situation.
 * Unknown states are never treated as running.
 */
public final class HomeStatePolicy {
  public enum State { NOT_STARTED, INSTALLING, WAITING_WEB, STOPPING, FAILED, EXITED, DISCONNECTED, UNKNOWN }

  private HomeStatePolicy() {}

  public static State classify(EngineState state) {
    if (state == null) return State.UNKNOWN;
    switch (state) {
      case NOT_STARTED:
      case STOPPED: return State.NOT_STARTED;
      case INSTALLING: return State.INSTALLING;
      case WAITING_WEB:
      case URL_READY: return State.WAITING_WEB;
      case STOPPING: return State.STOPPING;
      case FAILED: return State.FAILED;
      case EXITED: return State.EXITED;
      default: return State.UNKNOWN;
    }
  }

  public static boolean canStart(State state) {
    return state == State.NOT_STARTED || state == State.FAILED || state == State.EXITED
        || state == State.DISCONNECTED || state == State.UNKNOWN;
  }

  public static boolean canStop(State state) {
    return state == State.INSTALLING || state == State.WAITING_WEB || state == State.STOPPING;
  }

  public static boolean canRetry(State state) {
    return state != State.INSTALLING && state != State.STOPPING;
  }
}
