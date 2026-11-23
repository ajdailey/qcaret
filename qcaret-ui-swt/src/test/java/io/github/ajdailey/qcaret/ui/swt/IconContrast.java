package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;

/** Reads an icon's ink and its contrast on a background. */
final class IconContrast {
  /** The ink of icons for a dark background. */
  static final RGB LIGHT_INK = new RGB(0xd4, 0xd4, 0xd4);

  /** The ink of icons for a light background. */
  static final RGB DARK_INK = new RGB(0x3a, 0x3a, 0x3a);

  /** Lowest contrast of a disabled icon's strongest pixel on the dark chrome. */
  static final double DISABLED_MIN = 2.5;

  private IconContrast() {}

  /**
   * Returns the color of the icon's most opaque pixel.
   *
   * @param data the icon
   * @return the color
   */
  static RGB ink(ImageData data) {
    int[] at = strongest(data);
    return data.palette.getRGB(data.getPixel(at[0], at[1]));
  }

  /**
   * Returns the highest alpha of the icon.
   *
   * @param data the icon
   * @return 0 to 255
   */
  static int maxAlpha(ImageData data) {
    int[] at = strongest(data);
    return data.getAlpha(at[0], at[1]);
  }

  /**
   * Returns the WCAG contrast of the icon's most opaque pixel, drawn over the ground, on the
   * ground.
   *
   * @param data the icon
   * @param ground the background
   * @return the ratio
   */
  static double contrast(ImageData data, RGB ground) {
    RGB ink = ink(data);
    int alpha = maxAlpha(data);
    RGB drawn =
        new RGB(
            blend(ink.red, ground.red, alpha),
            blend(ink.green, ground.green, alpha),
            blend(ink.blue, ground.blue, alpha));
    return SwtThemeService.contrast(drawn, ground);
  }

  /**
   * Asserts that the icon's most opaque pixel is the ink, within the renderer's rounding.
   *
   * @param ink the ink
   * @param data the icon
   */
  static void assertInk(RGB ink, ImageData data) {
    RGB actual = ink(data);
    boolean near =
        Math.abs(actual.red - ink.red) <= 2
            && Math.abs(actual.green - ink.green) <= 2
            && Math.abs(actual.blue - ink.blue) <= 2;
    assertTrue(near, actual + " against " + ink);
  }

  private static int blend(int ink, int ground, int alpha) {
    return (ink * alpha + ground * (255 - alpha) + 127) / 255;
  }

  private static int[] strongest(ImageData data) {
    int[] best = {0, 0};
    int max = -1;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        int alpha = data.getAlpha(x, y);
        if (alpha > max) {
          max = alpha;
          best = new int[] {x, y};
        }
      }
    }
    return best;
  }
}
