package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/** Applies menu icons to a menu and its cascades, for the platforms whose menus show images. */
final class MenuImageWalk {
  private MenuImageWalk() {}

  /**
   * Shows the icons of a menu and of its cascades.
   *
   * @param menu the menu
   * @param images sets each item's image
   * @param pad true to give every other item of a menu with an icon a blank image
   */
  static void decorate(Menu menu, MenuBehavior.ItemImages images, boolean pad) {
    boolean any = false;
    List<MenuItem> plain = new ArrayList<MenuItem>();
    for (MenuItem item : menu.getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) != 0) {
        continue;
      }
      if (item.getMenu() != null) {
        decorate(item.getMenu(), images, pad);
      }
      if (images.apply(item)) {
        any = true;
      } else {
        plain.add(item);
      }
    }
    if (any && pad) {
      for (MenuItem item : plain) {
        images.applyBlank(item);
      }
    }
  }
}
