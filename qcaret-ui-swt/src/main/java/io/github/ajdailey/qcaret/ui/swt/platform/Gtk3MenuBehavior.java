package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import java.util.Set;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Menus on GTK 3: blank images pad a menu with an icon, the ink follows the toolbar, a disabled
 * icon on a dark background shows the faded copy, and reasons go in tool tips.
 */
class Gtk3MenuBehavior implements MenuBehavior {
  @Override
  public final void decorate(Menu menu, ItemImages images) {
    MenuImageWalk.decorate(menu, images, true);
  }

  @Override
  public final <T> T ink(Display display, Ink<T> ink) {
    return ink.live();
  }

  @Override
  public final Image disabledOnDark(Image plain, FadedImage faded) {
    return faded.get();
  }

  @Override
  public final void toolWindowMenus(Shell tool, ToolMenus menus) {}

  @Override
  public final void hookAppMenu(Display display, AppMenuActions actions) {}

  @Override
  public final Set<CommandId> systemCommands(Display display) {
    return MenuTable.TITLE_BAR_COMMANDS;
  }

  @Override
  public String label(String label, String reason) {
    return label;
  }

  @Override
  public void showReason(MenuItem item, String label, String reason) {
    item.setToolTipText(reason);
  }
}
