package io.github.zhanxing.dshandroid;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, fully preflighted plan for exporting an imported snapshot. */
final class SnapshotExportPlan {
  static final class Entry {
    private final Path path;
    private final String relative;
    private final boolean directory;
    private final long size;
    Entry(Path path, String relative, boolean directory, long size) {
      this.path = path; this.relative = relative; this.directory = directory; this.size = size;
    }
    Path path() { return path; }
    String relativePath() { return relative; }
    boolean isDirectory() { return directory; }
    long size() { return size; }
  }

  private final Path root;
  private final Path snapshot;
  private final List<Entry> entries;
  private final int files;
  private final long bytes;

  private SnapshotExportPlan(Path root, Path snapshot, List<Entry> entries, int files, long bytes) {
    this.root = root; this.snapshot = snapshot;
    this.entries = java.util.Collections.unmodifiableList(new ArrayList<>(entries));
    this.files = files; this.bytes = bytes;
  }

  static SnapshotExportPlan create(Path workspace, Path snapshot,
      WorkspaceTransfers.Cancellation cancellation) throws IOException {
    if (cancellation == null) throw new IOException("缺少取消检查");
    WorkspaceTransfers.check(cancellation);
    Path root = workspace.toAbsolutePath().normalize();
    Path candidate = snapshot.toAbsolutePath().normalize();
    WorkspaceTransfers.requireDirectory(root);
    if (!candidate.startsWith(root) || candidate.getParent() == null
        || !root.equals(candidate.getParent().toAbsolutePath().normalize())
        || !candidate.getFileName().toString().matches("import-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
      throw new IOException("拒绝非默认工作区直属的 import-UUID 快照");
    }
    WorkspaceTransfers.requireDirectory(candidate);
    // Validate every ancestor, including the workspace and snapshot itself.
    WorkspaceTransfers.confined(root, candidate);
    Accumulator a = new Accumulator(root, candidate, cancellation);
    a.visit(candidate, "", 0);
    return new SnapshotExportPlan(root, candidate, a.entries, a.files, a.bytes);
  }

  static SnapshotExportPlan plan(Path workspace, Path snapshot,
      WorkspaceTransfers.Cancellation cancellation) throws IOException {
    return create(workspace, snapshot, cancellation);
  }

  Path workspace() { return root; }
  Path snapshot() { return snapshot; }
  List<Entry> entries() { return entries; }
  int fileCount() { return files; }
  long totalBytes() { return bytes; }

  private static final class Accumulator {
    final Path root, snapshot;
    final WorkspaceTransfers.Cancellation cancellation;
    final List<Entry> entries = new ArrayList<>();
    int files;
    int entryCount;
    long bytes;
    Accumulator(Path root, Path snapshot, WorkspaceTransfers.Cancellation cancellation) {
      this.root = root; this.snapshot = snapshot; this.cancellation = cancellation;
    }
    void visit(Path dir, String relative, int depth) throws IOException {
      WorkspaceTransfers.check(cancellation);
      WorkspaceTransfers.requireDirectory(dir);
      if (depth > WorkspaceTransfers.MAX_DEPTH) throw new IOException("目录过深（最多 16 层）");
      List<Path> children = new ArrayList<>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
        for (Path child : stream) {
          WorkspaceTransfers.check(cancellation);
          if (children.size() >= WorkspaceTransfers.MAX_ENTRIES) throw new IOException("目录条目过多");
          children.add(child);
        }
      }
      children.sort(Comparator.comparing(p -> normalizedName(p.getFileName().toString())));
      Set<String> names = new HashSet<>();
      for (Path child : children) {
        WorkspaceTransfers.check(cancellation);
        String name = WorkspaceTransfers.safeName(child.getFileName().toString());
        if (!names.add(name)) throw new IOException("快照包含 NFC 重名：" + name);
        if (depth + 1 > WorkspaceTransfers.MAX_DEPTH) throw new IOException("目录过深（最多 16 层）");
        Path checked = WorkspaceTransfers.confined(snapshot, child);
        BasicFileAttributes attrs = Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String childRelative = relative.isEmpty() ? name : relative + "/" + name;
        if (attrs.isSymbolicLink()) throw new IOException("快照包含符号链接");
        if (attrs.isDirectory()) {
          if (++entryCount > WorkspaceTransfers.MAX_ENTRIES) throw new IOException("条目过多（最多 2000 个）");
          entries.add(new Entry(checked, childRelative, true, 0));
          visit(checked, childRelative, depth + 1);
        } else if (attrs.isRegularFile()) {
          if (attrs.size() > WorkspaceTransfers.MAX_FILE_BYTES
              || bytes > WorkspaceTransfers.MAX_TOTAL_BYTES - attrs.size()) {
            throw new IOException("超过传输限额（单文件 128 MiB、每批 256 MiB）");
          }
          if (++files > WorkspaceTransfers.MAX_FILES) throw new IOException("文件过多（最多 1000 个）");
          if (++entryCount > WorkspaceTransfers.MAX_ENTRIES) throw new IOException("条目过多（最多 2000 个）");
          bytes += attrs.size();
          entries.add(new Entry(checked, childRelative, false, attrs.size()));
        } else {
          throw new IOException("快照包含不支持的文件类型");
        }
      }
    }
    static String normalizedName(String value) {
      return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC);
    }
  }
}
