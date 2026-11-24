package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Consumer;

/** The embedded browser on win32: SWT's Edge (WebView2). */
final class Win32BrowserBehavior implements BrowserBehavior {
  @Override
  public String unavailable() {
    return null;
  }

  @Override
  public boolean followUngestured(String location, Consumer<String> follow) {
    follow.accept(location);
    return true;
  }

  @Override
  public void scrollToAnchor(Runnable scroll) {}

  @Override
  public void pageChanged(Runnable check) {}
}
