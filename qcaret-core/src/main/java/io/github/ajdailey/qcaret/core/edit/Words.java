package io.github.ajdailey.qcaret.core.edit;

/**
 * Word boundaries: a word is a run of letters, digits and {@code _}. Caret moves, double-click, Add
 * Next Occurrence and Delete Word treat a run of other non-blank chars as a word too, and stop at
 * every line break. The text is walked in clusters: a code point and the combining marks after it,
 * which take its kind, so neither a surrogate pair nor a base char and its marks is split. A mark
 * after a blank or a line break is a cluster of its own, not part of a word.
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
  public static int[] at(CharSequence text, int offset) {
    int length = text.length();
    if (!(offset < length && isWordAt(text, offset))
        && !(offset > 0 && isWordBefore(text, offset))) {
      return new int[0];
    }
    int start = offset;
    int end = offset;
    while (start > 0 && isWordBefore(text, start)) {
      start = clusterStart(text, start);
    }
    while (end < length && isWordAt(text, end)) {
      end = clusterEnd(text, end);
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
  public static int right(CharSequence text, int offset) {
    int length = text.length();
    if (offset >= length) {
      return length;
    }
    if (isBreak(text.charAt(offset))) {
      return breakEnd(text, offset);
    }
    int at = offset;
    int kind = kindAt(text, at);
    while (at < length && kind != BLANK && kindAt(text, at) == kind) {
      at = clusterEnd(text, at);
    }
    while (at < length && kindAt(text, at) == BLANK) {
      at = clusterEnd(text, at);
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
  public static int left(CharSequence text, int offset) {
    if (offset <= 0) {
      return 0;
    }
    if (isBreak(text.charAt(offset - 1))) {
      return breakStart(text, offset);
    }
    int at = offset;
    while (at > 0 && kindBefore(text, at) == BLANK) {
      at = clusterStart(text, at);
    }
    if (at == 0 || kindBefore(text, at) == BREAK) {
      return at;
    }
    int kind = kindBefore(text, at);
    while (at > 0 && kindBefore(text, at) == kind) {
      at = clusterStart(text, at);
    }
    return at;
  }

  /**
   * Reports whether the cluster holding the code point at an offset belongs to a word.
   *
   * @param text the text
   * @param offset an offset below the text length
   * @return true for a letter, digit, {@code _} or a mark on one
   */
  public static boolean isWordAt(CharSequence text, int offset) {
    return kindAt(text, offset) == WORD;
  }

  /**
   * Reports whether the cluster ending at an offset belongs to a word.
   *
   * @param text the text
   * @param offset an offset above 0
   * @return true for a letter, digit, {@code _} or a mark on one
   */
  public static boolean isWordBefore(CharSequence text, int offset) {
    return kindBefore(text, offset) == WORD;
  }

  /**
   * Reports whether a char can be part of a word, for a scan that reads one char at a time:
   * letters, digits, {@code _} and combining marks.
   *
   * @param c the char
   * @return true for a word char
   */
  static boolean isWord(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || CaseEdits.isMark(c);
  }

  /** Returns the start of the cluster ending at an offset. */
  private static int clusterStart(CharSequence text, int offset) {
    int at = offset;
    while (at > 0) {
      int cp = codePointBefore(text, at);
      at -= Character.charCount(cp);
      if (!CaseEdits.isMark(cp) || at == 0 || isBlankOrBreak(codePointBefore(text, at))) {
        return at;
      }
    }
    return at;
  }

  /** Returns the end of the cluster starting at an offset. */
  private static int clusterEnd(CharSequence text, int offset) {
    int cp = codePointAt(text, offset);
    int at = offset + Character.charCount(cp);
    if (isBlankOrBreak(cp)) {
      return at;
    }
    while (at < text.length()) {
      int next = codePointAt(text, at);
      if (!CaseEdits.isMark(next)) {
        break;
      }
      at += Character.charCount(next);
    }
    return at;
  }

  /** Returns the kind of the cluster ending at an offset: its first code point's. */
  private static int kindBefore(CharSequence text, int offset) {
    return kind(codePointAt(text, clusterStart(text, offset)));
  }

  /** Returns the kind of the cluster holding the code point at an offset. */
  private static int kindAt(CharSequence text, int offset) {
    return kindBefore(text, offset + Character.charCount(codePointAt(text, offset)));
  }

  private static int kind(int cp) {
    if (Character.isLetterOrDigit(cp) || cp == '_') {
      return WORD;
    }
    if (cp == ' ' || cp == '\t') {
      return BLANK;
    }
    return cp == '\r' || cp == '\n' ? BREAK : OTHER;
  }

  private static boolean isBlankOrBreak(int cp) {
    int kind = kind(cp);
    return kind == BLANK || kind == BREAK;
  }

  private static boolean isBreak(char c) {
    return c == '\r' || c == '\n';
  }

  /** Returns the offset past the line break at an offset, a CR LF as one. */
  private static int breakEnd(CharSequence text, int offset) {
    if (text.charAt(offset) == '\r'
        && offset + 1 < text.length()
        && text.charAt(offset + 1) == '\n') {
      return offset + 2;
    }
    return offset + 1;
  }

  /** Returns the offset before the line break ending at an offset, a CR LF as one. */
  private static int breakStart(CharSequence text, int offset) {
    if (text.charAt(offset - 1) == '\n' && offset >= 2 && text.charAt(offset - 2) == '\r') {
      return offset - 2;
    }
    return offset - 1;
  }

  private static int codePointAt(CharSequence text, int offset) {
    char c = text.charAt(offset);
    if (Character.isHighSurrogate(c) && offset + 1 < text.length()) {
      char low = text.charAt(offset + 1);
      if (Character.isLowSurrogate(low)) {
        return Character.toCodePoint(c, low);
      }
    }
    return c;
  }

  private static int codePointBefore(CharSequence text, int offset) {
    char c = text.charAt(offset - 1);
    if (Character.isLowSurrogate(c) && offset >= 2) {
      char high = text.charAt(offset - 2);
      if (Character.isHighSurrogate(high)) {
        return Character.toCodePoint(high, c);
      }
    }
    return c;
  }
}
