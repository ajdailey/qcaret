package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;
import java.nio.file.Path;

/** Windows: WebView2 keeps its data in the settings directory; the home stays. */
final class WindowsFileConfinement implements FileConfinement {
  static final WindowsFileConfinement INSTANCE = new WindowsFileConfinement();

  private WindowsFileConfinement() {}

  @Override
  public void confineHome(boolean perUser, Path settings) {}

  @Override
  public void placeBrowserData(BrowserData data) throws IOException {
    System.setProperty(EDGE_DATA_DIR, data.folder().toString());
  }

  @Override
  public boolean embedsGuide(boolean perUser) {
    return true;
  }
}
