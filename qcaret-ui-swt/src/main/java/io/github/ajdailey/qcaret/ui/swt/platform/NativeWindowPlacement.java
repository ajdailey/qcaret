package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

/** Placement on cocoa: the computed trim, and the position applied. */
class NativeWindowPlacement implements WindowPlacement {
  @Override
  public void place(Shell shell, Placer placer) {
    Rectangle trim = shell.computeTrim(0, 0, 100, 100);
    placer.place(trim.width - 100, trim.height - 100, positioned());
  }

  /**
   * Reports whether the window system takes a position; read at each placement.
   *
   * @return true
   */
  boolean positioned() {
    return true;
  }
}
