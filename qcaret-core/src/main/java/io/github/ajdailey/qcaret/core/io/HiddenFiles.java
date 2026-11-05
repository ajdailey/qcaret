package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The hidden-file rule of the directory walks. */
public final class HiddenFiles {
  private HiddenFiles() {}

  /**
   * Reports whether a file or directory is hidden: its name starts with {@code .}, or the platform
   * marks it hidden (on Windows the DOS attribute, read from disk).
   *
   * @param path the file or directory
   * @return true when hidden; false when the attribute cannot be read
   */
  public static boolean hidden(Path path) {
    Path name = path.getFileName();
    if (name != null && name.toString().startsWith(".")) {
      return true;
    }
    try {
      return Files.isHidden(path);
    } catch (IOException e) {
      return false;
    }
  }
}
