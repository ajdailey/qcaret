package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.buffer.TextCursor;
import io.github.ajdailey.qcaret.core.text.Modeline;
import io.github.ajdailey.qcaret.core.text.Modelines;

/** Reads the modelines of a document's text: {@link Modelines#parse} over its two ends. */
public final class TextModelines {
  /** Most chars of the first lines read. */
  static final int HEAD_CHARS = 64 * 1024;

  private TextModelines() {}

  /**
   * Parses the modelines of a text. Reads at most {@value #HEAD_CHARS} chars of its first {@value
   * Modelines#LINES} lines and its last {@value Modelines#TAIL_CHARS} chars, so the cost does not
   * grow with the text.
   *
   * @param text the text
   * @return the settings, {@link Modeline#NONE} when there is no modeline
   */
  public static Modeline read(TextContent text) {
    int length = text.length();
    int lines = text.lineCount();
    int headEnd = lines > Modelines.LINES ? text.offsetOfLine(Modelines.LINES) : length;
    String head = copy(text, 0, Math.min(headEnd, HEAD_CHARS));
    String tail = copy(text, Math.max(0, length - Modelines.TAIL_CHARS), length);
    return Modelines.parse(head, tail);
  }

  private static String copy(TextContent text, int from, int to) {
    StringBuilder out = new StringBuilder(to - from);
    TextCursor cursor = text.cursor(from);
    for (int i = from; i < to; i++) {
      out.append(cursor.next());
    }
    return out.toString();
  }
}
