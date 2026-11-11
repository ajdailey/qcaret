package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.io.ProcessLock;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The lock on {@value #FILE}: only its holder writes {@value SessionStore#FILE}. The instance that
 * restores the session holds it until the process ends; any other instance takes it for one
 * rewrite. While it is held, {@link SafePaths#holdLock} refuses every open and save of the file: a
 * save replaces the file, the lock stays on the old one, and the next claim takes the new one.
 */
public final class SessionLock implements AutoCloseable {
  /** The file name in the settings directory. */
  public static final String FILE = "qcaret_session.lock";

  /** How long {@link #acquire} waits out a holder; a rewrite holds the lock for milliseconds. */
  public static final Duration WAIT = Duration.ofSeconds(1);

  private static final long POLL_MILLIS = 50;

  private final Path file;
  private final ProcessLock lock;

  private SessionLock(Path file, ProcessLock lock) {
    this.file = file;
    this.lock = lock;
  }

  /**
   * Takes the lock, polling for up to {@link #WAIT}. Blocking.
   *
   * @param location the settings directory, created first in per-user mode
   * @return the lock, or null when another holder kept it for the whole wait or the thread was
   *     interrupted
   * @throws IOException when the file cannot be created or locked
   */
  public static SessionLock acquire(SettingsLocation location) throws IOException {
    Path dir = location.directory();
    if (location.mode() == SettingsMode.PER_USER && !Files.isDirectory(dir)) {
      SafePaths.settingsDirectory(dir);
    }
    Path file = SafePaths.child(dir, FILE);
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (true) {
      ProcessLock taken = ProcessLock.tryAcquire(file);
      if (taken != null) {
        try {
          SafePaths.holdLock(file, UiText.SESSION_LOCK_REFUSED.text());
        } catch (IOException e) {
          taken.close();
          throw e;
        }
        return new SessionLock(file, taken);
      }
      if (System.nanoTime() >= deadline) {
        return null;
      }
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
  }

  /**
   * Releases the lock; a second call does nothing.
   *
   * @throws IOException when the release fails
   */
  @Override
  public void close() throws IOException {
    SafePaths.releaseLock(file);
    lock.close();
  }
}
