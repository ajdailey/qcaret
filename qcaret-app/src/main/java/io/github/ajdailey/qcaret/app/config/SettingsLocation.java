package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.core.util.os.EnvPaths;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The settings directory and how it was chosen. In order of precedence: {@code --settings <dir>};
 * {@code <qcaret.home>} when {@code <qcaret.home>/qcaret.conf} exists (portable); on Linux, {@code
 * <qcaret.home>/bin} when {@code qcaret.conf} exists beside the {@code bin/qcaret} script
 * (portable); the per-user directory: {@code $XDG_CONFIG_HOME/qcaret} (default {@code
 * ~/.config/qcaret}) on Linux, {@code %APPDATA%\QCaret} on Windows, {@code ~/Library/Application
 * Support/QCaret} on macOS.
 *
 * @param mode how the directory was chosen
 * @param directory the directory; a per-user directory may not exist yet
 * @param home the installation directory, {@code qcaret.home}; null outside a packaged run
 */
public record SettingsLocation(SettingsMode mode, Path directory, Path home) {
  /** The portable-mode marker, which is also the settings file. */
  public static final String MARKER = "qcaret.conf";

  /**
   * Creates a location outside a packaged run.
   *
   * @param mode how the directory was chosen
   * @param directory the directory
   */
  public SettingsLocation(SettingsMode mode, Path directory) {
    this(mode, directory, null);
  }

  /**
   * Resolves the location from this process's properties and environment.
   *
   * @param explicit the validated {@code --settings} directory, or null
   * @return the location
   */
  public static SettingsLocation fromSystem(Path explicit) {
    return resolve(
        explicit,
        System.getProperty("qcaret.home"),
        OsTraits.current(),
        System.getenv(),
        System.getProperty("user.home"));
  }

  /**
   * Resolves the location.
   *
   * @param explicit the validated {@code --settings} directory, or null
   * @param home the {@code qcaret.home} property, or null outside a packaged run
   * @param os the system
   * @param env the process environment
   * @param userHome the {@code user.home} property
   * @return the location
   */
  public static SettingsLocation resolve(
      Path explicit, String home, OsTraits os, Map<String, String> env, String userHome) {
    Path installed = EnvPaths.absolute(home);
    if (explicit != null) {
      return new SettingsLocation(SettingsMode.EXPLICIT, explicit, installed);
    }
    if (installed != null) {
      if (Files.isRegularFile(installed.resolve(MARKER))) {
        return new SettingsLocation(SettingsMode.PORTABLE, installed, installed);
      }
      Path launcher = os.launcherDirectory(installed);
      if (launcher != null && Files.isRegularFile(launcher.resolve(MARKER))) {
        return new SettingsLocation(SettingsMode.PORTABLE, launcher, installed);
      }
    }
    return new SettingsLocation(
        SettingsMode.PER_USER, os.userSettings(env, Path.of(userHome)), installed);
  }

  /**
   * Reports whether QCaret keeps its files out of the user's home directory: explicit and portable
   * mode on Linux. There {@code user.home} is the settings directory and the user guide opens
   * outside the process, since WebKitGTK writes under the XDG cache and data directories.
   *
   * @param os the system
   * @return true in explicit and portable mode on Linux
   */
  public boolean confined(OsTraits os) {
    return mode != SettingsMode.PER_USER && os.confinesToSettings();
  }

  /**
   * Reports {@link #confined(OsTraits)} for this process's system.
   *
   * @return true in explicit and portable mode on Linux
   */
  public boolean confined() {
    return confined(OsTraits.current());
  }
}
