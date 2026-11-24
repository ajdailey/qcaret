package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.widgets.Control;

/**
 * The rounding a test allows in a position or size read in points. At a fractional monitor zoom
 * (win32 at 125 % or 150 %) SWT converts device pixels to points by rounding each value on its own,
 * so a length the product sets exactly can read back one point off, and a difference of two rounded
 * positions likewise.
 */
public final class ScaleSlack {
  private ScaleSlack() {}

  /**
   * Returns the points a length read on a control may differ from the exact one.
   *
   * @param control a control on the monitor whose zoom applies
   * @return 1 at a zoom that is not a whole multiple of 100, else 0
   */
  public static int of(Control control) {
    return control.getMonitor().getZoom() % 100 == 0 ? 0 : 1;
  }
}
