package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.document.Document;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
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
 * #MAX_COMMENTS} comments over all tabs. A read-only settings directory disables saving with one
 * WARN. Paths and comments are never logged.
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
  private static final String VERSION_KEY = "version";
  private static final String RECENT = "recent.";
  private static final String FILE_KEY = "file.";
  private static final String WINDOW = "window.";

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
   * Reads the session. Blocking.
   *
   * @return the session; {@link Session#EMPTY} when the file is missing or malformed
   */
  public Session load() {
    lock.lock();
    try {
      Path dir = location.directory();
      if (Files.isDirectory(dir) && !Files.isWritable(dir)) {
        disableSaving();
      }
      Properties p = read(SafePaths.child(dir, FILE));
      return p == null ? Session.EMPTY : parse(p);
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
      byte[] bytes = format(keys(session));
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
    } catch (AccessDeniedException e) {
      disableSaving();
      return false;
    } catch (IOException e) {
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

  // Writing.

  private static Map<String, String> keys(Session session) {
    Map<String, String> keys = new TreeMap<String, String>();
    keys.put(VERSION_KEY, VERSION);
    list(keys, RECENT, session.recent(), MAX_RECENT);
    int files = Math.min(MAX_FILES, session.files().size());
    for (int i = 0; i < files; i++) {
      Session.FileState f = session.files().get(i);
      String key = FILE_KEY + i + ".";
      keys.put(key + "path", f.path());
      put(keys, key + "encoding", f.encoding());
      put(keys, key + "language", f.language());
      keys.put(key + "tabWidth", Integer.toString(f.tabWidth()));
      keys.put(key + "spaces", Boolean.toString(f.insertSpaces()));
      put(keys, key + "selection", f.selections());
      keys.put(key + "top", Integer.toString(f.top()));
    }
    int tabs = 0;
    int comments = 0;
    int windows = Math.min(MAX_WINDOWS, session.windows().size());
    for (int w = 0; w < windows; w++) {
      Session.Window window = session.windows().get(w);
      String key = WINDOW + w + ".";
      WindowState bounds = window.bounds();
      optional(keys, key + "x", bounds.x());
      optional(keys, key + "y", bounds.y());
      optional(keys, key + "width", bounds.width());
      optional(keys, key + "height", bounds.height());
      keys.put(key + "maximized", Boolean.toString(bounds.maximized()));
      keys.put(key + "active", Integer.toString(window.active()));
      put(keys, key + "workspace", window.workspace());
      keys.put(key + "browser", Boolean.toString(window.browser()));
      put(keys, key + "sideWeights", window.sideWeights());
      list(keys, key + "expanded.", window.expanded(), MAX_EXPANDED);
      keys.put(key + "results", Boolean.toString(window.results()));
      put(keys, key + "dockWeights", window.dockWeights());
      list(keys, key + "closed.", window.closed(), MAX_CLOSED);
      for (int i = 0; i < Math.min(MAX_SEARCHES, window.searches().size()); i++) {
        search(keys, key + "search." + i + ".", window.searches().get(i));
      }
      int count = Math.min(MAX_TABS - tabs, window.tabs().size());
      for (int i = 0; i < count; i++) {
        comments += tab(keys, key + "tab." + i + ".", window.tabs().get(i), comments);
      }
      tabs += count;
    }
    return keys;
  }

  /** Writes a tab and returns the comments written. */
  private static int tab(Map<String, String> keys, String key, Session.Tab tab, int written) {
    put(keys, key + "path", tab.path());
    put(keys, key + "title", tab.title());
    put(keys, key + "buffer", tab.buffer());
    put(keys, key + "encoding", tab.encoding());
    put(keys, key + "language", tab.language());
    keys.put(key + "binary", Boolean.toString(tab.binary()));
    keys.put(key + "tabWidth", Integer.toString(tab.tabWidth()));
    keys.put(key + "spaces", Boolean.toString(tab.insertSpaces()));
    keys.put(key + "focused", Integer.toString(tab.focused()));
    keys.put(key + "sideBySide", Boolean.toString(tab.sideBySide()));
    put(keys, key + "weights", tab.weights());
    put(keys, key + "bookmarks", tab.bookmarks());
    for (int v = 0; v < Math.min(2, tab.views().size()); v++) {
      Session.View view = tab.views().get(v);
      String viewKey = key + "view." + v + ".";
      keys.put(viewKey + "selection", view.selections());
      keys.put(viewKey + "top", Integer.toString(view.top()));
      put(keys, viewKey + "folds", view.folds());
    }
    int count = Math.min(MAX_COMMENTS - written, tab.comments().size());
    for (int k = 0; k < count; k++) {
      Session.Comment comment = tab.comments().get(k);
      keys.put(key + "comment." + k, comment.line() + " " + comment.text());
    }
    return count;
  }

  private static void search(Map<String, String> keys, String key, Session.Search search) {
    SearchRequest r = search.request();
    keys.put(key + "text", r.text());
    keys.put(key + "regex", Boolean.toString(r.regex()));
    keys.put(key + "case", Boolean.toString(r.caseSensitive()));
    keys.put(key + "word", Boolean.toString(r.wholeWord()));
    keys.put(key + "scope", r.scope().name());
    put(keys, key + "folder", r.folder() == null ? "" : r.folder().toString());
    put(keys, key + "include", r.include());
    put(keys, key + "exclude", r.exclude());
    put(keys, key + "excluded", r.excludedDirectories());
    keys.put(key + "hidden", Boolean.toString(r.hidden()));
    keys.put(key + "pinned", Boolean.toString(search.pinned()));
  }

  private static void put(Map<String, String> keys, String key, String value) {
    if (!value.isEmpty()) {
      keys.put(key, value);
    }
  }

  private static void optional(Map<String, String> keys, String key, Integer value) {
    if (value != null) {
      keys.put(key, value.toString());
    }
  }

  private static void list(Map<String, String> keys, String prefix, List<String> list, int max) {
    for (int i = 0; i < Math.min(max, list.size()); i++) {
      keys.put(prefix + i, list.get(i));
    }
  }

  // Reading.

  private static Session parse(Properties p) {
    List<Session.Window> windows = new ArrayList<Session.Window>();
    int tabs = 0;
    int comments = 0;
    for (int w = 0; w < MAX_WINDOWS; w++) {
      String key = WINDOW + w + ".";
      if (p.getProperty(key + "active") == null) {
        break;
      }
      List<Session.Tab> list = new ArrayList<Session.Tab>();
      for (int i = 0; tabs < MAX_TABS; i++) {
        String tabKey = key + "tab." + i + ".";
        if (p.getProperty(tabKey + "tabWidth") == null) {
          break;
        }
        Session.Tab tab = tab(p, tabKey, MAX_COMMENTS - comments);
        if (!tab.path().isEmpty() || !tab.buffer().isEmpty()) {
          list.add(tab);
          tabs++;
          comments += tab.comments().size();
        }
      }
      windows.add(window(p, key, list));
    }
    return new Session(windows, list(p, RECENT, MAX_RECENT), files(p));
  }

  private static Session.Window window(Properties p, String key, List<Session.Tab> tabs) {
    List<Session.Search> searches = new ArrayList<Session.Search>();
    for (int i = 0; i < MAX_SEARCHES; i++) {
      String searchKey = key + "search." + i + ".";
      if (p.getProperty(searchKey + "text") == null) {
        break;
      }
      Session.Search search = search(p, searchKey);
      if (search != null) {
        searches.add(search);
      }
    }
    return new Session.Window(
        new WindowState(
            pixels(p, key + "x", -PIXELS),
            pixels(p, key + "y", -PIXELS),
            pixels(p, key + "width", 1),
            pixels(p, key + "height", 1),
            flag(p, key + "maximized")),
        tabs,
        number(p, key + "active", -1, MAX_TABS, -1),
        p.getProperty(key + "workspace", ""),
        flag(p, key + "browser"),
        p.getProperty(key + "sideWeights", ""),
        list(p, key + "expanded.", MAX_EXPANDED),
        flag(p, key + "results"),
        p.getProperty(key + "dockWeights", ""),
        searches,
        list(p, key + "closed.", MAX_CLOSED));
  }

  private static Session.Tab tab(Properties p, String key, int maxComments) {
    List<Session.View> views = new ArrayList<Session.View>();
    for (int v = 0; v < 2; v++) {
      String viewKey = key + "view." + v + ".";
      if (p.getProperty(viewKey + "selection") == null) {
        break;
      }
      views.add(
          new Session.View(
              p.getProperty(viewKey + "selection"),
              number(p, viewKey + "top", 0, Integer.MAX_VALUE, 0),
              p.getProperty(viewKey + "folds", "")));
    }
    List<Session.Comment> comments = new ArrayList<Session.Comment>();
    for (int k = 0; comments.size() < maxComments; k++) {
      String value = p.getProperty(key + "comment." + k);
      if (value == null) {
        break;
      }
      int space = value.indexOf(' ');
      if (space > 0) {
        int[] line = SessionText.numbers(value.substring(0, space));
        if (line.length == 1) {
          comments.add(new Session.Comment(line[0], value.substring(space + 1)));
        }
      }
    }
    return new Session.Tab(
        p.getProperty(key + "path", ""),
        p.getProperty(key + "title", ""),
        p.getProperty(key + "buffer", ""),
        p.getProperty(key + "encoding", ""),
        p.getProperty(key + "language", ""),
        flag(p, key + "binary"),
        number(p, key + "tabWidth", 1, Document.MAX_TAB_WIDTH, Document.DEFAULT_TAB_WIDTH),
        flag(p, key + "spaces"),
        views,
        number(p, key + "focused", 0, 1, 0),
        flag(p, key + "sideBySide"),
        p.getProperty(key + "weights", ""),
        p.getProperty(key + "bookmarks", ""),
        comments);
  }

  /** Returns a search, or null when its scope or folder does not parse. */
  private static Session.Search search(Properties p, String key) {
    try {
      String folder = p.getProperty(key + "folder", "");
      return new Session.Search(
          new SearchRequest(
              p.getProperty(key + "text"),
              flag(p, key + "regex"),
              flag(p, key + "case"),
              flag(p, key + "word"),
              SearchScope.valueOf(p.getProperty(key + "scope", "")),
              folder.isEmpty() ? null : Path.of(folder),
              p.getProperty(key + "include", ""),
              p.getProperty(key + "exclude", ""),
              p.getProperty(key + "excluded", ""),
              flag(p, key + "hidden")),
          flag(p, key + "pinned"));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static List<Session.FileState> files(Properties p) {
    List<Session.FileState> files = new ArrayList<Session.FileState>();
    for (int i = 0; i < MAX_FILES; i++) {
      String key = FILE_KEY + i + ".";
      String path = p.getProperty(key + "path");
      if (path == null) {
        break;
      }
      files.add(
          new Session.FileState(
              path,
              p.getProperty(key + "encoding", ""),
              p.getProperty(key + "language", ""),
              number(p, key + "tabWidth", 1, Document.MAX_TAB_WIDTH, Document.DEFAULT_TAB_WIDTH),
              flag(p, key + "spaces"),
              p.getProperty(key + "selection", ""),
              number(p, key + "top", 0, Integer.MAX_VALUE, 0)));
    }
    return files;
  }

  private static List<String> list(Properties p, String prefix, int max) {
    List<String> list = new ArrayList<String>();
    for (int i = 0; i < max; i++) {
      String value = p.getProperty(prefix + i);
      if (value == null) {
        break;
      }
      list.add(value);
    }
    return list;
  }

  private static boolean flag(Properties p, String key) {
    return "true".equals(p.getProperty(key, "false").strip());
  }

  private static final int PIXELS = 100_000;

  private static Integer pixels(Properties p, String key, int min) {
    int value = number(p, key, min, PIXELS, Integer.MIN_VALUE);
    return value == Integer.MIN_VALUE ? null : Integer.valueOf(value);
  }

  private static int number(Properties p, String key, int min, int max, int fallback) {
    String value = p.getProperty(key);
    if (value == null) {
      return fallback;
    }
    try {
      long parsed = Long.parseLong(value.strip());
      return parsed >= min && parsed <= max ? (int) parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
