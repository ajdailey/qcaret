package io.github.ajdailey.qcaret.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether a start takes the session and opens its windows, with {@code session.restore}, and
 * whether it owns the session.
 */
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
      {SessionMode.DEFAULT, true, true, true, true},
      {SessionMode.DEFAULT, true, false, true, false},
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

  /** Each row: request, session lock held elsewhere; owns the session. */
  @Test
  void aStartWithFilesOwnsTheSessionWhenNoOtherInstanceHoldsIt() {
    List<FileRequest> files = List.of(new FileRequest(Path.of("mock.txt")));
    Path left = Path.of("left.txt");
    Path right = Path.of("right.txt");
    Object[][] rows = {
      {new StartupRequest(files, null, null, null, null, null, false), false, true},
      {new StartupRequest(files, null, null, null, null, null, false), true, false},
      {
        new StartupRequest(List.<FileRequest>of(), null, null, null, null, null, false), false, true
      },
      {new StartupRequest(files, null, null, null, null, null, true), false, false},
      {request(SessionMode.SKIP, true), false, false},
      {request(SessionMode.RESTORE, true), true, false},
      {
        new StartupRequest(
            List.<FileRequest>of(),
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            SessionMode.DEFAULT,
            left,
            right),
        false,
        false
      },
    };
    for (int i = 0; i < rows.length; i++) {
      StartupRequest request = (StartupRequest) rows[i][0];
      assertEquals(rows[i][2], request.ownsSession((Boolean) rows[i][1]), "row " + i);
    }
  }
}
