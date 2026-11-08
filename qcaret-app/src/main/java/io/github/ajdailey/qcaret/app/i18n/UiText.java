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
  /** Window title with a document without a file: the name with its dirty mark. */
  WINDOW_TITLE_DOCUMENT,
  /** Window title with a file: the name with its dirty mark, the path. */
  WINDOW_TITLE_FILE,
  /** A running load: the file's name. */
  LOAD_PROGRESS,
  /** Several running loads: the count. */
  LOAD_PROGRESS_FILES,
  /** Command label. */
  COMMAND_CLEAR_CLOSED_FILES,
  /** File menu cascade of closed files. */
  MENU_RECENTLY_CLOSED,
  /** Command label. */
  COMMAND_RELOAD,
  /** Title of the Reload from Disk prompt. */
  RELOAD_TITLE,
  /** The Reload from Disk prompt on a dirty document: its name. */
  RELOAD_MESSAGE,
  /** Command label. */
  COMMAND_TAB_WIDTH_OTHER,
  /** Title of the tab width prompt. */
  TAB_WIDTH_TITLE,
  /** The tab width prompt: the largest width. */
  TAB_WIDTH_MESSAGE,
  /** Reopen with Encoding entry returning a file to detection. */
  MENU_ENCODING_AUTO,
  /** Preferences field. */
  PREFERENCES_TAB_WIDTH,
  /** Preferences field. */
  PREFERENCES_INSERT_SPACES,
  /** Command label. */
  COMMAND_RIGHT_MARGIN,
  /** Command label. */
  COMMAND_INSERT_DATE_TIME,
  /** Command label. */
  COMMAND_DOCUMENT_STATISTICS,
  /** Command label. */
  COMMAND_DELETE_WORD_BACK,
  /** Command label. */
  COMMAND_DELETE_WORD_FORWARD,
  /** Command label. */
  COMMAND_TITLE_CASE,
  /** Command label. */
  COMMAND_SWAP_CASE,
  /** Command label. */
  COMMAND_TRANSPOSE,
  /** Command label. */
  COMMAND_SELECT_ALL_OCCURRENCES,
  /** Command label. */
  COMMAND_SKIP_OCCURRENCE,
  /** Command label. */
  COMMAND_EXPAND_SELECTION,
  /** Command label. */
  COMMAND_SELECT_LINE,
  /** Command label. */
  COMMAND_UNDO_SELECTION,
  /** Command label. */
  COMMAND_REDO_SELECTION,
  /** Select All Occurrences found too many: the limit. */
  OCCURRENCES_LIMIT,
  /** A selection search's result arrived after an edit. */
  OCCURRENCES_STALE,
  /** Preferences field. */
  PREFERENCES_RIGHT_MARGIN,
  /** Preferences field. */
  PREFERENCES_DATE_FORMAT,
  /** Refusal of a date format: the field's label. */
  PREFERENCES_BAD_DATE_FORMAT,
  /** Title of the date format chooser. */
  DATE_FORMAT_TITLE,
  /** The date format chooser's prompt. */
  DATE_FORMAT_LABEL,
  /** A date format chooser row: the formatted time and the pattern. */
  DATE_FORMAT_ROW,
  /** Button inserting the chosen format. */
  BUTTON_INSERT,
  /** Title of the Document Statistics dialog. */
  STATISTICS_TITLE,
  /** Document Statistics heading: the document's name and the charset name. */
  STATISTICS_HEADING,
  /** Document Statistics column. */
  STATISTICS_DOCUMENT,
  /** Document Statistics column. */
  STATISTICS_SELECTION,
  /** Document Statistics row. */
  STATISTICS_LINES,
  /** Document Statistics row. */
  STATISTICS_WORDS,
  /** Document Statistics row. */
  STATISTICS_CHARS,
  /** Document Statistics row. */
  STATISTICS_NON_SPACE_CHARS,
  /** Document Statistics row. */
  STATISTICS_BYTES,
  /** Document Statistics while counting: the percent read. */
  STATISTICS_COUNTING,
  /** Document Statistics after an edit during the count. */
  STATISTICS_CHANGED,
  /** Document Statistics without a document. */
  STATISTICS_NO_DOCUMENT,
  /** Button counting again. */
  BUTTON_REFRESH,
  /** Command label. */
  COMMAND_WRAP_PARAGRAPH,
  /** Command label. */
  COMMAND_INDENT_GUIDES,
  /** Command label. */
  COMMAND_LINE_CHANGES,
  /** Gutter tooltip of a document too large for modified-line marks: the line and char limits. */
  LINE_CHANGES_OVER_LIMIT,
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
  COMMAND_PREFERENCES,
  /** Heading of the conflict list in the shortcut reference. */
  SHORTCUTS_CONFLICTS,
  /** Two commands on one key: the key and their names. */
  SHORTCUTS_CONFLICT_LINE,
  /** Preferences dialog title. */
  PREFERENCES_TITLE,
  /** Preferences group. */
  PREFERENCES_TAB_EDITOR,
  /** Preferences group. */
  PREFERENCES_TAB_FILES,
  /** Preferences group. */
  PREFERENCES_TAB_SEARCH,
  /** Preferences group. */
  PREFERENCES_TAB_APPEARANCE,
  /** Preferences group. */
  PREFERENCES_TAB_KEYS,
  /** Preferences field. */
  PREFERENCES_COMPLETION_CHARS,
  /** Preferences field. */
  PREFERENCES_AUTO_CLOSE,
  /** Preferences field. */
  PREFERENCES_HIGHLIGHT_CHARS,
  /** Preferences field. */
  PREFERENCES_POLL_SECONDS,
  /** Preferences field. */
  PREFERENCES_AUTO_RELOAD,
  /** Preferences field: seconds between recovery file writes. */
  PREFERENCES_RECOVERY_SECONDS,
  /** Preferences field: days a dead instance's recovery files are kept. */
  PREFERENCES_RECOVERY_DAYS,
  /** Preferences box: quit without asking and restore unsaved buffers. */
  PREFERENCES_SESSION_SNAPSHOT,
  /** Title of the session report. */
  SESSION_TITLE,
  /** Files of the last session that did not open: their names. */
  SESSION_SKIPPED,
  /** The names a session report lists and how many more it left out. */
  SESSION_SKIPPED_MORE,
  /** Comments the last save left out and how many a session holds. */
  SESSION_COMMENTS_DROPPED,
  /** Snapshot write failed; the quit asks about each buffer. */
  SESSION_SNAPSHOT_FAILED,
  /** Note on a restored search set. */
  SEARCH_RESTORED,
  /** Recovery dialog title. */
  RECOVERY_TITLE,
  /** Recovery dialog message. */
  RECOVERY_MESSAGE,
  /** Recovery dialog column. */
  RECOVERY_COLUMN_FILE,
  /** Recovery dialog column. */
  RECOVERY_COLUMN_TIME,
  /** Recovery dialog column. */
  RECOVERY_COLUMN_STATE,
  /** Recovery dialog button. */
  RECOVERY_RESTORE,
  /** Recovery dialog button. */
  RECOVERY_DISCARD,
  /** Recovery dialog button. */
  RECOVERY_LATER,
  /** Recovery row: an untitled buffer. */
  RECOVERY_NO_FILE,
  /** Recovery row: the original file holds the bytes the buffer started from. */
  RECOVERY_UNCHANGED,
  /** Recovery row: the original file changed since. */
  RECOVERY_CHANGED,
  /** Recovery row: the original file is gone. */
  RECOVERY_MISSING,
  /** Recovery dialog state: the original file exists and cannot be read. */
  RECOVERY_UNREADABLE,
  /** Recovery row: the recovery file is damaged. */
  RECOVERY_DAMAGED,
  /** A damaged recovery file was not restored. */
  RECOVERY_DAMAGED_KEPT,
  /** A recovered buffer does not fit the heap budget: the file's name. */
  RECOVERY_TOO_LARGE,
  /** A recovery file could not be read: its name. */
  RECOVERY_READ_FAILED,
  /** A tab of the file has unsaved changes: the file. */
  RECOVERY_TAB_DIRTY,
  /** A recovery file could not be written. */
  RECOVERY_WRITE_FAILED,
  /** Preferences field. */
  PREFERENCES_BACKUP_DAYS,
  /** Preferences field. */
  PREFERENCES_SEARCH_LINE_CHARS,
  /** Preferences field. */
  PREFERENCES_EXCLUDED_DIRECTORIES,
  /** Preferences field. */
  PREFERENCES_THEME,
  /** Theme choice. */
  PREFERENCES_THEME_LIGHT,
  /** Theme choice. */
  PREFERENCES_THEME_DARK,
  /** Theme choice. */
  PREFERENCES_THEME_SYSTEM,
  /** Preferences field. */
  PREFERENCES_FONT_SIZE,
  /** Preferences field. */
  PREFERENCES_THEME_FILE,
  /** Theme file choice: the built-in colors. */
  PREFERENCES_THEME_FILE_NONE,
  /** Preferences field. */
  PREFERENCES_FONT_FAMILY,
  /** Font family choice: the platform's monospace font. */
  PREFERENCES_FONT_FAMILY_DEFAULT,
  /** Refused field: its label, the smallest and the largest value. */
  PREFERENCES_BAD_NUMBER,
  /** Refused field: its label. */
  PREFERENCES_BAD_DIRECTORIES,
  /** Notice after a failed save. */
  PREFERENCES_NOT_SAVED,
  /** Hint in the key field. */
  PREFERENCES_KEY_CAPTURE,
  /** Add refused: the key and the command holding it. */
  PREFERENCES_KEY_TAKEN,
  /** Add refused: the key. */
  PREFERENCES_KEY_TYPES,
  /** A row with keys set by the user: the command's name. */
  PREFERENCES_KEY_CHANGED,
  /** Title of the prompt before the search histories are cleared. */
  PREFERENCES_CLEAR_HISTORY_TITLE,
  /** Message of the prompt before the search histories are cleared. */
  PREFERENCES_CLEAR_HISTORY_MESSAGE,
  /** Notice when the search histories stay in the settings file. */
  PREFERENCES_HISTORY_NOT_REMOVED,
  /** Button. */
  BUTTON_CLEAR_HISTORY,
  /** Button. */
  BUTTON_APPLY,
  /** Button. */
  BUTTON_RESTORE_DEFAULTS,
  /** Button. */
  BUTTON_ADD_KEY,
  /** Button. */
  BUTTON_REMOVE_KEY,
  /** Button. */
  BUTTON_RESET_KEY,
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

  /** Window > New Window. */
  COMMAND_NEW_WINDOW,

  /** Window > Move to New Window and the tab menu's item. */
  COMMAND_MOVE_TO_NEW_WINDOW,

  /** Window menu entry of a window with no document. */
  WINDOW_EMPTY,

  /** A tab that cannot move; takes the tab's name. */
  NOTICE_MOVE_BUSY,

  /** A tab whose file the target window holds; takes the tab's name. */
  NOTICE_MOVE_SHOWN,
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
  /** User guide window title. */
  GUIDE_TITLE,
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
  /** Reason of an open ended by an internal failure, such as running out of memory. */
  OPEN_STOPPED,
  /** The file is the running instance's lock. */
  INSTANCE_LOCK_REFUSED,
  /** The file is a recovery directory's lock, held by this process. */
  RECOVERY_LOCK_REFUSED,
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
  /** Syntax submenu of the programming languages A to M. */
  MENU_SYNTAX_PROGRAMMING_A_M,
  /** Syntax submenu of the programming languages N to Z. */
  MENU_SYNTAX_PROGRAMMING_N_Z,
  /** Syntax submenu of the markup and data formats. */
  MENU_SYNTAX_MARKUP,
  /** Syntax submenu of the build, configuration and log formats. */
  MENU_SYNTAX_BUILD,
  /** Syntax submenu of the user grammars without a known category. */
  MENU_SYNTAX_USER,
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
  COMMAND_UNFOLD_ALL,
  /** Edit submenu of the sort commands. */
  MENU_SORT_LINES,
  /** Command label. */
  COMMAND_SORT_LINES_ASCENDING,
  /** Command label. */
  COMMAND_SORT_LINES_DESCENDING,
  /** Command label. */
  COMMAND_SORT_LINES_IGNORE_CASE,
  /** Command label. */
  COMMAND_REMOVE_DUPLICATE_LINES,
  /** Command label. */
  COMMAND_TRIM_TRAILING_WHITESPACE,
  /** Command label. */
  COMMAND_COMPLETE_WORD,
  /** Command label. */
  COMMAND_SELECT_TO_MATCHING_BRACKET,
  /** Command label. */
  COMMAND_GO_TO_MATCHING_BRACKET,
  /** Search submenu of the bookmark commands. */
  MENU_BOOKMARKS,
  /** Command label. */
  COMMAND_TOGGLE_BOOKMARK,
  /** Command label. */
  COMMAND_NEXT_BOOKMARK,
  /** Command label. */
  COMMAND_PREVIOUS_BOOKMARK,
  /** Command label. */
  COMMAND_CLEAR_BOOKMARKS,
  /** Command label. */
  COMMAND_BOOKMARKS_LIST,
  /** Command label. */
  COMMAND_EDIT_BOOKMARK_COMMENT,
  /** Bookmark comment prompt title. */
  BOOKMARK_COMMENT_TITLE,
  /** Bookmark comment prompt: the line, from 1. */
  BOOKMARK_COMMENT_MESSAGE,
  /** Notice: a comment longer than the limit. */
  BOOKMARK_COMMENT_TOO_LONG,
  /** Notice: a document at its comment limit. */
  BOOKMARK_COMMENT_TOO_MANY,
  /** Bookmarks list button. */
  BOOKMARKS_GO_TO,
  /** Bookmarks list button. */
  BOOKMARKS_EDIT,
  /** Bookmarks list button. */
  BOOKMARKS_REMOVE,
  /** Bookmarks list button. */
  BOOKMARKS_HIDE,
  /** Bookmarks list column. */
  BOOKMARKS_LINE,
  /** Bookmarks list column. */
  BOOKMARKS_COMMENT,
  /** Bookmarks list column. */
  BOOKMARKS_TEXT,
  /** Bookmarks list summary: the document's name and its bookmark count. */
  BOOKMARKS_SUMMARY,
  /** Bookmarks list summary when rows were left out: name, rows, bookmarks. */
  BOOKMARKS_SUMMARY_CUT,
  /** Bookmarks list summary without a document. */
  BOOKMARKS_NO_DOCUMENT,
  /** Command label. */
  COMMAND_SHOW_WHITESPACE,
  /** Command label. */
  COMMAND_SHOW_LINE_ENDINGS,
  /** Command label. */
  COMMAND_COMMAND_PALETTE,
  /** Command palette title. */
  PALETTE_TITLE,
  /** Command palette filter field hint. */
  PALETTE_FILTER,
  /** Command label. */
  COMMAND_QUICK_OPEN,
  /** Command label. */
  COMMAND_SWITCH_DOCUMENT,
  /** File cascade of the recent files. */
  MENU_RECENT_FILES,
  /** Command label. */
  COMMAND_CLEAR_RECENT_FILES,
  /** Recent Files entry: its number and path. */
  RECENT_FILE,
  /** Command label. */
  COMMAND_WORKSPACE_BROWSER,
  /** Command label. */
  COMMAND_GO_BACK,
  /** Command label. */
  COMMAND_GO_FORWARD,
  /** Notice: a history position's file is gone. */
  HISTORY_MISSING,
  /** Quick Open title. */
  QUICK_OPEN_TITLE,
  /** Switch Document title. */
  SWITCH_DOCUMENT_TITLE,
  /** Quick Open filter hint. */
  QUICK_OPEN_FILTER,
  /** Quick Open column. */
  QUICK_OPEN_NAME,
  /** Quick Open column. */
  QUICK_OPEN_PATH,
  /** Quick Open summary: matched and indexed files. */
  QUICK_OPEN_FILES,
  /** Quick Open summary for an empty query: indexed files. */
  QUICK_OPEN_INDEXED,
  /** Quick Open summary after the index stopped at its file limit. */
  QUICK_OPEN_CUT_LIMIT,
  /** Quick Open summary after the index stopped for memory. */
  QUICK_OPEN_CUT_MEMORY,
  /** Quick Open summary while the index builds. */
  QUICK_OPEN_INDEXING,
  /** Quick Open summary while a worker matches. */
  QUICK_OPEN_MATCHING,
  /** Quick Open summary without a workspace folder. */
  QUICK_OPEN_NO_WORKSPACE,
  /** Switch Document summary. */
  QUICK_OPEN_DOCUMENTS,
  /** Workspace browser title without a folder. */
  WORKSPACE_NONE,
  /** Workspace browser row while a directory is listed. */
  WORKSPACE_LOADING,
  /** Workspace browser row of an empty directory. */
  WORKSPACE_EMPTY,
  /** Workspace browser row of an unreadable directory. */
  WORKSPACE_UNREADABLE,
  /** Workspace browser row after the entry limit. */
  WORKSPACE_TRUNCATED,
  /** Workspace browser row: entries the next activation adds and entries not shown. */
  WORKSPACE_MORE,
  /** Workspace browser button. */
  WORKSPACE_REFRESH,
  /** Workspace browser button. */
  WORKSPACE_REVEAL,
  /** Workspace browser button. */
  WORKSPACE_HIDE,
  /** Workspace browser menu item. */
  WORKSPACE_OPEN,
  /** Workspace browser menu item. */
  WORKSPACE_OPEN_DEFAULT,
  /** Workspace browser menu item. */
  WORKSPACE_SET_FOLDER,
  /** Workspace browser menu item. */
  WORKSPACE_COPY_PATH,
  /** Notice: the selected document is not below the workspace folder. */
  WORKSPACE_OUTSIDE,
  /** Notice: the selected document is hidden or in an excluded directory. */
  WORKSPACE_NOT_LISTED,
  /** Notice: Open in Default Application refused a file the system would run. */
  WORKSPACE_RUNS,
  /** Notice: no application opened the file. */
  WORKSPACE_LAUNCH_FAILED,
  /** Workspace browser menu item. */
  WORKSPACE_SEARCH_FOLDER,
  /** Workspace browser menu item. */
  WORKSPACE_NEW_FILE,
  /** Workspace browser menu item. */
  WORKSPACE_NEW_FOLDER,
  /** Workspace browser menu item. */
  WORKSPACE_RENAME,
  /** Workspace browser menu item. */
  WORKSPACE_DELETE,
  /** New File window title. */
  NEW_FILE_TITLE,
  /** New Folder window title. */
  NEW_FOLDER_TITLE,
  /** New File and New Folder field label; the directory. */
  NEW_ENTRY_MESSAGE,
  /** Rename window title. */
  RENAME_TITLE,
  /** Rename field label; the old name. */
  RENAME_MESSAGE,
  /** Refusal: not a single file name; the name. */
  FILE_NAME_INVALID,
  /** Refusal: the name is taken; the name. */
  FILE_EXISTS,
  /** Refusal: a save, reload or change prompt holds an open document under the entry. */
  FILE_BUSY,
  /** Create failed; the name, then the reason. */
  CREATE_FAILED,
  /** Rename failed; the name, then the reason. */
  RENAME_FAILED,
  /** Delete window title. */
  DELETE_TITLE,
  /** Delete prompt for a file; the name. */
  DELETE_FILE_MESSAGE,
  /** Delete prompt for a link; the name. */
  DELETE_LINK_MESSAGE,
  /** Delete prompt for a folder; the name, its files, its folders. */
  DELETE_FOLDER_MESSAGE,
  /** Delete prompt line: open documents with unsaved changes; their names. */
  DELETE_UNSAVED,
  /** Delete stopped; the name, the entry that failed, the reason, files and folders left. */
  DELETE_FAILED,
  /** Delete refused: the entry cannot be read; the name, then the reason. */
  DELETE_UNREADABLE,
  /** Save or reload refused while a browser rename of the file or a folder above it runs. */
  RENAME_RUNNING,
  /** Button label. */
  BUTTON_DELETE,
  /** Notice: a line command dropped its result because the document changed. */
  LINES_STALE,
  /** Command label. */
  COMMAND_SET_WORKSPACE,
  /** Command label. */
  COMMAND_HYPERSEARCH,
  /** Command label. */
  COMMAND_NEXT_RESULT,
  /** Command label. */
  COMMAND_PREVIOUS_RESULT,
  /** Command label. */
  COMMAND_SEARCH_RESULTS,
  /** Command label. */
  COMMAND_OVERVIEW_STRIP,
  /** Command label. */
  COMMAND_LINE_NUMBERS,
  /** Command label. */
  COMMAND_TOOLBAR,
  /** Tab menu item. */
  TAB_CLOSE_LEFT,
  /** Tab menu item. */
  TAB_CLOSE_RIGHT,
  /** Tab menu item. */
  TAB_OPEN_FOLDER,
  /** Notice when a tab's folder does not exist; takes the folder. */
  NOTICE_FOLDER_MISSING,
  /** HyperSearch scope. */
  SCOPE_CURRENT_DOCUMENT,
  /** HyperSearch scope. */
  SCOPE_SELECTION,
  /** HyperSearch scope. */
  SCOPE_OPEN_DOCUMENTS,
  /** HyperSearch scope. */
  SCOPE_WORKSPACE,
  /** HyperSearch scope. */
  SCOPE_DIRECTORY,
  /** HyperSearch scope. */
  SCOPE_TREE,
  /** HyperSearch dialog and message title. */
  SEARCH_TITLE,
  /** HyperSearch dialog field label. */
  SEARCH_SCOPE_LABEL,
  /** HyperSearch dialog field label. */
  SEARCH_DIRECTORY_LABEL,
  /** HyperSearch dialog button. */
  SEARCH_BROWSE,
  /** HyperSearch dialog field label. */
  SEARCH_INCLUDE_LABEL,
  /** HyperSearch dialog field label. */
  SEARCH_EXCLUDE_LABEL,
  /** HyperSearch dialog check box. */
  SEARCH_HIDDEN,
  /** HyperSearch dialog: the workspace folder. */
  SEARCH_WORKSPACE,
  /** HyperSearch dialog: no workspace folder. */
  SEARCH_WORKSPACE_NONE,
  /** HyperSearch dialog button. */
  SEARCH_RUN,
  /** Message: no search text. */
  SEARCH_EMPTY,
  /** Message: the scope needs a document. */
  SEARCH_NO_DOCUMENT,
  /** Message: the selection scope without a selection. */
  SEARCH_NO_SELECTION,
  /** Message: the workspace scope without a folder. */
  SEARCH_NO_WORKSPACE,
  /** Message: a directory scope without a valid directory. */
  SEARCH_NO_DIRECTORY,
  /** Message: a file mask does not parse. */
  SEARCH_BAD_MASK,
  /** Results row of a set: text, scope, matches, files. */
  SEARCH_SET,
  /** Set row suffix while running: files done, files to search. */
  SEARCH_RUNNING,
  /** Set row suffix. */
  SEARCH_CANCELED,
  /** Set row suffix: the match limit. */
  SEARCH_TRUNCATED,
  /** Set row suffix. */
  SEARCH_NO_MEMORY,
  /** Set row suffix. */
  SEARCH_RERUNNING,
  /** Set row suffix. */
  SEARCH_PINNED,
  /** Set row suffix: what was skipped. */
  SEARCH_SKIPPED,
  /** Skipped count. */
  SEARCH_SKIPPED_BINARY,
  /** Skipped count. */
  SEARCH_SKIPPED_LARGE,
  /** Skipped count. */
  SEARCH_SKIPPED_UNREADABLE,
  /** Skipped count. */
  SEARCH_SKIPPED_LINES,
  /** Skipped count. */
  SEARCH_SKIPPED_COMPLEX,
  /** Results row of a file: name, folder, matches. */
  SEARCH_FILE,
  /** File row suffix. */
  SEARCH_FILE_COMPLEX,
  /** File row suffix. */
  SEARCH_FILE_LINES,
  /** Row suffix of a changed source. */
  SEARCH_CHANGED,
  /** Notice: navigation found other text at a result. */
  SEARCH_STALE,
  /** Notice: a rerun stopped. */
  SEARCH_RERUN_KEPT,
  /** Notice: a result's untitled document is closed. */
  SEARCH_CLOSED,
  /** Notice: next result without results. */
  SEARCH_NO_RESULTS,
  /** Results dock button. */
  RESULTS_STOP,
  /** Results dock button. */
  RESULTS_RERUN,
  /** Results dock button. */
  RESULTS_PIN,
  /** Results dock button. */
  RESULTS_CLEAR,
  /** Results dock button. */
  RESULTS_CLEAR_ALL,
  /** Results dock button. */
  RESULTS_HIDE,
  /** Command label. */
  COMMAND_REPLACE_IN_RESULTS,
  /** Command label. */
  COMMAND_RESTORE_REPLACE_RUN,
  /** Results dock button. */
  RESULTS_RERUN_FILE,
  /** Results dock button. */
  RESULTS_REPLACE,
  /** Notice: a file rerun could not read the file: its name and why. */
  SEARCH_RERUN_FILE_SKIPPED,
  /** Notice: a file rerun found no match: its name. */
  SEARCH_RERUN_FILE_EMPTY,
  /** Notice: replace or file rerun on a running set. */
  SEARCH_NOT_FINISHED,
  /** Replace preview title. */
  REPLACE_TITLE,
  /** Replace preview header: text, scope, matches, files. */
  REPLACE_HEADER,
  /** Replace preview label. */
  REPLACE_WITH_LABEL,
  /** Replace preview label. */
  REPLACE_FILES_LABEL,
  /** Replace preview label. */
  REPLACE_MATCHES_LABEL,
  /** Replace preview label. */
  REPLACE_PREVIEW_LABEL,
  /** Replace preview file row: name, folder, checked matches, matches. */
  REPLACE_FILE,
  /** Replace preview summary: matches and files checked. */
  REPLACE_SUMMARY,
  /** Replace preview summary. */
  REPLACE_NOTHING_CHECKED,
  /** Replace preview summary: the parse error. */
  REPLACE_BAD_TEMPLATE,
  /** Replace preview pane while computing. */
  REPLACE_COMPUTING,
  /** Replace preview note: changes not shown. */
  REPLACE_MORE,
  /** Replace preview note: why a file will be skipped. */
  REPLACE_PREVIEW_SKIPPED,
  /** Replace preview button. */
  REPLACE_APPLY,
  /** Notice: a replace already runs. */
  REPLACE_RUNNING,
  /** Replace report title: text and replacement. */
  REPLACE_REPORT_TITLE,
  /** Report summary while running: files done, files. */
  REPLACE_REPORT_PROGRESS,
  /** Report summary: changed, skipped, failed, not attempted. */
  REPLACE_REPORT_DONE,
  /** Report summary suffix: the backup folder. */
  REPLACE_REPORT_BACKUPS,
  /** Report row: matches replaced. */
  REPLACE_ROW_CHANGED,
  /** Report row. */
  REPLACE_ROW_RESTORED,
  /** Report row: the reason. */
  REPLACE_ROW_SKIPPED,
  /** Report row: the error. */
  REPLACE_ROW_FAILED,
  /** Report row. */
  REPLACE_ROW_NOT_ATTEMPTED,
  /** Report button. */
  REPLACE_STOP,
  /** Report button. */
  REPLACE_RESTORE,
  /** Report column. */
  REPLACE_FILE_COLUMN,
  /** Report column. */
  REPLACE_FOLDER_COLUMN,
  /** Report column. */
  REPLACE_RESULT_COLUMN,
  /** Restore report title: the run. */
  RESTORE_TITLE,
  /** Restore chooser title. */
  RESTORE_CHOOSE_TITLE,
  /** Restore chooser label. */
  RESTORE_CHOOSE_LABEL,
  /** Restore chooser row: start time, files. */
  RESTORE_RUN_ROW,
  /** Message: no run is kept. */
  RESTORE_NO_RUNS,
  /** Message: the runs cannot be listed. */
  RESTORE_LIST_FAILED,
  /** Save As over an open file: title. */
  SAVE_AS_OPEN_TITLE,
  /** Save As over an open file with unsaved changes: its name. */
  SAVE_AS_OPEN_MESSAGE,
  /** Save As over an open file: confirm. */
  BUTTON_REPLACE_FILE,
  /** Replace skip reason. */
  REASON_MISSING,
  /** Replace skip reason. */
  REASON_CHANGED,
  /** Replace skip reason. */
  REASON_READ_ONLY,
  /** Replace skip reason. */
  REASON_UNREADABLE,
  /** Replace skip reason. */
  REASON_TOO_LARGE,
  /** Replace skip reason. */
  REASON_BINARY,
  /** Replace skip reason. */
  REASON_MALFORMED,
  /** Replace skip reason. */
  REASON_NO_ROUND_TRIP,
  /** Replace skip reason. */
  REASON_NO_MATCHES,
  /** Replace skip reason. */
  REASON_UNMAPPABLE,
  /** Replace skip reason. */
  REASON_NO_MEMORY,
  /** Replace skip reason. */
  REASON_CLOSED,
  /** Replace skip reason. */
  REASON_TOO_COMPLEX,
  /** Replace skip reason. */
  REASON_UNCHANGED,
  /** Replace skip reason. */
  REASON_CHANGED_SINCE,
  /** Notice: the workspace folder. */
  WORKSPACE_SET,
  /** Tab title of a document whose file was deleted. */
  TAB_MISSING,
  /** Save notice: a reload runs. */
  SAVE_RELOADING,
  /** Save notice: bytes that do not encode back; the file name, then the encoding. */
  SAVE_NO_ROUND_TRIP,
  /** Notice: a file was deleted. */
  CHANGE_MISSING,
  /** External change prompt title. */
  CHANGE_TITLE,
  /** External change prompt. */
  CHANGE_MESSAGE,
  /** Save conflict prompt. */
  CHANGE_SAVE_MESSAGE,
  /** Button label. */
  BUTTON_KEEP_MINE,
  /** Button label. */
  BUTTON_RELOAD,
  /** Button label. */
  BUTTON_COMPARE,
  /** Button label. */
  BUTTON_OVERWRITE,
  /** Notice: a reload failed. */
  RELOAD_FAILED,
  /** Notice: no memory to reload. */
  RELOAD_TOO_LARGE,
  /** Notice: edits kept a reload from finishing. */
  RELOAD_EDITED,
  /** Compare cascade in the View menu. */
  MENU_COMPARE,
  /** Command label. */
  COMMAND_COMPARE_DOCUMENTS,
  /** Command label. */
  COMMAND_COMPARE_WITH_DISK,
  /** Compare chooser title. */
  COMPARE_CHOOSE_TITLE,
  /** Compare chooser label. */
  COMPARE_CHOOSE_LABEL,
  /** Notice: one document only. */
  COMPARE_NO_OTHER,
  /** Notice: a side exceeds the bounds. */
  COMPARE_TOO_LARGE,
  /** Comparison side: the file on disk. */
  COMPARE_DISK,
  /** Notice: no file to compare. */
  COMPARE_NO_FILE,
  /** Notice: the file cannot be read. */
  COMPARE_FAILED,
  /** Comparison notice: no differences. */
  COMPARE_IDENTICAL,
  /** Comparison notice: long lines cut. */
  COMPARE_LINES_CUT,
  /** Comparison window title. */
  COMPARE_WINDOW_TITLE,
  /** Comparison button. */
  COMPARE_NEXT,
  /** Comparison button. */
  COMPARE_PREVIOUS,
  /** Comparison position. */
  COMPARE_POSITION,
  /** Comparison position without differences. */
  COMPARE_NONE,
  /** File > Print. */
  COMMAND_PRINT,
  /** Print row while the pages are counted; takes the document name. */
  PRINT_PREPARING,
  /** Print row; takes the document name, the page and the page count. */
  PRINT_PROGRESS,
  /** Page header; takes the page and the page count. */
  PRINT_PAGE,
  /** Notice after a job; takes the document name and the pages sent. */
  PRINT_SENT,
  /** Notice for a second job while one runs. */
  PRINT_RUNNING,
  /** Notice when the system has no printer. */
  PRINT_NO_PRINTER,
  /** Notice when the dialog's Selection choice meets no selection. */
  PRINT_NO_SELECTION,
  /** Notice for a page range past the end; takes the page count. */
  PRINT_NO_PAGES,
  /** Notice after a failed job; takes the document name. */
  PRINT_FAILED,
  /** Tab tool tip line naming where the settings came from; takes the sources. */
  SETTINGS_FROM,
  /** A modeline as a settings source; takes its kind. */
  SETTINGS_MODELINE,
  /** Preferences box for reading .editorconfig files. */
  PREFERENCES_EDITOR_CONFIG,
  /** Preferences box for reading modelines. */
  PREFERENCES_MODELINES,
  /** Command label. */
  COMMAND_GO_TO_SYMBOL,
  /** Quick Open detail column heading over symbol rows. */
  SYMBOL_HEADING,
  /** Symbol row detail; takes the kind and the line. */
  SYMBOL_DETAIL,
  /** Symbol summary without a document. */
  SYMBOL_NO_DOCUMENT,
  /** Symbol summary while the scan runs; takes the document name. */
  SYMBOL_SCANNING,
  /** Symbol summary after the document changed during every scan; takes its name. */
  SYMBOL_CHANGING,
  /** Symbol summary for plain text; takes the document name. */
  SYMBOL_PLAIN,
  /** Symbol summary for a grammar without symbol rules; takes the document and grammar names. */
  SYMBOL_NO_RULES,
  /** Symbol summary; takes the matched and found counts and the document name. */
  SYMBOL_COUNT,
  /** Symbol summary after the scan stopped at its count; takes the summary and the count. */
  SYMBOL_CUT,
  /** Symbol summary with long lines skipped; takes the summary and the line count. */
  SYMBOL_SKIPPED,
  /** Notice when no symbol matches a file@symbol query; takes the query and the document name. */
  SYMBOL_NOT_FOUND,
  /** Workspace browser button: the shown folder's parent. */
  BROWSE_PARENT,
  /** Workspace browser button: the home folder. */
  BROWSE_HOME,
  /** Workspace browser button: the favorites menu. */
  BROWSE_FAVORITES,
  /** Workspace browser button: the workspace folder again. */
  BROWSE_WORKSPACE,
  /** Favorites menu item. */
  BROWSE_ADD_FAVORITE,
  /** Favorites menu item. */
  BROWSE_REMOVE_FAVORITE,
  /** Favorites menu row without favorites. */
  BROWSE_NO_FAVORITES,
  /** Path field hint. */
  BROWSE_PATH_HINT,
  /** Path field tool tip while it shows the workspace folder. */
  BROWSE_PATH_WORKSPACE,
  /** Path field tool tip while it shows another folder; takes the workspace folder. */
  BROWSE_PATH_OTHER,
  /** Filter field hint. */
  BROWSE_FILTER_HINT,
  /** Check box: list hidden entries. */
  BROWSE_HIDDEN,
  /** Browser column. */
  BROWSE_COLUMN_NAME,
  /** Browser column. */
  BROWSE_COLUMN_SIZE,
  /** Browser column. */
  BROWSE_COLUMN_MODIFIED,
  /** Notice: Reveal Active File outside the shown folder; takes the file name. */
  BROWSE_OUTSIDE,
  /** Notice: a typed folder does not exist; takes the path. */
  BROWSE_MISSING,
  /** Notice: a typed path is a file; takes the path. */
  BROWSE_NOT_FOLDER,
  /** Notice: a typed path does not parse; takes the text. */
  BROWSE_INVALID,
  /** Notice: a filter does not parse; takes the text. */
  BROWSE_BAD_FILTER,
  /** Notice: a rename moved the workspace folder; takes the new path. */
  WORKSPACE_MOVED,
  /** A count of one match, for {@link #count}. */
  COUNT_MATCH,
  /** A count of matches other than one, for {@link #count}. */
  COUNT_MATCHES,
  /** A count of one file, for {@link #count}. */
  COUNT_FILE,
  /** A count of files other than one, for {@link #count}. */
  COUNT_FILES,
  /** A count of one page, for {@link #count}. */
  COUNT_PAGE,
  /** A count of pages other than one, for {@link #count}. */
  COUNT_PAGES,
  /** A count of one bookmark, for {@link #count}. */
  COUNT_BOOKMARK,
  /** A count of bookmarks other than one, for {@link #count}. */
  COUNT_BOOKMARKS,
  /** A count of one symbol, for {@link #count}. */
  COUNT_SYMBOL,
  /** A count of symbols other than one, for {@link #count}. */
  COUNT_SYMBOLS,
  /** Notice: a delete took the workspace folder; takes its path. */
  WORKSPACE_DELETED;

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
   * Formats a count with its noun: {@code one} when the count is 1, else {@code many}.
   *
   * @param count the count
   * @param one the pattern for a count of one, such as {@link #COUNT_FILE}
   * @param many the pattern for any other count, such as {@link #COUNT_FILES}
   * @return the formatted count, such as {@code 1 file} or {@code 2 files}
   */
  public static String count(long count, UiText one, UiText many) {
    return (count == 1 ? one : many).format(count);
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
