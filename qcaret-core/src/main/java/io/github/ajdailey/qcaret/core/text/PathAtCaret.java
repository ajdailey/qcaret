package io.github.ajdailey.qcaret.core.text;

/**
 * Finds the file path File > Open Path at Caret opens, in the text of the caret's line. No file is
 * read.
 *
 * <ul>
 *   <li>A caret between a pair of {@code "}, {@code '} or {@code `} on the line (pairs counted from
 *       the line start) takes everything between them, spaces included.
 *   <li>Otherwise the run of characters around the caret, or ending at it, stops at whitespace,
 *       quotes, {@code < > | ( ) [ ] { } , ; = * ?}; a trailing {@code .} or {@code :} is dropped.
 *   <li>A trailing {@code :line} or {@code :line:column}, both from 1, is split off. A drive
 *       ({@code C:\}) is one letter and a colon, so it never reads as a line.
 *   <li>A run starting with a scheme and {@code //} ({@code https://}, {@code file://}) or with
 *       {@code mailto:}, {@code data:}, {@code javascript:}, {@code tel:} or {@code urn:} is a URL.
 * </ul>
 */
public final class PathAtCaret {
  private static final String STOPS = "\"'`<>|()[]{},;=*?";
  private static final String QUOTES = "\"'`";
  private static final String[] SCHEMES = {"mailto:", "data:", "javascript:", "tel:", "urn:"};

  /**
   * A path found at the caret.
   *
   * @param path the path as written; never empty
   * @param line the line from 1, or 0 when none is given
   * @param column the column from 1, or 0 when none is given
   * @param url true when the text is a URL, which is never opened
   */
  public record Found(String path, int line, int column, boolean url) {}

  private PathAtCaret() {}

  /**
   * Finds the path at a caret.
   *
   * @param line the caret's line, without its line break, or a part of it around the caret
   * @param caret the caret's index in {@code line}
   * @return the path, or null when none is at the caret
   */
  public static Found find(CharSequence line, int caret) {
    String quoted = quoted(line, caret);
    if (quoted != null) {
      return split(quoted);
    }
    int at = caret;
    if (at >= line.length() || stop(line.charAt(at))) {
      at--;
    }
    if (at < 0 || stop(line.charAt(at))) {
      return null;
    }
    int start = at;
    while (start > 0 && !stop(line.charAt(start - 1))) {
      start--;
    }
    int end = at + 1;
    while (end < line.length() && !stop(line.charAt(end))) {
      end++;
    }
    String run = line.subSequence(start, end).toString();
    while (!run.isEmpty() && (run.endsWith(".") || run.endsWith(":"))) {
      run = run.substring(0, run.length() - 1);
    }
    return split(run);
  }

  /** Returns the text between the pair of quotes around the caret, or null. */
  private static String quoted(CharSequence line, int caret) {
    for (int q = 0; q < QUOTES.length(); q++) {
      char quote = QUOTES.charAt(q);
      int open = -1;
      for (int i = 0; i < line.length(); i++) {
        if (line.charAt(i) != quote) {
          continue;
        }
        if (open < 0) {
          open = i;
        } else if (open < caret && caret <= i) {
          return line.subSequence(open + 1, i).toString();
        } else {
          open = -1;
        }
      }
    }
    return null;
  }

  private static boolean stop(char c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c) || STOPS.indexOf(c) >= 0;
  }

  /** Splits a {@code :line} or {@code :line:column} suffix off; null for a blank path. */
  private static Found split(String text) {
    String path = text;
    int line = 0;
    int column = 0;
    int last = number(path);
    if (last > 0) {
      String rest = path.substring(0, path.lastIndexOf(':'));
      int before = number(rest);
      if (before > 0) {
        line = before;
        column = last;
        path = rest.substring(0, rest.lastIndexOf(':'));
      } else {
        line = last;
        path = rest;
      }
    }
    if (path.isBlank()) {
      return null;
    }
    return new Found(path, line, column, url(path));
  }

  /**
   * Returns the number after the last colon when the text ends with {@code :digits} and keeps more
   * than a drive letter before it; otherwise 0.
   */
  private static int number(String text) {
    int colon = text.lastIndexOf(':');
    if (colon < 0 || colon == text.length() - 1) {
      return 0;
    }
    String digits = text.substring(colon + 1);
    for (int i = 0; i < digits.length(); i++) {
      if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
        return 0;
      }
    }
    String before = text.substring(0, colon);
    if (before.isEmpty() || (before.length() == 1 && Character.isLetter(before.charAt(0)))) {
      return 0;
    }
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static boolean url(String path) {
    int i = 0;
    while (i < path.length()
        && (Character.isLetterOrDigit(path.charAt(i)) || "+.-".indexOf(path.charAt(i)) >= 0)
        && path.charAt(i) < 0x80) {
      i++;
    }
    if (i > 1 && Character.isLetter(path.charAt(0)) && path.startsWith("://", i)) {
      return true;
    }
    for (String scheme : SCHEMES) {
      if (path.regionMatches(true, 0, scheme, 0, scheme.length())) {
        return true;
      }
    }
    return false;
  }
}
