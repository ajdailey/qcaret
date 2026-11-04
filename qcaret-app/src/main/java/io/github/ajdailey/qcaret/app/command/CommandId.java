package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/**
 * Every command a menu item or key can run. Menus list their commands in declaration order; {@code
 * separatorBefore} starts a new section, a {@code check} command shows as a check item, and
 * consecutive commands with the same {@code submenu} form one cascade in their menu.
 */
public enum CommandId {
  /** Close the window. */
  QUIT(CommandGroup.FILE, UiText.COMMAND_QUIT, false, false),
  /** Light palette. */
  THEME_LIGHT(CommandGroup.VIEW, UiText.COMMAND_THEME_LIGHT, false, true, UiText.MENU_THEME),
  /** Dark palette. */
  THEME_DARK(CommandGroup.VIEW, UiText.COMMAND_THEME_DARK, false, true, UiText.MENU_THEME),
  /** The palette that follows the system setting. */
  THEME_SYSTEM(CommandGroup.VIEW, UiText.COMMAND_THEME_SYSTEM, false, true, UiText.MENU_THEME),
  /** Show the About dialog. */
  ABOUT(CommandGroup.HELP, UiText.COMMAND_ABOUT, false, false);

  private final CommandGroup group;
  private final UiText label;
  private final boolean separatorBefore;
  private final boolean check;
  private final UiText submenu;

  CommandId(CommandGroup group, UiText label, boolean separatorBefore, boolean check) {
    this(group, label, separatorBefore, check, null);
  }

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
   * @return the menu or {@link CommandGroup#LOG_VIEW}
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
