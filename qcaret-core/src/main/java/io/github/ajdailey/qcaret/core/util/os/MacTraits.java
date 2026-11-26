package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** macOS. */
final class MacTraits implements OsTraits {
  static final MacTraits INSTANCE = new MacTraits();

  private static final String[] FONT_LIBRARIES = {"/System/Library", "/Library"};

  private MacTraits() {}

  @Override
  public boolean fileNamesIgnoreCase() {
    return true;
  }

  @Override
  public LockKind fileLock() {
    return LockKind.FLOCK_MACOS;
  }

  @Override
  public DeviceNames deviceNames() {
    return FileNames.INSTANCE;
  }

  @Override
  public Path userSettings(Map<String, String> env, Path userHome) {
    return userHome.resolve("Library").resolve("Application Support").resolve("QCaret");
  }

  @Override
  public Path launcherDirectory(Path home) {
    return null;
  }

  @Override
  public FileConfinement fileConfinement() {
    return MacFileConfinement.INSTANCE;
  }

  @Override
  public ForegroundRights foregroundRights() {
    return NoForegroundRights.INSTANCE;
  }

  @Override
  public List<Path> fontDirectories(Map<String, String> env, String home) {
    List<Path> folders = new ArrayList<Path>();
    for (String library : FONT_LIBRARIES) {
      EnvPaths.addFolder(folders, library, "Fonts");
    }
    EnvPaths.addFolder(folders, home, "Library/Fonts");
    return folders;
  }

  @Override
  public boolean hidden(Path path) throws IOException {
    return Files.isHidden(path);
  }

  @Override
  public boolean watchServiceByDefault() {
    return false;
  }

  @Override
  public boolean runsInsteadOfOpening(Path file) {
    Path name = file.getFileName();
    return (name != null && RunnableTypes.mac(name.toString())) || Files.isExecutable(file);
  }
}
