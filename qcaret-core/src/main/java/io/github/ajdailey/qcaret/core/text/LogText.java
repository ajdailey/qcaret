package io.github.ajdailey.qcaret.core.text;

/**
 * Reduces a value that did not originate in QCaret's own code to one log-safe line before it
 * reaches a logger: a path, a search pattern, a configuration value or an I/O exception message.
 */
public final class LogText {
  /** Longest result, in chars; a longer value is cut. */
  public static final int MAX_CHARS = 1024;

  private LogText() {}

  /**
   * Returns {@code String.valueOf(value)} with every C0 control (CR and LF included), DEL, NEL,
   * U+2028 and U+2029 replaced by {@code _}, cut to {@link #MAX_CHARS}.
   *
   * @param value any value; null gives {@code null}
   * @return the single line
   */
  public static String oneLine(Object value) {
    String text = String.valueOf(value);
    int length = Math.min(text.length(), MAX_CHARS);
    StringBuilder out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      out.append(isBreak(c) ? '_' : c);
    }
    return out.toString();
  }

  private static boolean isBreak(char c) {
    return c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029;
  }
}
