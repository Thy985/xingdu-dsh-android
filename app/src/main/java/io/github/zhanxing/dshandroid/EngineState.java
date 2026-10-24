package io.github.zhanxing.dshandroid;

/**
 * Typed engine states produced by {@link DshService}. The service publishes these in its
 * snapshot; the UI derives display text from the state and never infers state from text.
 * FAILED carries a redacted message and EXITED carries the exit code in the snapshot detail.
 */
public enum EngineState {
  NOT_STARTED,
  INSTALLING,
  WAITING_WEB,
  URL_READY,
  STOPPING,
  STOPPED,
  FAILED,
  EXITED
}
