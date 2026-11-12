package io.github.ajdailey.qcaret.core.util.os;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Windows. */
final class WindowsTraits implements OsTraits {
  static final WindowsTraits INSTANCE = new WindowsTraits();

  private WindowsTraits() {}

  @Override
  public boolean fileNamesIgnoreCase() {
    return true;
  }

  @Override
  public LockKind fileLock() {
    return LockKind.CHANNEL;
  }

  @Override
  public boolean opensDeviceNames() {
    return true;
  }

  @Override
  public Path userSettings(Map<String, String> env, Path userHome) {
    Path appData = EnvPaths.absolute(env.get("APPDATA"));
    if (appData == null) {
      appData = userHome.resolve("AppData").resolve("Roaming");
    }
    return appData.resolve("QCaret");
  }

  @Override
  public boolean confinesToSettings() {
    return false;
  }

  @Override
  public boolean webView2() {
    return true;
  }

  @Override
  public List<Path> fontDirectories(Map<String, String> env, String home) {
    List<Path> folders = new ArrayList<Path>();
    EnvPaths.addFolder(folders, env.get("WINDIR"), "Fonts");
    EnvPaths.addFolder(folders, env.get("LOCALAPPDATA"), "Microsoft/Windows/Fonts");
    return folders;
  }
}
