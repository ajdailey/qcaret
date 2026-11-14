package io.github.ajdailey.qcaret.app.config;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Resolves the user paths the settings and session files hold, and relativizes them for writing.
 * The base is the installation directory ({@link SettingsLocation#home}), or the settings directory
 * in a run without one.
 *
 * <p>In {@link SettingsMode#PORTABLE} and {@link SettingsMode#EXPLICIT} mode {@link #write} gives a
 * path whose real path is the base or under it relative to the base's real path, {@code /}
 * separated, {@code .} for the base itself; any other path, and every path in {@link
 * SettingsMode#PER_USER} mode, stays as given. {@link #read} resolves a relative value against the
 * base's real path in every mode, never the working directory; a value with a root, such as {@code
 * \mock} on Windows, stays as stored. Real paths make a link into the installation relativize and
 * keep a {@code ..} from escaping it. Blocking; thread-safe.
 */
public final class InstallPaths {
  private final Path base;
  private final boolean relativizes;
  private volatile Path realBase;

  /**
   * Creates the paths of a location; nothing is read until the first call.
   *
   * @param location the settings location
   */
  public InstallPaths(SettingsLocation location) {
    Path home = location.home() == null ? location.directory() : location.home();
    this.base = home.toAbsolutePath().normalize();
    this.relativizes = location.mode() != SettingsMode.PER_USER;
  }

  /**
   * Returns a stored value as an absolute path.
   *
   * @param stored the value; blank for none
   * @return a value without a root resolved against the base and normalized; any other value
   *     unchanged
   */
  public String read(String stored) {
    if (stored.isBlank()) {
      return stored;
    }
    try {
      Path path = Path.of(stored);
      return path.getRoot() != null ? stored : realBase().resolve(path).normalize().toString();
    } catch (InvalidPathException e) {
      return stored;
    }
  }

  /**
   * Returns the value to store for a path.
   *
   * @param path an absolute path; empty for none
   * @return the path relative to the base where the mode and its real path allow, else unchanged
   */
  public String write(String path) {
    if (!relativizes || path.isEmpty()) {
      return path;
    }
    Path given;
    try {
      given = Path.of(path);
    } catch (InvalidPathException e) {
      return path;
    }
    if (!given.isAbsolute()) {
      return path;
    }
    Path root = realBase();
    Path real = real(given);
    if (!real.startsWith(root)) {
      return path;
    }
    Path inside = root.relativize(real);
    StringBuilder text = new StringBuilder();
    for (Path name : inside) {
      if (text.length() > 0) {
        text.append('/');
      }
      text.append(name);
    }
    return text.length() == 0 ? "." : text.toString();
  }

  private Path realBase() {
    Path known = realBase;
    if (known != null) {
      return known;
    }
    try {
      known = base.toRealPath();
      realBase = known;
      return known;
    } catch (IOException e) {
      return base;
    }
  }

  /** Returns the real path of the nearest existing ancestor with the missing names appended. */
  private static Path real(Path path) {
    Path current = path.normalize();
    Deque<Path> missing = new ArrayDeque<Path>();
    while (current != null) {
      try {
        Path real = current.toRealPath();
        for (Path name : missing) {
          real = real.resolve(name);
        }
        return real;
      } catch (IOException e) {
        Path name = current.getFileName();
        if (name == null) {
          break;
        }
        missing.addFirst(name);
        current = current.getParent();
      }
    }
    return path.normalize();
  }
}
