package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.text.FileSettings;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaveCleanupTest {
  private static FileSettings settings(boolean trim, boolean newline) {
    return new FileSettings(0, null, null, null, trim, newline, List.of(), null);
  }

  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  private static String cleaned(String text, boolean trim, boolean newline, LineEnding ending) {
    Document d = document(text);
    Edit edit =
        SaveCleanup.edits(d.text(), settings(trim, newline), ending, new CancellationToken());
    if (edit != null) {
      d.apply(edit.batch());
    }
    return d.text().toString();
  }

  /** Rows: text, trim, final newline, ending, result. */
  @Test
  void cleanupTable() {
    Object[][] rows = {
      {"a \nb\t\n", true, false, LineEnding.LF, "a\nb\n"},
      {"a \r\nb \r", true, false, LineEnding.LF, "a\r\nb\r"},
      {"a", false, true, LineEnding.CRLF, "a\r\n"},
      {"a  ", true, true, LineEnding.LF, "a\n"},
      {"a\n  ", true, true, LineEnding.LF, "a\n"},
      {"   ", true, true, LineEnding.LF, ""},
      {"", true, true, LineEnding.LF, ""},
      {"a \n", false, false, LineEnding.LF, "a \n"},
      {"a\r", false, true, LineEnding.LF, "a\r"},
    };
    for (Object[] row : rows) {
      assertEquals(
          row[4],
          cleaned((String) row[0], (Boolean) row[1], (Boolean) row[2], (LineEnding) row[3]),
          (String) row[0]);
    }
  }

  @Test
  void aCleanTextNeedsNoEdit() {
    assertNull(
        SaveCleanup.edits(
            document("a\n").text(), settings(true, true), LineEnding.LF, new CancellationToken()));
  }
}
