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
  /** Close the window, asking about unsaved documents. */
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
  /** Two columns per tab stop in the selected document. */
  TAB_WIDTH_2(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_2, false, true, UiText.MENU_TAB_WIDTH),
  /** Four columns per tab stop. */
  TAB_WIDTH_4(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_4, false, true, UiText.MENU_TAB_WIDTH),
  /** Eight columns per tab stop. */
  TAB_WIDTH_8(CommandGroup.EDIT, UiText.COMMAND_TAB_WIDTH_8, false, true, UiText.MENU_TAB_WIDTH),
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
  /** Convert every line break to CR LF; checked while every break is CR LF. */
  LINE_ENDING_CRLF(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_CRLF, true, true, UiText.MENU_LINE_ENDINGS),
  /** Convert every line break to LF. */
  LINE_ENDING_LF(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_LF, false, true, UiText.MENU_LINE_ENDINGS),
  /** Convert every line break to CR. */
  LINE_ENDING_CR(
      CommandGroup.ENCODING, UiText.COMMAND_LINE_ENDING_CR, false, true, UiText.MENU_LINE_ENDINGS),
  /** Select the tab to the right, wrapping around. */
  NEXT_TAB(CommandGroup.WINDOW, UiText.COMMAND_NEXT_TAB, false, false, null),
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
