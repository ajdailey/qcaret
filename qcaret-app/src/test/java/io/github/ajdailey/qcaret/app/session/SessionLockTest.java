package io.github.ajdailey.qcaret.app.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** The session file's lock between processes and within one JVM. */
class SessionLockTest {
  private static final long BOUND_SECONDS = 60;

  @TempDir Path temp;

  private SettingsLocation location() {
    return new SettingsLocation(SettingsMode.EXPLICIT, temp);
  }

  /** Windows releases a killed process's locks shortly after it ends. */
  private SessionLock awaitLock() throws IOException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
    SessionLock lock = SessionLock.acquire(location());
    while (lock == null && System.nanoTime() < deadline) {
      lock = SessionLock.acquire(location());
    }
    return lock;
  }

  @Test
  void aChildHoldingTheLockKeepsItFromThisProcessUntilItEnds() throws Exception {
    try (SessionLockMain.Child child = new SessionLockMain.Child(temp)) {
      assertEquals(SessionLockMain.HOLDING, child.said());
      assertNull(SessionLock.acquire(location()));
    }
    try (SessionLock lock = awaitLock()) {
      assertNotNull(lock);
    }
  }

  @Test
  void aChildIsRefusedWhileThisProcessHoldsTheLock() throws Exception {
    try (SessionLock lock = SessionLock.acquire(location())) {
      assertNotNull(lock);
      try (SessionLockMain.Child child = new SessionLockMain.Child(temp)) {
        assertEquals(SessionLockMain.BUSY, child.said());
      }
    }
  }

  @Test
  void aSecondAcquireInTheJvmIsNullUntilClose() throws Exception {
    SessionLock first = SessionLock.acquire(location());
    assertNotNull(first);
    assertNull(SessionLock.acquire(location()));
    first.close();
    try (SessionLock second = SessionLock.acquire(location())) {
      assertNotNull(second);
    }
  }

  @Test
  void theHeldFileIsRefusedAsAUserFile() throws Exception {
    final Path file = temp.resolve(SessionLock.FILE);
    try (SessionLock lock = SessionLock.acquire(location())) {
      assertNotNull(lock);
      IOException refused =
          assertThrows(
              IOException.class,
              new Executable() {
                @Override
                public void execute() throws IOException {
                  SafePaths.userFile(file);
                }
              });
      assertEquals(UiText.SESSION_LOCK_REFUSED.text(), refused.getMessage());
    }
    assertEquals(file.toRealPath(), SafePaths.userFile(file));
  }

  @Test
  void aPerUserDirectoryIsCreatedFirst() throws Exception {
    Path directory = temp.resolve("missing").resolve("qcaret");
    Files.createDirectories(directory.getParent());
    try (SessionLock lock =
        SessionLock.acquire(new SettingsLocation(SettingsMode.PER_USER, directory))) {
      assertNotNull(lock);
      assertTrue(Files.exists(directory.resolve(SessionLock.FILE)));
    }
  }

  @Test
  void aMissingExplicitDirectoryFails() {
    final Path directory = temp.resolve("missing");
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            SessionLock.acquire(new SettingsLocation(SettingsMode.EXPLICIT, directory));
          }
        });
  }
}
