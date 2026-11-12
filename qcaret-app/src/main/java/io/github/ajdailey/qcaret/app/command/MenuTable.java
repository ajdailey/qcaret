package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The layout of every menu: the commands of each menu-bar menu in order with their separators, the
 * editor's and the tab's context menus, and which check commands show as radio items. Consecutive
 * commands with the same {@link CommandId#submenu()} form one cascade. A separator before a
 * cascade's first command sits in the parent menu; one before a later command sits in the cascade.
 * Where the system supplies an application menu (macOS), About, Exit and Preferences sit there and
 * are left out of Help, File and Tools.
 */
public final class MenuTable {
  /** Commands the macOS application menu runs instead of the menu bar. */
  public static final Set<CommandId> APP_MENU_COMMANDS =
      EnumSet.of(CommandId.ABOUT, CommandId.QUIT, CommandId.PREFERENCES);

  /** Check commands shown as radio items: one choice of several. */
  public static final Set<CommandId> RADIO =
      EnumSet.of(
          CommandId.THEME_LIGHT,
          CommandId.THEME_DARK,
          CommandId.THEME_SYSTEM,
          CommandId.TAB_WIDTH_2,
          CommandId.TAB_WIDTH_4,
          CommandId.TAB_WIDTH_8,
          CommandId.TAB_WIDTH_OTHER,
          CommandId.LINE_ENDING_CRLF,
          CommandId.LINE_ENDING_LF,
          CommandId.LINE_ENDING_CR);

  /** The editor's context menu in order; null is a separator. */
  public static final List<CommandId> EDITOR_MENU =
      Collections.unmodifiableList(
          Arrays.asList(
              CommandId.UNDO,
              CommandId.REDO,
              null,
              CommandId.CUT,
              CommandId.COPY,
              CommandId.PASTE,
              null,
              CommandId.SELECT_ALL,
              null,
              CommandId.TOGGLE_BOOKMARK,
              CommandId.EDIT_BOOKMARK_COMMENT,
              null,
              CommandId.TOGGLE_FOLD));

  /** The tab's context menu labels in order; null is a separator. */
  public static final List<UiText> TAB_MENU =
      Collections.unmodifiableList(
          Arrays.asList(
              UiText.COMMAND_CLOSE,
              UiText.COMMAND_CLOSE_OTHERS,
              UiText.TAB_CLOSE_LEFT,
              UiText.TAB_CLOSE_RIGHT,
              UiText.TAB_CLOSE_ALL,
              null,
              UiText.TAB_COPY_PATH,
              UiText.TAB_COPY_FILE_NAME,
              UiText.TAB_OPEN_FOLDER,
              null,
              UiText.COMMAND_MOVE_TO_NEW_WINDOW));

  private static final Map<CommandGroup, List<CommandId>> LAYOUT = layout();

  /**
   * A menu-bar entry.
   *
   * @param command the command
   * @param separatorBefore true when a separator precedes it
   */
  public record Entry(CommandId command, boolean separatorBefore) {}

  private MenuTable() {}

  /**
   * Returns the commands a menu lists.
   *
   * @param group a group whose {@link CommandGroup#menu()} is true
   * @param appMenu true when the system's application menu holds About, Quit and Preferences
   * @return the commands in menu order
   */
  public static List<CommandId> commands(CommandGroup group, boolean appMenu) {
    List<CommandId> commands = new ArrayList<CommandId>();
    for (Entry entry : entries(group, appMenu)) {
      commands.add(entry.command());
    }
    return commands;
  }

  /**
   * Returns a menu's entries. The Encoding menu's first entry has a separator, after the cascades
   * the view adds; a separator before a command the application menu takes is dropped.
   *
   * @param group a group whose {@link CommandGroup#menu()} is true
   * @param appMenu true when the system's application menu holds About, Quit and Preferences
   * @return the entries in menu order
   */
  public static List<Entry> entries(CommandGroup group, boolean appMenu) {
    List<Entry> entries = new ArrayList<Entry>();
    List<CommandId> layout = LAYOUT.get(group);
    if (layout == null) {
      return entries;
    }
    boolean separator = false;
    for (CommandId command : layout) {
      if (command == null) {
        separator = true;
      } else if (appMenu && APP_MENU_COMMANDS.contains(command)) {
        separator = false;
      } else {
        entries.add(new Entry(command, separator));
        separator = false;
      }
    }
    return entries;
  }

  private static Map<CommandGroup, List<CommandId>> layout() {
    Map<CommandGroup, List<CommandId>> m =
        new EnumMap<CommandGroup, List<CommandId>>(CommandGroup.class);
    m.put(
        CommandGroup.FILE,
        Arrays.asList(
            CommandId.NEW,
            CommandId.OPEN,
            CommandId.RELOAD,
            null,
            CommandId.OPEN_ALL_RECENT,
            CommandId.CLEAR_RECENT_FILES,
            CommandId.REOPEN_CLOSED,
            CommandId.OPEN_ALL_CLOSED,
            CommandId.CLEAR_CLOSED_FILES,
            null,
            CommandId.SET_WORKSPACE,
            CommandId.QUICK_OPEN,
            null,
            CommandId.SAVE,
            CommandId.SAVE_AS,
            CommandId.SAVE_COPY_AS,
            CommandId.SAVE_ALL,
            null,
            CommandId.CLOSE,
            CommandId.CLOSE_ALL,
            CommandId.CLOSE_OTHERS,
            CommandId.CLOSE_LEFT,
            CommandId.CLOSE_RIGHT,
            null,
            CommandId.PRINT,
            null,
            CommandId.QUIT));
    m.put(
        CommandGroup.EDIT,
        Arrays.asList(
            CommandId.UNDO,
            CommandId.REDO,
            null,
            CommandId.CUT,
            CommandId.COPY,
            CommandId.PASTE,
            null,
            CommandId.SELECT_ALL,
            null,
            CommandId.DUPLICATE,
            CommandId.DELETE_LINE,
            CommandId.MOVE_LINES_UP,
            CommandId.MOVE_LINES_DOWN,
            CommandId.JOIN_LINES,
            null,
            CommandId.REMOVE_DUPLICATE_LINES,
            CommandId.TRIM_TRAILING_WHITESPACE,
            CommandId.WRAP_PARAGRAPH,
            CommandId.SORT_LINES_ASCENDING,
            CommandId.SORT_LINES_DESCENDING,
            CommandId.SORT_LINES_IGNORE_CASE,
            CommandId.UPPER_CASE,
            CommandId.LOWER_CASE,
            CommandId.TITLE_CASE,
            CommandId.SWAP_CASE,
            null,
            CommandId.TOGGLE_LINE_COMMENT,
            CommandId.TOGGLE_BLOCK_COMMENT,
            null,
            CommandId.INDENT,
            CommandId.OUTDENT,
            CommandId.TAB_WIDTH_2,
            CommandId.TAB_WIDTH_4,
            CommandId.TAB_WIDTH_8,
            CommandId.TAB_WIDTH_OTHER,
            CommandId.INDENT_WITH_SPACES,
            null,
            CommandId.COMPLETE_WORD,
            CommandId.INSERT_DATE_TIME,
            CommandId.TRANSPOSE,
            CommandId.DELETE_WORD_BACK,
            CommandId.DELETE_WORD_FORWARD,
            null,
            CommandId.READ_ONLY));
    m.put(
        CommandGroup.SELECTION,
        Arrays.asList(
            CommandId.SELECT_LINE,
            CommandId.EXPAND_SELECTION,
            CommandId.SELECT_TO_MATCHING_BRACKET,
            null,
            CommandId.ADD_CARET_ABOVE,
            CommandId.ADD_CARET_BELOW,
            CommandId.ADD_NEXT_OCCURRENCE,
            CommandId.SKIP_OCCURRENCE,
            CommandId.SELECT_ALL_OCCURRENCES,
            CommandId.SPLIT_INTO_LINES,
            null,
            CommandId.COLUMN_MODE,
            null,
            CommandId.UNDO_SELECTION,
            CommandId.REDO_SELECTION));
    m.put(
        CommandGroup.SEARCH,
        Arrays.asList(
            CommandId.FIND,
            CommandId.FIND_NEXT,
            CommandId.FIND_PREVIOUS,
            CommandId.REPLACE,
            null,
            CommandId.HYPERSEARCH,
            CommandId.NEXT_RESULT,
            CommandId.PREVIOUS_RESULT,
            CommandId.REPLACE_IN_RESULTS,
            CommandId.RESTORE_REPLACE_RUN,
            CommandId.SEARCH_RESULTS,
            null,
            CommandId.GO_TO_LINE,
            CommandId.GO_TO_SYMBOL,
            CommandId.GO_TO_MATCHING_BRACKET,
            CommandId.GO_BACK,
            CommandId.GO_FORWARD,
            null,
            CommandId.TOGGLE_BOOKMARK,
            CommandId.NEXT_BOOKMARK,
            CommandId.PREVIOUS_BOOKMARK,
            CommandId.EDIT_BOOKMARK_COMMENT,
            CommandId.CLEAR_BOOKMARKS,
            null,
            CommandId.BOOKMARKS_LIST));
    m.put(
        CommandGroup.VIEW,
        Arrays.asList(
            CommandId.WORD_WRAP,
            CommandId.SHOW_WHITESPACE,
            CommandId.SHOW_LINE_ENDINGS,
            CommandId.LINE_NUMBERS,
            CommandId.RIGHT_MARGIN,
            CommandId.INDENT_GUIDES,
            CommandId.LINE_CHANGES,
            CommandId.OVERVIEW_STRIP,
            null,
            CommandId.RELOAD_GRAMMARS,
            CommandId.FOLD,
            CommandId.UNFOLD,
            CommandId.TOGGLE_FOLD,
            null,
            CommandId.FOLD_ALL,
            CommandId.UNFOLD_ALL,
            null,
            CommandId.SPLIT_HORIZONTAL,
            CommandId.SPLIT_VERTICAL,
            null,
            CommandId.UNSPLIT,
            CommandId.FOCUS_OTHER_VIEW,
            CommandId.COMPARE_DOCUMENTS,
            CommandId.COMPARE_WITH_DISK,
            null,
            CommandId.ZOOM_IN,
            CommandId.ZOOM_OUT,
            CommandId.ZOOM_RESET,
            null,
            CommandId.THEME_LIGHT,
            CommandId.THEME_DARK,
            CommandId.THEME_SYSTEM,
            CommandId.TOOLBAR,
            CommandId.WORKSPACE_BROWSER,
            CommandId.ALWAYS_ON_TOP,
            null,
            CommandId.COMMAND_PALETTE));
    m.put(
        CommandGroup.ENCODING,
        Arrays.asList(
            null, CommandId.LINE_ENDING_CRLF, CommandId.LINE_ENDING_LF, CommandId.LINE_ENDING_CR));
    m.put(
        CommandGroup.TOOLS,
        Arrays.asList(
            CommandId.MD5_TEXT,
            CommandId.MD5_FILES,
            CommandId.MD5_SELECTION,
            CommandId.SHA1_TEXT,
            CommandId.SHA1_FILES,
            CommandId.SHA1_SELECTION,
            CommandId.SHA256_TEXT,
            CommandId.SHA256_FILES,
            CommandId.SHA256_SELECTION,
            CommandId.SHA512_TEXT,
            CommandId.SHA512_FILES,
            CommandId.SHA512_SELECTION,
            null,
            CommandId.VERIFY_CHECKSUMS,
            null,
            CommandId.DOCUMENT_STATISTICS,
            null,
            CommandId.PREFERENCES));
    m.put(
        CommandGroup.WINDOW,
        Arrays.asList(
            CommandId.NEW_WINDOW,
            CommandId.MOVE_TO_NEW_WINDOW,
            null,
            CommandId.NEXT_TAB,
            CommandId.PREVIOUS_TAB,
            CommandId.SWITCH_DOCUMENT));
    m.put(
        CommandGroup.HELP,
        Arrays.asList(
            CommandId.USER_GUIDE,
            CommandId.KEYBOARD_SHORTCUTS,
            CommandId.COMMAND_LINE_OPTIONS,
            CommandId.TIP_OF_THE_DAY,
            null,
            CommandId.SHOW_LOG,
            CommandId.DEBUG_LOG_FILE,
            CommandId.OPEN_LOG_FOLDER,
            CommandId.OPEN_SETTINGS_FOLDER,
            null,
            CommandId.REPORT_ISSUE,
            null,
            CommandId.ABOUT));
    return m;
  }
}
