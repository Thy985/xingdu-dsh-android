package io.github.zhanxing.dshandroid;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Plain filesystem half of SAF transfers. No URI is ever interpreted as a filesystem path. */
final class WorkspaceTransfers {
  static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
  static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
  static final int MAX_FILES = 1000;
  static final int MAX_ENTRIES = 2000;
  static final int MAX_DEPTH = 16;
  static final int MAX_BROWSE_ENTRIES = 200;

  interface Cancellation { boolean cancelled(); }

  static void check(Cancellation cancellation) throws IOException {
    if (cancellation.cancelled() || Thread.currentThread().isInterrupted()) {
      throw new IOException("文件传输已取消");
    }
  }

  static String safeName(String name) throws IOException {
    if (name != null) name = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC);
    if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
        || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240) {
      throw new IOException("文件名为空、过长或不安全");
    }
    for (int i = 0; i < name.length();) {
      char c = name.charAt(i);
      if (Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < name.length()
          && Character.isLowSurrogate(name.charAt(i + 1)))) {
        throw new IOException("文件名包含不完整的 Unicode 字符");
      }
      int codePoint = name.codePointAt(i);
      if (codePoint == '/' || codePoint == '\\' || Character.isISOControl(codePoint)
          || Character.getType(codePoint) == Character.FORMAT) {
        throw new IOException("文件名包含路径分隔符或不可见控制字符");
      }
      i += Character.charCount(codePoint);
    }
    return name;
  }

  static Path workspace(Path files) throws IOException {
    Path base = files.toAbsolutePath().normalize();
    requireDirectory(base);
    Path workspaces = base.resolve("workspaces");
    createDirectory(workspaces);
    Path root = workspaces.resolve("default");
    createDirectory(root);
    return root;
  }

  private static void createDirectory(Path path) throws IOException {
    try { Files.createDirectory(path); }
    catch (java.nio.file.FileAlreadyExistsException ignored) { /* Validate below. */ }
    requireDirectory(path);
  }

  static void requireDirectory(Path path) throws IOException {
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw new IOException("目录不可用或为符号链接");
    }
  }

  static Path confined(Path root, Path candidate) throws IOException {
    Path base = root.toAbsolutePath().normalize();
    Path path = candidate.toAbsolutePath().normalize();
    if (!path.startsWith(base)) throw new IOException("拒绝访问工作区以外的路径");
    requireDirectory(base);
    Path current = base;
    for (Path part : base.relativize(path)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IOException("不允许访问符号链接");
    }
    return path;
  }

  static final class Budget {
    private final long fileLimit;
    private final long totalLimit;
    private final int fileCountLimit;
    private final int entryLimit;
    long bytes;
    int files;
    int entries;
    Budget() { this(MAX_FILE_BYTES, MAX_TOTAL_BYTES, MAX_FILES, MAX_ENTRIES); }
    Budget(long fileLimit, long totalLimit, int fileCountLimit, int entryLimit) {
      this.fileLimit = fileLimit;
      this.totalLimit = totalLimit;
      this.fileCountLimit = fileCountLimit;
      this.entryLimit = entryLimit;
    }
    void entry(int depth) throws IOException {
      if (depth > MAX_DEPTH || ++entries > entryLimit) {
        throw new IOException("目录过深或条目过多（最多 16 层、2000 个条目）");
      }
    }
    void file() throws IOException {
      if (++files > fileCountLimit) throw new IOException("文件过多（最多 1000 个）");
    }
    void add(long fileBytes, int count) throws IOException {
      if (fileBytes > fileLimit || count > totalLimit - bytes) {
        throw new IOException("超过传输限额（单文件 128 MiB、每批 256 MiB）");
      }
      bytes += count;
    }
  }

  static long copy(InputStream in, OutputStream out, Budget budget, Cancellation cancellation)
      throws IOException {
    budget.file();
    byte[] buffer = new byte[64 * 1024];
    long size = 0;
    while (true) {
      check(cancellation);
      int count = in.read(buffer);
      if (count < 0) break;
      if (count == 0) continue;
      check(cancellation);
      size += count;
      budget.add(size, count);
      out.write(buffer, 0, count);
    }
    check(cancellation);
    return size;
  }

  static final class Batch implements AutoCloseable {
    final Path root;
    final Path staging;
    final Budget budget = new Budget();
    private final String destinationName;
    private boolean published;

    Batch(Path files) throws IOException {
      root = workspace(files);
      Path stagingRoot = files.toAbsolutePath().normalize().resolve("file-transfers");
      createDirectory(stagingRoot);
      String id = UUID.randomUUID().toString();
      staging = Files.createDirectory(stagingRoot.resolve("pending-" + id));
      destinationName = "import-" + id;
    }

    Path destination(Path parent, String name, boolean directory, int depth) throws IOException {
      budget.entry(depth);
      Path checkedParent = confined(staging, parent);
      requireDirectory(checkedParent);
      Path target = checkedParent.resolve(safeName(name));
      if (directory) Files.createDirectory(target);
      // Files are created with CREATE_NEW by the caller: duplicate names fail the entire batch.
      return target;
    }

    void write(Path destination, InputStream source, Cancellation cancellation) throws IOException {
      confined(staging, destination);
      try (OutputStream output = Files.newOutputStream(destination,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        copy(source, output, budget, cancellation);
      }
    }

    Path publish(Cancellation cancellation) throws IOException {
      check(cancellation);
      // Recheck ancestors; never overwrite an existing directory, never merge partial batches.
      workspace(root.getParent().getParent());
      Path destination = root.resolve(destinationName);
      Files.move(staging, destination);
      published = true;
      return destination;
    }

    @Override public void close() throws IOException {
      if (!published) deleteTree(staging);
    }
  }

  static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
        for (Path child : stream) deleteTree(child);
      }
    }
    Files.delete(path);
  }

  static List<Path> list(Path root, Path directory) throws IOException {
    Path dir = confined(root, directory);
    requireDirectory(dir);
    List<Path> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      int scanned = 0;
      for (Path item : stream) {
        if (++scanned > MAX_BROWSE_ENTRIES) {
          throw new IOException("此目录超过 200 个条目，暂不支持在导出选择器中浏览");
        }
        // Reject, rather than follow, links and device/FIFO/socket entries.
        BasicFileAttributes attrs = Files.readAttributes(item, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isSymbolicLink() && (attrs.isRegularFile() || attrs.isDirectory())) {
          safeName(item.getFileName().toString());
          entries.add(item);
        }
      }
    }
    entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
    return entries;
  }

  static InputStream openExport(Path root, Path selected) throws IOException {
    Path path = confined(root, selected);
    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
      throw new IOException("只能导出不超过 128 MiB 的普通文件");
    }
    return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
  }
}
