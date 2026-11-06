package io.github.ajdailey.qcaret.app.recovery;

import io.github.ajdailey.qcaret.core.document.LoadReservation;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Reads the text of a recovery file for assertions. */
public final class RecoveryTexts {
  private RecoveryTexts() {}

  /**
   * Returns a loaded file's text; the load's builder is used up.
   *
   * @param loaded the load
   * @return the text
   */
  public static String of(RecoveryFile.Loaded loaded) {
    TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, false);
    return loaded.text().build(encoding, LineEnding.LF, UndoLimits.DEFAULTS).text().toString();
  }

  /**
   * Reads a file's text.
   *
   * @param file the file
   * @return the text
   * @throws IOException when the file cannot be read
   */
  public static String read(Path file) throws IOException {
    return of(RecoveryFile.load(file, LoadReservation.UNMETERED));
  }
}
