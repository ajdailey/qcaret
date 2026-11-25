package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

/**
 * Placement on win32: placed again with the trim measured from the sized shell when it differs,
 * then fitted into its monitor's work area. A placement without a location keeps the position
 * Windows chose at creation, which can put a tall window's bottom under the taskbar.
 */
final class Win32WindowPlacement implements WindowPlacement {
  @Override
  public void place(Shell shell, Placer placer) {
    Rectangle computed = shell.computeTrim(0, 0, 100, 100);
    int width = computed.width - 100;
    int height = computed.height - 100;
    placer.place(width, height, true);
    Point size = shell.getSize();
    Rectangle client = shell.getClientArea();
    int measuredWidth = size.x - client.width;
    int measuredHeight = size.y - client.height;
    if (measuredWidth != width || measuredHeight != height) {
      placer.place(measuredWidth, measuredHeight, true);
    }
    fit(shell, shell.getMonitor().getClientArea());
  }

  @Override
  public void afterOpen(Shell shell, boolean maximized) {}

  /**
   * Fits a shell's bounds into a work area, size first, then location ({@link WindowGeometry#fit}).
   *
   * @param shell the shell
   * @param workArea the work area
   */
  static void fit(Shell shell, Rectangle workArea) {
    Rectangle bounds = shell.getBounds();
    WindowGeometry.Rect fitted =
        WindowGeometry.fit(
            new WindowGeometry.Rect(bounds.x, bounds.y, bounds.width, bounds.height),
            new WindowGeometry.Rect(workArea.x, workArea.y, workArea.width, workArea.height));
    if (fitted.x() != bounds.x
        || fitted.y() != bounds.y
        || fitted.width() != bounds.width
        || fitted.height() != bounds.height) {
      shell.setBounds(fitted.x(), fitted.y(), fitted.width(), fitted.height());
    }
  }
}
