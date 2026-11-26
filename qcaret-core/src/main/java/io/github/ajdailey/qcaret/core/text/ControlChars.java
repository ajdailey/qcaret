package io.github.ajdailey.qcaret.core.text;

/**
 * The control chars a view draws as a box holding their two uppercase hex digits: C0 (U+0000 to
 * U+001F) except TAB, DEL (U+007F) and C1 (U+0080 to U+009F). Each takes {@link #WIDTH} display
 * columns. A CR or LF counts only where it does not end a line; line ends are not drawn. The box is
 * display only: copy, save and search keep the char.
 */
public final class ControlChars {
  /** Display columns of a boxed char. */
  public static final int WIDTH = 2;

  /**
   * The char a view's widget gets in place of a boxed char: the object replacement char, which no
   * toolkit treats as a line break.
   */
  public static final char PLACEHOLDER = (char) 0xfffc;

  private static final String[] LABELS = new String[0xa0];

  static {
    String digits = "0123456789ABCDEF";
    for (int c = 0; c < LABELS.length; c++) {
      LABELS[c] = new String(new char[] {digits.charAt(c >> 4), digits.charAt(c & 0xf)});
    }
  }

  private ControlChars() {}

  /**
   * Reports whether a char draws as a box.
   *
   * @param c the char
   * @return true for C0 except TAB, DEL and C1
   */
  public static boolean boxed(char c) {
    return c < 0xa0 && (c < 0x20 ? c != '\t' : c >= 0x7f);
  }

  /**
   * Returns the box's text.
   *
   * @param c a char that {@link #boxed} accepts
   * @return its two uppercase hex digits, such as {@code 1B}; the same instance on every call
   */
  public static String label(char c) {
    return LABELS[c];
  }

  /**
   * Returns a line with each boxed char replaced by {@link #PLACEHOLDER}. Allocates only when the
   * line holds one.
   *
   * @param line a line or row without its break
   * @return {@code line} itself when it holds no boxed char, else a copy
   */
  public static String shown(String line) {
    int length = line.length();
    int i = 0;
    while (i < length && !boxed(line.charAt(i))) {
      i++;
    }
    if (i == length) {
      return line;
    }
    char[] chars = line.toCharArray();
    for (; i < length; i++) {
      if (boxed(chars[i])) {
        chars[i] = PLACEHOLDER;
      }
    }
    return new String(chars);
  }
}
