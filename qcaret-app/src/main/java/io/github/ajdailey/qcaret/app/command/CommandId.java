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
  /** Close the selected document. */
  CLOSE(CommandGroup.FILE, UiText.COMMAND_CLOSE, true, false, null),
  /** Close every document, asking about each unsaved one. */
  CLOSE_ALL(CommandGroup.FILE, UiText.COMMAND_CLOSE_ALL, false, false, null),
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
