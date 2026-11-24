package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

/** Placement on win32: placed again with the trim measured from the sized shell when it differs. */
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
  }
}
