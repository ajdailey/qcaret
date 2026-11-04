package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/** The menu a command appears in. */
public enum CommandGroup {
  /** File menu. */
  FILE(UiText.GROUP_FILE, true),
  /** Edit menu. */
  EDIT(UiText.GROUP_EDIT, true),
  /** View menu. */
  VIEW(UiText.GROUP_VIEW, true),
  /** Help menu. */
  HELP(UiText.GROUP_HELP, true);

  private final UiText label;
  private final boolean menu;

  CommandGroup(UiText label, boolean menu) {
    this.label = label;
    this.menu = menu;
  }

  /**
   * Returns the menu title, with {@code &} before the mnemonic.
   *
   * @return the label
   */
  public String label() {
    return label.text();
  }

  /**
   * Returns the label without the mnemonic marker.
   *
   * @return for example {@code File}
   */
  public String title() {
    return label().replace("&", "");
  }

  /**
   * Reports whether the group is a menu.
   *
   * @return true for a menu-bar menu
   */
  public boolean menu() {
    return menu;
  }
}
