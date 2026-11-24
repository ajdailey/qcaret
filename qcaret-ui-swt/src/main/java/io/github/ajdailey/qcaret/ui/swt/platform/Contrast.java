package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.RGB;

/** WCAG contrast of two colors, and a text color raised to the link contrast. */
public final class Contrast {
  /** Lowest contrast of link text on its background: the WCAG AA ratio for normal text. */
  public static final double LINK = 4.5;

  /** Percent of the way to white or black each step moves a color. */
  private static final int STEP_PERCENT = 5;

  private static final RGB WHITE = new RGB(255, 255, 255);
  private static final RGB BLACK = new RGB(0, 0, 0);

  private Contrast() {}

  /**
   * Moves a text color toward white or black, whichever contrasts more with the ground, until it
   * reaches {@link #LINK}.
   *
   * @param text the text color
   * @param ground the background color
   * @return the text color when already readable, otherwise the first readable step
   */
  public static RGB readable(RGB text, RGB ground) {
    RGB target = ratio(WHITE, ground) >= ratio(BLACK, ground) ? WHITE : BLACK;
    RGB result = text;
    for (int percent = STEP_PERCENT;
        percent <= 100 && ratio(result, ground) < LINK;
        percent += STEP_PERCENT) {
      result =
          new RGB(
              mix(text.red, target.red, percent),
              mix(text.green, target.green, percent),
              mix(text.blue, target.blue, percent));
    }
    return result;
  }

  /**
   * Returns the WCAG contrast ratio of two colors.
   *
   * @param first a color
   * @param second the other color
   * @return the ratio, from 1 to 21
   */
  public static double ratio(RGB first, RGB second) {
    double a = luminance(first);
    double b = luminance(second);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  private static int mix(int from, int to, int percent) {
    return from + (to - from) * percent / 100;
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue);
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
