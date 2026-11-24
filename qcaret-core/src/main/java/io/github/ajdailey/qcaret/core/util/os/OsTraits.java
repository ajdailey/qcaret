package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What differs between the operating systems QCaret runs on, one method per question a caller asks.
 * {@link #current()} is chosen once from {@code os.name}; nothing else in core, app or the launcher
 * reads that property. Tests take another system's answers from {@link #of(String)}.
 */
public interface OsTraits {
  /** How {@code ProcessLock} takes its lock. */
  enum LockKind {
    /** {@code FileChannel.tryLock}. */
    CHANNEL,
    /** {@code flock(2)} with the Linux constants. */
    FLOCK_LINUX,
    /** {@code flock(2)} with the macOS constants. */
    FLOCK_MACOS
  }

  /**
   * Returns this process's traits.
   *
   * @return the traits for this JVM's {@code os.name}
   */
  static OsTraits current() {
    return Current.TRAITS;
  }

  /**
   * Returns a system's traits: Windows for a name starting {@code Windows}, macOS for one starting
   * {@code Mac}, ignoring case, and Linux for any other name, null included.
   *
   * @param osName an {@code os.name} value, or null
   * @return the traits
   */
  static OsTraits of(String osName) {
    String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    if (name.startsWith("windows")) {
      return WindowsTraits.INSTANCE;
    }
    if (name.startsWith("mac")) {
      return MacTraits.INSTANCE;
    }
    return LinuxTraits.INSTANCE;
  }

  /**
   * Returns this JVM's OS name, version and architecture, space-separated, as About shows them.
   *
   * @return the line
   */
  static String description() {
    return System.getProperty("os.name")
        + " "
        + System.getProperty("os.version")
        + " "
        + System.getProperty("os.arch");
  }

  /**
   * Reports whether the default file system ignores the case of file names.
   *
   * @return true on Windows and macOS
   */
  boolean fileNamesIgnoreCase();

  /**
   * Returns how an exclusive process lock is taken.
   *
   * @return {@link LockKind#CHANNEL} on Windows, {@code flock} with the system's constants
   *     elsewhere
   */
  LockKind fileLock();

  /**
   * Returns the file name forms this system changes or opens as a device.
   *
   * @return {@link WindowsDeviceNames} on Windows, else {@link FileNames}
   */
  DeviceNames deviceNames();

  /**
   * Returns the per-user settings directory: {@code %APPDATA%\QCaret} on Windows, {@code
   * ~/Library/Application Support/QCaret} on macOS, {@code $XDG_CONFIG_HOME/qcaret} on Linux. A
   * variable that is unset, empty or not an absolute path takes its default under the home
   * directory.
   *
   * @param env the process environment
   * @param userHome the user's home directory
   * @return the directory, which need not exist
   */
  Path userSettings(Map<String, String> env, Path userHome);

  /**
   * Returns the launcher's directory, whose {@code qcaret.conf} also selects portable mode when the
   * installation directory has none.
   *
   * @param home the installation directory, the parent of {@code lib/}
   * @return {@code <home>/bin}, beside the {@code bin/qcaret} script, on Linux; null elsewhere,
   *     where the launcher sits in the installation directory or a sealed bundle
   */
  Path launcherDirectory(Path home);

  /**
   * Returns where the toolkit and the embedded browser keep their files.
   *
   * @return the system's {@link FileConfinement}
   */
  FileConfinement fileConfinement();

  /**
   * Returns the folders the system keeps fonts in.
   *
   * @param env the process environment
   * @param home the user's home directory
   * @return the folders, which need not exist, without duplicates
   */
  List<Path> fontDirectories(Map<String, String> env, String home);

  /**
   * Reports whether the system marks an entry hidden.
   *
   * @param path the file or directory
   * @return on Windows the entry's own DOS hidden attribute, so a junction or link answers for
   *     itself and not its target; {@link Files#isHidden} elsewhere
   * @throws IOException when the attributes cannot be read
   */
  boolean hidden(Path path) throws IOException;

  /**
   * Reports whether opening a file with its default application runs it: on Windows an extension in
   * {@link RunnableTypes#WINDOWS} or {@code PATHEXT}, whatever the attributes, since every readable
   * file is executable there; on macOS an extension in {@link RunnableTypes#MAC} or the execute
   * bit; on Linux the execute bit. Reads the file's attributes.
   *
   * @param file the file
   * @return true when the file would run
   */
  boolean runsInsteadOfOpening(Path file);
}
