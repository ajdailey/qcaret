package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.ErrorExitMarker;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiFailuresTest {
  @TempDir Path temp;

  private long now;
  private int quits;

  private UiFailures failures() {
    return new UiFailures(
        new LongSupplier() {
          @Override
          public long getAsLong() {
            return now;
          }
        },
        new Runnable() {
          @Override
          public void run() {
            quits++;
          }
        },
        marker());
  }

  private ErrorExitMarker marker() {
    return new ErrorExitMarker(new SettingsLocation(SettingsMode.EXPLICIT, temp));
  }

  private static RuntimeException failure() {
    return new IllegalStateException("mock failure");
  }

  @Test
  void tenFailuresWithinOneSecondKeepRunning() {
    UiFailures failures = failures();
    for (int i = 0; i < UiFailures.LIMIT; i++) {
      failures.runtime(failure());
    }
    assertFalse(failures.failed());
    assertEquals(0, quits);
    assertEquals(ErrorExitMarker.State.ABSENT, marker().read());
  }

  @Test
  void theEleventhFailureWithinOneSecondQuitsOnce() {
    UiFailures failures = failures();
    for (int i = 0; i <= UiFailures.LIMIT; i++) {
      now += UiFailures.WINDOW_NANOS / UiFailures.LIMIT;
      failures.runtime(failure());
    }
    assertTrue(failures.failed());
    assertEquals(1, quits);
    assertEquals(ErrorExitMarker.State.PENDING, marker().read());
    failures.runtime(failure());
    failures.error(new Error("mock error"));
    assertEquals(1, quits);
  }

  @Test
  void failuresSpreadOverMoreThanOneSecondKeepRunning() {
    UiFailures failures = failures();
    for (int i = 0; i < 100; i++) {
      now += UiFailures.WINDOW_NANOS / UiFailures.LIMIT + 1;
      failures.runtime(failure());
    }
    assertFalse(failures.failed());
    assertEquals(0, quits);
  }

  @Test
  void anErrorQuits() {
    UiFailures failures = failures();
    failures.error(new Error("mock error"));
    assertTrue(failures.failed());
    assertEquals(1, quits);
    assertEquals(ErrorExitMarker.State.PENDING, marker().read());
  }

  @Test
  void aFailedMarkerWriteStillQuits() throws Exception {
    Files.createDirectory(temp.resolve(ErrorExitMarker.FILE));
    UiFailures failures = failures();
    failures.error(new Error("mock error"));
    assertTrue(failures.failed());
    assertEquals(1, quits);
  }

  @Test
  void aQuitThatThrowsIsMarkedFailedWithTheMarkerWritten() {
    UiFailures failures =
        new UiFailures(
            System::nanoTime,
            new Runnable() {
              @Override
              public void run() {
                quits++;
                throw new IllegalStateException("mock quit failure");
              }
            },
            marker());
    assertFalse(failures.quitFailed());
    failures.error(new Error("mock error"));
    assertTrue(failures.failed());
    assertTrue(failures.quitFailed());
    assertEquals(ErrorExitMarker.State.PENDING, marker().read());
    failures.error(new Error("mock error"));
    assertEquals(1, quits);
  }
}
