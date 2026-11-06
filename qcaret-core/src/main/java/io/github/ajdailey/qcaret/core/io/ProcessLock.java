package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An exclusive lock on a file that the OS releases when the process dies. Linux and macOS use
 * {@code flock(2)} through {@link Flock}, Windows uses {@link FileChannel#tryLock}. A second
 * acquire of a held file gets null, in another process or this one, and reading the file or closing
 * another channel on it leaves the lock held.
 *
 * <p>POSIX record locks ({@code FileChannel.tryLock} on Linux and macOS) fail both of those: any
 * channel closing on the file drops the process's lock. Do not fall back to them when {@code flock}
 * is unavailable: on local Linux the two kinds do not conflict, so two launches on different paths
 * could both hold the file. {@link #tryAcquire} throws instead.
 */
public final class ProcessLock implements AutoCloseable {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

  /** What linking {@link Flock} gave, once per process; never loaded on Windows. */
  private static final class Linked {
    private static final Flock FLOCK;
    private static final IOException FAILURE;

    static {
      Flock linked = null;
      IOException failure = null;
      try {
        linked =
            Flock.bind(Flock.FOREIGN, Flock.Constants.of(System.getProperty("os.name", "unknown")));
      } catch (IOException e) {
        failure = e;
      }
      FLOCK = linked;
      FAILURE = failure;
    }

    private Linked() {}
  }

  private final AtomicBoolean closed = new AtomicBoolean();
  private final Flock flock;
  private final int descriptor;
  private final FileChannel channel;
  private final FileLock lock;

  private ProcessLock(Flock flock, int descriptor, FileChannel channel, FileLock lock) {
    this.flock = flock;
    this.descriptor = descriptor;
    this.channel = channel;
    this.lock = lock;
  }

  /**
   * Takes the lock without waiting, creating the file when it is missing.
   *
   * @param file the lock file
   * @return the lock, or null when it is held
   * @throws IOException when the file cannot be opened or locked, or {@code flock} is unavailable
   *     on this JDK
   */
  public static ProcessLock tryAcquire(Path file) throws IOException {
    if (WINDOWS) {
      return onChannel(file);
    }
    if (Linked.FAILURE != null) {
      throw new IOException(Linked.FAILURE.getMessage(), Linked.FAILURE);
    }
    return withFlock(file, Linked.FLOCK);
  }

  /** Takes the lock through {@code flock}. */
  static ProcessLock withFlock(Path file, Flock flock) throws IOException {
    int descriptor = flock.acquire(file);
    return descriptor == Flock.HELD ? null : new ProcessLock(flock, descriptor, null, null);
  }

  /** Takes the lock through {@link FileChannel#tryLock}. */
  static ProcessLock onChannel(Path file) throws IOException {
    FileChannel opened =
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock taken = null;
    try {
      taken = opened.tryLock();
    } catch (OverlappingFileLockException e) {
      taken = null;
    } finally {
      if (taken == null) {
        opened.close();
      }
    }
    return taken == null ? null : new ProcessLock(null, 0, opened, taken);
  }

  /**
   * Releases the lock; a second call does nothing. Callable from any thread.
   *
   * @throws IOException when the release fails
   */
  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (flock != null) {
      flock.release(descriptor);
      return;
    }
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}
