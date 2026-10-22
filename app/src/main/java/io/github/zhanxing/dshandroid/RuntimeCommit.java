package io.github.zhanxing.dshandroid;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Commits a prepared runtime and marker as one recoverable transaction.
 *
 * <p>Only operations through the marker move are the commit. Backup deletion is
 * deliberately best effort and is never allowed to undo a successful commit.
 * A crash can therefore leave runtime.previous-* or runtime.sha256.previous-*;
 * those backups are intentionally not guessed at or deleted on the next launch.
 */
public final class RuntimeCommit {
  private RuntimeCommit() {}

  public interface CancelCheck { boolean cancelled(); }

  /** The file-system boundary, injectable so transaction failures are testable on the JVM. */
  public interface FileOps {
    boolean exists(Path path);
    void move(Path from, Path to) throws IOException;
    void delete(Path path) throws IOException;
    void writeMarker(Path path, String value) throws IOException;
  }

  public static final FileOps FILES = new FileOps() {
    @Override public boolean exists(Path path) { return Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS); }
    @Override public void move(Path from, Path to) throws IOException {
      Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    @Override public void delete(Path path) throws IOException { deleteTree(path); }
    @Override public void writeMarker(Path path, String value) throws IOException {
      Files.write(path, value.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
  };

  private static void deleteTree(Path path) throws IOException {
    if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
        for (Path entry : entries) deleteTree(entry);
      }
    }
    Files.deleteIfExists(path);
  }

  /** Result of a committed install. A non-null cleanupFailure is informational only. */
  public static final class Result {
    public final Path target;
    public final Exception cleanupFailure;
    private Result(Path target, Exception cleanupFailure) {
      this.target = target;
      this.cleanupFailure = cleanupFailure;
    }
  }

  public static Result commit(Path target, Path stage, Path marker,
      Path old, Path oldMarker, String expected, FileOps ops, CancelCheck cancelled)
      throws Exception {
    boolean movedTarget = false;
    boolean installed = false;
    boolean movedMarker = false;
    Path markerStage = marker.resolveSibling(marker.getFileName() + ".staging-" + System.nanoTime());
    try {
      check(cancelled);
      if (ops.exists(target)) {
        ops.move(target, old);
        movedTarget = true;
      }
      check(cancelled);
      ops.move(stage, target);
      installed = true;
      check(cancelled);
      if (ops.exists(marker)) {
        ops.move(marker, oldMarker);
        movedMarker = true;
      }
      check(cancelled);
      // Atomic move consumes markerStage. On failure rollback owns staging cleanup,
      // so cleanup exceptions cannot replace the original write/move failure.
      ops.writeMarker(markerStage, expected + "\n");
      ops.move(markerStage, marker);

      // Commit is complete once the new marker is in place. Cleanup cannot roll it back.
      Exception cleanupFailure = null;
      try { if (ops.exists(old)) ops.delete(old); }
      catch (Exception e) { cleanupFailure = e; }
      try { if (ops.exists(oldMarker)) ops.delete(oldMarker); }
      catch (Exception e) {
        if (cleanupFailure == null) cleanupFailure = e;
        else cleanupFailure.addSuppressed(e);
      }
      return new Result(target, cleanupFailure);
    } catch (Exception original) {
      // Roll back every component, retaining the original failure even if rollback fails.
      rollback(original, ops, stage, target, old, marker, oldMarker, markerStage,
          installed, movedTarget, movedMarker);
      throw original;
    }
  }

  private static void check(CancelCheck cancelled) throws IOException {
    if (cancelled != null && cancelled.cancelled()) throw new java.io.InterruptedIOException("安装已取消");
  }

  private static void rollback(Exception original, FileOps ops, Path stage, Path target,
      Path old, Path marker, Path oldMarker, Path markerStage, boolean installed,
      boolean movedTarget, boolean movedMarker) {
    attempt(original, () -> { if (ops.exists(stage)) ops.delete(stage); });
    attempt(original, () -> { if (ops.exists(markerStage)) ops.delete(markerStage); });
    if (installed) attempt(original, () -> { if (ops.exists(target)) ops.delete(target); });
    if (movedTarget) attempt(original, () -> { if (ops.exists(old)) ops.move(old, target); });
    // Before moving the old marker it still belongs to the previous runtime.
    // Never delete it merely because cancellation happened at an earlier checkpoint.
    if (movedMarker) {
      attempt(original, () -> { if (ops.exists(marker)) ops.delete(marker); });
      attempt(original, () -> { if (ops.exists(oldMarker)) ops.move(oldMarker, marker); });
    }
  }

  private interface Action { void run() throws Exception; }
  private static void attempt(Exception original, Action action) {
    try { action.run(); }
    catch (Exception rollbackFailure) { original.addSuppressed(rollbackFailure); }
  }
}
