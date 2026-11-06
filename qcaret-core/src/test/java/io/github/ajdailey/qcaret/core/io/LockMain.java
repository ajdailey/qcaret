package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;
import java.nio.file.Path;

/** A child process for {@code ProcessLockTest}: tries a lock, says what it got and blocks. */
public final class LockMain {
  static final String HOLDING = "holding";
  static final String BUSY = "busy";

  private LockMain() {}

  /**
   * Tries the lock.
   *
   * @param args the lock file
   * @throws IOException when the lock fails
   * @throws InterruptedException never in practice; the test kills the process
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    ProcessLock lock = ProcessLock.tryAcquire(Path.of(args[0]));
    System.out.println(lock == null ? BUSY : HOLDING);
    System.out.flush();
    Thread.sleep(Long.MAX_VALUE);
  }
}
