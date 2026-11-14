package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.ThemeColors;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.document.Document;
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
  static final String THEME_FILE = "ui.themeFile";
  static final String EDITOR_COLORS = "ui.editorColors";
  static final String FONT_FAMILY = "editor.fontFamily";
  static final String TOOLBAR = "ui.toolbar";
  static final String WINDOW_X = "window.x";
  static final String WINDOW_Y = "window.y";
  static final String WINDOW_WIDTH = "window.width";
  static final String WINDOW_HEIGHT = "window.height";
  static final String WINDOW_MAXIMIZED = "window.maximized";
  static final String DEBUG_LOG_FILE = "log.debugFile";
  static final String COMPLETION_CHARS = "editor.completionChars";
  static final String AUTO_CLOSE = "editor.autoClose";
  static final String HIGHLIGHT_CHARS = "editor.maxHighlightLineChars";
  static final String TAB_WIDTH = "editor.tabWidth";
  static final String INSERT_SPACES = "editor.insertSpaces";
  static final String EDITOR_CONFIG = "editor.editorConfig";
  static final String MODELINES = "editor.modelines";
  static final String FONT_SIZE = "editor.fontSize";
  static final String POLL_SECONDS = "file.pollSeconds";
  static final String AUTO_RELOAD = "file.autoReloadClean";
  static final String BACKUP_DAYS = "replace.backupDays";
  static final String SEARCH_LINE_CHARS = "search.maxLineChars";
  static final String EXCLUDED = "workspace.excludedDirectories";
  static final String RECOVERY_SECONDS = "recovery.intervalSeconds";
  static final String RECOVERY_DAYS = "recovery.keepDays";
  static final String SESSION_SNAPSHOT = "session.snapshot";
  static final String CLOSE_WINDOW_WITH_LAST_TAB = "tabs.closeWindowWithLastTab";
  static final String SEARCH_REGEX = "search.regex";
  static final String SEARCH_CASE = "search.caseSensitive";
  static final String SEARCH_WORD = "search.wholeWord";
  static final String SEARCH_HIDDEN = "search.hiddenFiles";
  static final String HISTORY_TEXT = "search.history.text.";
  static final String HISTORY_DIRECTORY = "search.history.directory.";
  static final String HISTORY_MASK = "search.history.mask.";
  static final String HISTORY_REPLACE = "search.history.replace.";
  static final String RECENT_GRAMMARS = "syntax.recent";
  static final String RIGHT_MARGIN = "editor.rightMargin";
  static final String DATE_FORMAT = "editor.dateFormat";
  static final String RIGHT_MARGIN_SHOWN = "ui.rightMargin";
  static final String LAST_DATE_FORMAT = "ui.lastDateFormat";
  static final String INDENT_GUIDES = "ui.indentGuides";
  static final String LINE_CHANGES = "ui.lineChanges";
  static final String FAVORITES = "workspace.favorite.";
  static final String DIALOG = "dialog.";
  static final String WIDTH = ".width";
  static final String HEIGHT = ".height";
  static final String TIPS_ON_STARTUP = "help.tipsOnStartup";
  static final String NEXT_TIP = "help.nextTip";
  static final String CLOSE_SHOWS_LAST_USED = "tabs.closeShowsLastUsed";
  static final String WORD_WRAP = "ui.wordWrap";
  static final String WHITESPACE = "ui.whitespace";
  static final String LINE_ENDINGS = "ui.lineEndings";
  static final String OVERVIEW = "ui.overview";
  static final String LINE_NUMBERS = "ui.lineNumbers";
  static final String WORKSPACE_BROWSER = "ui.workspaceBrowser";
  static final String NEW_LINE_ENDING = "editor.newLineEnding";
  static final String NEW_ENCODING = "editor.newEncoding";
  static final String MEMORY_INDICATOR = "ui.memoryIndicator";

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
    keys.put(FONT_FAMILY, p.appearance().fontFamily());
    keys.put(EDITOR_COLORS, p.appearance().colors());
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
    keys.put(TAB_WIDTH, Integer.toString(o.tabWidth()));
    keys.put(INSERT_SPACES, Boolean.toString(o.insertSpaces()));
    keys.put(RIGHT_MARGIN, Integer.toString(o.rightMargin()));
    keys.put(DATE_FORMAT, o.dateFormat());
    keys.put(EDITOR_CONFIG, Boolean.toString(o.editorConfig()));
    keys.put(MODELINES, Boolean.toString(o.modelines()));
    keys.put(CLOSE_WINDOW_WITH_LAST_TAB, Boolean.toString(o.closeWindowWithLastTab()));
    keys.put(CLOSE_SHOWS_LAST_USED, Boolean.toString(o.closeShowsLastUsed()));
    keys.put(NEW_LINE_ENDING, o.newLineEnding().key());
    keys.put(NEW_ENCODING, o.newEncoding());
    UiState s = p.state();
    keys.put(SEARCH_REGEX, Boolean.toString(s.search().regex()));
    keys.put(SEARCH_CASE, Boolean.toString(s.search().caseSensitive()));
    keys.put(SEARCH_WORD, Boolean.toString(s.search().wholeWord()));
    keys.put(SEARCH_HIDDEN, Boolean.toString(s.search().hiddenFiles()));
    keys.put(TOOLBAR, Boolean.toString(s.toolbar()));
    keys.put(RIGHT_MARGIN_SHOWN, Boolean.toString(s.rightMargin()));
    if (!s.lastDateFormat().isEmpty()) {
      keys.put(LAST_DATE_FORMAT, s.lastDateFormat());
    }
    keys.put(INDENT_GUIDES, Boolean.toString(s.indentGuides()));
    keys.put(LINE_CHANGES, Boolean.toString(s.lineChanges()));
    ViewDefaults view = s.view();
    keys.put(WORD_WRAP, Boolean.toString(view.wordWrap()));
    keys.put(WHITESPACE, Boolean.toString(view.whitespace()));
    keys.put(LINE_ENDINGS, Boolean.toString(view.lineEndings()));
    keys.put(OVERVIEW, Boolean.toString(view.overview()));
    keys.put(LINE_NUMBERS, Boolean.toString(view.lineNumbers()));
    keys.put(WORKSPACE_BROWSER, Boolean.toString(view.workspaceBrowser()));
    keys.put(MEMORY_INDICATOR, Boolean.toString(s.memoryIndicator()));
    putList(keys, HISTORY_TEXT, s.texts());
    putList(keys, HISTORY_DIRECTORY, s.directories());
    putList(keys, HISTORY_MASK, s.masks());
    putList(keys, HISTORY_REPLACE, s.replacements());
    putList(keys, FAVORITES, s.favorites());
    if (!s.recentGrammars().isEmpty()) {
      keys.put(RECENT_GRAMMARS, String.join(";", s.recentGrammars()));
    }
    for (Map.Entry<String, UiState.Size> dialog : s.dialogs().entrySet()) {
      String key = DIALOG + dialog.getKey();
      keys.put(key + WIDTH, Integer.toString(dialog.getValue().width()));
      keys.put(key + HEIGHT, Integer.toString(dialog.getValue().height()));
    }
    keys.putAll(p.shortcuts().keys());
    keys.put(TIPS_ON_STARTUP, Boolean.toString(p.tips().onStartup()));
    keys.put(NEXT_TIP, Integer.toString(p.tips().next()));
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
        .withShortcuts(shortcuts(p, file))
        .withAppearance(appearance(p, file))
        .withTips(
            new TipSettings(
                flag(p, TIPS_ON_STARTUP, false, file),
                number(p, NEXT_TIP, 0, TipSettings.MAX_NEXT, 0, file)));
  }

  /**
   * Reads {@code ui.editorColors}: a scheme, else a theme file's name. A legacy {@code
   * ui.themeFile} stands in while {@code ui.editorColors} is missing or {@code follow}.
   */
  private static Appearance appearance(Properties p, Path file) {
    String family = p.getProperty(FONT_FAMILY, "").strip();
    if (!Appearance.validFamily(family)) {
      invalid(FONT_FAMILY, file);
      family = "";
    }
    String colors = p.getProperty(EDITOR_COLORS, EditorSchemes.FOLLOW).strip();
    String legacy = p.getProperty(THEME_FILE, "").strip();
    if (colors.equals(EditorSchemes.FOLLOW) && !legacy.isEmpty()) {
      if (ThemeColors.validName(legacy) && !EditorSchemes.known(legacy)) {
        colors = legacy;
      } else {
        invalid(THEME_FILE, file);
      }
    }
    if (!EditorSchemes.known(colors) && !ThemeColors.validName(colors)) {
      invalid(EDITOR_COLORS, file);
      colors = EditorSchemes.FOLLOW;
    }
    return new Appearance("", family, EditorSchemes.FOLLOW).withColors(colors);
  }

  /**
   * Replaces a legacy {@code ui.themeFile} line with {@code ui.editorColors}.
   *
   * @param properties the file's keys, changed in place
   * @param after the settings being saved
   * @return true when the file held the legacy key
   */
  static boolean dropLegacy(Properties properties, Preferences after) {
    if (properties.remove(THEME_FILE) == null) {
      return false;
    }
    properties.setProperty(EDITOR_COLORS, after.appearance().colors());
    return true;
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
        flag(p, SESSION_SNAPSHOT, d.sessionSnapshot(), file),
        number(p, TAB_WIDTH, 1, Document.MAX_TAB_WIDTH, d.tabWidth(), file),
        flag(p, INSERT_SPACES, d.insertSpaces(), file),
        number(p, RIGHT_MARGIN, 1, Options.MAX_RIGHT_MARGIN, d.rightMargin(), file),
        dateFormat(p, DATE_FORMAT, d.dateFormat(), file),
        flag(p, EDITOR_CONFIG, d.editorConfig(), file),
        flag(p, MODELINES, d.modelines(), file),
        flag(p, CLOSE_WINDOW_WITH_LAST_TAB, d.closeWindowWithLastTab(), file),
        flag(p, CLOSE_SHOWS_LAST_USED, d.closeShowsLastUsed(), file),
        newLineEnding(p, d.newLineEnding(), file),
        newEncoding(p, d.newEncoding(), file));
  }

  private static NewLineEnding newLineEnding(Properties p, NewLineEnding fallback, Path file) {
    String value = p.getProperty(NEW_LINE_ENDING);
    if (value == null) {
      return fallback;
    }
    NewLineEnding parsed = NewLineEnding.parse(value);
    if (parsed == null) {
      invalid(NEW_LINE_ENDING, file);
      return fallback;
    }
    return parsed;
  }

  private static String newEncoding(Properties p, String fallback, Path file) {
    String value = p.getProperty(NEW_ENCODING);
    if (value == null) {
      return fallback;
    }
    if (Options.validEncoding(value.strip())) {
      return value.strip();
    }
    invalid(NEW_ENCODING, file);
    return fallback;
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
        dialogs(p, file),
        flag(p, TOOLBAR, true, file),
        flag(p, RIGHT_MARGIN_SHOWN, false, file),
        dateFormat(p, LAST_DATE_FORMAT, "", file),
        flag(p, INDENT_GUIDES, false, file),
        flag(p, LINE_CHANGES, false, file),
        list(p, FAVORITES),
        view(p, file),
        flag(p, MEMORY_INDICATOR, false, file));
  }

  private static ViewDefaults view(Properties p, Path file) {
    ViewDefaults d = ViewDefaults.DEFAULTS;
    return new ViewDefaults(
        flag(p, WORD_WRAP, d.wordWrap(), file),
        flag(p, WHITESPACE, d.whitespace(), file),
        flag(p, LINE_ENDINGS, d.lineEndings(), file),
        flag(p, OVERVIEW, d.overview(), file),
        flag(p, LINE_NUMBERS, d.lineNumbers(), file),
        flag(p, WORKSPACE_BROWSER, d.workspaceBrowser(), file));
  }

  /** Returns a valid date and time pattern, the fallback when absent, or it with one WARN. */
  private static String dateFormat(Properties p, String key, String fallback, Path file) {
    String value = p.getProperty(key);
    if (value == null) {
      return fallback;
    }
    if (Options.validDateFormat(value)) {
      return value;
    }
    invalid(key, file);
    return fallback;
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
