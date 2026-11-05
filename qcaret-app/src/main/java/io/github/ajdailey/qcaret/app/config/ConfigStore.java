package io.github.ajdailey.qcaret.app.config;

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
import java.util.HashMap;
import java.util.Map;
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
 * does not parse falls back to its default. A read-only settings directory disables saving with one
 * WARN. Only file names and keys are logged, never values.
 *
 * <p>{@link #save} re-reads the file and writes only the keys that differ between the two settings
 * it is given, so another process's newer values and keys this version does not know stay. A
 * missing or malformed file gets every key. Saves write atomically ({@link AtomicFiles}). Two
 * processes saving within the same read and write can still lose one write: no file lock is held.
 */
public final class ConfigStore {
  /** Largest file read or written; a longer one is malformed. */
  public static final int MAX_BYTES = 1 << 20;

  /** The format version written. */
  public static final String VERSION = "1";

  static final String VERSION_KEY = "version";
  static final String INVALID_SUFFIX = ".invalid";

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
      Properties properties = read(file);
      return properties == null ? Preferences.DEFAULTS : PreferenceKeys.read(properties, file);
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
   * Writes the keys that differ between two settings over the file's current keys, atomically,
   * creating a per-user directory first. Does nothing when no key differs and the file is valid.
   * Blocking.
   *
   * @param before the settings the change started from
   * @param after the settings to save
   * @return false when saving is disabled or the write failed, which is logged
   */
  public boolean save(Preferences before, Preferences after) {
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
      Map<String, String> changed = changes(before, after);
      Properties properties = read(file);
      if (properties == null) {
        properties = new Properties();
        changed.putAll(PreferenceKeys.write(after));
      } else if (changed.isEmpty()) {
        return true;
      }
      for (Map.Entry<String, String> entry : changed.entrySet()) {
        if (entry.getValue() == null) {
          properties.remove(entry.getKey());
        } else {
          properties.setProperty(entry.getKey(), entry.getValue());
        }
      }
      properties.setProperty(VERSION_KEY, VERSION);
      byte[] bytes = format(properties);
      if (bytes.length > MAX_BYTES) {
        LOG.warn("Settings not saved: {} would be over 1 MiB", SettingsLocation.MARKER);
        return false;
      }
      if (keepInvalid && Files.exists(file)) {
        Path invalid = SafePaths.child(dir, SettingsLocation.MARKER + INVALID_SUFFIX);
        Files.copy(file, invalid, StandardCopyOption.REPLACE_EXISTING);
      }
      keepInvalid = false;
      AtomicFiles.write(file, bytes);
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

  /**
   * Applies another process's changes to settings that moved on since they were read.
   *
   * @param base the settings both sides started from
   * @param theirs the settings as read from the file
   * @param mine the settings to keep otherwise
   * @return {@code mine} with the keys that differ between {@code base} and {@code theirs}, apart
   *     from keys {@code mine} also changed: those are saved after the file was read
   */
  public static Preferences merge(Preferences base, Preferences theirs, Preferences mine) {
    Properties keys = new Properties();
    keys.putAll(PreferenceKeys.write(mine));
    Map<String, String> changedHere = changes(base, mine);
    for (Map.Entry<String, String> entry : changes(base, theirs).entrySet()) {
      if (changedHere.containsKey(entry.getKey())) {
        continue;
      }
      if (entry.getValue() == null) {
        keys.remove(entry.getKey());
      } else {
        keys.setProperty(entry.getKey(), entry.getValue());
      }
    }
    return PreferenceKeys.read(keys, Path.of(SettingsLocation.MARKER));
  }

  /** Returns the keys that differ, each with its new value or null when it goes. */
  private static Map<String, String> changes(Preferences before, Preferences after) {
    Map<String, String> old = PreferenceKeys.write(before);
    Map<String, String> next = PreferenceKeys.write(after);
    Map<String, String> changed = new HashMap<String, String>();
    for (Map.Entry<String, String> entry : next.entrySet()) {
      if (!entry.getValue().equals(old.get(entry.getKey()))) {
        changed.put(entry.getKey(), entry.getValue());
      }
    }
    for (String key : old.keySet()) {
      if (!next.containsKey(key)) {
        changed.put(key, null);
      }
    }
    return changed;
  }

  /**
   * Returns the file's properties, or null when it is missing or, with {@link #keepInvalid} set,
   * when it is malformed or cannot be read.
   */
  private Properties read(Path file) {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(file)) {
      bytes = Streams.readLimited(in, MAX_BYTES);
    } catch (NoSuchFileException e) {
      keepInvalid = false;
      return null;
    } catch (IOException e) {
      if (!keepInvalid) {
        LOG.warn(
            "Cannot read settings in {}: {}",
            LogText.oneLine(location.directory()),
            LogText.oneLine(e.getMessage()));
      }
      keepInvalid = true;
      return null;
    }
    Properties properties = parse(bytes);
    if (properties == null) {
      malformed(file);
      return null;
    }
    keepInvalid = false;
    return properties;
  }

  /** Returns the properties, or null when the bytes do not parse or name another version. */
  private static Properties parse(byte[] bytes) {
    Properties properties = new Properties();
    try {
      properties.load(new ByteArrayInputStream(bytes));
    } catch (IllegalArgumentException | IOException e) {
      return null;
    }
    String version = properties.getProperty(VERSION_KEY);
    if (version != null && !VERSION.equals(version.strip())) {
      return null;
    }
    return properties;
  }

  /** Returns the file content: sorted keys, ISO-8859-1 with escapes, no date comment. */
  private static byte[] format(Properties properties) throws IOException {
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

  private void malformed(Path file) {
    if (!keepInvalid) {
      LOG.warn(
          "Malformed settings file {}; defaults apply and it is kept as {} before the next save",
          LogText.oneLine(file),
          SettingsLocation.MARKER + INVALID_SUFFIX);
    }
    keepInvalid = true;
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
