package io.github.ajdailey.qcaret.core.io;

import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.file.Path;

/** The hidden-file rule of the directory walks. */
public final class HiddenFiles {
  private HiddenFiles() {}

  /**
   * Reports whether a file or directory is hidden: its name starts with {@code .}, or the system
   * marks the entry itself hidden ({@link OsTraits#hidden}). On Windows a hidden junction such as
   * {@code My Documents} is hidden although its target is not.
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
      return OsTraits.current().hidden(path);
    } catch (IOException e) {
      return false;
    }
  }
}
