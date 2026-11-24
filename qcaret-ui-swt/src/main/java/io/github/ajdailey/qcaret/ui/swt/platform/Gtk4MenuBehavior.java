package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.widgets.MenuItem;

/** Menus on GTK 4, where SWT drops menu item tool tips: a disabled item's label names why. */
final class Gtk4MenuBehavior extends Gtk3MenuBehavior {
  @Override
  public String label(String label, String reason) {
    return reason == null ? label : UiText.MENU_ITEM_REASON.format(label, reason);
  }

  @Override
  public void showReason(MenuItem item, String label, String reason) {
    item.setText(label(label, reason));
  }
}
