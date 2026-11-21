package io.github.zhanxing.dshandroid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parses the bundled runtime manifests into a layout decision and derives every
 * engine-facing path from it. Pure logic (no Android classes); fail-closed on
 * any malformed or inconsistent input.
 *
 * <p>Two layouts exist:
 *
 * <ul>
 *   <li><b>data</b> (legacy, targetSdk 28): everything lives in {@code
 *       files/runtime}; binaries are exec'd from the app data directory, which
 *       is exactly what API 29+ W^X enforcement forbids.</li>
 *   <li><b>split</b> (0.2.0+): {@code runtime.zip} carries data only; the five
 *       native components (node, bash, rg, PTY helper, flock N-API addon) ship
 *       as jniLibs {@code lib*.so} and land in {@code nativeLibraryDir}, the
 *       sanctioned exec/dlopen area. {@code runtime-native-manifest.json}
 *       carries their SHA-256 digests so the installer can verify PackageManager
 *       extraction the same way it verifies the zip.</li>
 * </ul>
 *
 * <p>The historical exec sites inside the data tree (engine binaries and the
 * injected ripgrep platform package) are recreated as symlinks pointing into
 * {@code nativeLibraryDir} at install time; dlopen'd libraries (SONAME aliases,
 * {@code libandroid-spawn.so}, {@code system.node}) stay in the data tree and
 * are reached through {@code LD_LIBRARY_PATH}.
 */
public final class RuntimeLayoutPolicy {
  public static final String LAYOUT_DATA = "data";
  public static final String LAYOUT_SPLIT = "split";
  public static final String NATIVE_MANIFEST = "runtime-native-manifest.json";

  public static final String LIB_NODE = "libnode.so";
  public static final String LIB_BASH = "libbash.so";
  public static final String LIB_RG = "librg.so";
  public static final String LIB_PTY = "libdsh_pty_exec.so";
  public static final String LIB_FLOCK = "libdsh_system_node.so";

  /** The exact set of native components a split runtime must ship. */
  public static final List<String> REQUIRED_NATIVE_LIBS = Collections.unmodifiableList(
      Arrays.asList(LIB_NODE, LIB_BASH, LIB_RG, LIB_PTY, LIB_FLOCK));

  /**
   * Data-tree exec sites that must reach nativeLibraryDir in split layout,
   * mapped to their native library. Keyed by zip-relative path; values are
   * names in {@link #REQUIRED_NATIVE_LIBS}. Duplicated values are intentional:
   * the engine's ripgrep resolver hits the injected platform-package copy,
   * whose directory is arch-dependent ({@code -arm64} vs {@code -x64}).
   */
  public static Map<String, String> splitSymlinkSites(String abi) {
    Map<String, String> sites = new LinkedHashMap<>();
    sites.put("bin/node", LIB_NODE);
    sites.put("bin/bash", LIB_BASH);
    sites.put("bin/rg", LIB_RG);
    sites.put("bin/dsh_pty_exec", LIB_PTY);
    sites.put("lib/node_modules/@vscode/ripgrep-android-" + ripgrepNodeArch(abi) + "/bin/rg", LIB_RG);
    return Collections.unmodifiableMap(sites);
  }

  /** Node-arch suffix used by build-runtime.sh's ripgrep platform-package injection. */
  public static String ripgrepNodeArch(String abi) {
    if ("arm64-v8a".equals(abi)) return "arm64";
    if ("x86_64".equals(abi)) return "x64";
    throw new IllegalArgumentException("无对应 NODE_ARCH 的 abi: " + abi);
  }

  private RuntimeLayoutPolicy() {}

  /** Parsed manifest decision. */
  public static final class Layout {
    public final String mode; // LAYOUT_DATA or LAYOUT_SPLIT
    public final String dshVersion;
    public final String abi;
    public final List<NativeLib> nativeLibs; // empty in data mode

    Layout(String mode, String dshVersion, String abi, List<NativeLib> nativeLibs) {
      this.mode = mode;
      this.dshVersion = dshVersion;
      this.abi = abi;
      this.nativeLibs = nativeLibs;
    }

    public boolean isSplit() { return LAYOUT_SPLIT.equals(mode); }

    public NativeLib nativeLib(String name) {
      for (NativeLib lib : nativeLibs) if (lib.name.equals(name)) return lib;
      return null;
    }
  }

  /** One jniLibs component with its expected digest and size. */
  public static final class NativeLib {
    public final String name;
    public final String sha256;
    public final long bytes;

    NativeLib(String name, String sha256, long bytes) {
      this.name = name;
      this.sha256 = sha256;
      this.bytes = bytes;
    }
  }

  /** Engine-facing paths derived from a layout (all string-typed, '/' separators). */
  public static final class EnginePaths {
    public final String node;
    public final String bash;
    public final String ldLibraryPath;
    public final String pathEnv;
    /** Absolute nativeLibraryDir when split; null in data mode. */
    public final String nativeDirEnv;

    EnginePaths(String node, String bash, String ldLibraryPath, String pathEnv, String nativeDirEnv) {
      this.node = node;
      this.bash = bash;
      this.ldLibraryPath = ldLibraryPath;
      this.pathEnv = pathEnv;
      this.nativeDirEnv = nativeDirEnv;
    }
  }

  /**
   * Derives engine paths. {@code runtimeDir} and {@code nativeDir} are plain
   * strings as the OS reports them; the returned values only ever join with
   * '/' so the same function is testable on any host.
   */
  public static EnginePaths enginePaths(Layout layout, String runtimeDir, String nativeDir) {
    if (LAYOUT_DATA.equals(layout.mode)) {
      return new EnginePaths(
          runtimeDir + "/bin/node",
          runtimeDir + "/bin/bash",
          runtimeDir + "/lib",
          runtimeDir + "/bin:/system/bin",
          null);
    }
    if (LAYOUT_SPLIT.equals(layout.mode)) {
      if (nativeDir == null || nativeDir.isEmpty()) {
        throw new IllegalStateException("split 布局缺少 nativeLibraryDir");
      }
      return new EnginePaths(
          nativeDir + "/" + LIB_NODE,
          nativeDir + "/" + LIB_BASH,
          nativeDir + ":" + runtimeDir + "/lib",
          nativeDir + ":" + runtimeDir + "/bin:/system/bin",
          nativeDir);
    }
    throw new IllegalStateException("未知运行时布局: " + layout.mode);
  }

  /**
   * Parses both manifests. {@code nativeManifestJson} is the content of the
   * {@code runtime-native-manifest.json} asset, or null when the asset is
   * absent (legacy builds). Every inconsistency fails closed.
   */
  public static Layout fromManifests(String runtimeManifestJson, String nativeManifestJson) {
    JSONObject m;
    try {
      m = new JSONObject(runtimeManifestJson);
    } catch (JSONException e) {
      throw new IllegalArgumentException("runtime-manifest.json 不是有效 JSON", e);
    }
    String dshVersion = m.optString("dshVersion", "");
    if (dshVersion.isEmpty()) throw new IllegalArgumentException("manifest 缺少 dshVersion");
    String abi = m.optString("abi", "");
    if (abi.isEmpty()) throw new IllegalArgumentException("manifest 缺少 abi");
    String mode = m.optString("layout", LAYOUT_DATA);
    if (!LAYOUT_DATA.equals(mode) && !LAYOUT_SPLIT.equals(mode)) {
      throw new IllegalArgumentException("未知布局: " + mode);
    }
    if (LAYOUT_SPLIT.equals(mode) && nativeManifestJson == null) {
      throw new IllegalArgumentException("layout=split 但缺少 " + NATIVE_MANIFEST);
    }
    if (LAYOUT_DATA.equals(mode) && nativeManifestJson != null) {
      throw new IllegalArgumentException("layout=data 但存在 " + NATIVE_MANIFEST + "，两份 manifest 不一致");
    }

    List<NativeLib> libs = new ArrayList<>();
    if (LAYOUT_SPLIT.equals(mode)) {
      JSONObject n;
      try {
        n = new JSONObject(nativeManifestJson);
      } catch (JSONException e) {
        throw new IllegalArgumentException(NATIVE_MANIFEST + " 不是有效 JSON", e);
      }
      requireEqual(n.optString("layout", ""), LAYOUT_SPLIT, "native manifest layout");
      requireEqual(n.optString("dshVersion", ""), dshVersion, "dshVersion");
      requireEqual(n.optString("abi", ""), abi, "abi");
      JSONArray entries = n.optJSONArray("libs");
      if (entries == null || entries.length() == 0) {
        throw new IllegalArgumentException(NATIVE_MANIFEST + " 缺少 libs");
      }
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.optJSONObject(i);
        if (entry == null) throw new IllegalArgumentException("libs[" + i + "] 不是对象");
        String name = entry.optString("name", "");
        if (!name.matches("lib[a-z0-9_]+\\.so")) {
          throw new IllegalArgumentException("非法本机组件名: " + name);
        }
        String sha256 = entry.optString("sha256", "").toLowerCase(Locale.US);
        if (!sha256.matches("[0-9a-f]{64}")) {
          throw new IllegalArgumentException("非法 sha256: " + name);
        }
        long bytes = entry.optLong("bytes", -1);
        if (bytes <= 0) throw new IllegalArgumentException("非法 bytes: " + name);
        libs.add(new NativeLib(name, sha256, bytes));
      }
      for (String required : REQUIRED_NATIVE_LIBS) {
        if (nativeLib(libs, required) == null) {
          throw new IllegalArgumentException("缺少必需本机组件: " + required);
        }
      }
      if (libs.size() != REQUIRED_NATIVE_LIBS.size()) {
        throw new IllegalArgumentException("本机组件数量与契约不符: " + libs.size());
      }
    }
    return new Layout(mode, dshVersion, abi, Collections.unmodifiableList(libs));
  }

  private static void requireEqual(String actual, String expected, String field) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(field + " 不一致: " + actual + " != " + expected);
    }
  }

  private static NativeLib nativeLib(List<NativeLib> libs, String name) {
    for (NativeLib lib : libs) if (lib.name.equals(name)) return lib;
    return null;
  }
}
