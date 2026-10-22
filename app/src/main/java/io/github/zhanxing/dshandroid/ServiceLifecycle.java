package io.github.zhanxing.dshandroid;

/** Pure lifecycle predicates kept deterministic for JVM regression tests. */
final class ServiceLifecycle {
  private ServiceLifecycle() {}

  static boolean shouldStart(boolean stopping, boolean starting, boolean processAlive) {
    return stopping || (!starting && !processAlive);
  }

  static boolean cancelled(long ownedGeneration, long currentGeneration,
      boolean stopRequested, boolean interrupted) {
    return stopRequested || ownedGeneration != currentGeneration || interrupted;
  }

  static boolean stopOwnsProcess(long stopGeneration, long currentGeneration,
      long processGeneration, boolean destroying) {
    return destroying || (stopGeneration <= currentGeneration
        && processGeneration <= stopGeneration);
  }
}
