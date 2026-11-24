package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/** The embedded browser on GTK 4: none may be created. */
final class Gtk4BrowserBehavior extends Gtk3BrowserBehavior {
  @Override
  public String unavailable() {
    return UiText.MARKDOWN_BROWSER_GTK4.text();
  }
}
