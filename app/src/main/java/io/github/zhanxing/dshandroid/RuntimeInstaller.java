package io.github.zhanxing.dshandroid;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.*;
import org.json.JSONObject;

public final class RuntimeInstaller {
  public static final String ZIP = "runtime.zip", MANIFEST = "runtime-manifest.json";
  private static final String MARKER = "runtime.sha256";
  private RuntimeInstaller() {}

  public static Path install(Context c) throws Exception { return install(c, () -> false); }
  public interface CancelCheck { boolean cancelled(); }

  public static Path install(Context c, CancelCheck cancelled) throws Exception {
    Path root = c.getFilesDir().toPath(), target = root.resolve("runtime");
    JSONObject m;
    try (InputStream in = c.getAssets().open(MANIFEST)) { m = new JSONObject(read(in)); }
    String expected = m.optString("sha256", "").toLowerCase(Locale.US);
    if (!expected.matches("[0-9a-f]{64}")) throw new SecurityException("manifest sha256 无效");
    String abi = m.optString("abi", "");
    boolean abiOk = false;
    for (String supported : Build.SUPPORTED_ABIS) if (supported.equals(abi)) abiOk = true;
    if (!abiOk) throw new SecurityException("运行时 ABI 不匹配: " + abi);
    Path marker = root.resolve(MARKER);
    if (Files.isDirectory(target) && Files.isRegularFile(marker)
        && expected.equals(new String(Files.readAllBytes(marker), "UTF-8").trim())
        && complete(target)) return target;
    if (cancelled.cancelled()) throw new InterruptedIOException("安装已取消");

    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = c.getAssets().open(ZIP)) {
      byte[] b = new byte[65536]; int n;
      while ((n = in.read(b)) > 0) {
        if (cancelled.cancelled()) throw new InterruptedIOException("安装已取消");
        md.update(b, 0, n);
      }
    }
    if (cancelled.cancelled()) throw new InterruptedIOException("安装已取消");
    if (!hex(md.digest()).equals(expected)) throw new SecurityException("runtime.zip SHA256 校验失败");

    Path stage = root.resolve("runtime.staging-" + System.nanoTime());
    Path old = root.resolve("runtime.previous-" + System.nanoTime());
    Path oldMarker = root.resolve(MARKER + ".previous-" + System.nanoTime());
    try {
      Files.createDirectories(stage);
      try (InputStream in = c.getAssets().open(ZIP); ZipInputStream z = new ZipInputStream(new BufferedInputStream(in))) {
        ZipEntry e; byte[] b = new byte[65536];
        while ((e = z.getNextEntry()) != null) {
          if (cancelled.cancelled()) throw new InterruptedIOException("安装已取消");
          String n = e.getName();
          if (!ZipPathPolicy.isSafe(n)) throw new SecurityException("ZIP 路径不安全: " + n);
          Path p = stage.resolve(n).normalize();
          if (!p.startsWith(stage)) throw new SecurityException("ZIP 路径越界");
          if (e.isDirectory()) Files.createDirectories(p);
          else {
            Files.createDirectories(p.getParent());
            try (OutputStream o = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW)) {
              int x;
              while ((x = z.read(b)) != -1) {
                if (cancelled.cancelled()) throw new InterruptedIOException("安装已取消");
                o.write(b, 0, x);
              }
            }
          }
          z.closeEntry();
        }
      }
      if (!complete(stage)) throw new IOException("runtime 必需文件缺失");
      chmod(stage, cancelled);
      if (cancelled.cancelled()) throw new InterruptedIOException("安装已取消");
      RuntimeCommit.commit(target, stage, marker, old, oldMarker, expected,
          RuntimeCommit.FILES, cancelled::cancelled);
      return target;
    } catch (Exception e) {
      // Preparation failures have no commit backups; remove only the uncommitted stage.
      if (Files.exists(stage)) delQuietly(stage, e);
      throw e;
    }
  }

  private static void delQuietly(Path p, Exception original) {
    try { del(p); } catch (Exception cleanupFailure) { original.addSuppressed(cleanupFailure); }
  }
  private static boolean complete(Path r) {
    return Files.isRegularFile(r.resolve("bin/node")) && Files.isRegularFile(r.resolve("bin/bash"))
        && Files.isRegularFile(r.resolve("bin/dsh_pty_exec"))
        && Files.isRegularFile(r.resolve("lib/node_modules/@deepseek-ai/node-addon-system/lib/flock.js"))
        && Files.isRegularFile(r.resolve("lib/node_modules/@deepseek-ai/node-addon-system/lib/system.node"))
        && Files.isRegularFile(r.resolve("lib/libandroid-spawn.so"))
        && Files.isRegularFile(r.resolve("lib/node_modules/@deepseek-ai/dsh/lib/bin.js"));
  }
  private static void chmod(Path p, CancelCheck cancelled) throws IOException {
    if (cancelled.cancelled()) throw new InterruptedIOException("安装已取消");
    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(p)) {
        for (Path entry : entries) chmod(entry, cancelled);
      }
    } else if (Files.isRegularFile(p)) {
      String name = p.getFileName().toString();
      if (p.toString().contains("/bin/") || name.equals("rg") || name.endsWith(".so") || name.endsWith(".node")) {
        if (!p.toFile().setExecutable(true, true)) throw new IOException("无法设置本机程序执行权限: " + name);
      }
    }
  }
  private static void del(Path p) throws IOException {
    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) try (DirectoryStream<Path> s = Files.newDirectoryStream(p)) { for (Path x : s) del(x); }
    Files.deleteIfExists(p);
  }
  static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.US, "%02x", x)); return s.toString(); }
  private static String read(InputStream i) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = i.read(b)) != -1) o.write(b, 0, n); return o.toString("UTF-8"); }
}
