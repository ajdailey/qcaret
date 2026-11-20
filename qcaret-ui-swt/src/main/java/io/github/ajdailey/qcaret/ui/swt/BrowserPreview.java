package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/** Whether the Browser Markdown preview renderer can run: the one check every window asks. */
final class BrowserPreview {
  private BrowserPreview() {}

  /**
   * Reports why the Browser renderer cannot run.
   *
   * @return the reason; this version has no Browser renderer
   */
  static String unavailable() {
    return UiText.MARKDOWN_BROWSER_NOT_BUILT.text();
  }
}
