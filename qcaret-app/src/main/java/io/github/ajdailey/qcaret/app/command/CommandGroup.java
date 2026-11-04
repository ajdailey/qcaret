package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/** Where a command appears: a menu, or the editor's own keys, which have no menu. */
public enum CommandGroup {
  /** File menu. */
  FILE(UiText.GROUP_FILE, true),
  /** Edit menu. */
  EDIT(UiText.GROUP_EDIT, true),
  /** View menu. */
  VIEW(UiText.GROUP_VIEW, true),
  /** Encoding menu; the menu adds the charset cascades. */
  ENCODING(UiText.GROUP_ENCODING, true),
  /** Window menu; the menu lists the open documents after its commands. */
  WINDOW(UiText.GROUP_WINDOW, true),
  /** Help menu. */
  HELP(UiText.GROUP_HELP, true),
  /** Keys of the focused editor; listed in Keyboard Shortcuts only. */
  EDITOR(UiText.GROUP_EDITOR, false);

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
