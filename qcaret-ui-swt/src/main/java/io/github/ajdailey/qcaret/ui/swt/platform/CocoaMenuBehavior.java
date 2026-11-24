package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Menus on cocoa: no menu images, Minimize and Zoom in the Window menu, and the system's
 * application menu, where About, Preferences and Quit run in the window activated last. Hide, Hide
 * Others and Show All stay native.
 */
final class CocoaMenuBehavior implements MenuBehavior {
  /** The application-menu items QCaret runs: About, Preferences and Quit. */
  private static final Set<Integer> APP_MENU_IDS =
      Set.of(SWT.ID_ABOUT, SWT.ID_PREFERENCES, SWT.ID_QUIT);

  @Override
  public void decorate(Menu menu, ItemImages images) {}

  @Override
  public <T> T ink(Display display, Ink<T> ink) {
    return ink.live();
  }

  @Override
  public Image disabledOnDark(Image plain, FadedImage faded) {
    return plain;
  }

  @Override
  public void addWindowControls(Menu menu, final Shell shell) {
    MenuItem minimize = new MenuItem(menu, SWT.PUSH, 0);
    minimize.setText(UiText.MENU_MINIMIZE.text());
    minimize.setAccelerator(SWT.MOD1 | 'M');
    minimize.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            shell.setMinimized(true);
          }
        });
    MenuItem zoom = new MenuItem(menu, SWT.PUSH, 1);
    zoom.setText(UiText.MENU_ZOOM.text());
    zoom.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            shell.setMaximized(!shell.getMaximized());
          }
        });
    new MenuItem(menu, SWT.SEPARATOR, 2);
  }

  @Override
  public void hookAppMenu(Display display, final AppMenuActions actions) {
    Menu menu = display.getSystemMenu();
    if (menu == null) {
      return;
    }
    for (MenuItem item : menu.getItems()) {
      final int id = item.getID();
      if (APP_MENU_IDS.contains(id)) {
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                actions.select(id);
              }
            });
      }
    }
    display.addListener(
        SWT.Close,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            actions.quit();
          }
        });
    display.addListener(
        SWT.OpenDocument,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            actions.open(event.text);
          }
        });
  }

  @Override
  public Set<CommandId> systemCommands(Display display) {
    return display.getSystemMenu() == null ? Set.of() : MenuTable.APP_MENU_COMMANDS;
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
