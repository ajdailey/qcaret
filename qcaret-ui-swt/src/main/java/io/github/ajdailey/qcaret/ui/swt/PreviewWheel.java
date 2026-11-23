package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Zooms a Markdown preview on a wheel turn with Ctrl (Command on macOS) over it, in place of a
 * scroll.
 */
final class PreviewWheel {
  private PreviewWheel() {}

  /**
   * Zooms the preview on each wheel turn with the modifier over a control.
   *
   * @param control the preview's text or browser control
   * @param controller runs the zoom
   */
  static void attach(Control control, final ApplicationController controller) {
    control.addListener(
        SWT.MouseVerticalWheel,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.doit && event.count != 0 && (event.stateMask & SWT.MOD1) != 0) {
              event.doit = false;
              controller.wheelZoom(true, event.count > 0 ? 1 : -1);
            }
          }
        });
  }
}
