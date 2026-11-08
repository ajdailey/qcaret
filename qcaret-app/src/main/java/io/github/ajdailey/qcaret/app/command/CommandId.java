package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/**
 * Every command a menu item or key can run. Menus list their commands in declaration order; {@code
 * separatorBefore} starts a new section, a {@code check} command shows as a check item, and
 * consecutive commands with the same {@code submenu} form one cascade in their menu. Keys come from
 * {@link Keymap}.
 */
public enum CommandId {
  /** Open an empty untitled document. */
  NEW(CommandGroup.FILE, UiText.COMMAND_NEW, false, false, null),
  /** Open files chosen in a dialog. */
  OPEN(CommandGroup.FILE, UiText.COMMAND_OPEN, false, false, null),
  /** Save the selected document. */
  SAVE(CommandGroup.FILE, UiText.COMMAND_SAVE, true, false, null),
  /** Save the selected document to a chosen file. */
  SAVE_AS(CommandGroup.FILE, UiText.COMMAND_SAVE_AS, false, false, null),
  /** Save every document with unsaved changes; an untitled one asks for a file. */
  SAVE_ALL(CommandGroup.FILE, UiText.COMMAND_SAVE_ALL, false, false, null),
  /** Close the selected document. */
  CLOSE(CommandGroup.FILE, UiText.COMMAND_CLOSE, true, false, null),
  /** Close every document, asking about each unsaved one. */
  CLOSE_ALL(CommandGroup.FILE, UiText.COMMAND_CLOSE_ALL, false, false, null),
  /** Open the file of the tab closed last again. */
  REOPEN_CLOSED(CommandGroup.FILE, UiText.COMMAND_REOPEN_CLOSED, false, false, null),
  /** Empty File > Recently Closed; the menu lists the files above it. */
  CLEAR_CLOSED_FILES(
      CommandGroup.FILE,
      UiText.COMMAND_CLEAR_CLOSED_FILES,
      false,
      false,
      UiText.MENU_RECENTLY_CLOSED),
  /** Read the selected document's file again, asking first when it has unsaved changes. */
  RELOAD(CommandGroup.FILE, UiText.COMMAND_RELOAD, false, false, null),
  /** Choose the window's workspace folder, the HyperSearch workspace scope. */
  SET_WORKSPACE(CommandGroup.FILE, UiText.COMMAND_SET_WORKSPACE, true, false, null),
  /** Open a document or workspace file chosen by a fuzzy match on its name or path. */
  QUICK_OPEN(CommandGroup.FILE, UiText.COMMAND_QUICK_OPEN, false, false, null),
  /** Print the selected document, or its selection, on a printer chosen in a dialog. */
  PRINT(CommandGroup.FILE, UiText.COMMAND_PRINT, true, false, null),
  /** Empty File > Recent Files; the menu lists the files above it. */
  CLEAR_RECENT_FILES(
      CommandGroup.FILE, UiText.COMMAND_CLEAR_RECENT_FILES, true, false, UiText.MENU_RECENT_FILES),
  /** Close every window, asking about unsaved documents. */
  QUIT(CommandGroup.FILE, UiText.COMMAND_QUIT, true, false, null),
  /** Undo in the selected editor. */
  UNDO(CommandGroup.EDIT, UiText.COMMAND_UNDO, false, false, null),
  /** Redo in the selected editor. */
  REDO(CommandGroup.EDIT, UiText.COMMAND_REDO, false, false, null),
  /** Cut the selections. */
  CUT(CommandGroup.EDIT, UiText.COMMAND_CUT, true, false, null),
  /** Copy the selections. */
  COPY(CommandGroup.EDIT, UiText.COMMAND_COPY, false, false, null),
  /** Paste at the selections. */
  PASTE(CommandGroup.EDIT, UiText.COMMAND_PASTE, false, false, null),
  /** Select the whole document. */
  SELECT_ALL(CommandGroup.EDIT, UiText.COMMAND_SELECT_ALL, true, false, null),
  /** Open the Preferences dialog; on macOS the application menu holds it. */
  PREFERENCES(CommandGroup.EDIT, UiText.COMMAND_PREFERENCES, true, false, null),
  /** Duplicate the selections, or the touched lines when every selection is a caret. */
  DUPLICATE(CommandGroup.EDIT, UiText.COMMAND_DUPLICATE, true, false, null),
  /** Delete every touched line. */
  DELETE_LINE(CommandGroup.EDIT, UiText.COMMAND_DELETE_LINE, false, false, null),
  /** Move the touched lines up one line. */
  MOVE_LINES_UP(CommandGroup.EDIT, UiText.COMMAND_MOVE_LINES_UP, false, false, null),
  /** Move the touched lines down one line. */
  MOVE_LINES_DOWN(CommandGroup.EDIT, UiText.COMMAND_MOVE_LINES_DOWN, false, false, null),
  /** Join the touched lines, or a caret's line with the next. */
  JOIN_LINES(CommandGroup.EDIT, UiText.COMMAND_JOIN_LINES, false, false, null),
  /** Convert the selections, or the words at the carets, to upper case. */
  UPPER_CASE(CommandGroup.EDIT, UiText.COMMAND_UPPER_CASE, true, false, null),
  /** Convert the selections, or the words at the carets, to lower case. */
  LOWER_CASE(CommandGroup.EDIT, UiText.COMMAND_LOWER_CASE, false, false, null),
  /** Indent the touched lines. */
  INDENT(CommandGroup.EDIT, UiText.COMMAND_INDENT, true, false, null),
  /** Outdent the touched lines. */
  OUTDENT(CommandGroup.EDIT, UiText.COMMAND_OUTDENT, false, false, null),
  /** Comment or uncomment the touched lines with the grammar's line comment. */
  TOGGLE_LINE_COMMENT(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_LINE_COMMENT, true, false, null),
  /** Comment or uncomment each selection with the grammar's block comment. */
  TOGGLE_BLOCK_COMMENT(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_BLOCK_COMMENT, false, false, null),
  /** Two columns per tab stop in the selected document. */
  TAB_WIDTH_2(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_2, false, true, UiText.MENU_TAB_WIDTH),
  /** Four columns per tab stop. */
  TAB_WIDTH_4(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_4, false, true, UiText.MENU_TAB_WIDTH),
  /** Eight columns per tab stop. */
  TAB_WIDTH_8(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_8, false, true, UiText.MENU_TAB_WIDTH),
  /** Ask for any tab width; checked for a width the cascade does not list. */
  TAB_WIDTH_OTHER(
      CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_OTHER, false, true, UiText.MENU_TAB_WIDTH),
  /** Indent the selected document with spaces instead of tabs. */
  INDENT_WITH_SPACES(CommandGroup.EDIT, UiText.COMMAND_INDENT_WITH_SPACES, false, true, null),
  /** Move the caret to a line and column. */
  GO_TO_LINE(CommandGroup.EDIT, UiText.COMMAND_GO_TO_LINE, true, false, null),
  /** Refuse or allow edits to the selected document. */
  READ_ONLY(CommandGroup.EDIT, UiText.COMMAND_READ_ONLY, true, true, null),
  /** Add a caret one row above the top caret. */
  ADD_CARET_ABOVE(CommandGroup.SELECTION, UiText.COMMAND_ADD_CARET_ABOVE, false, false, null),
  /** Add a caret one row below the bottom caret. */
  ADD_CARET_BELOW(CommandGroup.SELECTION, UiText.COMMAND_ADD_CARET_BELOW, false, false, null),
  /** Select the next occurrence of the primary selection, or the word at the caret. */
  ADD_NEXT_OCCURRENCE(
      CommandGroup.SELECTION, UiText.COMMAND_ADD_NEXT_OCCURRENCE, false, false, null),
  /** Split each selection that spans lines into one per line. */
  SPLIT_INTO_LINES(CommandGroup.SELECTION, UiText.COMMAND_SPLIT_INTO_LINES, false, false, null),
  /** Column selection mode: a mouse drag and Shift with an arrow key select a block. */
  COLUMN_MODE(CommandGroup.SELECTION, UiText.COMMAND_COLUMN_MODE, true, true, null),
  /** Word wrap in the window's editors. */
  WORD_WRAP(CommandGroup.VIEW, UiText.COMMAND_WORD_WRAP, false, true, null),
  /** Enlarge the editor font. */
  ZOOM_IN(CommandGroup.VIEW, UiText.COMMAND_ZOOM_IN, true, false, null),
  /** Shrink the editor font. */
  ZOOM_OUT(CommandGroup.VIEW, UiText.COMMAND_ZOOM_OUT, false, false, null),
  /** Return the editor font to its default size. */
  ZOOM_RESET(CommandGroup.VIEW, UiText.COMMAND_ZOOM_RESET, false, false, null),
  /** Light palette. */
  THEME_LIGHT(CommandGroup.VIEW, UiText.COMMAND_THEME_LIGHT, true, true, UiText.MENU_THEME),
  /** Dark palette. */
  THEME_DARK(CommandGroup.VIEW, UiText.COMMAND_THEME_DARK, false, true, UiText.MENU_THEME),
  /** The palette that follows the system setting. */
  THEME_SYSTEM(CommandGroup.VIEW, UiText.COMMAND_THEME_SYSTEM, false, true, UiText.MENU_THEME),
  /** Show the selected document in two views, one above the other; checked while so. */
  SPLIT_HORIZONTAL(
      CommandGroup.VIEW, UiText.COMMAND_SPLIT_HORIZONTAL, true, true, UiText.MENU_SPLIT),
  /** Show the selected document in two views side by side; checked while so. */
  SPLIT_VERTICAL(CommandGroup.VIEW, UiText.COMMAND_SPLIT_VERTICAL, false, true, UiText.MENU_SPLIT),
  /** Close every view of the selected document but the focused one. */
  UNSPLIT(CommandGroup.VIEW, UiText.COMMAND_UNSPLIT, true, false, UiText.MENU_SPLIT),
  /** Focus the selected document's other view. */
  FOCUS_OTHER_VIEW(
      CommandGroup.VIEW, UiText.COMMAND_FOCUS_OTHER_VIEW, false, false, UiText.MENU_SPLIT),
  /** Read the grammars again; the Syntax cascade lists the languages above it. */
  RELOAD_GRAMMARS(
      CommandGroup.VIEW, UiText.COMMAND_RELOAD_GRAMMARS, true, false, UiText.MENU_SYNTAX),
  /** Collapse the fold region the caret's line starts, or else the innermost one holding it. */
  FOLD(CommandGroup.VIEW, UiText.COMMAND_FOLD, true, false, UiText.MENU_FOLDING),
  /** Expand the collapsed region the caret's line starts. */
  UNFOLD(CommandGroup.VIEW, UiText.COMMAND_UNFOLD, false, false, UiText.MENU_FOLDING),
  /** Expand the collapsed region at the caret's line, or else fold. */
  TOGGLE_FOLD(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_FOLD, false, false, UiText.MENU_FOLDING),
  /** Collapse every fold region in the focused view. */
  FOLD_ALL(CommandGroup.VIEW, UiText.COMMAND_FOLD_ALL, true, false, UiText.MENU_FOLDING),
  /** Expand every collapsed region in the focused view. */
  UNFOLD_ALL(CommandGroup.VIEW, UiText.COMMAND_UNFOLD_ALL, false, false, UiText.MENU_FOLDING),
  /** Open the find bar. */
  FIND(CommandGroup.SEARCH, UiText.COMMAND_FIND, false, false, null),
  /** Select the next match. */
  FIND_NEXT(CommandGroup.SEARCH, UiText.COMMAND_FIND_NEXT, false, false, null),
  /** Select the previous match. */
  FIND_PREVIOUS(CommandGroup.SEARCH, UiText.COMMAND_FIND_PREVIOUS, false, false, null),
  /** Open the find bar with its replace row. */
  REPLACE(CommandGroup.SEARCH, UiText.COMMAND_REPLACE, true, false, null),
  /** Open the HyperSearch dialog. */
  HYPERSEARCH(CommandGroup.SEARCH, UiText.COMMAND_HYPERSEARCH, true, false, null),
  /** Open the next HyperSearch result. */
  NEXT_RESULT(CommandGroup.SEARCH, UiText.COMMAND_NEXT_RESULT, false, false, null),
  /** Open the previous HyperSearch result. */
  PREVIOUS_RESULT(CommandGroup.SEARCH, UiText.COMMAND_PREVIOUS_RESULT, false, false, null),
  /** Show the HyperSearch results dock; checked while it shows. */
  SEARCH_RESULTS(CommandGroup.SEARCH, UiText.COMMAND_SEARCH_RESULTS, false, true, null),
  /** Open the replace preview for the set last navigated, or the newest set with matches. */
  REPLACE_IN_RESULTS(CommandGroup.SEARCH, UiText.COMMAND_REPLACE_IN_RESULTS, false, false, null),
  /** Write back, from its backups, the files a replace run changed. */
  RESTORE_REPLACE_RUN(CommandGroup.SEARCH, UiText.COMMAND_RESTORE_REPLACE_RUN, false, false, null),
  /** Convert every line break to CR LF; checked while every break is CR LF. */
  LINE_ENDING_CRLF(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_CRLF, true, true, UiText.MENU_LINE_ENDINGS),
  /** Convert every line break to LF. */
  LINE_ENDING_LF(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_LF, false, true, UiText.MENU_LINE_ENDINGS),
  /** Convert every line break to CR. */
  LINE_ENDING_CR(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_CR, false, true, UiText.MENU_LINE_ENDINGS),
  /** Open a new window with an untitled document. */
  NEW_WINDOW(CommandGroup.WINDOW, UiText.COMMAND_NEW_WINDOW, false, false, null),
  /** Move the selected tab to a new window. */
  MOVE_TO_NEW_WINDOW(CommandGroup.WINDOW, UiText.COMMAND_MOVE_TO_NEW_WINDOW, false, false, null),
  /** Select the tab to the right, wrapping around. */
  NEXT_TAB(CommandGroup.WINDOW, UiText.COMMAND_NEXT_TAB, true, false, null),
  /** Select the tab to the left, wrapping around. */
  PREVIOUS_TAB(CommandGroup.WINDOW, UiText.COMMAND_PREVIOUS_TAB, false, false, null),
  /** Close every document but the selected one. */
  CLOSE_OTHERS(CommandGroup.WINDOW, UiText.COMMAND_CLOSE_OTHERS, true, false, null),
  /** Show the key bindings. */
  KEYBOARD_SHORTCUTS(CommandGroup.HELP, UiText.COMMAND_KEYBOARD_SHORTCUTS, false, false, null),
  /** Show the command-line usage. */
  COMMAND_LINE_OPTIONS(CommandGroup.HELP, UiText.COMMAND_COMMAND_LINE_OPTIONS, false, false, null),
  /** Show the offline user guide. */
  USER_GUIDE(CommandGroup.HELP, UiText.COMMAND_USER_GUIDE, false, false, null),
  /** Show the recent log lines. */
  SHOW_LOG(CommandGroup.HELP, UiText.COMMAND_SHOW_LOG, true, false, null),
  /** Write the log at DEBUG to a file in the settings folder. */
  DEBUG_LOG_FILE(CommandGroup.HELP, UiText.COMMAND_DEBUG_LOG_FILE, false, true, null),
  /** Open the folder of the debug log file. */
  OPEN_LOG_FOLDER(CommandGroup.HELP, UiText.COMMAND_OPEN_LOG_FOLDER, false, false, null),
  /** Open the settings folder. */
  OPEN_SETTINGS_FOLDER(CommandGroup.HELP, UiText.COMMAND_OPEN_SETTINGS_FOLDER, false, false, null),
  /** Copy the runtime details and open the project's issue page in the browser. */
  REPORT_ISSUE(CommandGroup.HELP, UiText.COMMAND_REPORT_ISSUE, true, false, null),
  /** Show the About dialog. */
  ABOUT(CommandGroup.HELP, UiText.COMMAND_ABOUT, false, false, null),
  /** Sort the touched lines, or every line, by code unit. */
  SORT_LINES_ASCENDING(
      CommandGroup.EDIT, UiText.COMMAND_SORT_LINES_ASCENDING, true, false, UiText.MENU_SORT_LINES),
  /** Sort the touched lines, or every line, by code unit, last first. */
  SORT_LINES_DESCENDING(
      CommandGroup.EDIT,
      UiText.COMMAND_SORT_LINES_DESCENDING,
      false,
      false,
      UiText.MENU_SORT_LINES),
  /** Sort the touched lines, or every line, ignoring case. */
  SORT_LINES_IGNORE_CASE(
      CommandGroup.EDIT,
      UiText.COMMAND_SORT_LINES_IGNORE_CASE,
      false,
      false,
      UiText.MENU_SORT_LINES),
  /** Remove repeated lines from the touched lines, or every line. */
  REMOVE_DUPLICATE_LINES(
      CommandGroup.EDIT, UiText.COMMAND_REMOVE_DUPLICATE_LINES, false, false, null),
  /** Remove trailing spaces and tabs from the touched lines, or every line. */
  TRIM_TRAILING_WHITESPACE(
      CommandGroup.EDIT, UiText.COMMAND_TRIM_TRAILING_WHITESPACE, false, false, null),
  /** Complete the word before the caret from the words of the open documents. */
  COMPLETE_WORD(CommandGroup.EDIT, UiText.COMMAND_COMPLETE_WORD, true, false, null),
  /** Select from each caret's bracket to its partner, both included. */
  SELECT_TO_MATCHING_BRACKET(
      CommandGroup.SELECTION, UiText.COMMAND_SELECT_TO_MATCHING_BRACKET, true, false, null),
  /** Move each caret past the partner of the bracket beside it. */
  GO_TO_MATCHING_BRACKET(
      CommandGroup.SEARCH, UiText.COMMAND_GO_TO_MATCHING_BRACKET, true, false, null),
  /** Bookmark each caret's line, or remove its bookmark. */
  TOGGLE_BOOKMARK(
      CommandGroup.SEARCH, UiText.COMMAND_TOGGLE_BOOKMARK, false, false, UiText.MENU_BOOKMARKS),
  /** Move the caret to the next bookmarked line. */
  NEXT_BOOKMARK(
      CommandGroup.SEARCH, UiText.COMMAND_NEXT_BOOKMARK, false, false, UiText.MENU_BOOKMARKS),
  /** Move the caret to the previous bookmarked line. */
  PREVIOUS_BOOKMARK(
      CommandGroup.SEARCH, UiText.COMMAND_PREVIOUS_BOOKMARK, false, false, UiText.MENU_BOOKMARKS),
  /** Remove every bookmark of the selected document. */
  CLEAR_BOOKMARKS(
      CommandGroup.SEARCH, UiText.COMMAND_CLEAR_BOOKMARKS, false, false, UiText.MENU_BOOKMARKS),
  /** Show the Bookmarks list of the selected document; checked while it shows. */
  BOOKMARKS_LIST(
      CommandGroup.SEARCH, UiText.COMMAND_BOOKMARKS_LIST, true, true, UiText.MENU_BOOKMARKS),
  /** Ask for the comment of the primary caret's line's bookmark, bookmarking the line. */
  EDIT_BOOKMARK_COMMENT(CommandGroup.EDIT, UiText.COMMAND_EDIT_BOOKMARK_COMMENT, true, false, null),
  /** Insert the date and time in a chosen format at every caret, replacing each selection. */
  INSERT_DATE_TIME(CommandGroup.EDIT, UiText.COMMAND_INSERT_DATE_TIME, true, false, null),
  /** Count the lines, words, chars and bytes of the selected document and its selections. */
  DOCUMENT_STATISTICS(CommandGroup.EDIT, UiText.COMMAND_DOCUMENT_STATISTICS, false, false, null),
  /** Mark spaces and tabs in the window's editors. */
  SHOW_WHITESPACE(CommandGroup.VIEW, UiText.COMMAND_SHOW_WHITESPACE, true, true, null),
  /** Mark each line break in the window's editors with its kind. */
  SHOW_LINE_ENDINGS(CommandGroup.VIEW, UiText.COMMAND_SHOW_LINE_ENDINGS, false, true, null),
  /** Show the overview strip beside each editor's text; checked while shown. */
  OVERVIEW_STRIP(CommandGroup.VIEW, UiText.COMMAND_OVERVIEW_STRIP, false, true, null),
  /** Show the line numbers in the window's editors; checked while shown. */
  LINE_NUMBERS(CommandGroup.VIEW, UiText.COMMAND_LINE_NUMBERS, false, true, null),
  /** Show a line at {@code editor.rightMargin} in every editor; checked while shown. */
  RIGHT_MARGIN(CommandGroup.VIEW, UiText.COMMAND_RIGHT_MARGIN, false, true, null),
  /** Show the main toolbar in every window; checked while shown. */
  TOOLBAR(CommandGroup.VIEW, UiText.COMMAND_TOOLBAR, false, true, null),
  /** Find and run a command by name. */
  COMMAND_PALETTE(CommandGroup.VIEW, UiText.COMMAND_COMMAND_PALETTE, true, false, null),
  /** Show or hide the workspace folder's tree. */
  WORKSPACE_BROWSER(CommandGroup.VIEW, UiText.COMMAND_WORKSPACE_BROWSER, true, true, null),
  /** Move the caret to the position before the last jump. */
  GO_BACK(CommandGroup.SEARCH, UiText.COMMAND_GO_BACK, true, false, null),
  /** Move the caret to the position Go Back left. */
  GO_FORWARD(CommandGroup.SEARCH, UiText.COMMAND_GO_FORWARD, false, false, null),
  /** Select an open document chosen by a fuzzy match on its name or path. */
  SWITCH_DOCUMENT(CommandGroup.WINDOW, UiText.COMMAND_SWITCH_DOCUMENT, true, false, null),
  /** Compare the selected document with another open document, side by side. */
  COMPARE_DOCUMENTS(
      CommandGroup.VIEW, UiText.COMMAND_COMPARE_DOCUMENTS, true, false, UiText.MENU_COMPARE),
  /** Compare the selected document's file on disk with its text, side by side. */
  COMPARE_WITH_DISK(
      CommandGroup.VIEW, UiText.COMMAND_COMPARE_WITH_DISK, false, false, UiText.MENU_COMPARE),
  /** Move every caret one char left, or to the start of its selection. */
  CARET_LEFT(CommandGroup.EDITOR, UiText.COMMAND_CARET_LEFT, false, false, null),
  /** Move every caret one char right, or to the end of its selection. */
  CARET_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_CARET_RIGHT, false, false, null),
  /** Move every caret one row up, keeping its column. */
  CARET_UP(CommandGroup.EDITOR, UiText.COMMAND_CARET_UP, false, false, null),
  /** Move every caret one row down, keeping its column. */
  CARET_DOWN(CommandGroup.EDITOR, UiText.COMMAND_CARET_DOWN, false, false, null),
  /** Move every caret to the previous word start. */
  WORD_LEFT(CommandGroup.EDITOR, UiText.COMMAND_WORD_LEFT, false, false, null),
  /** Move every caret past the next word. */
  WORD_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_WORD_RIGHT, false, false, null),
  /** Move every caret to its line's first non-blank char, or the line start. */
  LINE_START(CommandGroup.EDITOR, UiText.COMMAND_LINE_START, false, false, null),
  /** Move every caret to its line's end. */
  LINE_END(CommandGroup.EDITOR, UiText.COMMAND_LINE_END, false, false, null),
  /** Move every caret one page up. */
  PAGE_UP(CommandGroup.EDITOR, UiText.COMMAND_PAGE_UP, false, false, null),
  /** Move every caret one page down. */
  PAGE_DOWN(CommandGroup.EDITOR, UiText.COMMAND_PAGE_DOWN, false, false, null),
  /** Move the caret to the document start. */
  DOCUMENT_START(CommandGroup.EDITOR, UiText.COMMAND_DOCUMENT_START, false, false, null),
  /** Move the caret to the document end. */
  DOCUMENT_END(CommandGroup.EDITOR, UiText.COMMAND_DOCUMENT_END, false, false, null),
  /** {@link #CARET_LEFT}, extending every selection. */
  SELECT_LEFT(CommandGroup.EDITOR, UiText.COMMAND_SELECT_LEFT, false, false, null),
  /** {@link #CARET_RIGHT}, extending every selection. */
  SELECT_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_SELECT_RIGHT, false, false, null),
  /** {@link #CARET_UP}, extending every selection. */
  SELECT_UP(CommandGroup.EDITOR, UiText.COMMAND_SELECT_UP, false, false, null),
  /** {@link #CARET_DOWN}, extending every selection. */
  SELECT_DOWN(CommandGroup.EDITOR, UiText.COMMAND_SELECT_DOWN, false, false, null),
  /** {@link #WORD_LEFT}, extending every selection. */
  SELECT_WORD_LEFT(CommandGroup.EDITOR, UiText.COMMAND_SELECT_WORD_LEFT, false, false, null),
  /** {@link #WORD_RIGHT}, extending every selection. */
  SELECT_WORD_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_SELECT_WORD_RIGHT, false, false, null),
  /** {@link #LINE_START}, extending every selection. */
  SELECT_LINE_START(CommandGroup.EDITOR, UiText.COMMAND_SELECT_LINE_START, false, false, null),
  /** {@link #LINE_END}, extending every selection. */
  SELECT_LINE_END(CommandGroup.EDITOR, UiText.COMMAND_SELECT_LINE_END, false, false, null),
  /** {@link #PAGE_UP}, extending every selection. */
  SELECT_PAGE_UP(CommandGroup.EDITOR, UiText.COMMAND_SELECT_PAGE_UP, false, false, null),
  /** {@link #PAGE_DOWN}, extending every selection. */
  SELECT_PAGE_DOWN(CommandGroup.EDITOR, UiText.COMMAND_SELECT_PAGE_DOWN, false, false, null),
  /** {@link #DOCUMENT_START}, extending every selection. */
  SELECT_DOCUMENT_START(
      CommandGroup.EDITOR, UiText.COMMAND_SELECT_DOCUMENT_START, false, false, null),
  /** {@link #DOCUMENT_END}, extending every selection. */
  SELECT_DOCUMENT_END(CommandGroup.EDITOR, UiText.COMMAND_SELECT_DOCUMENT_END, false, false, null),
  /** Extend the block selection, or start one, one column left. */
  BLOCK_LEFT(CommandGroup.EDITOR, UiText.COMMAND_BLOCK_LEFT, false, false, null),
  /** Extend the block selection, or start one, one column right. */
  BLOCK_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_BLOCK_RIGHT, false, false, null),
  /** Extend the block selection, or start one, one line up. */
  BLOCK_UP(CommandGroup.EDITOR, UiText.COMMAND_BLOCK_UP, false, false, null),
  /** Extend the block selection, or start one, one line down. */
  BLOCK_DOWN(CommandGroup.EDITOR, UiText.COMMAND_BLOCK_DOWN, false, false, null),
  /** Insert a tab or spaces at every caret, or indent the lines a selection spans. */
  INSERT_TAB(CommandGroup.EDITOR, UiText.COMMAND_INSERT_TAB, false, false, null),
  /** Insert the document's line ending at every selection. */
  NEWLINE(CommandGroup.EDITOR, UiText.COMMAND_NEWLINE, false, false, null),
  /** Delete every selection, or the char before each caret. */
  DELETE_BACK(CommandGroup.EDITOR, UiText.COMMAND_DELETE_BACK, false, false, null),
  /** Delete every selection, or the char after each caret. */
  DELETE_FORWARD(CommandGroup.EDITOR, UiText.COMMAND_DELETE_FORWARD, false, false, null),
  /** Cancel a running save and drop the secondary selections. */
  CANCEL(CommandGroup.EDITOR, UiText.COMMAND_CANCEL, false, false, null);

  private final CommandGroup group;
  private final UiText label;
  private final boolean separatorBefore;
  private final boolean check;
  private final UiText submenu;

  CommandId(
      CommandGroup group, UiText label, boolean separatorBefore, boolean check, UiText submenu) {
    this.group = group;
    this.label = label;
    this.separatorBefore = separatorBefore;
    this.check = check;
    this.submenu = submenu;
  }

  /**
   * Returns the cascade the command sits in within its menu.
   *
   * @return the cascade's label, with {@code &} before the mnemonic, or null for the menu itself
   */
  public String submenu() {
    return submenu == null ? null : submenu.text();
  }

  /**
   * Returns the group.
   *
   * @return the menu
   */
  public CommandGroup group() {
    return group;
  }

  /**
   * Returns the menu label, with {@code &} before the mnemonic.
   *
   * @return the label
   */
  public String label() {
    return label.text();
  }

  /**
   * Returns the label without the mnemonic marker or a trailing ellipsis.
   *
   * @return for example {@code Go to Line}
   */
  public String title() {
    String plain = label().replace("&", "");
    return plain.endsWith("...") ? plain.substring(0, plain.length() - 3) : plain;
  }

  /**
   * Reports whether a menu separator precedes the command.
   *
   * @return true at the start of a menu section
   */
  public boolean separatorBefore() {
    return separatorBefore;
  }

  /**
   * Reports whether the menu shows the command as a check item.
   *
   * @return true for a toggle or a mode
   */
  public boolean check() {
    return check;
  }
}
