package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.CommandId;
import java.util.Set;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Menus on win32: icons in the ink of the native side the Display started with, which pop-up menus
 * keep after a live switch; a reserved image column; reasons in tool tips.
 */
final class Win32MenuBehavior implements MenuBehavior {
  @Override
  public void decorate(Menu menu, ItemImages images) {
    MenuImageWalk.decorate(menu, images, false);
  }

  @Override
  public <T> T ink(Display display, Ink<T> ink) {
    return ink.fixed(NativeTheme.startDark(display));
  }

  @Override
  public Image disabledOnDark(Image plain, FadedImage faded) {
    return plain;
  }

  @Override
  public void addWindowControls(Menu menu, Shell shell) {}

  @Override
  public void toolWindowMenus(Shell tool, ToolMenus menus) {}

  @Override
  public void hookAppMenu(Display display, AppMenuActions actions) {}

  @Override
  public Set<CommandId> systemCommands(Display display) {
    return Set.of();
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
