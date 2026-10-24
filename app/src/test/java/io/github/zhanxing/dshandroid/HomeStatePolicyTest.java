package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HomeStatePolicyTest {
  @Test public void classifiesTypedEngineStates() {
    assertEquals(HomeStatePolicy.State.NOT_STARTED, HomeStatePolicy.classify(EngineState.NOT_STARTED));
    assertEquals(HomeStatePolicy.State.NOT_STARTED, HomeStatePolicy.classify(EngineState.STOPPED));
    assertEquals(HomeStatePolicy.State.INSTALLING, HomeStatePolicy.classify(EngineState.INSTALLING));
    assertEquals(HomeStatePolicy.State.WAITING_WEB, HomeStatePolicy.classify(EngineState.WAITING_WEB));
    assertEquals(HomeStatePolicy.State.WAITING_WEB, HomeStatePolicy.classify(EngineState.URL_READY));
    assertEquals(HomeStatePolicy.State.STOPPING, HomeStatePolicy.classify(EngineState.STOPPING));
    assertEquals(HomeStatePolicy.State.FAILED, HomeStatePolicy.classify(EngineState.FAILED));
    assertEquals(HomeStatePolicy.State.EXITED, HomeStatePolicy.classify(EngineState.EXITED));
    assertEquals(HomeStatePolicy.State.UNKNOWN, HomeStatePolicy.classify(null));
  }

  @Test public void controlsUseConservativeKnownStatePolicy() {
    assertTrue(HomeStatePolicy.canStart(HomeStatePolicy.State.NOT_STARTED));
    assertFalse(HomeStatePolicy.canStop(HomeStatePolicy.State.NOT_STARTED));
    assertTrue(HomeStatePolicy.canStop(HomeStatePolicy.State.INSTALLING));
    assertTrue(HomeStatePolicy.canStop(HomeStatePolicy.State.WAITING_WEB));
    assertFalse(HomeStatePolicy.canStart(HomeStatePolicy.State.STOPPING));
    assertTrue(HomeStatePolicy.canStart(HomeStatePolicy.State.FAILED));
    assertTrue(HomeStatePolicy.canStart(HomeStatePolicy.State.EXITED));
    assertTrue(HomeStatePolicy.canStart(HomeStatePolicy.State.DISCONNECTED));
    assertFalse(HomeStatePolicy.canStop(HomeStatePolicy.State.UNKNOWN));
    assertTrue(HomeStatePolicy.canStart(HomeStatePolicy.State.UNKNOWN));
    assertTrue(HomeStatePolicy.canRetry(HomeStatePolicy.State.UNKNOWN));
    assertFalse(HomeStatePolicy.canRetry(HomeStatePolicy.State.INSTALLING));
    assertFalse(HomeStatePolicy.canRetry(HomeStatePolicy.State.STOPPING));
  }
}
