package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/**
 * Every command a menu item or key can run. Menus list their commands in declaration order; {@code
 * separatorBefore} starts a new section, a {@code check} command shows as a check item, and
 * consecutive commands with the same {@code submenu} form one cascade in their menu.
 */
public enum CommandId {
  /** Open an empty untitled document. */
  NEW(CommandGroup.FILE, UiText.COMMAND_NEW, false, false, null, "Mod+N"),
  /** Open files chosen in a dialog. */
  OPEN(CommandGroup.FILE, UiText.COMMAND_OPEN, false, false, null, "Mod+O"),
  /** Save the selected document. */
  SAVE(CommandGroup.FILE, UiText.COMMAND_SAVE, true, false, null, "Mod+S"),
  /** Save the selected document to a chosen file. */
  SAVE_AS(CommandGroup.FILE, UiText.COMMAND_SAVE_AS, false, false, null, "Mod+Shift+S"),
  /** Close the selected document. */
  CLOSE(CommandGroup.FILE, UiText.COMMAND_CLOSE, true, false, null, "Mod+W"),
  /** Close the window, asking about unsaved documents. */
  QUIT(CommandGroup.FILE, UiText.COMMAND_QUIT, true, false, null, null),
  /** Undo in the selected editor. */
  UNDO(CommandGroup.EDIT, UiText.COMMAND_UNDO, false, false, null, null),
  /** Redo in the selected editor. */
  REDO(CommandGroup.EDIT, UiText.COMMAND_REDO, false, false, null, null),
  /** Cut the selections. */
  CUT(CommandGroup.EDIT, UiText.COMMAND_CUT, true, false, null, null),
  /** Copy the selections. */
  COPY(CommandGroup.EDIT, UiText.COMMAND_COPY, false, false, null, null),
  /** Paste at the selections. */
  PASTE(CommandGroup.EDIT, UiText.COMMAND_PASTE, false, false, null, null),
  /** Select the whole document. */
  SELECT_ALL(CommandGroup.EDIT, UiText.COMMAND_SELECT_ALL, true, false, null, null),
  /** Word wrap in the window's editors. */
  WORD_WRAP(CommandGroup.VIEW, UiText.COMMAND_WORD_WRAP, false, true, null, null),
  /** Light palette. */
  THEME_LIGHT(CommandGroup.VIEW, UiText.COMMAND_THEME_LIGHT, true, true, UiText.MENU_THEME, null),
  /** Dark palette. */
  THEME_DARK(CommandGroup.VIEW, UiText.COMMAND_THEME_DARK, false, true, UiText.MENU_THEME, null),
  /** The palette that follows the system setting. */
  THEME_SYSTEM(
      CommandGroup.VIEW, UiText.COMMAND_THEME_SYSTEM, false, true, UiText.MENU_THEME, null),
  /** Show the About dialog. */
  ABOUT(CommandGroup.HELP, UiText.COMMAND_ABOUT, false, false, null, null);

  private final CommandGroup group;
  private final UiText label;
  private final boolean separatorBefore;
  private final boolean check;
  private final UiText submenu;
  private final String accelerator;

  CommandId(
      CommandGroup group,
      UiText label,
      boolean separatorBefore,
      boolean check,
      UiText submenu,
      String accelerator) {
    this.group = group;
    this.label = label;
    this.separatorBefore = separatorBefore;
    this.check = check;
    this.submenu = submenu;
    this.accelerator = accelerator;
  }

  /**
   * Returns the menu accelerator of a window-wide command. Editing keys are not accelerators: the
   * editor dispatches them, so they never fire from another control.
   *
   * @return for example {@code Mod+Shift+S}, {@code Mod} being Ctrl or Command; null for none
   */
  public String accelerator() {
    return accelerator;
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
