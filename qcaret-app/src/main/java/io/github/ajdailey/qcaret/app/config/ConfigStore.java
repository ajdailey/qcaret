package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.util.Streams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves {@link Preferences} in {@code qcaret.conf} in the settings directory. Both block,
 * so they run off the UI thread; a lock serializes them. Thread-safe.
 *
 * <p>The file is Java properties with {@code version=1}; a file without {@code version} is read as
 * version 1, so an empty portable marker is valid. A file that cannot be read, is over {@link
 * #MAX_BYTES}, does not parse or has another version is malformed: defaults apply, one WARN names
 * it, and the first save copies it to {@code qcaret.conf.invalid} before replacing it. A value that
 * does not parse falls back to its default. Saves write atomically ({@link AtomicFiles}). A
 * read-only settings directory disables saving with one WARN. Only file names and keys are logged,
 * never values.
 */
public final class ConfigStore {
  /** Largest file read; a longer one is malformed. */
  public static final int MAX_BYTES = 1 << 20;

  /** The format version written. */
  public static final String VERSION = "1";

  static final String VERSION_KEY = "version";
  static final String THEME_KEY = "ui.theme";
  static final String WINDOW_X = "window.x";
  static final String WINDOW_Y = "window.y";
  static final String WINDOW_WIDTH = "window.width";
  static final String WINDOW_HEIGHT = "window.height";
  static final String WINDOW_MAXIMIZED = "window.maximized";
  static final String DEBUG_LOG_FILE = "log.debugFile";
  static final String COMPLETION_CHARS = "editor.completionChars";
  static final String INVALID_SUFFIX = ".invalid";

  /** Largest window coordinate or size accepted, in pixels. */
  static final int MAX_PIXELS = 100_000;

  private static final Logger LOG = LoggerFactory.getLogger(ConfigStore.class);

  private final SettingsLocation location;
  private final ReentrantLock lock = new ReentrantLock();
  private boolean keepInvalid;
  private boolean saveDisabled;

  /**
   * Creates the store; nothing is read until {@link #load}.
   *
   * @param location the settings directory
   */
  public ConfigStore(SettingsLocation location) {
    this.location = location;
  }

  /**
   * Returns the settings directory.
   *
   * @return the location
   */
  public SettingsLocation location() {
    return location;
  }

  /**
   * Reports whether saving is disabled because the directory is read-only.
   *
   * @return true after a load or save found the directory read-only
   */
  public boolean saveDisabled() {
    lock.lock();
    try {
      return saveDisabled;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reads the settings. Blocking.
   *
   * @return the settings; defaults when the file is missing or malformed
   */
  public Preferences load() {
    lock.lock();
    try {
      Path dir = location.directory();
      if (Files.isDirectory(dir) && !Files.isWritable(dir)) {
        disableSaving();
      }
      Path file = SafePaths.child(dir, SettingsLocation.MARKER);
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file)) {
        bytes = Streams.readLimited(in, MAX_BYTES);
      } catch (NoSuchFileException e) {
        return Preferences.DEFAULTS;
      }
      Properties properties = parse(bytes);
      if (properties == null) {
        malformed(file);
        return Preferences.DEFAULTS;
      }
      return read(properties, file);
    } catch (IOException e) {
      LOG.warn(
          "Cannot read settings in {}: {}",
          LogText.oneLine(location.directory()),
          LogText.oneLine(e.getMessage()));
      keepInvalid = true;
      return Preferences.DEFAULTS;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Writes the settings atomically, creating a per-user directory first. Blocking.
   *
   * @param preferences the settings
   * @return false when saving is disabled or the write failed, which is logged
   */
  public boolean save(Preferences preferences) {
    lock.lock();
    try {
      if (saveDisabled) {
        return false;
      }
      Path dir = location.directory();
      if (location.mode() == SettingsMode.PER_USER && !Files.isDirectory(dir)) {
        SafePaths.settingsDirectory(dir);
      }
      Path file = SafePaths.child(dir, SettingsLocation.MARKER);
      if (keepInvalid && Files.exists(file)) {
        Path invalid = SafePaths.child(dir, SettingsLocation.MARKER + INVALID_SUFFIX);
        Files.copy(file, invalid, StandardCopyOption.REPLACE_EXISTING);
      }
      keepInvalid = false;
      AtomicFiles.write(file, format(preferences));
      return true;
    } catch (AccessDeniedException e) {
      disableSaving();
      return false;
    } catch (IOException e) {
      if (Files.isDirectory(location.directory()) && !Files.isWritable(location.directory())) {
        disableSaving();
      } else {
        LOG.warn(
            "Cannot save settings in {}: {}",
            LogText.oneLine(location.directory()),
            LogText.oneLine(e.getMessage()));
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  /** Returns the properties, or null when the bytes do not parse or name another version. */
  private static Properties parse(byte[] bytes) throws IOException {
    Properties properties = new Properties();
    try {
      properties.load(new ByteArrayInputStream(bytes));
    } catch (IllegalArgumentException e) {
      return null;
    }
    String version = properties.getProperty(VERSION_KEY);
    if (version != null && !VERSION.equals(version.strip())) {
      return null;
    }
    return properties;
  }

  private static Preferences read(Properties properties, Path file) {
    Preferences preferences = Preferences.DEFAULTS;
    String theme = properties.getProperty(THEME_KEY);
    if (theme != null) {
      ThemeMode mode = ThemeMode.parse(theme);
      if (mode == null) {
        invalid(THEME_KEY, file);
      } else {
        preferences = preferences.withTheme(mode);
      }
    }
    preferences = preferences.withDebugLogFile(flag(properties, DEBUG_LOG_FILE, false, file));
    long chars = number(properties, COMPLETION_CHARS, 0, Preferences.MAX_COMPLETION_CHARS, file);
    if (chars != Long.MIN_VALUE) {
      preferences = preferences.withCompletionChars((int) chars);
    }
    return preferences.withWindow(
        new WindowState(
            optional(properties, WINDOW_X, -MAX_PIXELS, MAX_PIXELS, file),
            optional(properties, WINDOW_Y, -MAX_PIXELS, MAX_PIXELS, file),
            optional(properties, WINDOW_WIDTH, 1, MAX_PIXELS, file),
            optional(properties, WINDOW_HEIGHT, 1, MAX_PIXELS, file),
            flag(properties, WINDOW_MAXIMIZED, false, file)));
  }

  /** Returns a whole number in range, null when absent, or null with one WARN when invalid. */
  private static Integer optional(Properties properties, String key, int min, int max, Path file) {
    long value = number(properties, key, min, max, file);
    return value == Long.MIN_VALUE ? null : Integer.valueOf((int) value);
  }

  /**
   * Returns a whole number in range, {@code Long.MIN_VALUE} when absent, or {@code Long.MIN_VALUE}
   * with one WARN when invalid.
   */
  private static long number(Properties properties, String key, long min, long max, Path file) {
    String value = properties.getProperty(key);
    if (value == null) {
      return Long.MIN_VALUE;
    }
    try {
      long parsed = Long.parseLong(value.strip());
      if (parsed >= min && parsed <= max) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    invalid(key, file);
    return Long.MIN_VALUE;
  }

  private static boolean flag(Properties properties, String key, boolean fallback, Path file) {
    String value = properties.getProperty(key);
    if (value == null) {
      return fallback;
    }
    String text = value.strip();
    if (text.equals("true") || text.equals("false")) {
      return text.equals("true");
    }
    invalid(key, file);
    return fallback;
  }

  private static void invalid(String key, Path file) {
    LOG.warn("Ignoring an invalid {} in {}", LogText.oneLine(key), LogText.oneLine(file));
  }

  /** Returns the file content: sorted keys, ISO-8859-1 with escapes, no date comment. */
  static byte[] format(Preferences preferences) throws IOException {
    Properties properties = new Properties();
    properties.setProperty(VERSION_KEY, VERSION);
    properties.setProperty(THEME_KEY, preferences.theme().key());
    WindowState window = preferences.window();
    storeOptional(properties, WINDOW_X, window.x());
    storeOptional(properties, WINDOW_Y, window.y());
    storeOptional(properties, WINDOW_WIDTH, window.width());
    storeOptional(properties, WINDOW_HEIGHT, window.height());
    properties.setProperty(WINDOW_MAXIMIZED, Boolean.toString(window.maximized()));
    properties.setProperty(DEBUG_LOG_FILE, Boolean.toString(preferences.debugLogFile()));
    properties.setProperty(COMPLETION_CHARS, Integer.toString(preferences.completionChars()));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    properties.store(out, null);
    String text = out.toString(StandardCharsets.ISO_8859_1);
    StringBuilder kept = new StringBuilder();
    for (String line : text.split("\\R")) {
      if (!line.startsWith("#")) {
        kept.append(line).append('\n');
      }
    }
    return kept.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  private static void storeOptional(Properties properties, String key, Integer value) {
    if (value != null) {
      properties.setProperty(key, value.toString());
    }
  }

  private void malformed(Path file) {
    keepInvalid = true;
    LOG.warn(
        "Malformed settings file {}; defaults apply and it is kept as {} before the next save",
        LogText.oneLine(file),
        SettingsLocation.MARKER + INVALID_SUFFIX);
  }

  private void disableSaving() {
    if (!saveDisabled) {
      saveDisabled = true;
      LOG.warn(
          "Settings directory {} is read-only; settings are not saved",
          LogText.oneLine(location.directory()));
    }
  }
}
