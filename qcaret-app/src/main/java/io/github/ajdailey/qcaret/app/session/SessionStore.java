package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.ViewDefaults;
import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.util.Streams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves {@link Session} in {@code qcaret_session.conf} in the settings directory, as Java
 * properties with {@code version=1}: {@code recent.N}, {@code file.N.*}, {@code window.W.*} and
 * {@code window.W.tab.N.*}, each list numbered from 0 without gaps. Both block; a lock serializes
 * them. Thread-safe.
 *
 * <p>A file without {@code version} reads as version 1. A file that cannot be read, is over {@link
 * #MAX_BYTES}, does not parse or has another version is malformed: {@link #load} returns {@link
 * Session#EMPTY}, one WARN names it, and the next save copies it to {@code
 * qcaret_session.conf.invalid} before replacing it. A value that does not parse falls back to its
 * default; a tab without a path or buffer is skipped. A session keeps at most {@link #MAX_WINDOWS}
 * windows, {@link #MAX_TABS} tabs over all windows, {@link #MAX_FILES} file entries and {@link
 * #MAX_COMMENTS} comments over all tabs. {@code droppedComments}, written when not 0, counts the
 * comments a save left out; a missing or malformed value reads as 0. A read-only settings directory
 * disables saving with one WARN. Paths and comments are never logged.
 */
public final class SessionStore {
  /** The file name in the settings directory. */
  public static final String FILE = "qcaret_session.conf";

  /** The format version written. */
  public static final String VERSION = "1";

  /** Most windows kept. */
  public static final int MAX_WINDOWS = 32;

  /** Most tabs kept over all windows. */
  public static final int MAX_TABS = 256;

  /** Most files in the per-file LRU. */
  public static final int MAX_FILES = 500;

  /** Most recent files kept. */
  public static final int MAX_RECENT = 20;

  /** Most closed files kept per window. */
  public static final int MAX_CLOSED = 20;

  /** Most expanded directories kept per window. */
  public static final int MAX_EXPANDED = 1000;

  /** Most retained searches kept per window. */
  public static final int MAX_SEARCHES = 32;

  /** Most bookmark comments kept over all tabs. */
  public static final int MAX_COMMENTS = 1000;

  /** Largest file read or written; a longer one is malformed. */
  public static final int MAX_BYTES = 4 << 20;

  static final String INVALID_SUFFIX = ".invalid";

  private static final Logger LOG = LoggerFactory.getLogger(SessionStore.class);
  static final String VERSION_KEY = "version";
  static final String RECENT = "recent.";
  static final String FILE_KEY = "file.";
  static final String WINDOW = "window.";
  static final String DROPPED_COMMENTS = "droppedComments";

  private final SettingsLocation location;
  private final ReentrantLock lock = new ReentrantLock();
  private boolean keepInvalid;
  private boolean saveDisabled;

  /**
   * Creates the store; nothing is read until {@link #load}.
   *
   * @param location the settings directory
   */
  public SessionStore(SettingsLocation location) {
    this.location = location;
  }

  /**
   * Returns the settings directory the file lives in.
   *
   * @return the location
   */
  public SettingsLocation location() {
    return location;
  }

  /**
   * Reads the session; a window entry without a display key takes {@link ViewDefaults#DEFAULTS}.
   * Blocking.
   *
   * @return the session; {@link Session#EMPTY} when the file is missing or malformed
   */
  public Session load() {
    return load(ViewDefaults.DEFAULTS);
  }

  /**
   * Reads the session. Blocking.
   *
   * @param absent the display state and browser visibility of a window entry that lacks the key
   * @return the session; {@link Session#EMPTY} when the file is missing or malformed
   */
  public Session load(ViewDefaults absent) {
    lock.lock();
    try {
      Path dir = location.directory();
      if (Files.isDirectory(dir) && !Files.isWritable(dir)) {
        disableSaving();
      }
      Properties p = read(SafePaths.child(dir, FILE));
      return p == null ? Session.EMPTY : SessionReader.parse(p, absent);
    } catch (IOException e) {
      LOG.warn("Cannot read {}: {}", FILE, LogText.oneLine(e.getMessage()));
      keepInvalid = true;
      return Session.EMPTY;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Writes the session atomically, creating a per-user directory first. Blocking.
   *
   * @param session the session
   * @return false when saving is disabled, the file would be over {@link #MAX_BYTES} or the write
   *     failed, which is logged
   */
  public boolean save(Session session) {
    lock.lock();
    try {
      if (saveDisabled) {
        return false;
      }
      Path dir = location.directory();
      if (location.mode() == SettingsMode.PER_USER && !Files.isDirectory(dir)) {
        SafePaths.settingsDirectory(dir);
      }
      byte[] bytes = format(SessionWriter.keys(session));
      if (bytes.length > MAX_BYTES) {
        LOG.warn("Session not saved: {} would be over {} bytes", FILE, MAX_BYTES);
        return false;
      }
      Path file = SafePaths.child(dir, FILE);
      if (keepInvalid && Files.exists(file)) {
        Path invalid = SafePaths.child(dir, FILE + INVALID_SUFFIX);
        Files.copy(file, invalid, StandardCopyOption.REPLACE_EXISTING);
      }
      keepInvalid = false;
      AtomicFiles.write(file, bytes);
      return true;
    } catch (IOException e) {
      // A denied move alone says nothing about the directory: on Windows a directory at the
      // target or a file open elsewhere is denied too, and the next save can succeed.
      if (Files.isDirectory(location.directory()) && !Files.isWritable(location.directory())) {
        disableSaving();
      } else {
        LOG.warn("Cannot save {}: {}", FILE, LogText.oneLine(e.getMessage()));
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  private void disableSaving() {
    if (!saveDisabled) {
      saveDisabled = true;
      LOG.warn("Settings directory is read-only; {} is not saved", FILE);
    }
  }

  /** Returns the file's properties, or null when it is missing or malformed. */
  private Properties read(Path file) {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(file)) {
      bytes = Streams.readLimited(in, MAX_BYTES);
    } catch (NoSuchFileException e) {
      keepInvalid = false;
      return null;
    } catch (IOException e) {
      LOG.warn("Cannot read {}: {}", FILE, LogText.oneLine(e.getMessage()));
      keepInvalid = true;
      return null;
    }
    Properties properties = new Properties();
    try {
      properties.load(new ByteArrayInputStream(bytes));
    } catch (IllegalArgumentException | IOException e) {
      return malformed();
    }
    String version = properties.getProperty(VERSION_KEY);
    if (version != null && !VERSION.equals(version.strip())) {
      return malformed();
    }
    keepInvalid = false;
    return properties;
  }

  private Properties malformed() {
    LOG.warn("Malformed {}; it is kept as {} before the next save", FILE, FILE + INVALID_SUFFIX);
    keepInvalid = true;
    return null;
  }

  private static byte[] format(Map<String, String> keys) throws IOException {
    Properties properties = new Properties();
    properties.putAll(keys);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    properties.store(out, null);
    StringBuilder kept = new StringBuilder();
    for (String line : out.toString(StandardCharsets.ISO_8859_1).split("\\R")) {
      if (!line.startsWith("#")) {
        kept.append(line).append('\n');
      }
    }
    return kept.toString().getBytes(StandardCharsets.ISO_8859_1);
  }
}
