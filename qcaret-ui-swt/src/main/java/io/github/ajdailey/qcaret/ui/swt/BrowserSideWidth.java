package io.github.ajdailey.qcaret.ui.swt;

import java.util.Arrays;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The workspace browser's default width in the window's side {@link SashForm}: a quarter of the
 * width, widened to the browser's button row in one row, up to half the width. The default follows
 * every resize until weights are restored or a sash drag changes them; after that the sash keeps
 * its proportions and a narrower browser wraps the row. Call on the UI thread.
 */
final class BrowserSideWidth {
  private final SashForm side;
  private final Composite buttons;
  private int[] applied;

  /**
   * Applies the default and follows resizes.
   *
   * @param side the sash with the browser first and the editors second
   * @param buttons the browser's button row
   */
  BrowserSideWidth(SashForm side, Composite buttons) {
    this.side = side;
    this.buttons = buttons;
    side.setWeights(new int[] {1, 3});
    applied = side.getWeights();
    side.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fit();
          }
        });
  }

  /**
   * Restores saved weights and stops applying the default.
   *
   * @param weights the browser's and the editors' weights
   */
  void restore(int[] weights) {
    side.setWeights(weights);
    applied = null;
  }

  /** Applies the default to the current width while the weights are still the default's. */
  void fit() {
    if (applied == null || !Arrays.equals(applied, side.getWeights())) {
      applied = null;
      return;
    }
    int total = side.getClientArea().width - side.getSashWidth();
    if (total <= 0 || side.getMaximizedControl() != null) {
      return;
    }
    // SashForm rounds each weight up to 1/65536 and the share down, losing up to 1 pixel.
    int row = buttons.computeSize(SWT.DEFAULT, SWT.DEFAULT).x + 1;
    int browser = Math.min(Math.max(total / 4, row), total / 2);
    side.setWeights(new int[] {browser, total - browser});
    applied = side.getWeights();
  }
}
