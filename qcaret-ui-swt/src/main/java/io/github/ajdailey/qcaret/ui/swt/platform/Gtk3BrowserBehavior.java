package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Consumer;

/** The embedded browser on GTK 3: WebKitGTK. */
class Gtk3BrowserBehavior implements BrowserBehavior {
  @Override
  public String unavailable() {
    return null;
  }

  @Override
  public final boolean followUngestured(String location, Consumer<String> follow) {
    return false;
  }

  @Override
  public final void scrollToAnchor(Runnable scroll) {
    scroll.run();
  }

  @Override
  public final void pageChanged(Runnable check) {
    check.run();
  }
}
