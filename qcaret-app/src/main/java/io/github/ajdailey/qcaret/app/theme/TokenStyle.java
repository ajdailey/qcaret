package io.github.ajdailey.qcaret.app.theme;

/**
 * How a token role paints: on the view background, in its foreground color and font style.
 *
 * @param foreground the text color
 * @param bold bold font
 * @param italic italic font
 * @param underline underlined
 */
public record TokenStyle(Rgb foreground, boolean bold, boolean italic, boolean underline) {
  /** Checks the components. */
  public TokenStyle {
    if (foreground == null) {
      throw new IllegalArgumentException("null foreground");
    }
  }

  /**
   * Returns a plain style.
   *
   * @param rgb {@code 0xRRGGBB}
   * @return the style, neither bold, italic nor underlined
   */
  public static TokenStyle plain(int rgb) {
    return new TokenStyle(Rgb.of(rgb), false, false, false);
  }

  /**
   * Returns a bold style.
   *
   * @param rgb {@code 0xRRGGBB}
   * @return the style
   */
  public static TokenStyle bold(int rgb) {
    return new TokenStyle(Rgb.of(rgb), true, false, false);
  }

  /**
   * Returns an italic style.
   *
   * @param rgb {@code 0xRRGGBB}
   * @return the style
   */
  public static TokenStyle italic(int rgb) {
    return new TokenStyle(Rgb.of(rgb), false, true, false);
  }

  /**
   * Returns an underlined style.
   *
   * @param rgb {@code 0xRRGGBB}
   * @return the style
   */
  public static TokenStyle underline(int rgb) {
    return new TokenStyle(Rgb.of(rgb), false, false, true);
  }
}
