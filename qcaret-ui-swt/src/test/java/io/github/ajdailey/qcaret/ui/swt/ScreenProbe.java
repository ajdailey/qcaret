package io.github.ajdailey.qcaret.ui.swt;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Assumptions;

/**
 * Checks, before a test reads screen pixels, that the screen shows the test window: a probe child
 * of a known color, placed above the shell's other children, must read back through {@code
 * GC.copyArea} from the Display. It reads another color where a window covers the shell (the {@code
 * windows-11-arm} runner shows Windows' first-run screen over the desktop) and on GTK 4, where the
 * copy reads white. The probe does not raise the shell.
 */
final class ScreenProbe {
  static final RGB PROBE = new RGB(0x12, 0x9a, 0x56);
  private static final int SIZE = 32;
  private static final int TOLERANCE = 8;
  private static final Duration BOUND = Duration.ofSeconds(2);

  private ScreenProbe() {}

  /** Returns whether a screen read shows the shell, polling while the probe paints. */
  static boolean shows(Shell shell) {
    final Display display = shell.getDisplay();
    Composite probe = new Composite(shell, SWT.NONE);
    if (shell.getLayout() instanceof GridLayout) {
      GridData excluded = new GridData();
      excluded.exclude = true;
      probe.setLayoutData(excluded);
    }
    try {
      probe.setBackground(new Color(PROBE));
      probe.setBounds(100, 100, SIZE, SIZE);
      probe.moveAbove(null);
      final Point center = probe.toDisplay(SIZE / 2, SIZE / 2);
      return EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              display.update();
              return near(pixel(display, center.x, center.y), PROBE, TOLERANCE);
            }
          },
          BOUND);
    } finally {
      probe.dispose();
      display.update();
    }
  }

  /** Aborts the running test unless a screen read shows the shell. */
  static void assumeShows(Shell shell) {
    if (!shows(shell)) {
      abort("the screen does not show the test window");
    }
  }

  /**
   * Aborts the running test unless a captured image shows the expected color at a point, for a
   * capture that does not come from a screen read.
   */
  static void assumeDrawn(ImageData data, int x, int y, RGB expected, String what) {
    RGB seen = data.palette.getRGB(data.getPixel(x, y));
    if (!near(seen, expected, TOLERANCE)) {
      abort(what + " reads " + seen + ", wanted " + expected);
    }
  }

  /** Reads one screen pixel. */
  static RGB pixel(Display display, int x, int y) {
    Image image = new Image(display, 1, 1);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, x, y);
      ImageData data = image.getImageData();
      return data.palette.getRGB(data.getPixel(0, 0));
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static boolean near(RGB a, RGB b, int tolerance) {
    return Math.abs(a.red - b.red) <= tolerance
        && Math.abs(a.green - b.green) <= tolerance
        && Math.abs(a.blue - b.blue) <= tolerance;
  }

  private static void abort(String reason) {
    String gtk4 = "1".equals(System.getenv("SWT_GTK4")) ? " (GTK 4)" : "";
    String message =
        reason + " on " + SWT.getPlatform() + gtk4 + " " + System.getProperty("os.arch");
    System.err.println("ScreenProbe: " + message);
    Assumptions.abort(message);
  }
}
