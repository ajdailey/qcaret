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
  /** Encoding menu. */
  GROUP_ENCODING,
  /** Window menu. */
  GROUP_WINDOW,
  /** Editor keys, in Keyboard Shortcuts. */
  GROUP_EDITOR,
  /** Command label. */
  COMMAND_CLOSE_ALL,
  /** Command label. */
  COMMAND_ZOOM_IN,
  /** Command label. */
  COMMAND_ZOOM_OUT,
  /** Command label. */
  COMMAND_ZOOM_RESET,
  /** Encoding submenu of the line-ending conversions. */
  MENU_LINE_ENDINGS,
  /** Command label. */
  COMMAND_LINE_ENDING_CRLF,
  /** Command label. */
  COMMAND_LINE_ENDING_LF,
  /** Command label. */
  COMMAND_LINE_ENDING_CR,
  /** Command label. */
  COMMAND_NEXT_TAB,
  /** Command label. */
  COMMAND_PREVIOUS_TAB,
  /** Command label. */
  COMMAND_CLOSE_OTHERS,
  /** Command label. */
  COMMAND_KEYBOARD_SHORTCUTS,
  /** Command label. */
  COMMAND_COMMAND_LINE_OPTIONS,
  /** Command label. */
  COMMAND_USER_GUIDE,
  /** Command label. */
  COMMAND_SHOW_LOG,
  /** Command label. */
  COMMAND_DEBUG_LOG_FILE,
  /** Command label. */
  COMMAND_OPEN_LOG_FOLDER,
  /** Command label. */
  COMMAND_OPEN_SETTINGS_FOLDER,
  /** Command label. */
  COMMAND_REPORT_ISSUE,
  /** Editor key, in Keyboard Shortcuts. */
  COMMAND_NEWLINE,
  /** Editor key, in Keyboard Shortcuts. */
  COMMAND_DELETE_BACK,
  /** Editor key, in Keyboard Shortcuts. */
  COMMAND_DELETE_FORWARD,
  /** Editor key, in Keyboard Shortcuts. */
  COMMAND_CANCEL,
  /** Command label. */
  COMMAND_SAVE_ALL,
  /** Command label. */
  COMMAND_REOPEN_CLOSED,
  /** Command label. */
  COMMAND_DUPLICATE,
  /** Command label. */
  COMMAND_DELETE_LINE,
  /** Command label. */
  COMMAND_MOVE_LINES_UP,
  /** Command label. */
  COMMAND_MOVE_LINES_DOWN,
  /** Command label. */
  COMMAND_JOIN_LINES,
  /** Command label. */
  COMMAND_UPPER_CASE,
  /** Command label. */
  COMMAND_LOWER_CASE,
  /** Command label. */
  COMMAND_INDENT,
  /** Command label. */
  COMMAND_OUTDENT,
  /** Command label. */
  COMMAND_TAB_WIDTH_2,
  /** Command label. */
  COMMAND_TAB_WIDTH_4,
  /** Command label. */
  COMMAND_TAB_WIDTH_8,
  /** Command label. */
  COMMAND_INDENT_WITH_SPACES,
  /** Command label. */
  COMMAND_GO_TO_LINE,
  /** Command label. */
  COMMAND_READ_ONLY,
  /** Command label. */
  COMMAND_ADD_CARET_ABOVE,
  /** Command label. */
  COMMAND_ADD_CARET_BELOW,
  /** Command label. */
  COMMAND_ADD_NEXT_OCCURRENCE,
  /** Command label. */
  COMMAND_SPLIT_INTO_LINES,
  /** Command label. */
  COMMAND_COLUMN_MODE,
  /** Command label. */
  COMMAND_CARET_LEFT,
  /** Command label. */
  COMMAND_CARET_RIGHT,
  /** Command label. */
  COMMAND_CARET_UP,
  /** Command label. */
  COMMAND_CARET_DOWN,
  /** Command label. */
  COMMAND_WORD_LEFT,
  /** Command label. */
  COMMAND_WORD_RIGHT,
  /** Command label. */
  COMMAND_LINE_START,
  /** Command label. */
  COMMAND_LINE_END,
  /** Command label. */
  COMMAND_PAGE_UP,
  /** Command label. */
  COMMAND_PAGE_DOWN,
  /** Command label. */
  COMMAND_DOCUMENT_START,
  /** Command label. */
  COMMAND_DOCUMENT_END,
  /** Command label. */
  COMMAND_SELECT_LEFT,
  /** Command label. */
  COMMAND_SELECT_RIGHT,
  /** Command label. */
  COMMAND_SELECT_UP,
  /** Command label. */
  COMMAND_SELECT_DOWN,
  /** Command label. */
  COMMAND_SELECT_WORD_LEFT,
  /** Command label. */
  COMMAND_SELECT_WORD_RIGHT,
  /** Command label. */
  COMMAND_SELECT_LINE_START,
  /** Command label. */
  COMMAND_SELECT_LINE_END,
  /** Command label. */
  COMMAND_SELECT_PAGE_UP,
  /** Command label. */
  COMMAND_SELECT_PAGE_DOWN,
  /** Command label. */
  COMMAND_SELECT_DOCUMENT_START,
  /** Command label. */
  COMMAND_SELECT_DOCUMENT_END,
  /** Command label. */
  COMMAND_BLOCK_LEFT,
  /** Command label. */
  COMMAND_BLOCK_RIGHT,
  /** Command label. */
  COMMAND_BLOCK_UP,
  /** Command label. */
  COMMAND_BLOCK_DOWN,
  /** Command label. */
  COMMAND_INSERT_TAB,
  /** Selection menu. */
  GROUP_SELECTION,
  /** Edit menu cascade of tab widths. */
  MENU_TAB_WIDTH,
  /** Title of the Go to Line prompt. */
  GO_TO_TITLE,
  /** The Go to Line prompt: the line count. */
  GO_TO_MESSAGE,
  /** Go to Line input that is not a line, or a line and a column: the input. */
  GO_TO_INVALID,
  /** Keyboard Shortcuts filter hint. */
  SHORTCUTS_FILTER,
  /** Keyboard Shortcuts column. */
  SHORTCUTS_GROUP,
  /** Keyboard Shortcuts column. */
  SHORTCUTS_COMMAND,
  /** Keyboard Shortcuts column. */
  SHORTCUTS_KEYS,
  /** Encoding submenu: load the file again with a charset. */
  MENU_REOPEN_ENCODING,
  /** Encoding submenu: the charset the next save writes. */
  MENU_SAVE_ENCODING,
  /** Encoding cascade of every other charset. */
  MENU_ENCODING_OTHER,
  /** Tab title of a read-only document. */
  TAB_READ_ONLY,
  /** Tab title of a binary file's read-only view. */
  TAB_BINARY,
  /** Tab menu item. */
  TAB_COPY_PATH,
  /** Tab menu item. */
  TAB_COPY_FILE_NAME,
  /** Save refused for a binary file's view. */
  SAVE_BINARY,
  /** Title of the binary file prompt. */
  BINARY_TITLE,
  /** Binary file prompt. */
  BINARY_MESSAGE,
  /** Binary file prompt button. */
  BUTTON_OPEN_READ_ONLY,
  /** Title of the key binding list. */
  SHORTCUTS_TITLE,
  /** Title of the command-line usage. */
  COMMAND_LINE_TITLE,
  /** Title of the log window. */
  SHOW_LOG_TITLE,
  /** Log window text when nothing was logged. */
  SHOW_LOG_EMPTY,
  /** Notice after Debug Log File opens the file. */
  NOTICE_LOG_FILE_ON,
  /** Notice after Debug Log File closes the file. */
  NOTICE_LOG_FILE_OFF,
  /** Notice when the debug log file does not open. */
  NOTICE_LOG_FILE_FAILED,
  /** Notice when a folder cannot be created. */
  NOTICE_FOLDER_FAILED,
  /** Notice when no application opened a file, folder or page. */
  NOTICE_LAUNCH_FAILED,
  /** Notice after Report an Issue. */
  NOTICE_ISSUE_DETAILS,
  /** Notice when the user guide cannot be written. */
  NOTICE_GUIDE_FAILED,
  /** Notice naming the user guide when no application opened it. */
  NOTICE_GUIDE_AT,
  /** Status bar: line and column. */
  STATUS_POSITION,
  /** Status bar: selected chars. */
  STATUS_SELECTION,
  /** Status bar: selected chars and carets. */
  STATUS_SELECTION_CARETS,
  /** Status bar: a charset written with a byte order mark. */
  STATUS_BOM,
  /** Status bar line endings. */
  STATUS_CRLF,
  /** Status bar line endings. */
  STATUS_LF,
  /** Status bar line endings. */
  STATUS_CR,
  /** Status bar line endings: more than one kind. */
  STATUS_MIXED,
  /** Status bar language without a syntax. */
  STATUS_PLAIN_TEXT,
  /** Status bar marker of a read-only document. */
  STATUS_READ_ONLY,
  /** Status bar marker of a binary file's view. */
  STATUS_BINARY,
  /** Status bar link to Show Log after a warning. */
  STATUS_WARNINGS,
  /** Tooltip of the warnings link. */
  STATUS_WARNINGS_TIP,
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
  BUTTON_CANCEL,
  /** Search menu. */
  GROUP_SEARCH,
  /** View submenu of the split commands. */
  MENU_SPLIT,
  /** Command label. */
  COMMAND_SPLIT_HORIZONTAL,
  /** Command label. */
  COMMAND_SPLIT_VERTICAL,
  /** Command label. */
  COMMAND_UNSPLIT,
  /** Command label. */
  COMMAND_FOCUS_OTHER_VIEW,
  /** Command label. */
  COMMAND_FIND,
  /** Command label. */
  COMMAND_FIND_NEXT,
  /** Command label. */
  COMMAND_FIND_PREVIOUS,
  /** Command label. */
  COMMAND_REPLACE,
  /** Find bar label of the search field. */
  FIND_LABEL,
  /** Find bar label of the replacement field. */
  FIND_REPLACE_LABEL,
  /** Find bar check box. */
  FIND_MATCH_CASE,
  /** Find bar check box. */
  FIND_WHOLE_WORD,
  /** Find bar check box. */
  FIND_REGEX,
  /** Button. */
  BUTTON_PREVIOUS,
  /** Button. */
  BUTTON_NEXT,
  /** Button. */
  BUTTON_REPLACE,
  /** Button. */
  BUTTON_REPLACE_ALL,
  /** Find bar notice while a search runs. */
  FIND_SEARCHING,
  /** Find bar notice. */
  FIND_NOT_FOUND,
  /** Find bar notice: a forward search continued from the top. */
  FIND_WRAPPED_TOP,
  /** Find bar notice: a backward search continued from the bottom. */
  FIND_WRAPPED_BOTTOM,
  /** Find bar notice: the regex error and its index. */
  FIND_BAD_PATTERN,
  /** Find bar notice: the replacement error. */
  FIND_BAD_REPLACEMENT,
  /** Find bar notice: the regex overflowed the stack. */
  FIND_TOO_COMPLEX,
  /** Find bar notice: the number of matches Replace All replaced. */
  FIND_REPLACED,
  /** Find bar notice: Replace All dropped its result because the document changed. */
  FIND_REPLACE_ALL_STALE,
  FIND_REPLACE_STALE,
  /** Command label. */
  COMMAND_TOGGLE_LINE_COMMENT,
  /** Command label. */
  COMMAND_TOGGLE_BLOCK_COMMENT,
  /** Command label. */
  COMMAND_RELOAD_GRAMMARS,
  /** View menu cascade of the languages. */
  MENU_SYNTAX,
  /** Status bar language field's tip. */
  STATUS_LANGUAGE_TIP,
  /** Notice: grammar files skipped, the first with its reason. */
  NOTICE_GRAMMARS_SKIPPED,
  /** View submenu of the fold commands. */
  MENU_FOLDING,
  /** Command label. */
  COMMAND_FOLD,
  /** Command label. */
  COMMAND_UNFOLD,
  /** Command label. */
  COMMAND_TOGGLE_FOLD,
  /** Command label. */
  COMMAND_FOLD_ALL,
  /** Command label. */
  COMMAND_UNFOLD_ALL;

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
