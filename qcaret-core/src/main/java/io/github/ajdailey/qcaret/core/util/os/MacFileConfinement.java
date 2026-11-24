package io.github.ajdailey.qcaret.core.util.os;

import java.nio.file.Path;

/** macOS: the home and the browser's data stay where the system puts them. */
final class MacFileConfinement implements FileConfinement {
  static final MacFileConfinement INSTANCE = new MacFileConfinement();

  private MacFileConfinement() {}

  @Override
  public void confineHome(boolean perUser, Path settings) {}

  @Override
  public void placeBrowserData(BrowserData data) {}

  @Override
  public boolean embedsGuide(boolean perUser) {
    return true;
  }
}
