package io.github.ajdailey.qcaret.app.theme;

/**
 * A color with 8-bit components, free of any UI toolkit.
 *
 * @param red 0-255
 * @param green 0-255
 * @param blue 0-255
 */
public record Rgb(int red, int green, int blue) {
  /** Checks the components. */
  public Rgb {
    if ((red | green | blue) < 0 || red > 255 || green > 255 || blue > 255) {
      throw new IllegalArgumentException("components must be 0-255");
    }
  }

  /**
   * Creates a color from {@code 0xRRGGBB}.
   *
   * @param rgb the packed value
   * @return the color
   */
  public static Rgb of(int rgb) {
    return new Rgb((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
  }
}
