package io.github.ajdailey.qcaret.core.document;

/**
 * Visual columns: a tab advances to the next multiple of the tab width, an East Asian wide or
 * fullwidth code point takes 2 columns, every other code point 1. The wide ranges are the main
 * blocks of Unicode's East Asian Width W and F classes (Hangul Jamo, CJK, Hangul syllables,
 * compatibility ideographs, fullwidth forms, emoji); an ambiguous-width char counts 1.
 */
public final class Columns {
  private Columns() {}

  /**
   * Returns the columns a code point takes outside a tab.
   *
   * @param codePoint the code point
   * @return 1 or 2
   */
  public static int width(int codePoint) {
    boolean wide =
        (codePoint >= 0x1100 && codePoint <= 0x115f)
            || (codePoint >= 0x2e80 && codePoint <= 0x303e)
            || (codePoint >= 0x3041 && codePoint <= 0x33ff)
            || (codePoint >= 0x3400 && codePoint <= 0x4dbf)
            || (codePoint >= 0x4e00 && codePoint <= 0x9fff)
            || (codePoint >= 0xa000 && codePoint <= 0xa4cf)
            || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
            || (codePoint >= 0xf900 && codePoint <= 0xfaff)
            || (codePoint >= 0xfe30 && codePoint <= 0xfe4f)
            || (codePoint >= 0xff00 && codePoint <= 0xff60)
            || (codePoint >= 0xffe0 && codePoint <= 0xffe6)
            || (codePoint >= 0x1f300 && codePoint <= 0x1f64f)
            || (codePoint >= 0x1f900 && codePoint <= 0x1f9ff)
            || (codePoint >= 0x20000 && codePoint <= 0x3fffd);
    return wide ? 2 : 1;
  }

  /**
   * Returns the visual column of an index in a line.
   *
   * @param line the line's text without its break
   * @param index an index from 0 to the line length
   * @param tabWidth columns per tab stop, at least 1
   * @return the column
   */
  public static int column(CharSequence line, int index, int tabWidth) {
    int column = 0;
    int i = 0;
    while (i < index) {
      char c = line.charAt(i);
      if (c == '\t') {
        column += tabWidth - column % tabWidth;
        i++;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < line.length()
          && Character.isLowSurrogate(line.charAt(i + 1))) {
        column += width(Character.toCodePoint(c, line.charAt(i + 1)));
        i += 2;
      } else {
        column += width(c);
        i++;
      }
    }
    return column;
  }

  /**
   * Returns the index in a line at a visual column. A column inside a tab or a wide char gives the
   * index before it, or after it when {@code roundUp}; a column past the end gives the line length.
   *
   * @param line the line's text without its break
   * @param column the visual column, at least 0
   * @param tabWidth columns per tab stop, at least 1
   * @param roundUp true to take the char that covers {@code column} into the left part
   * @return an index from 0 to the line length
   */
  public static int index(CharSequence line, int column, int tabWidth, boolean roundUp) {
    int at = 0;
    int i = 0;
    int length = line.length();
    while (i < length && at < column) {
      char c = line.charAt(i);
      int step = 1;
      int width;
      if (c == '\t') {
        width = tabWidth - at % tabWidth;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < length
          && Character.isLowSurrogate(line.charAt(i + 1))) {
        width = width(Character.toCodePoint(c, line.charAt(i + 1)));
        step = 2;
      } else {
        width = width(c);
      }
      if (at + width > column && !roundUp) {
        return i;
      }
      at += width;
      i += step;
    }
    return i;
  }
}
