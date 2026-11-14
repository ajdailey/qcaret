package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.ErrorExitMarker;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorExitStartTest {
  @TempDir Path temp;

  private ErrorExitMarker marker() {
    return new ErrorExitMarker(new SettingsLocation(SettingsMode.EXPLICIT, temp));
  }

  private ErrorExitStart begin(boolean restore) {
    return ErrorExitStart.begin(marker(), restore, temp, 42);
  }

  @Test
  void withoutTheMarkerNothingChanges() {
    assertEquals(new ErrorExitStart(false, false), begin(true));
    assertEquals(new ErrorExitStart(false, false), begin(false));
    assertEquals(ErrorExitMarker.State.ABSENT, marker().read());
  }

  @Test
  void aRestoringStartSkipsShowsTheNoticeAndDeletesTheMarker() {
    marker().write();
    assertEquals(new ErrorExitStart(true, true), begin(true));
    assertEquals(ErrorExitMarker.State.ABSENT, marker().read());
    assertEquals(new ErrorExitStart(false, false), begin(true));
  }

  @Test
  void aStartWithoutTheSessionShowsTheNoticeOnceAndARestoringStartStillSkips() {
    marker().write();
    assertEquals(new ErrorExitStart(false, true), begin(false));
    assertEquals(ErrorExitMarker.State.SHOWN, marker().read());
    assertEquals(new ErrorExitStart(false, false), begin(false));
    assertEquals(new ErrorExitStart(true, false), begin(true));
    assertEquals(ErrorExitMarker.State.ABSENT, marker().read());
  }

  @Test
  void aSkippingStartMovesTheSnapshotsToRecovery() throws Exception {
    Path snapshot = Files.createDirectory(temp.resolve(RecoveryStore.SNAPSHOT));
    Files.writeString(snapshot.resolve("b1.qcr"), "mock");
    marker().write();
    begin(false);
    assertTrue(Files.exists(snapshot.resolve("b1.qcr")));
    begin(true);
    assertFalse(Files.exists(snapshot.resolve("b1.qcr")));
    assertTrue(
        Files.exists(
            temp.resolve(RecoveryStore.DIRECTORY).resolve("snapshot-42").resolve("b1.qcr")));
  }
}
