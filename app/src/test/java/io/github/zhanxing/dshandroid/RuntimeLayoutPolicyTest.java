package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class RuntimeLayoutPolicyTest {
  private static final String SHA_A = "aa".repeat(32);
  private static final String SHA_B = "bb".repeat(32);
  private static final String SHA_C = "cc".repeat(32);
  private static final String SHA_D = "dd".repeat(32);
  private static final String SHA_E = "ee".repeat(32);

  private static String legacyManifest() throws org.json.JSONException {
    return new JSONObject()
        .put("dshVersion", "0.2.1-rc.2").put("abi", "arm64-v8a")
        .put("sha256", SHA_A).toString();
  }

  private static String nativeManifest() throws org.json.JSONException {
    return new JSONObject()
        .put("layout", "split").put("abi", "arm64-v8a").put("dshVersion", "0.2.1-rc.2")
        .put("libs", new org.json.JSONArray()
            .put(new JSONObject().put("name", RuntimeLayoutPolicy.LIB_NODE).put("sha256", SHA_A).put("bytes", 1))
            .put(new JSONObject().put("name", RuntimeLayoutPolicy.LIB_BASH).put("sha256", SHA_B).put("bytes", 2))
            .put(new JSONObject().put("name", RuntimeLayoutPolicy.LIB_RG).put("sha256", SHA_C).put("bytes", 3))
            .put(new JSONObject().put("name", RuntimeLayoutPolicy.LIB_PTY).put("sha256", SHA_D).put("bytes", 4))
            .put(new JSONObject().put("name", RuntimeLayoutPolicy.LIB_FLOCK).put("sha256", SHA_E).put("bytes", 5)))
        .toString();
  }

  private static String splitManifest() throws org.json.JSONException {
    return new JSONObject()
        .put("dshVersion", "0.2.1-rc.2").put("abi", "arm64-v8a")
        .put("layout", "split").put("sha256", SHA_A).toString();
  }

  @Test public void legacyManifestYieldsDataLayout() throws Exception {
    RuntimeLayoutPolicy.Layout layout = RuntimeLayoutPolicy.fromManifests(legacyManifest(), null);
    assertFalse(layout.isSplit());
    assertTrue(layout.nativeLibs.isEmpty());
    RuntimeLayoutPolicy.EnginePaths paths = RuntimeLayoutPolicy.enginePaths(layout, "/data/runtime", "/data/native");
    assertEquals("/data/runtime/bin/node", paths.node);
    assertEquals("/data/runtime/etc/tls/openssl.cnf", paths.opensslConf);
    assertEquals("/data/runtime/bin/bash", paths.bash);
    assertEquals("/data/runtime/lib", paths.ldLibraryPath);
    assertEquals("/data/runtime/bin:/system/bin", paths.pathEnv);
    assertNull(paths.nativeDirEnv);
  }

  @Test public void splitManifestsYieldSplitLayout() throws Exception {
    RuntimeLayoutPolicy.Layout layout = RuntimeLayoutPolicy.fromManifests(splitManifest(), nativeManifest());
    assertTrue(layout.isSplit());
    assertEquals(5, layout.nativeLibs.size());
    assertEquals(SHA_C, layout.nativeLib(RuntimeLayoutPolicy.LIB_RG).sha256);
    RuntimeLayoutPolicy.EnginePaths paths = RuntimeLayoutPolicy.enginePaths(layout, "/data/runtime", "/data/native");
    assertEquals("/data/native/libnode.so", paths.node);
    assertEquals("/data/native/libbash.so", paths.bash);
    assertEquals("/data/native:/data/runtime/lib", paths.ldLibraryPath);
    assertEquals("/data/native:/data/runtime/bin:/system/bin", paths.pathEnv);
    assertEquals("/data/native", paths.nativeDirEnv);
    assertEquals("/data/runtime/etc/tls/openssl.cnf", paths.opensslConf);
  }

  @Test public void autoDetectedLayoutIsRejectedForLegacyZip() throws Exception {
    // Legacy runtime manifest paired with a native manifest = inconsistent bundle.
    try {
      RuntimeLayoutPolicy.fromManifests(legacyManifest(), nativeManifest());
      fail("data 布局不允许携带 native manifest");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void splitLayoutRequiresNativeManifest() throws Exception {
    try {
      RuntimeLayoutPolicy.fromManifests(splitManifest(), null);
      fail("split 布局缺少 native manifest 必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void inconsistentDshVersionIsRejected() throws Exception {
    String wrong = new JSONObject(nativeManifest()).put("dshVersion", "9.9.9").toString();
    try {
      RuntimeLayoutPolicy.fromManifests(splitManifest(), wrong);
      fail("dshVersion 不一致必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void inconsistentAbiIsRejected() throws Exception {
    String wrong = new JSONObject(nativeManifest()).put("abi", "x86_64").toString();
    try {
      RuntimeLayoutPolicy.fromManifests(splitManifest(), wrong);
      fail("abi 不一致必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void malformedSha256IsRejected() throws Exception {
    JSONObject bad = new JSONObject(nativeManifest());
    bad.getJSONArray("libs").getJSONObject(0).put("sha256", "short");
    try {
      RuntimeLayoutPolicy.fromManifests(splitManifest(), bad.toString());
      fail("非法 sha256 必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void pathTraversalLibNameIsRejected() throws Exception {
    JSONObject bad = new JSONObject(nativeManifest());
    bad.getJSONArray("libs").getJSONObject(0).put("name", "../evil.so");
    try {
      RuntimeLayoutPolicy.fromManifests(splitManifest(), bad.toString());
      fail("路径类组件名必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void missingRequiredLibIsRejected() throws Exception {
    JSONObject bad = new JSONObject(nativeManifest());
    bad.getJSONArray("libs").remove(3); // PTY helper
    try {
      RuntimeLayoutPolicy.fromManifests(splitManifest(), bad.toString());
      fail("缺少必需组件必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void unknownLayoutStringIsRejected() throws Exception {
    String wrong = new JSONObject(splitManifest()).put("layout", "flat").toString();
    try {
      RuntimeLayoutPolicy.fromManifests(wrong, nativeManifest());
      fail("未知布局必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void malformedJsonFailsClosed() throws Exception {
    try {
      RuntimeLayoutPolicy.fromManifests("{not json", null);
      fail("非法 JSON 必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }

  @Test public void symlinkSitesCoverHistoricalExecLocations() throws Exception {
    Map<String, String> sites = RuntimeLayoutPolicy.splitSymlinkSites("arm64-v8a");
    assertEquals(5, sites.size());
    assertEquals(RuntimeLayoutPolicy.LIB_NODE, sites.get("bin/node"));
    assertEquals(RuntimeLayoutPolicy.LIB_BASH, sites.get("bin/bash"));
    assertEquals(RuntimeLayoutPolicy.LIB_PTY, sites.get("bin/dsh_pty_exec"));
    // Both ripgrep resolution sites point at the same native component.
    assertEquals(RuntimeLayoutPolicy.LIB_RG, sites.get("bin/rg"));
    assertEquals(RuntimeLayoutPolicy.LIB_RG,
        sites.get("lib/node_modules/@vscode/ripgrep-android-arm64/bin/rg"));
    for (String lib : sites.values()) {
      assertTrue("符号链接目标必须在契约集合内: " + lib,
          RuntimeLayoutPolicy.REQUIRED_NATIVE_LIBS.contains(lib));
    }
  }

  @Test public void splitEnginePathsWithoutNativeDirFailsClosed() throws Exception {
    RuntimeLayoutPolicy.Layout layout = RuntimeLayoutPolicy.fromManifests(splitManifest(), nativeManifest());
    try {
      RuntimeLayoutPolicy.enginePaths(layout, "/data/runtime", "");
      fail("split 布局缺少 nativeLibraryDir 必须失败");
    } catch (IllegalStateException expected) { /* fail-closed */ }
  }
}
