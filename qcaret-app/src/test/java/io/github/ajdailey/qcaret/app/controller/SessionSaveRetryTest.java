package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A timed session save whose write fails: a directory at the session file fails every write until
 * the test deletes it. The retry arms the timer through {@code ManualUiDispatcher}, which fails a
 * {@code timerExec} off the test thread.
 */
class SessionSaveRetryTest {
  @TempDir Path temp;

  private Path file;
  private Harness harness;
  private SessionController session;
  private String remembered;

  @BeforeEach
  void create() throws Exception {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    file = settings.resolve(SessionStore.FILE);
    harness = new Harness(temp);
    SessionStore store = store();
    harness.application.startSession(store, store.load(), true);
    session = harness.application.sessionController();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !session.restoring() && Files.isRegularFile(file);
          }
        },
        "the first save");
    harness.awaitIdle("restore");
    remembered = temp.resolve("mock.txt").toString();
  }

  @AfterEach
  void close() throws Exception {
    if (Files.isDirectory(file)) {
      Files.delete(file);
    }
    if (!harness.view.disposed) {
      harness.close();
    }
  }

  private SessionStore store() {
    return new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, file.getParent()));
  }

  /** A save may land between the two steps; the directory then fails every later one. */
  private void replaceWithDirectory() throws IOException {
    for (int attempt = 0; attempt < 1000; attempt++) {
      Files.deleteIfExists(file);
      try {
        Files.createDirectory(file);
        return;
      } catch (FileAlreadyExistsException e) {
        Thread.onSpinWait();
      }
    }
    throw new IOException("A save kept replacing the file");
  }

  /**
   * Fails the writes, makes a change and runs the UI until the timer is armed past the short
   * delays, which only a retry after several failed saves does.
   */
  private void awaitLongRetry() throws Exception {
    replaceWithDirectory();
    session.remember(new Session.FileState(remembered, "", "", 4, false, "", 0));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return session.armed() && harness.ui.queued() == 0;
          }
        },
        "a retry armed as a long timer");
  }

  private void awaitSaved() throws InterruptedException {
    harness.await(
        new SpacedRead(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                for (Session.FileState state : store().load().files()) {
                  if (state.path().equals(remembered)) {
                    return true;
                  }
                }
                return false;
              }
            }),
        "the change in the file");
  }

  /** Runs the retry, whose write fails again, and waits until its worker ended. */
  private void runFailingRetry(Runnable meanwhile) throws InterruptedException {
    harness.ui.runLongTimers();
    meanwhile.run();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.workers.liveThreadNames().contains("qcaret-lifecycle-");
          }
        },
        "the retry's worker ended");
    harness.ui.runPending();
  }

  @Test
  void aFailedTimedSaveReachesTheFileWithoutAFurtherChange() throws Exception {
    awaitLongRetry();
    Files.delete(file);
    harness.ui.runLongTimers();
    awaitSaved();
  }

  @Test
  void aRetryAfterCloseArmsNothing() throws Exception {
    awaitLongRetry();
    runFailingRetry(
        new Runnable() {
          @Override
          public void run() {
            session.close(false);
          }
        });
    assertFalse(session.armed());
  }

  @Test
  void aRetryDuringAQuitHoldArmsNothingAndACancelSaves() throws Exception {
    awaitLongRetry();
    runFailingRetry(
        new Runnable() {
          @Override
          public void run() {
            session.quitStarted();
          }
        });
    assertFalse(session.armed());
    Files.delete(file);
    session.quitCanceled();
    awaitSaved();
  }
}
