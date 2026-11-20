package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.widgets.Display;

/**
 * Whether the Browser Markdown preview renderer can run: the one check every window asks. It cannot
 * under GTK 4 ({@link PlatformTraits#opensBrowser}), nor on a Display where a preview's browser
 * control failed to create or failed a call; that failure holds until the Display ends.
 */
final class BrowserPreview {
  private static final String FAILURE = "qcaret.browserPreviewFailure";

  private BrowserPreview() {}

  /**
   * Reports why the Browser renderer cannot run.
   *
   * @param display the Display
   * @return the reason, or null when it can run
   */
  static String unavailable(Display display) {
    if (!PlatformTraits.PLATFORM.opensBrowser()) {
      return UiText.MARKDOWN_BROWSER_GTK4.text();
    }
    Object failure = display.getData(FAILURE);
    return failure == null ? null : UiText.MARKDOWN_BROWSER_FAILED.format(failure);
  }

  /**
   * Records a browser failure; {@link #unavailable} reports it from now on.
   *
   * @param display the Display
   * @param reason the error's message
   */
  static void failed(Display display, String reason) {
    display.setData(FAILURE, reason == null ? "" : reason);
  }
}
