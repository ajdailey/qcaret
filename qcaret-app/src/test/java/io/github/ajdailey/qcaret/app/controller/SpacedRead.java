package io.github.ajdailey.qcaret.app.controller;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * A poll condition that reads a file a background save replaces, run at most once per {@link #GAP}.
 * On Windows an open handle on the target refuses the replace, delete sharing included, and {@code
 * AtomicFiles} retries the move for a quarter of a second; a read on every poll can refuse every
 * try and fail the save the test waits for.
 */
final class SpacedRead implements BooleanSupplier {
  /** Longer than all tries of one move, so one move meets at most one read. */
  static final Duration GAP = Duration.ofMillis(300);

  private final BooleanSupplier read;
  private long next = System.nanoTime();

  SpacedRead(BooleanSupplier read) {
    this.read = read;
  }

  @Override
  public boolean getAsBoolean() {
    long now = System.nanoTime();
    if (now - next < 0) {
      return false;
    }
    next = now + GAP.toNanos();
    return read.getAsBoolean();
  }
}
