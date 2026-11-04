package io.github.ajdailey.qcaret.app.i18n;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Every string the UI shows, keyed by constant name in the {@code messages} resource bundle of this
 * package. English is the only bundle shipped. A pattern takes {@link String#format} arguments,
 * formatted in {@link Locale#ROOT}; a label marks its mnemonic with {@code &}.
 */
public enum UiText {
  /** Window title. */
  WINDOW_TITLE,
  /** Title of a document without a file: its number. */
  TAB_UNTITLED,
  /** File menu. */
  GROUP_FILE,
  /** Edit menu. */
  GROUP_EDIT,
  /** View menu. */
  GROUP_VIEW,
  /** Help menu. */
  GROUP_HELP,
  /** Command label. */
  COMMAND_NEW,
  /** Command label. */
  COMMAND_OPEN,
  /** Command label. */
  COMMAND_SAVE,
  /** Command label. */
  COMMAND_SAVE_AS,
  /** Command label. */
  COMMAND_CLOSE,
  /** Command label. */
  COMMAND_QUIT,
  /** Command label. */
  COMMAND_UNDO,
  /** Command label. */
  COMMAND_REDO,
  /** Command label. */
  COMMAND_CUT,
  /** Command label. */
  COMMAND_COPY,
  /** Command label. */
  COMMAND_PASTE,
  /** Command label. */
  COMMAND_SELECT_ALL,
  /** Command label. */
  COMMAND_WORD_WRAP,
  /** View submenu of the light, dark and system themes. */
  MENU_THEME,
  /** Command label. */
  COMMAND_THEME_LIGHT,
  /** Command label. */
  COMMAND_THEME_DARK,
  /** Command label. */
  COMMAND_THEME_SYSTEM,
  /** Command label. */
  COMMAND_ABOUT,
  /** Title of the notice that native controls change at the next start. */
  THEME_RESTART_TITLE,
  /** The notice that native controls change at the next start. */
  THEME_RESTART_MESSAGE,
  /** About heading: the version. */
  ABOUT_NAME,
  /** About tagline. */
  ABOUT_TAGLINE,
  /** About copyright. */
  ABOUT_COPYRIGHT,
  /** About license line. */
  ABOUT_LICENSE,
  /** Button. */
  BUTTON_LICENSES,
  /** Button. */
  BUTTON_COPY_DETAILS,
  /** Button. */
  BUTTON_OK,
  /** Button. */
  BUTTON_COPY,
  /** Button. */
  BUTTON_CLOSE,
  /** Details line: the version. */
  DETAILS_VERSION,
  /** Details line: Java version and vendor. */
  DETAILS_JAVA,
  /** Details line: OS. */
  DETAILS_OS,
  /** Details line: toolkit. */
  DETAILS_TOOLKIT,
  /** Details line: settings directory and mode. */
  DETAILS_SETTINGS,
  /** Settings mode. */
  SETTINGS_EXPLICIT,
  /** Settings mode. */
  SETTINGS_PORTABLE,
  /** Settings mode. */
  SETTINGS_PER_USER,
  /** Licenses heading: the file name. */
  LICENSES_HEADING,
  /** Licenses: a notice file missing from the build. */
  LICENSES_MISSING,
  /** Licenses window title. */
  LICENSES_TITLE,
  /** Licenses read error: the reason. */
  LICENSES_READ_FAILED,
  /** Title of open notices. */
  OPEN_TITLE,
  /** Open failure: the file name and the reason. */
  OPEN_FAILED,
  /** Open failure reason for a binary file. */
  OPEN_BINARY,
  /** Opened read-only: the file name and the charset. */
  OPEN_READ_ONLY,
  /**
   * Open refused by the heap budget: the file name, its MiB, the MiB needed, the MiB available, the
   * maximum heap in MiB and a suggested {@code -Xmx} in GiB.
   */
  OPEN_TOO_LARGE,
  /** Title of save notices. */
  SAVE_TITLE,
  /** Save refused while one runs: the document name. */
  SAVE_RUNNING,
  /** Save stopped by an external change: the document name. */
  SAVE_CHANGED_ON_DISK,
  /** Save failure: the document name and the reason. */
  SAVE_FAILED,
  /** Title of the unsaved-changes prompt. */
  UNSAVED_TITLE,
  /** The unsaved-changes prompt: the document name. */
  UNSAVED_MESSAGE,
  /** Button. */
  BUTTON_SAVE,
  /** Button. */
  BUTTON_DONT_SAVE,
  /** Button. */
  BUTTON_CANCEL;

  private static final String BUNDLE = "io.github.ajdailey.qcaret.app.i18n.messages";

  /** Loads the bundle on first use, after every constant exists. */
  private static final class Holder {
    static final ResourceBundle MESSAGES =
        ResourceBundle.getBundle(BUNDLE, Locale.getDefault(), UiText.class.getClassLoader());
  }

  /**
   * Returns the text.
   *
   * @return the bundle value
   */
  public String text() {
    return Holder.MESSAGES.getString(name());
  }

  /**
   * Formats the text as a pattern.
   *
   * @param args the pattern arguments
   * @return the formatted text
   */
  public String format(Object... args) {
    return String.format(Locale.ROOT, text(), args);
  }

  /**
   * Returns the bundle the constants read, for tests.
   *
   * @return the bundle
   */
  static ResourceBundle bundle() {
    return Holder.MESSAGES;
  }
}
