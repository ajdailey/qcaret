package io.github.ajdailey.qcaret.ui.swt;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Paints a control into an image through {@code Control.print}. GTK 4 prints the render node of the
 * widget's last drawn frame, and {@code print} returns false while a redraw is queued; a failed
 * print dispatches events and retries, so the frame clock draws the pending frame. On win32 the
 * first print of a new shell can return true with every pixel black (19 of 1,000 runs of a {@code
 * MarkdownPaneBlocksTest} case on {@code windows-2025}); such a print is retried too.
 */
final class ControlPrint {
  private static final Duration BOUND = Duration.ofSeconds(5);

  private ControlPrint() {}

  /**
   * Prints a control.
   *
   * @param display the Display, on the calling thread
   * @param control the control to print
   * @return the control's pixels, or null when no print succeeded within 5 s (SWT issue 2819)
   */
  static ImageData of(final Display display, final Control control) {
    final ImageData[] printed = new ImageData[1];
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            printed[0] = once(display, control);
            return printed[0] != null;
          }
        },
        BOUND);
    return printed[0];
  }

  /**
   * Prints a control as {@link #of} does and returns its device pixels at the control's monitor
   * zoom.
   *
   * @param display the Display, on the calling thread
   * @param control the control to print
   * @return the pixels, or null when no print succeeded within 5 s
   */
  static ImageData atZoom(final Display display, final Control control) {
    final ImageData[] printed = new ImageData[1];
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            printed[0] = once(display, control, true);
            return printed[0] != null;
          }
        },
        BOUND);
    return printed[0];
  }

  private static ImageData once(Display display, Control control) {
    return once(display, control, false);
  }

  private static ImageData once(Display display, Control control, boolean device) {
    Image image = new Image(display, control.getSize().x, control.getSize().y);
    try {
      GC gc = new GC(image);
      try {
        if (!control.print(gc)) {
          return null;
        }
      } finally {
        gc.dispose();
      }
      ImageData data =
          device ? image.getImageData(control.getMonitor().getZoom()) : Pixels.read(image, control);
      return black(data) ? null : data;
    } finally {
      image.dispose();
    }
  }

  /** Reports whether every pixel is black. */
  private static boolean black(ImageData data) {
    RGB black = new RGB(0, 0, 0);
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        if (!data.palette.getRGB(data.getPixel(x, y)).equals(black)) {
          return false;
        }
      }
    }
    return true;
  }
}
