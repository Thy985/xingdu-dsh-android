package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HomeStatePolicyTest {
  @Test public void classifiesKnownLifecycleStates() {
    assertEquals(HomeStatePolicy.State.NOT_STARTED, HomeStatePolicy.classify("未启动"));
    assertEquals(HomeStatePolicy.State.INSTALLING, HomeStatePolicy.classify("正在校验与安装本机运行时…"));
    assertEquals(HomeStatePolicy.State.WAITING_WEB, HomeStatePolicy.classify("引擎已启动，等待官方 Web 服务…"));
    assertEquals(HomeStatePolicy.State.STOPPING, HomeStatePolicy.classify("正在停止…"));
    assertEquals(HomeStatePolicy.State.FAILED, HomeStatePolicy.classify("启动失败：校验失败"));
    assertEquals(HomeStatePolicy.State.EXITED, HomeStatePolicy.classify("引擎已退出（1），可查看日志后重试"));
    assertEquals(HomeStatePolicy.State.DISCONNECTED, HomeStatePolicy.classify("服务已断开"));
    assertEquals(HomeStatePolicy.State.UNKNOWN, HomeStatePolicy.classify("运行中（未知来源）"));
    assertEquals(HomeStatePolicy.State.UNKNOWN, HomeStatePolicy.classify(null));
  }

  @Test public void controlsUseConservativeKnownStatePolicy() {
    assertTrue(HomeStatePolicy.canStart("未启动"));
    assertFalse(HomeStatePolicy.canStop("未启动"));
    assertTrue(HomeStatePolicy.canStop("正在校验与安装本机运行时…"));
    assertTrue(HomeStatePolicy.canStop("引擎已启动，等待官方 Web 服务…"));
    assertFalse(HomeStatePolicy.canStart("正在停止…"));
    assertTrue(HomeStatePolicy.canStart("启动失败：网络错误"));
    assertTrue(HomeStatePolicy.canStart("引擎已退出（-1），可查看日志后重试"));
    assertTrue(HomeStatePolicy.canStart("服务已断开"));
    assertFalse(HomeStatePolicy.canStop("运行中（未知来源）"));
    assertTrue(HomeStatePolicy.canStart("运行中（未知来源）"));
    assertTrue(HomeStatePolicy.canRetry("未知状态"));
    assertFalse(HomeStatePolicy.canRetry("正在校验与安装本机运行时…"));
  }
}
