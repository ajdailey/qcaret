package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;

/**
 * Word boundaries: a word is a run of letters, digits and {@code _}. Caret moves treat a run of
 * other non-blank chars as a word too, and stop at every line break.
 */
public final class Words {
  private static final int WORD = 0;
  private static final int BLANK = 1;
  private static final int BREAK = 2;
  private static final int OTHER = 3;

  private Words() {}

  /**
   * Returns the word an offset touches, the one after it when it touches two.
   *
   * @param text the text
   * @param offset an offset from 0 to the text length
   * @return {start, end}, or an empty array when no word touches it
   */
  public static int[] at(TextContent text, int offset) {
    int start = offset;
    int end = offset;
    if (offset < text.length() && isWord(text.charAt(offset))) {
      end++;
    } else if (offset > 0 && isWord(text.charAt(offset - 1))) {
      start--;
    } else {
      return new int[0];
    }
    while (start > 0 && isWord(text.charAt(start - 1))) {
      start--;
    }
    while (end < text.length() && isWord(text.charAt(end))) {
      end++;
    }
    return new int[] {start, end};
  }

  /**
   * Returns where a word move right ends: past the rest of the run at the offset and the blanks
   * after it; at a line break, past the break.
   *
   * @param text the text
   * @param offset the start
   * @return the end offset
   */
  public static int right(TextContent text, int offset) {
    int length = text.length();
    if (offset >= length) {
      return length;
    }
    char c = text.charAt(offset);
    if (c == '\r' || c == '\n') {
      return Moves.right(text, offset);
    }
    int at = offset;
    int kind = kind(c);
    while (at < length && kind != BLANK && kind(text.charAt(at)) == kind) {
      at++;
    }
    while (at < length && kind(text.charAt(at)) == BLANK) {
      at++;
    }
    return at;
  }

  /**
   * Returns where a word move left ends: before the blanks left of the offset and the run before
   * them; at a line start, before the break.
   *
   * @param text the text
   * @param offset the start
   * @return the end offset
   */
  public static int left(TextContent text, int offset) {
    if (offset <= 0) {
      return 0;
    }
    char c = text.charAt(offset - 1);
    if (c == '\r' || c == '\n') {
      return Moves.left(text, offset);
    }
    int at = offset;
    while (at > 0 && kind(text.charAt(at - 1)) == BLANK) {
      at--;
    }
    if (at == 0 || isBreak(text.charAt(at - 1))) {
      return at;
    }
    int kind = kind(text.charAt(at - 1));
    while (at > 0 && kind(text.charAt(at - 1)) == kind) {
      at--;
    }
    return at;
  }

  private static int kind(char c) {
    if (isWord(c)) {
      return WORD;
    }
    if (c == ' ' || c == '\t') {
      return BLANK;
    }
    return isBreak(c) ? BREAK : OTHER;
  }

  private static boolean isBreak(char c) {
    return c == '\r' || c == '\n';
  }

  static boolean isWord(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }
}
