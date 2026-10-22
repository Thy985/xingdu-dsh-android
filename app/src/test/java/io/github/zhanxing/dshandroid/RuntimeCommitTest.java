package io.github.zhanxing.dshandroid;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuntimeCommitTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test public void cancellationAtEveryCommitCheckpointPreservesOldMarker() throws Exception {
    for (int checkpoint = 1; checkpoint <= 4; checkpoint++) {
      Path root = tmp.newFolder("cancel-" + checkpoint).toPath();
      Path target = dir(root, "runtime", "old-runtime");
      Path stage = dir(root, "stage", "new-runtime");
      Path marker = file(root, "runtime.sha256", "old\n");
      java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
      final int cancelAt = checkpoint;
      try {
        RuntimeCommit.commit(target, stage, marker, root.resolve("old"), root.resolve("oldMarker"),
            "new", RuntimeCommit.FILES, () -> checks.incrementAndGet() == cancelAt);
        fail("expected cancellation at " + checkpoint);
      } catch (java.io.InterruptedIOException expected) { /* rollback must preserve both old values */ }
      assertEquals("old-runtime", new String(Files.readAllBytes(target.resolve("value")), StandardCharsets.UTF_8));
      assertEquals("old\n", new String(Files.readAllBytes(marker), StandardCharsets.UTF_8));
      assertFalse(Files.exists(stage));
    }
  }

  @Test public void cleanupDoesNotFollowSymlinkIntoWorkspace() throws Exception {
    Path root = tmp.getRoot().toPath();
    Path workspace = dir(root, "workspace", "keep-user-data");
    Path backup = root.resolve("backup");
    Files.createDirectories(backup);
    Files.createSymbolicLink(backup.resolve("link"), workspace);
    RuntimeCommit.FILES.delete(backup);
    assertFalse(Files.exists(backup));
    assertEquals("keep-user-data", new String(Files.readAllBytes(workspace.resolve("value")), StandardCharsets.UTF_8));
  }

  @Test public void markerFailureRestoresOldRuntimeAndMarker() throws Exception {
    Path root = tmp.getRoot().toPath();
    Path target = dir(root, "runtime", "old-runtime");
    Path stage = dir(root, "stage", "new-runtime");
    Path marker = file(root, "runtime.sha256", "old\n");
    Path old = root.resolve("runtime.previous");
    Path oldMarker = root.resolve("runtime.sha256.previous");
    FailingOps ops = new FailingOps(RuntimeCommit.FILES);
    ops.failWrites = true;

    try {
      RuntimeCommit.commit(target, stage, marker, old, oldMarker, digest("new"), ops, () -> false);
      fail("expected marker write failure");
    } catch (IOException expected) {
      assertEquals("injected marker write failure", expected.getMessage());
    }
    assertEquals("old-runtime", new String(Files.readAllBytes(target.resolve("value")), StandardCharsets.UTF_8));
    assertEquals("old\n", new String(Files.readAllBytes(marker), StandardCharsets.UTF_8));
    assertFalse(Files.exists(old));
    assertFalse(Files.exists(oldMarker));
    assertFalse(Files.exists(stage));
  }

  @Test public void cleanupFailureDoesNotUndoCommittedRuntime() throws Exception {
    Path root = tmp.getRoot().toPath();
    Path target = dir(root, "runtime", "old-runtime");
    Path stage = dir(root, "stage", "new-runtime");
    Path marker = file(root, "runtime.sha256", "old\n");
    Path old = root.resolve("runtime.previous");
    Path oldMarker = root.resolve("runtime.sha256.previous");
    FailingOps ops = new FailingOps(RuntimeCommit.FILES);
    ops.failDeletesContaining = old.toString();

    RuntimeCommit.Result result = RuntimeCommit.commit(target, stage, marker, old, oldMarker,
        digest("new"), ops, () -> false);
    assertEquals("new-runtime", new String(Files.readAllBytes(target.resolve("value")), StandardCharsets.UTF_8));
    assertEquals(digest("new") + "\n", new String(Files.readAllBytes(marker), StandardCharsets.UTF_8));
    assertTrue(Files.exists(old));
    assertNotNull(result.cleanupFailure);
  }

  @Test public void markerMoveFailureRestoresBothOldValues() throws Exception {
    Path root = tmp.getRoot().toPath();
    Path target = dir(root, "runtime", "old-runtime");
    Path stage = dir(root, "stage", "new-runtime");
    Path marker = file(root, "runtime.sha256", "old\n");
    FailingOps ops = new FailingOps(RuntimeCommit.FILES);
    ops.failNextMoveTo = marker;
    try {
      RuntimeCommit.commit(target, stage, marker, root.resolve("old"), root.resolve("oldMarker"),
          "new", ops, () -> false);
      fail("expected marker move failure");
    } catch (IOException expected) { assertEquals("injected move failure", expected.getMessage()); }
    assertEquals("old-runtime", new String(Files.readAllBytes(target.resolve("value")), StandardCharsets.UTF_8));
    assertEquals("old\n", new String(Files.readAllBytes(marker), StandardCharsets.UTF_8));
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.staging-*")) {
      assertFalse("no staged marker should remain", entries.iterator().hasNext());
    }
  }

  @Test public void firstInstallMarkerFailureLeavesNoRuntimeOrMarker() throws Exception {
    Path root = tmp.getRoot().toPath();
    Path target = root.resolve("runtime");
    Path stage = dir(root, "stage", "new-runtime");
    Path marker = root.resolve("runtime.sha256");
    Path old = root.resolve("runtime.previous");
    Path oldMarker = root.resolve("runtime.sha256.previous");
    FailingOps ops = new FailingOps(RuntimeCommit.FILES);
    ops.failWrites = true;

    try {
      RuntimeCommit.commit(target, stage, marker, old, oldMarker, digest("new"), ops, () -> false);
      fail("expected marker write failure");
    } catch (IOException expected) {
      // The original failure, rather than a cleanup failure, is propagated.
      assertEquals("injected marker write failure", expected.getMessage());
    }
    assertFalse(Files.exists(target));
    assertFalse(Files.exists(marker));
    assertFalse(Files.exists(stage));
  }

  @Test public void rollbackFailureIsSuppressedOnOriginalFailure() throws Exception {
    Path root = tmp.getRoot().toPath();
    Path target = dir(root, "runtime", "old-runtime");
    Path stage = dir(root, "stage", "new-runtime");
    Path marker = file(root, "runtime.sha256", "old\n");
    Path old = root.resolve("runtime.previous");
    Path oldMarker = root.resolve("runtime.sha256.previous");
    FailingOps ops = new FailingOps(RuntimeCommit.FILES);
    ops.failWrites = true;
    ops.failDeletesContaining = target.toString();

    try {
      RuntimeCommit.commit(target, stage, marker, old, oldMarker, digest("new"), ops, () -> false);
      fail("expected marker write failure");
    } catch (IOException expected) {
      assertEquals("injected marker write failure", expected.getMessage());
      assertTrue("rollback failure must be retained", expected.getSuppressed().length > 0);
    }
  }

  private static String digest(String value) {
    return RuntimeInstaller.hex(value.getBytes(StandardCharsets.UTF_8));
  }
  private static Path file(Path root, String name, String content) throws IOException {
    Path p = root.resolve(name); Files.write(p, content.getBytes(StandardCharsets.UTF_8)); return p;
  }
  private static Path dir(Path root, String name, String content) throws IOException {
    Path p = root.resolve(name); Files.createDirectories(p); Files.write(p.resolve("value"), content.getBytes(StandardCharsets.UTF_8)); return p;
  }

  private static final class FailingOps implements RuntimeCommit.FileOps {
    private final RuntimeCommit.FileOps delegate;
    boolean failWrites;
    String failDeletesContaining;
    Path failNextMoveTo;
    FailingOps(RuntimeCommit.FileOps delegate) { this.delegate = delegate; }
    @Override public boolean exists(Path p) { return delegate.exists(p); }
    @Override public void move(Path from, Path to) throws IOException {
      if (to.equals(failNextMoveTo)) {
        failNextMoveTo = null;
        throw new IOException("injected move failure");
      }
      delegate.move(from, to);
    }
    @Override public void delete(Path p) throws IOException {
      if (failDeletesContaining != null && p.toString().contains(failDeletesContaining))
        throw new IOException("injected delete failure");
      delegate.delete(p);
    }
    @Override public void writeMarker(Path p, String value) throws IOException {
      if (failWrites) throw new IOException("injected marker write failure");
      delegate.writeMarker(p, value);
    }
  }
}
