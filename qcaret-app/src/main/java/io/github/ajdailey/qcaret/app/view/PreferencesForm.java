package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;

/**
 * The Preferences dialog's fields. Numbers stay text until {@link PreferencesInput#apply} checks
 * them.
 *
 * @param completionChars word chars typed before completion opens
 * @param autoClose the Auto-Close box
 * @param maxHighlightLineChars the longest line colored
 * @param tabWidth columns per tab stop of a document without saved state
 * @param insertSpaces the box making such a document indent with spaces
 * @param pollSeconds seconds between file checks
 * @param autoReloadClean the Reload box
 * @param recoveryIntervalSeconds seconds between recovery file writes
 * @param recoveryKeepDays days a dead instance's recovery files are kept
 * @param sessionSnapshot the snapshot box
 * @param backupDays days a replace run's backups are kept
 * @param searchMaxLineChars the longest line searched
 * @param excludedDirectories directory names, {@code ;}-separated
 * @param theme the theme
 * @param fontSize the editor font size in points; blank for the platform's default
 * @param shortcuts the keys set in place of the defaults
 * @param themeFile the theme file's name; empty for the built-in colors
 * @param fontFamily the editor font family; empty for the platform's monospace font
 */
public record PreferencesForm(
    String completionChars,
    boolean autoClose,
    String maxHighlightLineChars,
    String tabWidth,
    boolean insertSpaces,
    String pollSeconds,
    boolean autoReloadClean,
    String recoveryIntervalSeconds,
    String recoveryKeepDays,
    boolean sessionSnapshot,
    String backupDays,
    String searchMaxLineChars,
    String excludedDirectories,
    ThemeMode theme,
    String fontSize,
    ShortcutOverrides shortcuts,
    String themeFile,
    String fontFamily) {
  /** The dialog's groups, in tab order. */
  public enum Group {
    /** Editing. */
    EDITOR,
    /** File checks. */
    FILES,
    /** Search, replace and the workspace. */
    SEARCH,
    /** Theme and font. */
    APPEARANCE,
    /** Key bindings. */
    KEYS
  }

  /**
   * Why a form was refused.
   *
   * @param group the group holding the field
   * @param message names the field and what it accepts
   */
  public record Problem(Group group, String message) {}
}
