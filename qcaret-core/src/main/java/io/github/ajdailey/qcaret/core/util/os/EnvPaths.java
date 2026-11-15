package io.github.ajdailey.qcaret.core.util.os;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Paths read from environment variables and system properties. */
public final class EnvPaths {
  private EnvPaths() {}

  /**
   * Parses a value as an absolute path.
   *
   * @param value the variable's value, or null
   * @return the normalized path, or null when the value is null, empty, holds NUL, is not a path or
   *     is relative
   */
  public static Path absolute(String value) {
    if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
      return null;
    }
    try {
      Path path = Path.of(value);
      return path.isAbsolute() ? path.normalize() : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /**
   * Returns a Windows environment variable. Windows compares variable names ignoring case and sets
   * some in lower case, such as {@code windir}, while the map from {@link System#getenv()} compares
   * them exactly.
   *
   * @param env the process environment
   * @param name the variable's name in any case
   * @return the value, or null when no variable has the name
   */
  static String windows(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value != null) {
      return value;
    }
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String key = entry.getKey();
      if (key.length() == name.length() && key.regionMatches(true, 0, name, 0, name.length())) {
        return entry.getValue();
      }
    }
    return null;
  }

  /** Adds {@code base/child} unless the base is null or empty or the folder is listed. */
  static void addFolder(List<Path> folders, String base, String child) {
    if (base != null && !base.isEmpty()) {
      Path folder = Path.of(base).resolve(child);
      if (!folders.contains(folder)) {
        folders.add(folder);
      }
    }
  }
}
