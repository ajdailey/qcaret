package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Consumer;

/** The embedded browser on cocoa: WebKit. */
final class CocoaBrowserBehavior implements BrowserBehavior {
  @Override
  public String unavailable() {
    return null;
  }

  @Override
  public boolean followUngestured(String location, Consumer<String> follow) {
    return false;
  }

  @Override
  public void scrollToAnchor(Runnable scroll) {}

  @Override
  public void pageChanged(Runnable check) {}
}
