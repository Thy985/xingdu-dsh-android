package io.github.zhanxing.dshandroid;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.*;

public final class SnapshotExportPlanTest {
  private Path temp, workspace, snapshot;
  @Before public void setUp() throws Exception {
    temp = Files.createTempDirectory("snapshot-plan-");
    workspace = Files.createDirectories(temp.resolve("workspaces/default"));
    snapshot = Files.createDirectory(workspace.resolve("import-123e4567-e89b-12d3-a456-426614174000"));
  }
  @After public void tearDown() throws Exception { if (temp != null) delete(temp); }
  private static void delete(Path p) throws IOException {
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) try (DirectoryStream<Path> s=Files.newDirectoryStream(p)) { for(Path c:s) delete(c); }
    Files.deleteIfExists(p);
  }
  private static void failIo(Io action) throws Exception { try { action.run(); fail("expected IOException"); } catch (IOException expected) {} }
  private interface Io { void run() throws Exception; }
  private SnapshotExportPlan plan() throws Exception { return SnapshotExportPlan.plan(workspace, snapshot, () -> false); }

  @Test public void nestedEmptyDirectoriesAndFilesAreSafeSortedPreorder() throws Exception {
    Files.createDirectories(snapshot.resolve("z/empty"));
    Files.write(snapshot.resolve("z/a.txt"), new byte[]{1,2});
    Files.write(snapshot.resolve("a.txt"), new byte[]{3});
    SnapshotExportPlan p = plan();
    assertEquals(4, p.entries().size());
    assertFalse(p.entries().get(0).isDirectory());
    assertEquals("a.txt", p.entries().get(0).relativePath());
    assertEquals(2, p.fileCount());
  }

  @Test public void nfcDuplicateNamesAreRejected() throws Exception {
    Files.write(snapshot.resolve("e\u0301.txt"), new byte[]{1});
    Files.write(snapshot.resolve("é.txt"), new byte[]{2});
    failIo(() -> plan());
  }
  @Test public void symlinksAndEscapesAndInvalidRootsAreRejected() throws Exception {
    Path outside = Files.createTempDirectory(temp, "outside");
    Files.write(outside.resolve("secret"), new byte[]{1});
    Files.createSymbolicLink(snapshot.resolve("link"), outside.resolve("secret"));
    failIo(() -> plan());
    failIo(() -> SnapshotExportPlan.plan(workspace, outside, () -> false));
    failIo(() -> SnapshotExportPlan.plan(workspace, workspace.resolve("import-not-uuid"), () -> false));
  }
  @Test public void cancellationIsCheckedDuringTraversal() throws Exception {
    Files.write(snapshot.resolve("x"), new byte[]{1});
    failIo(() -> SnapshotExportPlan.plan(workspace, snapshot, () -> true));
  }
  @Test public void unsupportedTypeFileCountDepthAndSingleFileLimitsAreRejected() throws Exception {
    Path fifo = snapshot.resolve("fifo");
    try { Files.createSymbolicLink(fifo, snapshot.resolve("missing")); failIo(() -> plan()); } catch (UnsupportedOperationException ignored) {}
    delete(snapshot); snapshot = Files.createDirectory(workspace.resolve("import-123e4567-e89b-12d3-a456-426614174000"));
    for (int i=0;i<=WorkspaceTransfers.MAX_FILES;i++) Files.write(snapshot.resolve("f"+i), new byte[]{1});
    failIo(() -> plan());
    delete(snapshot); snapshot = Files.createDirectory(workspace.resolve("import-123e4567-e89b-12d3-a456-426614174000"));
    Path d=snapshot; for(int i=0;i<WorkspaceTransfers.MAX_DEPTH;i++) d=Files.createDirectory(d.resolve("d"+i));
    Files.write(d.resolve("deep"), new byte[]{1}); failIo(() -> plan());
    delete(snapshot); snapshot = Files.createDirectory(workspace.resolve("import-123e4567-e89b-12d3-a456-426614174000"));
    Path huge=snapshot.resolve("huge");
    try (java.io.RandomAccessFile f=new java.io.RandomAccessFile(huge.toFile(), "rw")) { f.setLength(WorkspaceTransfers.MAX_FILE_BYTES+1); }
    failIo(() -> plan());
  }
  @Test public void deletionOrReplacementAfterPlanIsDetectedByNoFollowRevalidation() throws Exception {
    Path f=Files.write(snapshot.resolve("file"), new byte[]{1});
    SnapshotExportPlan p=plan(); assertEquals(1,p.fileCount());
    Files.delete(f); Files.createSymbolicLink(f, temp.resolve("outside"));
    failIo(() -> WorkspaceTransfers.openExport(workspace, p.entries().get(0).path()));
  }

  @Test public void actualFifoIsRejectedWithoutOpeningIt() throws Exception {
    Process p = new ProcessBuilder("mkfifo", snapshot.resolve("pipe").toString()).start();
    assertEquals("Linux JVM fixture must create a FIFO", 0, p.waitFor());
    failIo(() -> plan());
  }

  @Test public void totalBytesAndEntryLimitsAreEnforcedBeforeExport() throws Exception {
    for (int i = 0; i < 3; i++) {
      try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(snapshot.resolve("large" + i).toFile(), "rw")) {
        f.setLength(100L * 1024 * 1024);
      }
    }
    failIo(() -> plan());
    delete(snapshot);
    snapshot = Files.createDirectory(workspace.resolve("import-123e4567-e89b-12d3-a456-426614174000"));
    for (int i = 0; i <= WorkspaceTransfers.MAX_ENTRIES; i++) Files.createDirectory(snapshot.resolve("d" + i));
    failIo(() -> plan());
  }

  @Test public void traversalCancellationAndImmutablePlanAreReal() throws Exception {
    for (int i = 0; i < 8; i++) Files.write(snapshot.resolve("f" + i), new byte[]{1});
    final java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
    failIo(() -> SnapshotExportPlan.plan(workspace, snapshot, () -> checks.incrementAndGet() > 5));
    assertTrue(checks.get() > 5);
    SnapshotExportPlan p = plan();
    try { p.entries().clear(); fail("plan must be immutable"); }
    catch (UnsupportedOperationException expected) { }
  }

  @Test public void deletionAndOversizeAfterPreflightStillFailReopen() throws Exception {
    Path file = Files.write(snapshot.resolve("file"), new byte[]{1});
    SnapshotExportPlan p = plan();
    Files.delete(file);
    failIo(() -> WorkspaceTransfers.openExport(workspace, p.entries().get(0).path()));
    try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file.toFile(), "rw")) {
      f.setLength(WorkspaceTransfers.MAX_FILE_BYTES + 1);
    }
    failIo(() -> WorkspaceTransfers.openExport(workspace, p.entries().get(0).path()));
  }
}
