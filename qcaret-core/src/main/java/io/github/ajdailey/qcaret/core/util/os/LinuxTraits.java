package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Linux, and any system {@link OsTraits#of} does not name. */
final class LinuxTraits implements OsTraits {
  static final LinuxTraits INSTANCE = new LinuxTraits();

  private static final String DATA_DIRS = "/usr/local/share:/usr/share";

  private LinuxTraits() {}

  @Override
  public boolean fileNamesIgnoreCase() {
    return false;
  }

  @Override
  public LockKind fileLock() {
    return LockKind.FLOCK_LINUX;
  }

  @Override
  public boolean opensDeviceNames() {
    return false;
  }

  @Override
  public Path userSettings(Map<String, String> env, Path userHome) {
    Path xdg = EnvPaths.absolute(env.get("XDG_CONFIG_HOME"));
    if (xdg == null) {
      xdg = userHome.resolve(".config");
    }
    return xdg.resolve("qcaret");
  }

  @Override
  public Path launcherDirectory(Path home) {
    return home.resolve("bin");
  }

  @Override
  public boolean confinesToSettings() {
    return true;
  }

  @Override
  public boolean webView2() {
    return false;
  }

  @Override
  public List<Path> fontDirectories(Map<String, String> env, String home) {
    List<Path> folders = new ArrayList<Path>();
    EnvPaths.addFolder(folders, home, ".fonts");
    String dataHome = env.get("XDG_DATA_HOME");
    if (dataHome == null || dataHome.isEmpty()) {
      EnvPaths.addFolder(folders, home, ".local/share/fonts");
    } else {
      EnvPaths.addFolder(folders, dataHome, "fonts");
    }
    String dataDirs = env.get("XDG_DATA_DIRS");
    if (dataDirs == null || dataDirs.isEmpty()) {
      dataDirs = DATA_DIRS;
    }
    for (String dir : dataDirs.split(":")) {
      EnvPaths.addFolder(folders, dir, "fonts");
    }
    return folders;
  }

  @Override
  public boolean hidden(Path path) throws IOException {
    return Files.isHidden(path);
  }
}
