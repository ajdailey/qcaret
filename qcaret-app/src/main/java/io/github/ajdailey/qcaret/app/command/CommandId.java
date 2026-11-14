package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/**
 * Every command a menu item or key can run. {@code group} names the menu that lists the command,
 * {@code submenu} its cascade there, and a {@code check} command shows as a check item. {@link
 * MenuTable} orders the menus; keys come from {@link Keymap}.
 */
public enum CommandId {
  /** Open an empty untitled document. */
  NEW(CommandGroup.FILE, UiText.COMMAND_NEW, false, null),
  /** Open files chosen in a dialog. */
  OPEN(CommandGroup.FILE, UiText.COMMAND_OPEN, false, null),
  /** Save the selected document. */
  SAVE(CommandGroup.FILE, UiText.COMMAND_SAVE, false, null),
  /** Save the selected document to a chosen file. */
  SAVE_AS(CommandGroup.FILE, UiText.COMMAND_SAVE_AS, false, null),
  /** Write the selected document's text to a chosen file; the document keeps its own. */
  SAVE_COPY_AS(CommandGroup.FILE, UiText.COMMAND_SAVE_COPY_AS, false, null),
  /** Save every document with unsaved changes; an untitled one asks for a file. */
  SAVE_ALL(CommandGroup.FILE, UiText.COMMAND_SAVE_ALL, false, null),
  /** Close the selected document. */
  CLOSE(CommandGroup.FILE, UiText.COMMAND_CLOSE, false, null),
  /** Close every document, asking about each unsaved one. */
  CLOSE_ALL(CommandGroup.FILE, UiText.COMMAND_CLOSE_ALL, false, null),
  /** Open the file of the tab closed last again. */
  REOPEN_CLOSED(CommandGroup.FILE, UiText.COMMAND_REOPEN_CLOSED, false, null),
  /** Open every file of File > Recently Closed. */
  OPEN_ALL_CLOSED(
      CommandGroup.FILE, UiText.COMMAND_OPEN_ALL_CLOSED, false, UiText.MENU_RECENTLY_CLOSED),
  /** Empty File > Recently Closed; the menu lists the files above it. */
  CLEAR_CLOSED_FILES(
      CommandGroup.FILE, UiText.COMMAND_CLEAR_CLOSED_FILES, false, UiText.MENU_RECENTLY_CLOSED),
  /** Read the selected document's file again, asking first when it has unsaved changes. */
  RELOAD(CommandGroup.FILE, UiText.COMMAND_RELOAD, false, null),
  /** Choose the window's workspace folder, the HyperSearch workspace scope. */
  SET_WORKSPACE(CommandGroup.FILE, UiText.COMMAND_SET_WORKSPACE, false, null),
  /** Open a document or workspace file chosen by a fuzzy match on its name or path. */
  QUICK_OPEN(CommandGroup.FILE, UiText.COMMAND_QUICK_OPEN, false, null),
  /** Print the selected document, or its selection, on a printer chosen in a dialog. */
  PRINT(CommandGroup.FILE, UiText.COMMAND_PRINT, false, null),
  /** Open every file of File > Recent Files. */
  OPEN_ALL_RECENT(
      CommandGroup.FILE, UiText.COMMAND_OPEN_ALL_RECENT, false, UiText.MENU_RECENT_FILES),
  /** Empty File > Recent Files; the menu lists the files above it. */
  CLEAR_RECENT_FILES(
      CommandGroup.FILE, UiText.COMMAND_CLEAR_RECENT_FILES, false, UiText.MENU_RECENT_FILES),
  /** Drop the files of File > Recent Files that no longer exist. */
  CLEAN_UP_RECENT(
      CommandGroup.FILE, UiText.COMMAND_CLEAN_UP_RECENT, false, UiText.MENU_RECENT_FILES),
  /** Close every window, asking about unsaved documents. */
  QUIT(CommandGroup.FILE, UiText.COMMAND_QUIT, false, null),
  /** Undo in the selected editor. */
  UNDO(CommandGroup.EDIT, UiText.COMMAND_UNDO, false, null),
  /** Redo in the selected editor. */
  REDO(CommandGroup.EDIT, UiText.COMMAND_REDO, false, null),
  /** Cut the selections. */
  CUT(CommandGroup.EDIT, UiText.COMMAND_CUT, false, null),
  /** Copy the selections. */
  COPY(CommandGroup.EDIT, UiText.COMMAND_COPY, false, null),
  /** Paste at the selections. */
  PASTE(CommandGroup.EDIT, UiText.COMMAND_PASTE, false, null),
  /** Select the whole document. */
  SELECT_ALL(CommandGroup.EDIT, UiText.COMMAND_SELECT_ALL, false, null),
  /** Open the Preferences dialog; on macOS the application menu holds it. */
  PREFERENCES(CommandGroup.TOOLS, UiText.COMMAND_PREFERENCES, false, null),
  /** Duplicate the selections, or the touched lines when every selection is a caret. */
  DUPLICATE(CommandGroup.EDIT, UiText.COMMAND_DUPLICATE, false, UiText.MENU_LINE),
  /** Delete every touched line. */
  DELETE_LINE(CommandGroup.EDIT, UiText.COMMAND_DELETE_LINE, false, UiText.MENU_LINE),
  /** Move the touched lines up one line. */
  MOVE_LINES_UP(CommandGroup.EDIT, UiText.COMMAND_MOVE_LINES_UP, false, UiText.MENU_LINE),
  /** Move the touched lines down one line. */
  MOVE_LINES_DOWN(CommandGroup.EDIT, UiText.COMMAND_MOVE_LINES_DOWN, false, UiText.MENU_LINE),
  /** Join the touched lines, or a caret's line with the next. */
  JOIN_LINES(CommandGroup.EDIT, UiText.COMMAND_JOIN_LINES, false, UiText.MENU_LINE),
  /** Convert the selections, or the words at the carets, to upper case. */
  UPPER_CASE(CommandGroup.EDIT, UiText.COMMAND_UPPER_CASE, false, UiText.MENU_CONVERT_CASE),
  /** Convert the selections, or the words at the carets, to lower case. */
  LOWER_CASE(CommandGroup.EDIT, UiText.COMMAND_LOWER_CASE, false, UiText.MENU_CONVERT_CASE),
  /** Indent the touched lines. */
  INDENT(CommandGroup.EDIT, UiText.COMMAND_INDENT, false, null),
  /** Outdent the touched lines. */
  OUTDENT(CommandGroup.EDIT, UiText.COMMAND_OUTDENT, false, null),
  /** Comment or uncomment the touched lines with the grammar's line comment. */
  TOGGLE_LINE_COMMENT(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_LINE_COMMENT, false, null),
  /** Comment or uncomment each selection with the grammar's block comment. */
  TOGGLE_BLOCK_COMMENT(CommandGroup.EDIT, UiText.COMMAND_TOGGLE_BLOCK_COMMENT, false, null),
  /** Two columns per tab stop in the selected document. */
  TAB_WIDTH_2(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_2, true, UiText.MENU_TAB_WIDTH),
  /** Four columns per tab stop. */
  TAB_WIDTH_4(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_4, true, UiText.MENU_TAB_WIDTH),
  /** Eight columns per tab stop. */
  TAB_WIDTH_8(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_8, true, UiText.MENU_TAB_WIDTH),
  /** Ask for any tab width; checked for a width the cascade does not list. */
  TAB_WIDTH_OTHER(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_OTHER, true, UiText.MENU_TAB_WIDTH),
  /** Indent the selected document with spaces instead of tabs. */
  INDENT_WITH_SPACES(CommandGroup.EDIT, UiText.COMMAND_INDENT_WITH_SPACES, true, null),
  /** Move the caret to a line and column. */
  GO_TO_LINE(CommandGroup.SEARCH, UiText.COMMAND_GO_TO_LINE, false, null),
  /** Open Quick Open over the selected document's symbols. */
  GO_TO_SYMBOL(CommandGroup.SEARCH, UiText.COMMAND_GO_TO_SYMBOL, false, null),
  /** Refuse or allow edits to the selected document. */
  READ_ONLY(CommandGroup.EDIT, UiText.COMMAND_READ_ONLY, true, null),
  /** Add a caret one row above the top caret. */
  ADD_CARET_ABOVE(CommandGroup.SELECTION, UiText.COMMAND_ADD_CARET_ABOVE, false, null),
  /** Add a caret one row below the bottom caret. */
  ADD_CARET_BELOW(CommandGroup.SELECTION, UiText.COMMAND_ADD_CARET_BELOW, false, null),
  /** Select the next occurrence of the primary selection, or the word at the caret. */
  ADD_NEXT_OCCURRENCE(CommandGroup.SELECTION, UiText.COMMAND_ADD_NEXT_OCCURRENCE, false, null),
  /** Split each selection that spans lines into one per line. */
  SPLIT_INTO_LINES(CommandGroup.SELECTION, UiText.COMMAND_SPLIT_INTO_LINES, false, null),
  /** Column selection mode: a mouse drag and Shift with an arrow key select a block. */
  COLUMN_MODE(CommandGroup.SELECTION, UiText.COMMAND_COLUMN_MODE, true, null),
  /** Word wrap in the window's editors. */
  WORD_WRAP(CommandGroup.VIEW, UiText.COMMAND_WORD_WRAP, true, null),
  /** Enlarge the editor font. */
  ZOOM_IN(CommandGroup.VIEW, UiText.COMMAND_ZOOM_IN, false, null),
  /** Shrink the editor font. */
  ZOOM_OUT(CommandGroup.VIEW, UiText.COMMAND_ZOOM_OUT, false, null),
  /** Return the editor font to its default size. */
  ZOOM_RESET(CommandGroup.VIEW, UiText.COMMAND_ZOOM_RESET, false, null),
  /** Light palette. */
  THEME_LIGHT(CommandGroup.VIEW, UiText.COMMAND_THEME_LIGHT, true, UiText.MENU_THEME),
  /** Dark palette. */
  THEME_DARK(CommandGroup.VIEW, UiText.COMMAND_THEME_DARK, true, UiText.MENU_THEME),
  /** The palette that follows the system setting. */
  THEME_SYSTEM(CommandGroup.VIEW, UiText.COMMAND_THEME_SYSTEM, true, UiText.MENU_THEME),
  /** Show the selected document in two views, one above the other; checked while so. */
  SPLIT_HORIZONTAL(CommandGroup.VIEW, UiText.COMMAND_SPLIT_HORIZONTAL, true, UiText.MENU_SPLIT),
  /** Show the selected document in two views side by side; checked while so. */
  SPLIT_VERTICAL(CommandGroup.VIEW, UiText.COMMAND_SPLIT_VERTICAL, true, UiText.MENU_SPLIT),
  /** Close every view of the selected document but the focused one. */
  UNSPLIT(CommandGroup.VIEW, UiText.COMMAND_UNSPLIT, false, UiText.MENU_SPLIT),
  /** Focus the selected document's other view. */
  FOCUS_OTHER_VIEW(CommandGroup.VIEW, UiText.COMMAND_FOCUS_OTHER_VIEW, false, UiText.MENU_SPLIT),
  /** Read the grammars again; the Syntax cascade lists the languages above it. */
  RELOAD_GRAMMARS(CommandGroup.VIEW, UiText.COMMAND_RELOAD_GRAMMARS, false, UiText.MENU_SYNTAX),
  /** Collapse the fold region the caret's line starts, or else the innermost one holding it. */
  FOLD(CommandGroup.VIEW, UiText.COMMAND_FOLD, false, UiText.MENU_FOLDING),
  /** Expand the collapsed region the caret's line starts. */
  UNFOLD(CommandGroup.VIEW, UiText.COMMAND_UNFOLD, false, UiText.MENU_FOLDING),
  /** Expand the collapsed region at the caret's line, or else fold. */
  TOGGLE_FOLD(CommandGroup.VIEW, UiText.COMMAND_TOGGLE_FOLD, false, UiText.MENU_FOLDING),
  /** Collapse every fold region in the focused view. */
  FOLD_ALL(CommandGroup.VIEW, UiText.COMMAND_FOLD_ALL, false, UiText.MENU_FOLDING),
  /** Expand every collapsed region in the focused view. */
  UNFOLD_ALL(CommandGroup.VIEW, UiText.COMMAND_UNFOLD_ALL, false, UiText.MENU_FOLDING),
  /** Open the find bar. */
  FIND(CommandGroup.SEARCH, UiText.COMMAND_FIND, false, null),
  /** Select the next match. */
  FIND_NEXT(CommandGroup.SEARCH, UiText.COMMAND_FIND_NEXT, false, null),
  /** Select the previous match. */
  FIND_PREVIOUS(CommandGroup.SEARCH, UiText.COMMAND_FIND_PREVIOUS, false, null),
  /** Open the find bar with its replace row. */
  REPLACE(CommandGroup.SEARCH, UiText.COMMAND_REPLACE, false, null),
  /** Open the HyperSearch dialog. */
  HYPERSEARCH(CommandGroup.SEARCH, UiText.COMMAND_HYPERSEARCH, false, null),
  /** Open the next HyperSearch result. */
  NEXT_RESULT(CommandGroup.SEARCH, UiText.COMMAND_NEXT_RESULT, false, null),
  /** Open the previous HyperSearch result. */
  PREVIOUS_RESULT(CommandGroup.SEARCH, UiText.COMMAND_PREVIOUS_RESULT, false, null),
  /** Show the HyperSearch results dock; checked while it shows. */
  SEARCH_RESULTS(CommandGroup.SEARCH, UiText.COMMAND_SEARCH_RESULTS, true, null),
  /** Open the replace preview for the set last navigated, or the newest set with matches. */
  REPLACE_IN_RESULTS(CommandGroup.SEARCH, UiText.COMMAND_REPLACE_IN_RESULTS, false, null),
  /** Write back, from its backups, the files a replace run changed. */
  RESTORE_REPLACE_RUN(CommandGroup.SEARCH, UiText.COMMAND_RESTORE_REPLACE_RUN, false, null),
  /** Convert every line break to CR LF; checked while every break is CR LF. */
  LINE_ENDING_CRLF(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_CRLF, true, UiText.MENU_LINE_ENDINGS),
  /** Convert every line break to LF. */
  LINE_ENDING_LF(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_LF, true, UiText.MENU_LINE_ENDINGS),
  /** Convert every line break to CR. */
  LINE_ENDING_CR(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_CR, true, UiText.MENU_LINE_ENDINGS),
  /** Open a new window with an untitled document. */
  NEW_WINDOW(CommandGroup.WINDOW, UiText.COMMAND_NEW_WINDOW, false, null),
  /** Move the selected tab to a new window. */
  MOVE_TO_NEW_WINDOW(CommandGroup.WINDOW, UiText.COMMAND_MOVE_TO_NEW_WINDOW, false, null),
  /** Select the tab to the right, wrapping around. */
  NEXT_TAB(CommandGroup.WINDOW, UiText.COMMAND_NEXT_TAB, false, null),
  /** Select the tab to the left, wrapping around. */
  PREVIOUS_TAB(CommandGroup.WINDOW, UiText.COMMAND_PREVIOUS_TAB, false, null),
  /** Close every document but the selected one. */
  CLOSE_OTHERS(CommandGroup.FILE, UiText.COMMAND_CLOSE_OTHERS, false, UiText.MENU_CLOSE_MULTIPLE),
  /** Show the key bindings. */
  KEYBOARD_SHORTCUTS(CommandGroup.HELP, UiText.COMMAND_KEYBOARD_SHORTCUTS, false, null),
  /** Show the command-line usage. */
  COMMAND_LINE_OPTIONS(CommandGroup.HELP, UiText.COMMAND_COMMAND_LINE_OPTIONS, false, null),
  /** Show the offline user guide. */
  USER_GUIDE(CommandGroup.HELP, UiText.COMMAND_USER_GUIDE, false, null),
  /** Show the Tip of the Day dialog, or bring it to the front. */
  TIP_OF_THE_DAY(CommandGroup.HELP, UiText.COMMAND_TIP_OF_THE_DAY, false, null),
  /** Show the recent log lines. */
  SHOW_LOG(CommandGroup.HELP, UiText.COMMAND_SHOW_LOG, false, null),
  /** Write the log at DEBUG to a file in the settings folder. */
  DEBUG_LOG_FILE(CommandGroup.HELP, UiText.COMMAND_DEBUG_LOG_FILE, true, null),
  /** Open the folder of the debug log file. */
  OPEN_LOG_FOLDER(CommandGroup.HELP, UiText.COMMAND_OPEN_LOG_FOLDER, false, null),
  /** Open the settings folder. */
  OPEN_SETTINGS_FOLDER(CommandGroup.HELP, UiText.COMMAND_OPEN_SETTINGS_FOLDER, false, null),
  /** Copy the runtime details and open the project's issue page in the browser. */
  REPORT_ISSUE(CommandGroup.HELP, UiText.COMMAND_REPORT_ISSUE, false, null),
  /** Show the About dialog. */
  ABOUT(CommandGroup.HELP, UiText.COMMAND_ABOUT, false, null),
  /** Sort the touched lines, or every line, by code unit. */
  SORT_LINES_ASCENDING(
      CommandGroup.EDIT, UiText.COMMAND_SORT_LINES_ASCENDING, false, UiText.MENU_SORT_LINES),
  /** Sort the touched lines, or every line, by code unit, last first. */
  SORT_LINES_DESCENDING(
      CommandGroup.EDIT, UiText.COMMAND_SORT_LINES_DESCENDING, false, UiText.MENU_SORT_LINES),
  /** Sort the touched lines, or every line, ignoring case. */
  SORT_LINES_IGNORE_CASE(
      CommandGroup.EDIT, UiText.COMMAND_SORT_LINES_IGNORE_CASE, false, UiText.MENU_SORT_LINES),
  /** Remove repeated lines from the touched lines, or every line. */
  REMOVE_DUPLICATE_LINES(
      CommandGroup.EDIT, UiText.COMMAND_REMOVE_DUPLICATE_LINES, false, UiText.MENU_LINE),
  /** Remove trailing spaces and tabs from the touched lines, or every line. */
  TRIM_TRAILING_WHITESPACE(
      CommandGroup.EDIT, UiText.COMMAND_TRIM_TRAILING_WHITESPACE, false, UiText.MENU_LINE),
  /**
   * Hard-wrap the selected lines, or the paragraph at each caret, at {@code editor.rightMargin}.
   */
  WRAP_PARAGRAPH(CommandGroup.EDIT, UiText.COMMAND_WRAP_PARAGRAPH, false, UiText.MENU_LINE),
  /** Complete the word before the caret from the words of the open documents. */
  COMPLETE_WORD(CommandGroup.EDIT, UiText.COMMAND_COMPLETE_WORD, false, null),
  /** Select from each caret's bracket to its partner, both included. */
  SELECT_TO_MATCHING_BRACKET(
      CommandGroup.SELECTION, UiText.COMMAND_SELECT_TO_MATCHING_BRACKET, false, null),
  /** Move each caret past the partner of the bracket beside it. */
  GO_TO_MATCHING_BRACKET(CommandGroup.SEARCH, UiText.COMMAND_GO_TO_MATCHING_BRACKET, false, null),
  /** Bookmark each caret's line, or remove its bookmark. */
  TOGGLE_BOOKMARK(
      CommandGroup.SEARCH, UiText.COMMAND_TOGGLE_BOOKMARK, false, UiText.MENU_BOOKMARKS),
  /** Move the caret to the next bookmarked line. */
  NEXT_BOOKMARK(CommandGroup.SEARCH, UiText.COMMAND_NEXT_BOOKMARK, false, UiText.MENU_BOOKMARKS),
  /** Move the caret to the previous bookmarked line. */
  PREVIOUS_BOOKMARK(
      CommandGroup.SEARCH, UiText.COMMAND_PREVIOUS_BOOKMARK, false, UiText.MENU_BOOKMARKS),
  /** Remove every bookmark of the selected document. */
  CLEAR_BOOKMARKS(
      CommandGroup.SEARCH, UiText.COMMAND_CLEAR_BOOKMARKS, false, UiText.MENU_BOOKMARKS),
  /** Show the Bookmarks list of the selected document; checked while it shows. */
  BOOKMARKS_LIST(CommandGroup.SEARCH, UiText.COMMAND_BOOKMARKS_LIST, true, UiText.MENU_BOOKMARKS),
  /** Ask for the comment of the primary caret's line's bookmark, bookmarking the line. */
  EDIT_BOOKMARK_COMMENT(
      CommandGroup.SEARCH, UiText.COMMAND_EDIT_BOOKMARK_COMMENT, false, UiText.MENU_BOOKMARKS),
  /** Insert the date and time in a chosen format at every caret, replacing each selection. */
  INSERT_DATE_TIME(CommandGroup.EDIT, UiText.COMMAND_INSERT_DATE_TIME, false, null),
  /** Count the lines, words, chars and bytes of the selected document and its selections. */
  DOCUMENT_STATISTICS(CommandGroup.TOOLS, UiText.COMMAND_DOCUMENT_STATISTICS, false, null),
  /** Show the MD5 of a typed text. */
  MD5_TEXT(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_TEXT, false, UiText.MENU_MD5),
  /** Hash chosen files with MD5 into a list. */
  MD5_FILES(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_FILES, false, UiText.MENU_MD5),
  /** Copy the MD5 of the selections' bytes. */
  MD5_SELECTION(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_SELECTION, false, UiText.MENU_MD5),
  /** Show the SHA-1 of a typed text. */
  SHA1_TEXT(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_TEXT, false, UiText.MENU_SHA_1),
  /** Hash chosen files with SHA-1 into a list. */
  SHA1_FILES(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_FILES, false, UiText.MENU_SHA_1),
  /** Copy the SHA-1 of the selections' bytes. */
  SHA1_SELECTION(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_SELECTION, false, UiText.MENU_SHA_1),
  /** Show the SHA-256 of a typed text. */
  SHA256_TEXT(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_TEXT, false, UiText.MENU_SHA_256),
  /** Hash chosen files with SHA-256 into a list. */
  SHA256_FILES(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_FILES, false, UiText.MENU_SHA_256),
  /** Copy the SHA-256 of the selections' bytes. */
  SHA256_SELECTION(
      CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_SELECTION, false, UiText.MENU_SHA_256),
  /** Show the SHA-512 of a typed text. */
  SHA512_TEXT(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_TEXT, false, UiText.MENU_SHA_512),
  /** Hash chosen files with SHA-512 into a list. */
  SHA512_FILES(CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_FILES, false, UiText.MENU_SHA_512),
  /** Copy the SHA-512 of the selections' bytes. */
  SHA512_SELECTION(
      CommandGroup.TOOLS, UiText.COMMAND_CHECKSUM_SELECTION, false, UiText.MENU_SHA_512),
  /**
   * Check the files of a {@code sha256sum}-style list: the selected document's file or one chosen.
   */
  VERIFY_CHECKSUMS(CommandGroup.TOOLS, UiText.COMMAND_VERIFY_CHECKSUMS, false, null),
  /** Mark spaces and tabs in the window's editors. */
  SHOW_WHITESPACE(CommandGroup.VIEW, UiText.COMMAND_SHOW_WHITESPACE, true, null),
  /** Mark each line break in the window's editors with its kind. */
  SHOW_LINE_ENDINGS(CommandGroup.VIEW, UiText.COMMAND_SHOW_LINE_ENDINGS, true, null),
  /** Show the overview strip beside each editor's text; checked while shown. */
  OVERVIEW_STRIP(CommandGroup.VIEW, UiText.COMMAND_OVERVIEW_STRIP, true, null),
  /** Show the line numbers in the window's editors; checked while shown. */
  LINE_NUMBERS(CommandGroup.VIEW, UiText.COMMAND_LINE_NUMBERS, true, null),
  /** Show a line at {@code editor.rightMargin} in every editor; checked while shown. */
  RIGHT_MARGIN(CommandGroup.VIEW, UiText.COMMAND_RIGHT_MARGIN, true, null),
  /**
   * Show a line at each indent level of leading whitespace in every editor; checked while shown.
   */
  INDENT_GUIDES(CommandGroup.VIEW, UiText.COMMAND_INDENT_GUIDES, true, null),
  /** Mark the lines changed since the last load or save in every editor; checked while shown. */
  LINE_CHANGES(CommandGroup.VIEW, UiText.COMMAND_LINE_CHANGES, true, null),
  /** Show the main toolbar in every window; checked while shown. */
  TOOLBAR(CommandGroup.VIEW, UiText.COMMAND_TOOLBAR, true, null),
  /** Show the memory indicator in every window's status bar; checked while shown. */
  MEMORY_INDICATOR(CommandGroup.VIEW, UiText.COMMAND_MEMORY_INDICATOR, true, null),
  /** Keep the window above other windows; checked while on top, disabled where unsupported. */
  ALWAYS_ON_TOP(CommandGroup.VIEW, UiText.COMMAND_ALWAYS_ON_TOP, true, null),
  /** Find and run a command by name. */
  COMMAND_PALETTE(CommandGroup.VIEW, UiText.COMMAND_COMMAND_PALETTE, false, null),
  /** Show or hide the workspace folder's tree. */
  WORKSPACE_BROWSER(CommandGroup.VIEW, UiText.COMMAND_WORKSPACE_BROWSER, true, null),
  /** Move the caret to the position before the last jump. */
  GO_BACK(CommandGroup.SEARCH, UiText.COMMAND_GO_BACK, false, null),
  /** Move the caret to the position Go Back left. */
  GO_FORWARD(CommandGroup.SEARCH, UiText.COMMAND_GO_FORWARD, false, null),
  /** Select an open document chosen by a fuzzy match on its name or path. */
  SWITCH_DOCUMENT(CommandGroup.WINDOW, UiText.COMMAND_SWITCH_DOCUMENT, false, null),
  /** Compare the selected document with another open document, side by side. */
  COMPARE_DOCUMENTS(
      CommandGroup.VIEW, UiText.COMMAND_COMPARE_DOCUMENTS, false, UiText.MENU_COMPARE),
  /** Compare the selected document's file on disk with its text, side by side. */
  COMPARE_WITH_DISK(
      CommandGroup.VIEW, UiText.COMMAND_COMPARE_WITH_DISK, false, UiText.MENU_COMPARE),
  /** Delete each selection, or the word or blanks before each caret. */
  DELETE_WORD_BACK(CommandGroup.EDIT, UiText.COMMAND_DELETE_WORD_BACK, false, null),
  /** Delete each selection, or the word and blanks after each caret. */
  DELETE_WORD_FORWARD(CommandGroup.EDIT, UiText.COMMAND_DELETE_WORD_FORWARD, false, null),
  /** Capitalize each word of the selections, or the word at each caret. */
  TITLE_CASE(CommandGroup.EDIT, UiText.COMMAND_TITLE_CASE, false, UiText.MENU_CONVERT_CASE),
  /** Swap upper and lower case in the selections, or the word at each caret. */
  SWAP_CASE(CommandGroup.EDIT, UiText.COMMAND_SWAP_CASE, false, UiText.MENU_CONVERT_CASE),
  /** Swap the characters around each caret, or rotate the texts of two or more selections. */
  TRANSPOSE(CommandGroup.EDIT, UiText.COMMAND_TRANSPOSE, false, null),
  /** Select every occurrence of the primary selection, or of the word at the caret. */
  SELECT_ALL_OCCURRENCES(
      CommandGroup.SELECTION, UiText.COMMAND_SELECT_ALL_OCCURRENCES, false, null),
  /** Drop the occurrence added last and select the next one. */
  SKIP_OCCURRENCE(CommandGroup.SELECTION, UiText.COMMAND_SKIP_OCCURRENCE, false, null),
  /** Widen each selection to the word, quotes, brackets, line or text around it. */
  EXPAND_SELECTION(CommandGroup.SELECTION, UiText.COMMAND_EXPAND_SELECTION, false, null),
  /** Select the lines each selection touches, or the next line too. */
  SELECT_LINE(CommandGroup.SELECTION, UiText.COMMAND_SELECT_LINE, false, null),
  /** Return the carets and selections to their state before the last change. */
  UNDO_SELECTION(CommandGroup.SELECTION, UiText.COMMAND_UNDO_SELECTION, false, null),
  /** Return the carets and selections Undo Selection changed. */
  REDO_SELECTION(CommandGroup.SELECTION, UiText.COMMAND_REDO_SELECTION, false, null),
  /** Move every caret one char left, or to the start of its selection. */
  CARET_LEFT(CommandGroup.EDITOR, UiText.COMMAND_CARET_LEFT, false, null),
  /** Move every caret one char right, or to the end of its selection. */
  CARET_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_CARET_RIGHT, false, null),
  /** Move every caret one row up, keeping its column. */
  CARET_UP(CommandGroup.EDITOR, UiText.COMMAND_CARET_UP, false, null),
  /** Move every caret one row down, keeping its column. */
  CARET_DOWN(CommandGroup.EDITOR, UiText.COMMAND_CARET_DOWN, false, null),
  /** Move every caret to the previous word start. */
  WORD_LEFT(CommandGroup.EDITOR, UiText.COMMAND_WORD_LEFT, false, null),
  /** Move every caret past the next word. */
  WORD_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_WORD_RIGHT, false, null),
  /** Move every caret to its line's first non-blank char, or the line start. */
  LINE_START(CommandGroup.EDITOR, UiText.COMMAND_LINE_START, false, null),
  /** Move every caret to its line's end. */
  LINE_END(CommandGroup.EDITOR, UiText.COMMAND_LINE_END, false, null),
  /** Move every caret one page up. */
  PAGE_UP(CommandGroup.EDITOR, UiText.COMMAND_PAGE_UP, false, null),
  /** Move every caret one page down. */
  PAGE_DOWN(CommandGroup.EDITOR, UiText.COMMAND_PAGE_DOWN, false, null),
  /** Move the caret to the document start. */
  DOCUMENT_START(CommandGroup.EDITOR, UiText.COMMAND_DOCUMENT_START, false, null),
  /** Move the caret to the document end. */
  DOCUMENT_END(CommandGroup.EDITOR, UiText.COMMAND_DOCUMENT_END, false, null),
  /** {@link #CARET_LEFT}, extending every selection. */
  SELECT_LEFT(CommandGroup.EDITOR, UiText.COMMAND_SELECT_LEFT, false, null),
  /** {@link #CARET_RIGHT}, extending every selection. */
  SELECT_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_SELECT_RIGHT, false, null),
  /** {@link #CARET_UP}, extending every selection. */
  SELECT_UP(CommandGroup.EDITOR, UiText.COMMAND_SELECT_UP, false, null),
  /** {@link #CARET_DOWN}, extending every selection. */
  SELECT_DOWN(CommandGroup.EDITOR, UiText.COMMAND_SELECT_DOWN, false, null),
  /** {@link #WORD_LEFT}, extending every selection. */
  SELECT_WORD_LEFT(CommandGroup.EDITOR, UiText.COMMAND_SELECT_WORD_LEFT, false, null),
  /** {@link #WORD_RIGHT}, extending every selection. */
  SELECT_WORD_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_SELECT_WORD_RIGHT, false, null),
  /** {@link #LINE_START}, extending every selection. */
  SELECT_LINE_START(CommandGroup.EDITOR, UiText.COMMAND_SELECT_LINE_START, false, null),
  /** {@link #LINE_END}, extending every selection. */
  SELECT_LINE_END(CommandGroup.EDITOR, UiText.COMMAND_SELECT_LINE_END, false, null),
  /** {@link #PAGE_UP}, extending every selection. */
  SELECT_PAGE_UP(CommandGroup.EDITOR, UiText.COMMAND_SELECT_PAGE_UP, false, null),
  /** {@link #PAGE_DOWN}, extending every selection. */
  SELECT_PAGE_DOWN(CommandGroup.EDITOR, UiText.COMMAND_SELECT_PAGE_DOWN, false, null),
  /** {@link #DOCUMENT_START}, extending every selection. */
  SELECT_DOCUMENT_START(CommandGroup.EDITOR, UiText.COMMAND_SELECT_DOCUMENT_START, false, null),
  /** {@link #DOCUMENT_END}, extending every selection. */
  SELECT_DOCUMENT_END(CommandGroup.EDITOR, UiText.COMMAND_SELECT_DOCUMENT_END, false, null),
  /** Extend the block selection, or start one, one column left. */
  BLOCK_LEFT(CommandGroup.EDITOR, UiText.COMMAND_BLOCK_LEFT, false, null),
  /** Extend the block selection, or start one, one column right. */
  BLOCK_RIGHT(CommandGroup.EDITOR, UiText.COMMAND_BLOCK_RIGHT, false, null),
  /** Extend the block selection, or start one, one line up. */
  BLOCK_UP(CommandGroup.EDITOR, UiText.COMMAND_BLOCK_UP, false, null),
  /** Extend the block selection, or start one, one line down. */
  BLOCK_DOWN(CommandGroup.EDITOR, UiText.COMMAND_BLOCK_DOWN, false, null),
  /** Insert a tab or spaces at every caret, or indent the lines a selection spans. */
  INSERT_TAB(CommandGroup.EDITOR, UiText.COMMAND_INSERT_TAB, false, null),
  /** Insert the document's line ending at every selection. */
  NEWLINE(CommandGroup.EDITOR, UiText.COMMAND_NEWLINE, false, null),
  /** Delete every selection, or the char before each caret. */
  DELETE_BACK(CommandGroup.EDITOR, UiText.COMMAND_DELETE_BACK, false, null),
  /** Delete every selection, or the char after each caret. */
  DELETE_FORWARD(CommandGroup.EDITOR, UiText.COMMAND_DELETE_FORWARD, false, null),
  /** Cancel a running save and drop the secondary selections. */
  CANCEL(CommandGroup.EDITOR, UiText.COMMAND_CANCEL, false, null),
  /** Close the tabs left of the selected one, asking about each unsaved one. */
  CLOSE_LEFT(CommandGroup.FILE, UiText.TAB_CLOSE_LEFT, false, UiText.MENU_CLOSE_MULTIPLE),
  /** Close the tabs right of the selected one, asking about each unsaved one. */
  CLOSE_RIGHT(CommandGroup.FILE, UiText.TAB_CLOSE_RIGHT, false, UiText.MENU_CLOSE_MULTIPLE);

  private final CommandGroup group;
  private final UiText label;
  private final boolean check;
  private final UiText submenu;

  CommandId(CommandGroup group, UiText label, boolean check, UiText submenu) {
    this.group = group;
    this.label = label;
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
   * Returns the title after its cascade's title when it sits in one, for lists that show commands
   * outside their menus. A title that starts with the cascade's title as a whole word stands alone.
   *
   * @return for example {@code SHA-256: Generate}, or {@code Split Horizontally} in Split
   */
  public String qualifiedTitle() {
    String title = title();
    if (submenu == null) {
      return title;
    }
    String cascade = submenu.text().replace("&", "");
    if (title.equals(cascade) || title.startsWith(cascade + " ")) {
      return title;
    }
    return cascade + ": " + title;
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
