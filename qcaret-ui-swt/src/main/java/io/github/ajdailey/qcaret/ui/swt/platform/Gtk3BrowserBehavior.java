package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.function.Consumer;
import org.eclipse.swt.browser.Browser;

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

  @Override
  public void menuMnemonics(Browser browser, UiDispatcher ui) {}
}
