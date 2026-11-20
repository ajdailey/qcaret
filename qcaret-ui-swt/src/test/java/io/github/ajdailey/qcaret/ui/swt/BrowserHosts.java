package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Shell;

/**
 * Skips a test where the Browser preview cannot run: the product's factory creates a browser in a
 * shell, a failure is recorded as {@code PreviewSlot} records it, and {@link
 * BrowserPreview#unavailable} gives the reason, as the View menu shows it.
 */
final class BrowserHosts {
  private BrowserHosts() {}

  /**
   * Skips the test, with the reason, unless a browser can be created on the shell's Display.
   *
   * @param shell receives a browser, disposed before this returns
   */
  static void assumeBrowser(Shell shell) {
    if (BrowserPreview.unavailable(shell.getDisplay()) == null) {
      try {
        GuideWindow.BROWSER.create(shell).dispose();
      } catch (SWTException | SWTError e) {
        BrowserPreview.failed(shell.getDisplay(), e.getMessage());
      }
    }
    String reason = BrowserPreview.unavailable(shell.getDisplay());
    assumeTrue(reason == null, reason);
  }
}
