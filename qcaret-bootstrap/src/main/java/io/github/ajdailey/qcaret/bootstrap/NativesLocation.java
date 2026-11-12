package io.github.ajdailey.qcaret.bootstrap;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The directory SWT's natives load from, in order of precedence:
 *
 * <ol>
 *   <li>a bundle's {@code <qcaret.home>/lib/swt}, verified and never written;
 *   <li>{@code <settings>/cache/swt/<swt version>-<target>} for {@code --settings <dir>}, or with
 *       the portable settings directory: {@code <qcaret.home>} when {@code
 *       <qcaret.home>/qcaret.conf} exists, else on Linux {@code <qcaret.home>/bin} when {@code
 *       qcaret.conf} exists there;
 *   <li>the per-user cache: {@code ${XDG_CACHE_HOME:-~/.cache}/qcaret/swt} on Linux, {@code
 *       %LOCALAPPDATA%\QCaret\cache\swt} on Windows, {@code
 *       ~/Library/Caches/io.github.ajdailey.qcaret/swt} on macOS, each with a {@code <swt
 *       version>-<target>} child.
 * </ol>
 *
 * @param dir the natives directory
 * @param writable false for a bundle directory
 */
record NativesLocation(Path dir, boolean writable) {
  /**
   * Resolves the location.
   *
   * @param home {@code qcaret.home}
   * @param settings the validated {@code --settings} directory, or null
   * @param target the running target
   * @param swtVersion the SWT version from the index
   * @param env the process environment
   * @param userHome the {@code user.home} property
   * @return the location
   */
  static NativesLocation resolve(
      Path home,
      Path settings,
      Target target,
      String swtVersion,
      Map<String, String> env,
      String userHome) {
    Path bundled = home.resolve("lib").resolve("swt");
    if (Files.isDirectory(bundled)) {
      return new NativesLocation(bundled, false);
    }
    String leaf = swtVersion + "-" + target.id();
    if (settings != null) {
      return new NativesLocation(settings.resolve("cache").resolve("swt").resolve(leaf), true);
    }
    Path portable = portable(home, target);
    if (portable != null) {
      return new NativesLocation(portable.resolve("cache").resolve("swt").resolve(leaf), true);
    }
    return new NativesLocation(userCache(target, env, userHome).resolve(leaf), true);
  }

  /**
   * Returns the portable settings directory: {@code <home>} when it holds {@code qcaret.conf}, else
   * on Linux {@code <home>/bin} when that holds one; null otherwise. Follows {@code
   * SettingsLocation}, which the bootstrap cannot load.
   */
  private static Path portable(Path home, Target target) {
    if (Files.isRegularFile(home.resolve("qcaret.conf"))) {
      return home;
    }
    if (!target.isWindows() && !target.isMac()) {
      Path bin = home.resolve("bin");
      if (Files.isRegularFile(bin.resolve("qcaret.conf"))) {
        return bin;
      }
    }
    return null;
  }

  private static Path userCache(Target target, Map<String, String> env, String userHome) {
    Path home = Path.of(userHome);
    if (target.isWindows()) {
      Path local = absolute(env.get("LOCALAPPDATA"));
      if (local == null) {
        local = home.resolve("AppData").resolve("Local");
      }
      return local.resolve("QCaret").resolve("cache").resolve("swt");
    }
    if (target.isMac()) {
      return home.resolve("Library")
          .resolve("Caches")
          .resolve("io.github.ajdailey.qcaret")
          .resolve("swt");
    }
    Path xdg = absolute(env.get("XDG_CACHE_HOME"));
    if (xdg == null) {
      xdg = home.resolve(".cache");
    }
    return xdg.resolve("qcaret").resolve("swt");
  }

  /** Returns the value as a path when it is a non-empty absolute path, else null. */
  private static Path absolute(String value) {
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
}
