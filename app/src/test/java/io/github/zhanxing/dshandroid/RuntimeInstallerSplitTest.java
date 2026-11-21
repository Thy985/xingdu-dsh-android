package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JVM tests for the split-layout completeness and native-verification logic.
 * Symlink creation needs OS privileges on Windows, so every test assumes a
 * working probe first (org.junit.Assume): CI (Linux) always runs, Windows runs
 * only with developer mode / symlink privilege.
 */
public class RuntimeInstallerSplitTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path root, nativeDir;
  private RuntimeLayoutPolicy.Layout layout;

  @Before public void setUp() throws Exception {
    root = tmp.newFolder("runtime").toPath();
    nativeDir = tmp.newFolder("nativelib").toPath();
    // Probe symlink privilege; skip the class where the OS refuses.
    Path probe = tmp.newFolder("probe").toPath().resolve("link");
    try {
      Files.createSymbolicLink(probe, tmp.newFile("probe-target").toPath());
      Files.delete(probe);
    } catch (UnsupportedOperationException | java.io.IOException e) {
      assumeTrue("此环境无法创建符号链接，跳过 split 安装逻辑测试", false);
    }
    layout = RuntimeLayoutPolicy.fromManifests(splitManifest(), manifestForCurrentPayloads());
  }

  private static String splitManifest() throws org.json.JSONException {
    return new JSONObject()
        .put("dshVersion", "0.2.1-rc.2").put("abi", "arm64-v8a")
        .put("layout", "split").put("sha256", "aa".repeat(32)).toString();
  }

  private void writeNativeLibs() throws Exception {
    for (String name : RuntimeLayoutPolicy.REQUIRED_NATIVE_LIBS) {
      Files.write(nativeDir.resolve(name), payload(name));
    }
  }

  private static byte[] payload(String name) {
    return ("p:" + name).getBytes(StandardCharsets.UTF_8);
  }

  private String manifestForCurrentPayloads() throws Exception {
    JSONArray libs = new JSONArray();
    for (String name : RuntimeLayoutPolicy.REQUIRED_NATIVE_LIBS) {
      byte[] p = ("p:" + name).getBytes(StandardCharsets.UTF_8);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      libs.put(new JSONObject().put("name", name)
          .put("sha256", RuntimeInstaller.hex(md.digest(p))).put("bytes", p.length));
    }
    return new JSONObject()
        .put("layout", "split").put("abi", "arm64-v8a").put("dshVersion", "0.2.1-rc.2")
        .put("libs", libs).toString();
  }

  private void writeDataFiles() throws Exception {
    for (String rel : new String[] {
        "lib/node_modules/@deepseek-ai/node-addon-system/lib/flock.js",
        "lib/node_modules/@deepseek-ai/node-addon-system/lib/system.node",
        "lib/libandroid-spawn.so",
        "lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "etc/tls/cert.pem",
        "usr/bin/.keep"}) {
      Path f = root.resolve(rel);
      Files.createDirectories(f.getParent());
      Files.write(f, new byte[] {1});
    }
  }

  @Test public void freshStagedDataTreeIsCompleteWithoutLinks() throws Exception {
    writeDataFiles();
    assertTrue(RuntimeInstaller.complete(root, layout, nativeDir.toString()));
  }

  @Test public void regularFileAtExecSiteBreaksCompleteness() throws Exception {
    writeDataFiles();
    Files.createDirectories(root.resolve("bin"));
    Files.write(root.resolve("bin/node"), new byte[] {1});
    assertFalse(RuntimeInstaller.complete(root, layout, nativeDir.toString()));
  }

  @Test public void correctSymlinkAtExecSiteIsAccepted() throws Exception {
    writeDataFiles();
    for (Map.Entry<String, String> site : RuntimeLayoutPolicy.splitSymlinkSites(layout.abi).entrySet()) {
      Path link = root.resolve(site.getKey());
      Files.createDirectories(link.getParent());
      Files.createSymbolicLink(link, nativeDir.resolve(site.getValue()));
    }
    assertTrue(RuntimeInstaller.complete(root, layout, nativeDir.toString()));
  }

  @Test public void wrongTargetSymlinkIsRejected() throws Exception {
    writeDataFiles();
    Path link = root.resolve("bin/node");
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, nativeDir.resolve("other-binary.so"));
    assertFalse(RuntimeInstaller.complete(root, layout, nativeDir.toString()));
  }

  @Test public void linkSplitExecSitesCreatesAllSitesIdempotently() throws Exception {
    writeDataFiles();
    for (String name : RuntimeLayoutPolicy.REQUIRED_NATIVE_LIBS) {
      Files.write(nativeDir.resolve(name), new byte[] {1});
    }
    RuntimeInstaller.linkSplitExecSites(root, layout.abi, nativeDir.toString(), () -> false);
    // Idempotent: a second pass over existing links must not throw.
    RuntimeInstaller.linkSplitExecSites(root, layout.abi, nativeDir.toString(), () -> false);
    for (Map.Entry<String, String> site : RuntimeLayoutPolicy.splitSymlinkSites(layout.abi).entrySet()) {
      assertTrue(root.resolve(site.getKey()).toString(),
          Files.isSymbolicLink(root.resolve(site.getKey())));
    }
  }

  @Test public void verifyNativeLibsAcceptsMatchingPayloads() throws Exception {
    writeNativeLibs();
    RuntimeLayoutPolicy.Layout exact =
        RuntimeLayoutPolicy.fromManifests(splitManifest(), manifestForCurrentPayloads());
    RuntimeInstaller.verifyNativeLibs(nativeDir.toString(), exact, () -> false); // must not throw
  }

  @Test public void verifyNativeLibsRejectsSizeMismatch() throws Exception {
    writeNativeLibs();
    RuntimeLayoutPolicy.Layout exact =
        RuntimeLayoutPolicy.fromManifests(splitManifest(), manifestForCurrentPayloads());
    // Flip declared bytes on one lib so Files.size() disagrees.
    JSONObject bad = new JSONObject(manifestForCurrentPayloads());
    bad.getJSONArray("libs").getJSONObject(0).put("bytes", 999);
    RuntimeLayoutPolicy.Layout wrong = RuntimeLayoutPolicy.fromManifests(splitManifest(), bad.toString());
    try {
      RuntimeInstaller.verifyNativeLibs(nativeDir.toString(), wrong, () -> false);
      fail("大小不符必须拒绝");
    } catch (SecurityException expected) { /* fail-closed */ }
  }

  @Test public void verifyNativeLibsRejectsDigestMismatch() throws Exception {
    writeNativeLibs();
    JSONObject bad = new JSONObject(manifestForCurrentPayloads());
    bad.getJSONArray("libs").getJSONObject(0).put("sha256", "ff".repeat(32));
    RuntimeLayoutPolicy.Layout wrong = RuntimeLayoutPolicy.fromManifests(splitManifest(), bad.toString());
    try {
      RuntimeInstaller.verifyNativeLibs(nativeDir.toString(), wrong, () -> false);
      fail("摘要不符必须拒绝");
    } catch (SecurityException expected) { /* fail-closed */ }
  }

  @Test public void verifyNativeLibsRejectsMissingComponent() throws Exception {
    writeNativeLibs();
    Files.delete(nativeDir.resolve(RuntimeLayoutPolicy.LIB_RG));
    RuntimeLayoutPolicy.Layout exact =
        RuntimeLayoutPolicy.fromManifests(splitManifest(), manifestForCurrentPayloads());
    try {
      RuntimeInstaller.verifyNativeLibs(nativeDir.toString(), exact, () -> false);
      fail("缺失组件必须拒绝");
    } catch (SecurityException expected) { /* fail-closed */ }
  }

  @Test public void splitSymlinkSitesAreArchAware() {
    assertTrue(RuntimeLayoutPolicy.splitSymlinkSites("arm64-v8a").keySet().stream()
        .anyMatch(k -> k.contains("ripgrep-android-arm64/")));
    assertTrue(RuntimeLayoutPolicy.splitSymlinkSites("x86_64").keySet().stream()
        .anyMatch(k -> k.contains("ripgrep-android-x64/")));
    try {
      RuntimeLayoutPolicy.splitSymlinkSites("mips64");
      fail("未知 abi 必须失败");
    } catch (IllegalArgumentException expected) { /* fail-closed */ }
  }
}
