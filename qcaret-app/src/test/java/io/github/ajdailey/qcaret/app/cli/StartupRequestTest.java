package io.github.ajdailey.qcaret.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Whether a start takes the session and opens its windows, with {@code session.restore}. */
class StartupRequestTest {
  private static StartupRequest request(SessionMode mode, boolean file) {
    List<FileRequest> files =
        file ? List.of(new FileRequest(Path.of("mock.txt"))) : List.<FileRequest>of();
    return new StartupRequest(files, null, null, null, null, null, false, false, mode, null, null);
  }

  /** Each row: option, file named, preference; takes the session; opens its windows. */
  @Test
  void theOptionWinsOverThePreference() {
    Object[][] rows = {
      {SessionMode.DEFAULT, false, true, true, true},
      {SessionMode.DEFAULT, false, false, true, false},
      {SessionMode.DEFAULT, true, true, false, false},
      {SessionMode.RESTORE, false, false, true, true},
      {SessionMode.RESTORE, true, false, true, true},
      {SessionMode.SKIP, false, true, false, false},
    };
    for (Object[] row : rows) {
      StartupRequest request = request((SessionMode) row[0], (Boolean) row[1]);
      String name = row[0] + " file=" + row[1] + " preference=" + row[2];
      assertEquals(row[3], request.restoresSession(), name);
      assertEquals(row[4], request.restoresWindows((Boolean) row[2]), name);
    }
  }
}
