package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The settings of the Preferences dialog apart from the theme and the keys. Immutable.
 *
 * @param completionChars {@code editor.completionChars}: word chars typed before the completion
 *     popup opens on its own, 0 for never
 * @param autoClose {@code editor.autoClose}: typing an opener also inserts its closer
 * @param maxHighlightLineChars {@code editor.maxHighlightLineChars}: longer lines stay plain
 * @param fontSize {@code editor.fontSize}: the editor font in points, 0 for the platform's default
 * @param pollSeconds {@code file.pollSeconds}: seconds between checks of every open file
 * @param autoReloadClean {@code file.autoReloadClean}: a changed file reloads a clean buffer
 *     without a prompt
 * @param backupDays {@code replace.backupDays}: days a replace run's backups are kept
 * @param searchMaxLineChars {@code search.maxLineChars}: longer lines are skipped and counted
 * @param excludedDirectories {@code workspace.excludedDirectories}: directory names, {@code
 *     ;}-separated, that the browser, the index and directory searches skip
 * @param recoveryIntervalSeconds {@code recovery.intervalSeconds}: seconds between writes of each
 *     changed dirty buffer's recovery file
 * @param recoveryKeepDays {@code recovery.keepDays}: days a dead instance's recovery files are kept
 * @param sessionSnapshot {@code session.snapshot}: a quit asks nothing and the next start restores
 *     unsaved and untitled buffers
 * @param tabWidth {@code editor.tabWidth}: columns per tab stop of a document without saved state
 * @param insertSpaces {@code editor.insertSpaces}: such a document indents with spaces
 * @param rightMargin {@code editor.rightMargin}: the column the right margin line sits left of
 * @param dateFormat {@code editor.dateFormat}: Insert Date and Time's first format, a {@link
 *     DateTimeFormatter} pattern
 * @param editorConfig {@code editor.editorConfig}: documents take the settings of {@code
 *     .editorconfig} files
 * @param modelines {@code editor.modelines}: documents take the settings of vim and Emacs modelines
 * @param closeWindowWithLastTab {@code tabs.closeWindowWithLastTab}: closing a window's last tab
 *     closes the window
 * @param closeShowsLastUsed {@code tabs.closeShowsLastUsed}: closing the shown tab shows the most
 *     recently used tab, not the one after it
 * @param newLineEnding {@code editor.newLineEnding}: the line ending of a new document and of a
 *     text without line breaks
 * @param newEncoding {@code editor.newEncoding}: the charset of a new document and of an empty
 *     file, one of {@link TextEncoding#MENU}
 * @param workspaceDefault {@code workspace.default}: the workspace folder of a window not restored
 *     from the session, empty for none
 */
public record Options(
    int completionChars,
    boolean autoClose,
    int maxHighlightLineChars,
    int fontSize,
    int pollSeconds,
    boolean autoReloadClean,
    int backupDays,
    int searchMaxLineChars,
    String excludedDirectories,
    int recoveryIntervalSeconds,
    int recoveryKeepDays,
    boolean sessionSnapshot,
    int tabWidth,
    boolean insertSpaces,
    int rightMargin,
    String dateFormat,
    boolean editorConfig,
    boolean modelines,
    boolean closeWindowWithLastTab,
    boolean closeShowsLastUsed,
    NewLineEnding newLineEnding,
    String newEncoding,
    String workspaceDefault) {
  /** Formats a pattern on trial; a fixed zone keeps the check off the time-zone rules. */
  private static final ZonedDateTime SAMPLE_TIME =
      ZonedDateTime.of(2000, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);

  /** Largest {@code completionChars}. */
  public static final int MAX_COMPLETION_CHARS = 20;

  /** Smallest {@code maxHighlightLineChars}. */
  public static final int MIN_HIGHLIGHT_CHARS = 1_000;

  /** Largest {@code maxHighlightLineChars}. */
  public static final int MAX_HIGHLIGHT_CHARS = 1_000_000;

  /** Smallest {@code fontSize} apart from 0, in points. */
  public static final int MIN_FONT_SIZE = 4;

  /** Largest {@code fontSize}, in points. */
  public static final int MAX_FONT_SIZE = 72;

  /** Shortest {@code pollSeconds}. */
  public static final int MIN_POLL_SECONDS = 1;

  /** Longest {@code pollSeconds}. */
  public static final int MAX_POLL_SECONDS = 3600;

  /** Fewest {@code backupDays}. */
  public static final int MIN_BACKUP_DAYS = 1;

  /** Most {@code backupDays}. */
  public static final int MAX_BACKUP_DAYS = 365;

  /** Smallest {@code searchMaxLineChars}. */
  public static final int MIN_SEARCH_LINE_CHARS = 10_000;

  /** Largest {@code searchMaxLineChars}. */
  public static final int MAX_SEARCH_LINE_CHARS = 100_000_000;

  /** Longest {@code excludedDirectories}. */
  public static final int MAX_EXCLUDED_CHARS = 4096;

  /** Shortest {@code recoveryIntervalSeconds}. */
  public static final int MIN_RECOVERY_SECONDS = 5;

  /** Longest {@code recoveryIntervalSeconds}. */
  public static final int MAX_RECOVERY_SECONDS = 600;

  /** Fewest {@code recoveryKeepDays}. */
  public static final int MIN_RECOVERY_DAYS = 1;

  /** Most {@code recoveryKeepDays}. */
  public static final int MAX_RECOVERY_DAYS = 365;

  /** Largest {@code rightMargin}. */
  public static final int MAX_RIGHT_MARGIN = 1000;

  /** Longest {@code dateFormat}. */
  public static final int MAX_DATE_FORMAT_CHARS = 100;

  /** The settings when the file holds none. */
  public static final Options DEFAULTS =
      new Options(
          0,
          true,
          SyntaxEngine.DEFAULT_MAX_LINE_CHARS,
          0,
          5,
          true,
          ReplaceBackups.DEFAULT_DAYS,
          1_000_000,
          FileFilter.DEFAULT_EXCLUDED_DIRECTORIES,
          30,
          30,
          false,
          Document.DEFAULT_TAB_WIDTH,
          false,
          80,
          "yyyy-MM-dd HH:mm",
          true,
          true,
          false,
          false,
          NewLineEnding.LF,
          "UTF-8",
          "");

  /** Checks the components. */
  public Options {
    range(completionChars, 0, MAX_COMPLETION_CHARS);
    range(maxHighlightLineChars, MIN_HIGHLIGHT_CHARS, MAX_HIGHLIGHT_CHARS);
    if (fontSize != 0) {
      range(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
    }
    range(pollSeconds, MIN_POLL_SECONDS, MAX_POLL_SECONDS);
    range(backupDays, MIN_BACKUP_DAYS, MAX_BACKUP_DAYS);
    range(searchMaxLineChars, MIN_SEARCH_LINE_CHARS, MAX_SEARCH_LINE_CHARS);
    range(recoveryIntervalSeconds, MIN_RECOVERY_SECONDS, MAX_RECOVERY_SECONDS);
    range(recoveryKeepDays, MIN_RECOVERY_DAYS, MAX_RECOVERY_DAYS);
    range(tabWidth, 1, Document.MAX_TAB_WIDTH);
    if (!validDirectories(excludedDirectories)) {
      throw new IllegalArgumentException("excludedDirectories");
    }
    range(rightMargin, 1, MAX_RIGHT_MARGIN);
    if (!validDateFormat(dateFormat)) {
      throw new IllegalArgumentException("dateFormat");
    }
    if (newLineEnding == null) {
      throw new IllegalArgumentException("newLineEnding");
    }
    if (!validEncoding(newEncoding)) {
      throw new IllegalArgumentException("newEncoding");
    }
    if (!validFolder(workspaceDefault)) {
      throw new IllegalArgumentException("workspaceDefault");
    }
  }

  /** Longest {@code workspaceDefault}. */
  public static final int MAX_FOLDER_CHARS = 4096;

  /**
   * Reports whether a folder value is valid; whether the folder exists is checked where it is used.
   *
   * @param folder a path, empty for none, or null
   * @return false for null, over {@link #MAX_FOLDER_CHARS} chars, or holding a control char
   */
  public static boolean validFolder(String folder) {
    if (folder == null || folder.length() > MAX_FOLDER_CHARS) {
      return false;
    }
    for (int i = 0; i < folder.length(); i++) {
      if (Character.isISOControl(folder.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reports whether a charset name is one of the Encoding menu's that this runtime supports.
   *
   * @param name a charset name as {@link TextEncoding#MENU} spells it, or null
   * @return false for null, another spelling, or a charset the runtime lacks
   */
  public static boolean validEncoding(String name) {
    return name != null && TextEncoding.MENU.contains(name) && Charset.isSupported(name);
  }

  /**
   * Reports whether a date and time pattern is valid.
   *
   * @param pattern a {@link DateTimeFormatter} pattern, or null
   * @return false for null, blank, over {@link #MAX_DATE_FORMAT_CHARS} chars, holding a control
   *     char, refused by {@link DateTimeFormatter#ofPattern}, or failing to format a zoned date and
   *     time
   */
  public static boolean validDateFormat(String pattern) {
    if (pattern == null || pattern.isBlank() || pattern.length() > MAX_DATE_FORMAT_CHARS) {
      return false;
    }
    for (int i = 0; i < pattern.length(); i++) {
      if (Character.isISOControl(pattern.charAt(i))) {
        return false;
      }
    }
    try {
      DateTimeFormatter.ofPattern(pattern).format(SAMPLE_TIME);
      return true;
    } catch (IllegalArgumentException | DateTimeException e) {
      return false;
    }
  }

  private static void range(int value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(value + " is not in " + min + " to " + max);
    }
  }

  /**
   * Reports whether a list of excluded directories is valid.
   *
   * @param list {@code ;}-separated names, or null
   * @return false for null, over {@link #MAX_EXCLUDED_CHARS} chars, or a name holding a path
   *     separator or a glob char
   */
  public static boolean validDirectories(String list) {
    if (list == null || list.length() > MAX_EXCLUDED_CHARS) {
      return false;
    }
    for (int i = 0; i < list.length(); i++) {
      if ("/\\*?[]{}:\n\r\t".indexOf(list.charAt(i)) >= 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns a copy with another completion trigger.
   *
   * @param chars word chars typed before the popup opens, 0 for never
   * @return the copy
   */
  public Options withCompletionChars(int chars) {
    return new Options(
        chars,
        autoClose,
        maxHighlightLineChars,
        fontSize,
        pollSeconds,
        autoReloadClean,
        backupDays,
        searchMaxLineChars,
        excludedDirectories,
        recoveryIntervalSeconds,
        recoveryKeepDays,
        sessionSnapshot,
        tabWidth,
        insertSpaces,
        rightMargin,
        dateFormat,
        editorConfig,
        modelines,
        closeWindowWithLastTab,
        closeShowsLastUsed,
        newLineEnding,
        newEncoding,
        workspaceDefault);
  }

  /**
   * Returns a copy with another editor font size.
   *
   * @param points the size, 0 for the platform's default
   * @return the copy
   */
  public Options withFontSize(int points) {
    return new Options(
        completionChars,
        autoClose,
        maxHighlightLineChars,
        points,
        pollSeconds,
        autoReloadClean,
        backupDays,
        searchMaxLineChars,
        excludedDirectories,
        recoveryIntervalSeconds,
        recoveryKeepDays,
        sessionSnapshot,
        tabWidth,
        insertSpaces,
        rightMargin,
        dateFormat,
        editorConfig,
        modelines,
        closeWindowWithLastTab,
        closeShowsLastUsed,
        newLineEnding,
        newEncoding,
        workspaceDefault);
  }

  /**
   * Returns a copy with another default workspace folder.
   *
   * @param folder the folder, empty for none
   * @return the copy
   */
  public Options withWorkspaceDefault(String folder) {
    return new Options(
        completionChars,
        autoClose,
        maxHighlightLineChars,
        fontSize,
        pollSeconds,
        autoReloadClean,
        backupDays,
        searchMaxLineChars,
        excludedDirectories,
        recoveryIntervalSeconds,
        recoveryKeepDays,
        sessionSnapshot,
        tabWidth,
        insertSpaces,
        rightMargin,
        dateFormat,
        editorConfig,
        modelines,
        closeWindowWithLastTab,
        closeShowsLastUsed,
        newLineEnding,
        newEncoding,
        folder);
  }
}
