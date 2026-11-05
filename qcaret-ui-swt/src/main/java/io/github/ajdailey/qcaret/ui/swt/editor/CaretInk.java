package io.github.ajdailey.qcaret.ui.swt.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;

/**
 * The color of the primary caret's image. Each platform composites a caret image over the text with
 * its own operator, so the image holds the color that shows the scheme's caret color over the
 * current line's background:
 *
 * <table>
 *   <caption>Caret image compositing</caption>
 *   <tr><th>Platform</th><th>Operator</th><th>Shown</th></tr>
 *   <tr><td>GTK 3, cocoa</td><td>difference</td><td>{@code |background - image|} per channel</td></tr>
 *   <tr><td>win32</td><td>XOR (assumed)</td><td>{@code background ^ image}</td></tr>
 *   <tr><td>GTK 4</td><td>over</td><td>the image</td></tr>
 * </table>
 *
 * <p>Under difference a channel the background cannot reach (amber over navy: red 0xf3 over 0x22)
 * takes the nearest value it can show.
 */
public final class CaretInk {
  /** How the platform draws a caret image over the text. */
  public enum Blend {
    /** Per channel {@code |background - image|}. */
    DIFFERENCE,
    /** Per channel {@code background ^ image}. */
    XOR,
    /** The image replaces the background. */
    OVER
  }

  private CaretInk() {}

  /**
   * Returns this platform's caret operator. GTK 4 is the {@code SWT_GTK4=1} environment SWT reads.
   *
   * @return the operator
   */
  public static Blend platform() {
    return switch (SWT.getPlatform()) {
      case "gtk" -> "1".equals(System.getenv("SWT_GTK4")) ? Blend.OVER : Blend.DIFFERENCE;
      case "win32" -> Blend.XOR;
      default -> Blend.DIFFERENCE;
    };
  }

  /**
   * Returns the image color that shows a caret color over a background.
   *
   * @param background the background under the caret
   * @param caret the color to show
   * @param blend the operator
   * @return the image color
   */
  public static RGB source(RGB background, RGB caret, Blend blend) {
    return new RGB(
        channel(background.red, caret.red, blend),
        channel(background.green, caret.green, blend),
        channel(background.blue, caret.blue, blend));
  }

  /**
   * Returns the color an image color shows over a background.
   *
   * @param background the background under the caret
   * @param image the image color
   * @param blend the operator
   * @return the color on screen
   */
  public static RGB shown(RGB background, RGB image, Blend blend) {
    return switch (blend) {
      case DIFFERENCE ->
          new RGB(
              Math.abs(background.red - image.red),
              Math.abs(background.green - image.green),
              Math.abs(background.blue - image.blue));
      case XOR ->
          new RGB(
              background.red ^ image.red,
              background.green ^ image.green,
              background.blue ^ image.blue);
      case OVER -> image;
    };
  }

  private static int channel(int background, int caret, Blend blend) {
    return switch (blend) {
      case DIFFERENCE -> {
        if (background + caret <= 255) {
          yield background + caret;
        }
        if (background - caret >= 0) {
          yield background - caret;
        }
        // Neither reaches the caret value: 255 shows 255 - background, 0 shows background.
        yield 255 - background >= background ? 255 : 0;
      }
      case XOR -> background ^ caret;
      case OVER -> caret;
    };
  }
}
