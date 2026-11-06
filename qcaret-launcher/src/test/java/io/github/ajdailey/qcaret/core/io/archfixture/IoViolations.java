package io.github.ajdailey.qcaret.core.io.archfixture;

import java.io.IOException;
import java.nio.channels.FileChannel;

/** I/O classes that each break one architecture rule; never instantiated. */
final class IoViolations {
  private IoViolations() {}

  /** Takes a POSIX record lock on Linux and macOS, which any closing channel drops. */
  static final class Locks {
    void take(FileChannel channel) throws IOException {
      channel.tryLock();
      channel.lock();
    }
  }

  /** Blocks under a monitor, which pins a virtual thread. */
  static final class Synchronized {
    synchronized void write() {
      // Mock body.
    }
  }
}
