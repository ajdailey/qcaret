package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorExitMarkerTest {
  @TempDir Path temp;

  @Test
  void writeCreatesAPerUserDirectoryThenMarkShownAndClear() {
    Path dir = temp.resolve("per-user");
    ErrorExitMarker marker = new ErrorExitMarker(new SettingsLocation(SettingsMode.PER_USER, dir));
    assertEquals(ErrorExitMarker.State.ABSENT, marker.read());
    assertTrue(marker.write());
    assertTrue(Files.isRegularFile(dir.resolve(ErrorExitMarker.FILE)));
    assertEquals(ErrorExitMarker.State.PENDING, marker.read());
    assertTrue(marker.markShown());
    assertEquals(ErrorExitMarker.State.SHOWN, marker.read());
    assertTrue(marker.write());
    assertEquals(ErrorExitMarker.State.PENDING, marker.read());
    marker.clear();
    assertEquals(ErrorExitMarker.State.ABSENT, marker.read());
    marker.clear();
  }

  @Test
  void otherContentReadsAsPending() throws Exception {
    Files.writeString(
        temp.resolve(ErrorExitMarker.FILE), "noticeShownLater\n", StandardCharsets.US_ASCII);
    ErrorExitMarker marker = new ErrorExitMarker(new SettingsLocation(SettingsMode.EXPLICIT, temp));
    assertEquals(ErrorExitMarker.State.PENDING, marker.read());
  }

  @Test
  void aFailedWriteReturnsFalse() throws Exception {
    Path notDirectory = Files.createFile(temp.resolve("file"));
    ErrorExitMarker marker =
        new ErrorExitMarker(new SettingsLocation(SettingsMode.EXPLICIT, notDirectory));
    assertFalse(marker.write());
    assertEquals(ErrorExitMarker.State.ABSENT, marker.read());
  }
}
