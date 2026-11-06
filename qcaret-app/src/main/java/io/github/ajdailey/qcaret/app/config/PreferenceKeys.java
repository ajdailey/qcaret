package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps {@link Preferences} to and from the keys of {@code qcaret.conf}. A value that does not parse
 * or is out of range falls back to its default with one WARN naming the key, never the value.
 */
final class PreferenceKeys {
  static final String THEME = "ui.theme";
  static final String WINDOW_X = "window.x";
  static final String WINDOW_Y = "window.y";
  static final String WINDOW_WIDTH = "window.width";
  static final String WINDOW_HEIGHT = "window.height";
  static final String WINDOW_MAXIMIZED = "window.maximized";
  static final String DEBUG_LOG_FILE = "log.debugFile";
  static final String COMPLETION_CHARS = "editor.completionChars";
  static final String AUTO_CLOSE = "editor.autoClose";
  static final String HIGHLIGHT_CHARS = "editor.maxHighlightLineChars";
  static final String FONT_SIZE = "editor.fontSize";
  static final String POLL_SECONDS = "file.pollSeconds";
  static final String AUTO_RELOAD = "file.autoReloadClean";
  static final String BACKUP_DAYS = "replace.backupDays";
  static final String SEARCH_LINE_CHARS = "search.maxLineChars";
  static final String EXCLUDED = "workspace.excludedDirectories";
  static final String RECOVERY_SECONDS = "recovery.intervalSeconds";
  static final String RECOVERY_DAYS = "recovery.keepDays";
  static final String SESSION_SNAPSHOT = "session.snapshot";
  static final String SEARCH_REGEX = "search.regex";
  static final String SEARCH_CASE = "search.caseSensitive";
  static final String SEARCH_WORD = "search.wholeWord";
  static final String SEARCH_HIDDEN = "search.hiddenFiles";
  static final String HISTORY_TEXT = "search.history.text.";
  static final String HISTORY_DIRECTORY = "search.history.directory.";
  static final String HISTORY_MASK = "search.history.mask.";
  static final String HISTORY_REPLACE = "search.history.replace.";
  static final String RECENT_GRAMMARS = "syntax.recent";
  static final String DIALOG = "dialog.";
  static final String WIDTH = ".width";
  static final String HEIGHT = ".height";

  /** Largest window coordinate or size accepted, in pixels. */
  static final int MAX_PIXELS = 100_000;

  private static final Logger LOG = LoggerFactory.getLogger(PreferenceKeys.class);

  private PreferenceKeys() {}

  /**
   * Returns every key of the settings; an unknown placement, an empty list and a command without an
   * override have none.
   *
   * @param p the settings
   * @return the keys, sorted
   */
  static Map<String, String> write(Preferences p) {
    Map<String, String> keys = new TreeMap<String, String>();
    keys.put(THEME, p.theme().key());
    WindowState window = p.window();
    putOptional(keys, WINDOW_X, window.x());
    putOptional(keys, WINDOW_Y, window.y());
    putOptional(keys, WINDOW_WIDTH, window.width());
    putOptional(keys, WINDOW_HEIGHT, window.height());
    keys.put(WINDOW_MAXIMIZED, Boolean.toString(window.maximized()));
    keys.put(DEBUG_LOG_FILE, Boolean.toString(p.debugLogFile()));
    Options o = p.options();
    keys.put(COMPLETION_CHARS, Integer.toString(o.completionChars()));
    keys.put(AUTO_CLOSE, Boolean.toString(o.autoClose()));
    keys.put(HIGHLIGHT_CHARS, Integer.toString(o.maxHighlightLineChars()));
    keys.put(FONT_SIZE, Integer.toString(o.fontSize()));
    keys.put(POLL_SECONDS, Integer.toString(o.pollSeconds()));
    keys.put(AUTO_RELOAD, Boolean.toString(o.autoReloadClean()));
    keys.put(BACKUP_DAYS, Integer.toString(o.backupDays()));
    keys.put(SEARCH_LINE_CHARS, Integer.toString(o.searchMaxLineChars()));
    keys.put(EXCLUDED, o.excludedDirectories());
    keys.put(RECOVERY_SECONDS, Integer.toString(o.recoveryIntervalSeconds()));
    keys.put(RECOVERY_DAYS, Integer.toString(o.recoveryKeepDays()));
    keys.put(SESSION_SNAPSHOT, Boolean.toString(o.sessionSnapshot()));
    UiState s = p.state();
    keys.put(SEARCH_REGEX, Boolean.toString(s.search().regex()));
    keys.put(SEARCH_CASE, Boolean.toString(s.search().caseSensitive()));
    keys.put(SEARCH_WORD, Boolean.toString(s.search().wholeWord()));
    keys.put(SEARCH_HIDDEN, Boolean.toString(s.search().hiddenFiles()));
    putList(keys, HISTORY_TEXT, s.texts());
    putList(keys, HISTORY_DIRECTORY, s.directories());
    putList(keys, HISTORY_MASK, s.masks());
    putList(keys, HISTORY_REPLACE, s.replacements());
    if (!s.recentGrammars().isEmpty()) {
      keys.put(RECENT_GRAMMARS, String.join(";", s.recentGrammars()));
    }
    for (Map.Entry<String, UiState.Size> dialog : s.dialogs().entrySet()) {
      String key = DIALOG + dialog.getKey();
      keys.put(key + WIDTH, Integer.toString(dialog.getValue().width()));
      keys.put(key + HEIGHT, Integer.toString(dialog.getValue().height()));
    }
    keys.putAll(p.shortcuts().keys());
    return keys;
  }

  private static void putOptional(Map<String, String> keys, String key, Integer value) {
    if (value != null) {
      keys.put(key, value.toString());
    }
  }

  private static void putList(Map<String, String> keys, String prefix, List<String> list) {
    for (int i = 0; i < list.size(); i++) {
      keys.put(prefix + i, list.get(i));
    }
  }

  /**
   * Reads the settings.
   *
   * @param p the file's properties
   * @param file the file, for warnings
   * @return the settings; defaults for keys that are absent or invalid
   */
  static Preferences read(Properties p, Path file) {
    Preferences result = Preferences.DEFAULTS;
    String theme = p.getProperty(THEME);
    if (theme != null) {
      ThemeMode mode = ThemeMode.parse(theme);
      if (mode == null) {
        invalid(THEME, file);
      } else {
        result = result.withTheme(mode);
      }
    }
    return result
        .withDebugLogFile(flag(p, DEBUG_LOG_FILE, false, file))
        .withWindow(
            new WindowState(
                optional(p, WINDOW_X, -MAX_PIXELS, MAX_PIXELS, file),
                optional(p, WINDOW_Y, -MAX_PIXELS, MAX_PIXELS, file),
                optional(p, WINDOW_WIDTH, 1, MAX_PIXELS, file),
                optional(p, WINDOW_HEIGHT, 1, MAX_PIXELS, file),
                flag(p, WINDOW_MAXIMIZED, false, file)))
        .withOptions(options(p, file))
        .withState(state(p, file))
        .withShortcuts(shortcuts(p, file));
  }

  private static Options options(Properties p, Path file) {
    Options d = Options.DEFAULTS;
    int fontSize = number(p, FONT_SIZE, 0, Options.MAX_FONT_SIZE, d.fontSize(), file);
    if (fontSize != 0 && fontSize < Options.MIN_FONT_SIZE) {
      invalid(FONT_SIZE, file);
      fontSize = d.fontSize();
    }
    String excluded = p.getProperty(EXCLUDED, d.excludedDirectories());
    if (!Options.validDirectories(excluded)) {
      invalid(EXCLUDED, file);
      excluded = d.excludedDirectories();
    }
    return new Options(
        number(p, COMPLETION_CHARS, 0, Options.MAX_COMPLETION_CHARS, d.completionChars(), file),
        flag(p, AUTO_CLOSE, d.autoClose(), file),
        number(
            p,
            HIGHLIGHT_CHARS,
            Options.MIN_HIGHLIGHT_CHARS,
            Options.MAX_HIGHLIGHT_CHARS,
            d.maxHighlightLineChars(),
            file),
        fontSize,
        number(
            p,
            POLL_SECONDS,
            Options.MIN_POLL_SECONDS,
            Options.MAX_POLL_SECONDS,
            d.pollSeconds(),
            file),
        flag(p, AUTO_RELOAD, d.autoReloadClean(), file),
        number(
            p, BACKUP_DAYS, Options.MIN_BACKUP_DAYS, Options.MAX_BACKUP_DAYS, d.backupDays(), file),
        number(
            p,
            SEARCH_LINE_CHARS,
            Options.MIN_SEARCH_LINE_CHARS,
            Options.MAX_SEARCH_LINE_CHARS,
            d.searchMaxLineChars(),
            file),
        excluded.strip(),
        number(
            p,
            RECOVERY_SECONDS,
            Options.MIN_RECOVERY_SECONDS,
            Options.MAX_RECOVERY_SECONDS,
            d.recoveryIntervalSeconds(),
            file),
        number(
            p,
            RECOVERY_DAYS,
            Options.MIN_RECOVERY_DAYS,
            Options.MAX_RECOVERY_DAYS,
            d.recoveryKeepDays(),
            file),
        flag(p, SESSION_SNAPSHOT, d.sessionSnapshot(), file));
  }

  private static UiState state(Properties p, Path file) {
    SearchToggles toggles =
        new SearchToggles(
            flag(p, SEARCH_REGEX, false, file),
            flag(p, SEARCH_CASE, false, file),
            flag(p, SEARCH_WORD, false, file),
            flag(p, SEARCH_HIDDEN, false, file));
    List<String> grammars = new ArrayList<String>();
    for (String id : p.getProperty(RECENT_GRAMMARS, "").split(";", -1)) {
      String trimmed = id.strip();
      if (!trimmed.isEmpty() && !grammars.contains(trimmed)) {
        grammars.add(trimmed);
      }
    }
    return new UiState(
        toggles,
        list(p, HISTORY_TEXT),
        list(p, HISTORY_DIRECTORY),
        list(p, HISTORY_MASK),
        list(p, HISTORY_REPLACE),
        grammars,
        dialogs(p, file));
  }

  /** Returns a history from index 0 up to the first gap, without duplicates or long entries. */
  private static List<String> list(Properties p, String prefix) {
    List<String> list = new ArrayList<String>();
    for (int i = 0; i < UiState.MAX_HISTORY; i++) {
      String value = p.getProperty(prefix + i);
      if (value == null) {
        break;
      }
      if (!value.isBlank() && value.length() <= UiState.MAX_ENTRY_CHARS && !list.contains(value)) {
        list.add(value);
      }
    }
    return list;
  }

  private static Map<String, UiState.Size> dialogs(Properties p, Path file) {
    Map<String, UiState.Size> sizes = new LinkedHashMap<String, UiState.Size>();
    for (String key : new TreeSet<String>(p.stringPropertyNames())) {
      if (key.startsWith(DIALOG) && key.endsWith(WIDTH) && sizes.size() < UiState.MAX_DIALOGS) {
        String name = key.substring(DIALOG.length(), key.length() - WIDTH.length());
        Integer width = optional(p, key, 1, MAX_PIXELS, file);
        Integer height = optional(p, DIALOG + name + HEIGHT, 1, MAX_PIXELS, file);
        if (width != null && height != null && name.matches("[A-Za-z0-9]+")) {
          sizes.put(name, new UiState.Size(width, height));
        }
      }
    }
    return sizes;
  }

  private static ShortcutOverrides shortcuts(Properties p, Path file) {
    Map<String, String> keys = new TreeMap<String, String>();
    for (String key : p.stringPropertyNames()) {
      if (key.startsWith(ShortcutOverrides.PREFIX)) {
        keys.put(key, p.getProperty(key));
      }
    }
    List<String> refused = new ArrayList<String>();
    ShortcutOverrides overrides = ShortcutOverrides.parse(keys, refused);
    for (String key : refused) {
      invalid(key, file);
    }
    return overrides;
  }

  /** Returns a whole number in range, null when absent, or null with one WARN when invalid. */
  private static Integer optional(Properties p, String key, int min, int max, Path file) {
    if (p.getProperty(key) == null) {
      return null;
    }
    int value = number(p, key, min, max, Integer.MIN_VALUE, file);
    return value == Integer.MIN_VALUE ? null : Integer.valueOf(value);
  }

  private static int number(Properties p, String key, int min, int max, int fallback, Path file) {
    String value = p.getProperty(key);
    if (value == null) {
      return fallback;
    }
    try {
      long parsed = Long.parseLong(value.strip());
      if (parsed >= min && parsed <= max) {
        return (int) parsed;
      }
    } catch (NumberFormatException e) {
      // Reported below.
    }
    invalid(key, file);
    return fallback;
  }

  private static boolean flag(Properties p, String key, boolean fallback, Path file) {
    String value = p.getProperty(key);
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
}
