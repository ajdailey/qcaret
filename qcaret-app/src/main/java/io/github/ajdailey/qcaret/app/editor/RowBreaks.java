package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.text.ControlChars;
import java.util.Arrays;

/**
 * Where a line's display rows start. A row ends where the next char would pass the wrap column,
 * after the last space or tab in the row when it has one, else before that char; independently, a
 * row never holds more than the row char limit or {@value #MAX_ROW_CONTROLS} {@link ControlChars}
 * control chars. Columns count from the row start, as the widget lays out each row on its own: a
 * tab advances to the next tab stop, a wide char and a control char take 2. A break never falls
 * between the two chars of a surrogate pair.
 */
public final class RowBreaks {
  /**
   * Control chars per row at most. {@code StyledText} measures each control char's box with a pass
   * over its row, so a paint costs boxes times row length: a row of 16,384 chars with 1,638 boxes
   * did not finish one paint in 120 s (GTK 3, measured).
   */
  public static final int MAX_ROW_CONTROLS = 128;

  /** No breaks: the line is one row. */
  static final int[] NONE = new int[0];

  private RowBreaks() {}

  /**
   * Computes a line's row breaks.
   *
   * @param line the line's text without its break
   * @param wrapColumns columns per row, or 0 for no wrap
   * @param maxRowChars chars per row at most, at least 2
   * @param tabWidth columns per tab stop, at least 1
   * @return the in-line offsets where the second and later rows start, ascending, each above 0 and
   *     below the line length; none for a line of at most {@code maxRowChars} chars and {@link
   *     #MAX_ROW_CONTROLS} control chars without wrap
   */
  public static int[] compute(CharSequence line, int wrapColumns, int maxRowChars, int tabWidth) {
    int length = line.length();
    if (wrapColumns <= 0 && length <= maxRowChars && length <= MAX_ROW_CONTROLS) {
      return NONE;
    }
    int[] out = NONE;
    int count = 0;
    int rowStart = 0;
    int column = 0;
    int lastSpace = -1;
    int controls = 0;
    int i = 0;
    while (i < length) {
      char c = line.charAt(i);
      int step = 1;
      int width;
      if (c == '\t') {
        width = tabWidth - column % tabWidth;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < length
          && Character.isLowSurrogate(line.charAt(i + 1))) {
        width = Columns.width(Character.toCodePoint(c, line.charAt(i + 1)));
        step = 2;
      } else {
        width = Columns.width(c);
      }
      boolean tooWide = wrapColumns > 0 && column + width > wrapColumns && i > rowStart;
      boolean tooLong = i - rowStart + step > maxRowChars && i > rowStart;
      boolean tooMany = controls == MAX_ROW_CONTROLS && ControlChars.boxed(c);
      if (tooWide || tooLong || tooMany) {
        int at = tooWide && lastSpace > rowStart ? lastSpace : i;
        if (count == out.length) {
          out = Arrays.copyOf(out, Math.max(4, count * 2));
        }
        out[count++] = at;
        rowStart = at;
        column = 0;
        lastSpace = -1;
        controls = 0;
        i = at;
        continue;
      }
      if (ControlChars.boxed(c)) {
        controls++;
      }
      column += width;
      i += step;
      if (c == ' ' || c == '\t') {
        lastSpace = i;
      }
    }
    return count == 0 ? NONE : Arrays.copyOf(out, count);
  }
}
