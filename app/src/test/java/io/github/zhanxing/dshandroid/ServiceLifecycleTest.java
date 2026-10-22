package io.github.zhanxing.dshandroid;

import static org.junit.Assert.*;
import org.junit.Test;

public class ServiceLifecycleTest {
  @Test public void startIsDeduplicatedUnlessStopIsPending() {
    assertTrue(ServiceLifecycle.shouldStart(false, false, false));
    assertFalse(ServiceLifecycle.shouldStart(false, true, false));
    assertFalse(ServiceLifecycle.shouldStart(false, false, true));
    assertFalse(ServiceLifecycle.shouldStart(false, true, true));
    for (boolean starting : new boolean[] {false, true}) {
      for (boolean alive : new boolean[] {false, true}) {
        assertTrue(ServiceLifecycle.shouldStart(true, starting, alive));
      }
    }
  }

  @Test public void staleStopCannotKillNewGeneration() {
    assertFalse(ServiceLifecycle.stopOwnsProcess(4, 5, 5, false));
    assertTrue(ServiceLifecycle.stopOwnsProcess(5, 5, 5, false));
    assertTrue(ServiceLifecycle.stopOwnsProcess(4, 5, 5, true));
  }

  @Test public void normalStopOwnsProcessFromPrecedingStart() {
    // START creates process generation 7; STOP itself advances generation to 8.
    assertTrue(ServiceLifecycle.stopOwnsProcess(8, 8, 7, false));
  }

  @Test public void queuedRestartStillAllowsStopToReapPreviousProcess() {
    // Worker queue: stop(8), start(9). Old process(7) must exit before start(9).
    assertTrue(ServiceLifecycle.stopOwnsProcess(8, 9, 7, false));
    assertFalse(ServiceLifecycle.stopOwnsProcess(8, 9, 9, false));
  }

  @Test public void cancellationCoversStopGenerationAndInterrupt() {
    assertFalse(ServiceLifecycle.cancelled(7, 7, false, false));
    assertTrue(ServiceLifecycle.cancelled(7, 8, false, false));
    assertTrue(ServiceLifecycle.cancelled(7, 7, true, false));
    assertTrue(ServiceLifecycle.cancelled(7, 7, false, true));
  }
}
