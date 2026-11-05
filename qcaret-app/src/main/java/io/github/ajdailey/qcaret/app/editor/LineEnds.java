package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.text.LineEnding;

/** The break that ends a line, for the line-ending marks. */
public final class LineEnds {
  private LineEnds() {}

  /**
   * Returns the break after a line.
   *
   * @param document the document
   * @param line a zero-based line
   * @return the break, or null for the last line, which has none
   */
  public static LineEnding of(Document document, int line) {
    TextSnapshot text = document.text();
    if (line + 1 >= text.lineCount()) {
      return null;
    }
    int end = text.offsetOfLine(line) + text.lineLength(line);
    int next = text.offsetOfLine(line + 1);
    if (next - end == 2) {
      return LineEnding.CRLF;
    }
    return text.charAt(end) == '\r' ? LineEnding.CR : LineEnding.LF;
  }
}
