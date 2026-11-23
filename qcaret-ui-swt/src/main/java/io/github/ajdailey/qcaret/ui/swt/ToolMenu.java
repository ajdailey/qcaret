package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.widgets.Menu;

/**
 * A toolbar drop-down's menu, held as its {@code ToolItem}'s data: {@link ToolBarNames} names the
 * item by {@link #title}, and {@link ToolbarOverflow} lists a hidden one as a cascade.
 */
interface ToolMenu {
  /**
   * Returns the drop-down's title.
   *
   * @return the title
   */
  String title();

  /**
   * Adds the drop-down's items to a menu, in their current state.
   *
   * @param menu the menu
   */
  void fill(Menu menu);
}
