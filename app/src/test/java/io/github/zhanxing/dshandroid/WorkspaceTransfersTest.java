package io.github.zhanxing.dshandroid;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkspaceTransfersTest {
  private Path temp;

  @Before public void setUp() throws Exception { temp = Files.createTempDirectory("workspace-transfers-test-"); }

  @After public void tearDown() throws Exception {
    if (temp != null) deleteNoFollow(temp);
  }

  private static void deleteNoFollow(Path p) throws IOException {
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
      try (Stream<Path> children = Files.list(p)) {
        for (Path child : (Iterable<Path>) children::iterator) deleteNoFollow(child);
      }
    }
    Files.deleteIfExists(p);
  }

  private static void expectIo(IoAction action) throws Exception {
    try { action.run(); fail("expected IOException"); }
    catch (IOException expected) { }
  }
  private interface IoAction { void run() throws Exception; }

  @Test public void safeNameRejectsPathsControlsNulFormatDotsAndSurrogates() throws Exception {
    String[] bad = {null, "", ".", "..", "a/b", "a\\b", "a\u0000b", "a\u0001b", "a\u200Eb", "a\uD800",
        "a\uDC00", "a" + new String(Character.toChars(0xE0001))};
    for (String value : bad) expectIo(() -> WorkspaceTransfers.safeName(value));
  }

  @Test public void safeNameAcceptsChineseEmojiAndNfcNormalizes() throws Exception {
    assertEquals("中文文件-😀", WorkspaceTransfers.safeName("中文文件-😀"));
    assertEquals("é", WorkspaceTransfers.safeName("e\u0301"));
    assertEquals("a", WorkspaceTransfers.safeName("a"));
    expectIo(() -> WorkspaceTransfers.safeName(repeat("a", 241)));
    assertEquals(240, WorkspaceTransfers.safeName(repeat("中", 80)).getBytes(StandardCharsets.UTF_8).length);
    expectIo(() -> WorkspaceTransfers.safeName(repeat("中", 81)));
  }

  @Test public void workspaceRejectsSymlinkedWorkspaceDirectories() throws Exception {
    Path files = temp.resolve("files"); Files.createDirectory(files);
    Path outside = temp.resolve("outside"); Files.createDirectory(outside);
    Files.createSymbolicLink(files.resolve("workspaces"), outside);
    expectIo(() -> WorkspaceTransfers.workspace(files));

    Path files2 = temp.resolve("files2"); Files.createDirectory(files2);
    Path workspaces = files2.resolve("workspaces"); Files.createDirectory(workspaces);
    Files.createSymbolicLink(workspaces.resolve("default"), outside);
    expectIo(() -> WorkspaceTransfers.workspace(files2));
  }

  @Test public void confinedRejectsOutsideAndSymlinkAncestor() throws Exception {
    Path root = temp.resolve("root"); Files.createDirectories(root);
    Path outside = temp.resolve("outside"); Files.createDirectories(outside);
    expectIo(() -> WorkspaceTransfers.confined(root, outside.resolve("x")));
    Path link = root.resolve("link"); Files.createSymbolicLink(link, outside);
    expectIo(() -> WorkspaceTransfers.confined(root, link.resolve("child")));
    expectIo(() -> WorkspaceTransfers.confined(link, link));
  }

  @Test public void batchPublishesNormallyAndUsesUniqueNonOverwritingDirectories() throws Exception {
    WorkspaceTransfers.Batch first = new WorkspaceTransfers.Batch(temp);
    Path f1 = first.destination(first.staging, "中文😀.txt", false, 1);
    first.write(f1, bytes("one"), () -> false);
    Path published1 = first.publish(() -> false);
    first.close();
    WorkspaceTransfers.Batch second = new WorkspaceTransfers.Batch(temp);
    Path f2 = second.destination(second.staging, "same.txt", false, 1);
    second.write(f2, bytes("two"), () -> false);
    Path published2 = second.publish(() -> false);
    second.close();
    assertNotEquals(published1, published2);
    assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(published1.resolve("中文😀.txt")));
    assertArrayEquals("two".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(published2.resolve("same.txt")));
  }

  @Test public void duplicateNfcNamesFailAndBatchIsCleaned() throws Exception {
    WorkspaceTransfers.Batch batch = new WorkspaceTransfers.Batch(temp);
    Path first = batch.destination(batch.staging, "e\u0301.txt", false, 1);
    batch.write(first, bytes("one"), () -> false);
    Path duplicate = batch.destination(batch.staging, "é.txt", false, 1);
    expectIo(() -> batch.write(duplicate, bytes("two"), () -> false));
    Path staging = batch.staging;
    batch.close();
    assertFalse(Files.exists(staging, LinkOption.NOFOLLOW_LINKS));
  }

  @Test public void cancelledAndFailingStreamsCleanStaging() throws Exception {
    WorkspaceTransfers.Batch cancelled = new WorkspaceTransfers.Batch(temp);
    Path cp = cancelled.destination(cancelled.staging, "cancelled", false, 1);
    Path cstage = cancelled.staging;
    expectIo(() -> cancelled.write(cp, bytes("x"), () -> true));
    cancelled.close();
    assertFalse(Files.exists(cstage, LinkOption.NOFOLLOW_LINKS));

    WorkspaceTransfers.Batch failed = new WorkspaceTransfers.Batch(temp);
    Path fp = failed.destination(failed.staging, "failed", false, 1);
    Path fstage = failed.staging;
    expectIo(() -> failed.write(fp, new InputStream() {
      int n;
      @Override public int read() throws IOException { if (n++ > 0) throw new IOException("boom"); return 'x'; }
    }, () -> false));
    failed.close();
    assertFalse(Files.exists(fstage, LinkOption.NOFOLLOW_LINKS));
  }

  @Test public void importBudgetEnforcesDepthCountAndFileTotalLimits() throws Exception {
    WorkspaceTransfers.Budget depth = new WorkspaceTransfers.Budget(100, 100, 100, 100);
    expectIo(() -> depth.entry(WorkspaceTransfers.MAX_DEPTH + 1));
    WorkspaceTransfers.Budget entries = new WorkspaceTransfers.Budget(100, 100, 100, 1);
    entries.entry(0); expectIo(() -> entries.entry(0));
    WorkspaceTransfers.Budget files = new WorkspaceTransfers.Budget(100, 100, 1, 100);
    files.file(); expectIo(files::file);
    WorkspaceTransfers.Budget one = new WorkspaceTransfers.Budget(3, 10, 10, 10);
    expectIo(() -> one.add(4, 1));
    one.add(3, 3); expectIo(() -> one.add(8, 8));
  }

  @Test public void exportRejectsDirectorySymlinkAndOutsideAndReadsBytes() throws Exception {
    Path root = temp.resolve("export"); Files.createDirectories(root);
    Path file = root.resolve("hello.txt"); Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));
    try (InputStream in = WorkspaceTransfers.openExport(root, file)) {
      assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), readAll(in));
    }
    Path outside = temp.resolve("outside"); Files.createDirectories(outside);
    Files.write(outside.resolve("secret"), data("secret"));
    Files.createSymbolicLink(root.resolve("dir-link"), outside);
    expectIo(() -> WorkspaceTransfers.openExport(root, root.resolve("dir-link")));
    expectIo(() -> WorkspaceTransfers.openExport(root, outside.resolve("secret")));
  }

  @Test public void listReturnsSortedFilesAndDirectoriesAndCountsSymlinkIn200Limit() throws Exception {
    Path root = temp.resolve("list"); Files.createDirectories(root);
    Files.write(root.resolve("b"), data("b")); Files.createDirectory(root.resolve("a-dir"));
    List<Path> listed = WorkspaceTransfers.list(root, root);
    assertEquals(2, listed.size());
    assertEquals("a-dir", listed.get(0).getFileName().toString());
    assertEquals("b", listed.get(1).getFileName().toString());

    Path full = temp.resolve("full"); Files.createDirectories(full);
    for (int i = 0; i < 199; i++) Files.write(full.resolve(String.format("f%03d", i)), data("x"));
    Files.createSymbolicLink(full.resolve("symlink"), root);
    Files.write(full.resolve("last"), data("x"));
    expectIo(() -> WorkspaceTransfers.list(full, full));
  }

  @Test public void streamCopyHonorsExactByteLimitsAndCancellationDuringRead() throws Exception {
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    WorkspaceTransfers.Budget budget = new WorkspaceTransfers.Budget(3, 5, 3, 3);
    assertEquals(3, WorkspaceTransfers.copy(bytes("abc"), output, budget, () -> false));
    assertEquals(2, WorkspaceTransfers.copy(bytes("de"), output, budget, () -> false));
    expectIo(() -> WorkspaceTransfers.copy(bytes("f"), output, budget, () -> false));
    assertEquals("abcde", output.toString("UTF-8"));
    expectIo(() -> WorkspaceTransfers.copy(bytes("abcd"), new java.io.ByteArrayOutputStream(),
        new WorkspaceTransfers.Budget(3, 10, 2, 2), () -> false));
    java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
    InputStream interrupted = new ByteArrayInputStream(data("first")) {
      @Override public synchronized int read(byte[] b, int off, int len) {
        int count = super.read(b, off, len);
        stop.set(true);
        return count;
      }
    };
    expectIo(() -> WorkspaceTransfers.copy(interrupted, new java.io.ByteArrayOutputStream(),
        new WorkspaceTransfers.Budget(), stop::get));
  }

  @Test public void exportRejectsOrdinaryDirectoryFileLinkAndOversizedFile() throws Exception {
    Path root = Files.createDirectory(temp.resolve("export-types"));
    Path file = Files.write(root.resolve("plain"), data("safe"));
    Path directory = Files.createDirectory(root.resolve("directory"));
    Files.createSymbolicLink(root.resolve("file-link"), file);
    expectIo(() -> WorkspaceTransfers.openExport(root, directory));
    expectIo(() -> WorkspaceTransfers.openExport(root, root.resolve("file-link")));
    Path huge = root.resolve("too-large");
    try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(huge.toFile(), "rw")) {
      sparse.setLength(WorkspaceTransfers.MAX_FILE_BYTES + 1);
    }
    expectIo(() -> WorkspaceTransfers.openExport(root, huge));
  }

  @Test public void publishDoesNotOverwriteAnExistingDestination() throws Exception {
    try (WorkspaceTransfers.Batch batch = new WorkspaceTransfers.Batch(temp)) {
      Path source = batch.destination(batch.staging, "new.txt", false, 1);
      batch.write(source, bytes("new"), () -> false);
      java.lang.reflect.Field name = WorkspaceTransfers.Batch.class.getDeclaredField("destinationName");
      name.setAccessible(true);
      Path existing = Files.createDirectory(batch.root.resolve((String) name.get(batch)));
      Files.write(existing.resolve("keep"), data("untouched"));
      expectIo(() -> batch.publish(() -> false));
      assertArrayEquals(data("untouched"), Files.readAllBytes(existing.resolve("keep")));
      assertFalse(Files.exists(existing.resolve("new.txt")));
    }
  }

  @Test public void publishRechecksWorkspaceAncestorAndCancelledBatchNeverAppears() throws Exception {
    WorkspaceTransfers.Batch batch = new WorkspaceTransfers.Batch(temp);
    Path stage = batch.staging;
    expectIo(() -> batch.publish(() -> true));
    Path previous = batch.root.resolveSibling("previous");
    Files.move(batch.root, previous);
    Path outside = Files.createDirectory(temp.resolve("outside-publish"));
    Files.createSymbolicLink(batch.root, outside);
    expectIo(() -> batch.publish(() -> false));
    batch.close();
    assertFalse(Files.exists(stage));
    try (Stream<Path> children = Files.list(outside)) { assertEquals(0L, children.count()); }
  }

  private static byte[] data(String s) { return s.getBytes(StandardCharsets.UTF_8); }
  private static InputStream bytes(String s) { return new ByteArrayInputStream(data(s)); }
  private static byte[] readAll(InputStream in) throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] b = new byte[32]; int n; while ((n = in.read(b)) >= 0) if (n > 0) out.write(b, 0, n);
    return out.toByteArray();
  }
  private static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }
}
