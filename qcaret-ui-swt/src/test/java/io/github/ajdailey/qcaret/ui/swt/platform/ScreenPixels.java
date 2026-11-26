package io.github.ajdailey.qcaret.ui.swt.platform;

import java.time.Duration;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * A screen read of a control in device pixels, for tests that place drawing to the pixel. A point
 * maps to the pixel {@code point * zoom / 100}.
 */
final class ScreenPixels {
  private final ImageData data;
  private final int zoom;

  private ScreenPixels(ImageData data, int zoom) {
    this.data = data;
    this.zoom = zoom;
  }

  /**
   * Dispatches pending events for up to a bound, paints, and reads a control from the screen.
   *
   * @param control the control
   * @param bound the longest time to dispatch
   * @return the pixels
   */
  static ScreenPixels read(Control control, Duration bound) {
    Display display = control.getDisplay();
    long deadline = System.nanoTime() + bound.toNanos();
    while (System.nanoTime() < deadline && display.readAndDispatch()) {
      // Runs queued work.
    }
    control.update();
    display.update();
    Point size = control.getSize();
    Point origin = control.toDisplay(0, 0);
    Image image = new Image(display, Math.max(1, size.x), Math.max(1, size.y));
    GC gc = new GC(display);
    try {
      gc.copyArea(image, origin.x, origin.y);
      int zoom = zoom(control);
      return new ScreenPixels(image.getImageData(zoom), zoom);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Returns a numeric {@code swt.autoScale} value, else the control's monitor zoom. */
  private static int zoom(Control control) {
    String scale = System.getProperty("swt.autoScale");
    if (scale != null) {
      try {
        return Integer.parseInt(scale.trim());
      } catch (NumberFormatException named) {
        return control.getMonitor().getZoom();
      }
    }
    return control.getMonitor().getZoom();
  }

  /**
   * Returns a length in points as device pixels.
   *
   * @param points the length
   * @return the pixels, rounded
   */
  int pixels(int points) {
    return Math.round(points * zoom / 100f);
  }

  int width() {
    return data.width;
  }

  int height() {
    return data.height;
  }

  /**
   * Returns the color of a device pixel.
   *
   * @param x the column
   * @param y the row
   * @return the color
   */
  RGB at(int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }

  /**
   * Reports whether two colors differ by at most a tolerance in each channel.
   *
   * @param a a color
   * @param b another
   * @param tolerance the largest channel difference
   * @return true when close
   */
  static boolean near(RGB a, RGB b, int tolerance) {
    return Math.abs(a.red - b.red) <= tolerance
        && Math.abs(a.green - b.green) <= tolerance
        && Math.abs(a.blue - b.blue) <= tolerance;
  }
}
