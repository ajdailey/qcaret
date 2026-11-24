package io.github.ajdailey.qcaret.core.util.os;

import java.nio.file.Path;

/** Linux: in its own settings directory QCaret moves the home and opens the guide outside. */
final class LinuxFileConfinement implements FileConfinement {
  static final LinuxFileConfinement INSTANCE = new LinuxFileConfinement();

  private LinuxFileConfinement() {}

  @Override
  public void confineHome(boolean perUser, Path settings) {
    if (perUser) {
      return;
    }
    if (System.getProperty(HOME_FOLDER) == null) {
      System.setProperty(HOME_FOLDER, System.getProperty("user.home", ""));
    }
    System.setProperty("user.home", settings.toString());
  }

  @Override
  public void placeBrowserData(BrowserData data) {}

  @Override
  public boolean embedsGuide(boolean perUser) {
    return perUser;
  }
}
