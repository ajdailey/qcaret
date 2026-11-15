package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;

/**
 * Caret moves within the text: by char, by grapheme cluster ({@link Clusters}) and to a line's
 * ends. A CR LF and a surrogate pair are one char step.
 */
public final class Moves {
  private Moves() {}

  /**
   * Returns the offset one char left.
   *
   * @param text the text
   * @param offset the start
   * @return the offset, 0 at the start
   */
  public static int left(TextContent text, int offset) {
    if (offset <= 0) {
      return 0;
    }
    if (offset >= 2) {
      char c = text.charAt(offset - 1);
      char p = text.charAt(offset - 2);
      if ((c == '\n' && p == '\r')
          || (Character.isLowSurrogate(c) && Character.isHighSurrogate(p))) {
        return offset - 2;
      }
    }
    return offset - 1;
  }

  /**
   * Returns the offset one char right.
   *
   * @param text the text
   * @param offset the start
   * @return the offset, the length at the end
   */
  public static int right(TextContent text, int offset) {
    int length = text.length();
    if (offset >= length) {
      return length;
    }
    if (offset + 1 < length) {
      char c = text.charAt(offset);
      char n = text.charAt(offset + 1);
      if ((c == '\r' && n == '\n')
          || (Character.isHighSurrogate(c) && Character.isLowSurrogate(n))) {
        return offset + 2;
      }
    }
    return offset + 1;
  }

  /**
   * Returns the cluster boundary left of an offset, where Left takes a caret.
   *
   * @param text the text
   * @param offset the start
   * @return the offset, 0 at the start
   */
  public static int clusterLeft(TextContent text, int offset) {
    if (offset <= 0) {
      return 0;
    }
    return Math.max(0, new Clusters(text, offset, 0, text.length()).preceding(offset));
  }

  /**
   * Returns the cluster boundary right of an offset, where Right takes a caret.
   *
   * @param text the text
   * @param offset the start
   * @return the offset, the length at the end
   */
  public static int clusterRight(TextContent text, int offset) {
    int length = text.length();
    if (offset >= length) {
      return length;
    }
    int at = new Clusters(text, offset, 0, length).following(offset);
    return at < 0 ? length : at;
  }

  /**
   * Returns where Home goes: the line's first non-blank char, or the line start when the offset is
   * already there or the line is blank.
   *
   * @param text the text
   * @param offset the caret
   * @return the offset
   */
  public static int home(TextContent text, int offset) {
    int line = text.lineAt(offset);
    int start = text.offsetOfLine(line);
    int end = Lines.end(text, line);
    int first = start;
    while (first < end && (text.charAt(first) == ' ' || text.charAt(first) == '\t')) {
      first++;
    }
    return first == offset || first == end ? start : first;
  }

  /**
   * Returns where End goes: the end of the line, before its break.
   *
   * @param text the text
   * @param offset the caret
   * @return the offset
   */
  public static int end(TextContent text, int offset) {
    return Lines.end(text, text.lineAt(offset));
  }
}
