package io.github.ajdailey.qcaret.ui.swt.platform;

import java.nio.file.Path;

/** The guide in a browser window: win32, cocoa and GTK 3. */
final class BrowserGuideDisplay implements GuideDisplay {
  @Override
  public boolean show(Path file, Shows shows) {
    return shows.browser(file);
  }
}
