package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;

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
    String excludedDirectories) {
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
          FileFilter.DEFAULT_EXCLUDED_DIRECTORIES);

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
    if (!validDirectories(excludedDirectories)) {
      throw new IllegalArgumentException("excludedDirectories");
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
        excludedDirectories);
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
        excludedDirectories);
  }
}
