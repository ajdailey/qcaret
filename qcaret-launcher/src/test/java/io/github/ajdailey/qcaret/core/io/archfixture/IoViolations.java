package io.github.ajdailey.qcaret.core.io.archfixture;

/** I/O classes that each break one architecture rule; never instantiated. */
final class IoViolations {
  private IoViolations() {}

  /** Blocks under a monitor, which pins a virtual thread. */
  static final class Synchronized {
    synchronized void write() {
      // Mock body.
    }
  }
}
