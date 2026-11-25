package io.github.ajdailey.qcaret.ui.swt;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;

/**
 * The menus a Display shows now: menu bar menus, their cascades and context menus, tracked from
 * {@code SWT.Show} and {@code SWT.Hide} through Display filters. {@link WindowViews} waits while a
 * window shows one, since disposing the window closes the menu under the pointer. GTK SWT can send
 * {@code SWT.Show} for a menu it never maps and no {@code SWT.Hide} after it, so a tracked menu
 * counts only while {@code Menu.getVisible} is true. Confined to the UI thread.
 */
final class ShownMenus {
  private static final Predicate<Menu> VISIBLE =
      new Predicate<Menu>() {
        @Override
        public boolean test(Menu menu) {
          return menu.getVisible();
        }
      };

  private final Set<Menu> shown = new HashSet<Menu>();
  private final Predicate<Menu> visible;

  /**
   * Starts tracking the menus of a Display.
   *
   * @param display the Display
   */
  ShownMenus(Display display) {
    this(display, VISIBLE);
  }

  /**
   * Starts tracking the menus of a Display with a given visibility check.
   *
   * @param display the Display
   * @param visible true while a menu that sent {@code SWT.Show} is on screen
   */
  ShownMenus(Display display, Predicate<Menu> visible) {
    this.visible = visible;
    Listener track =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            // A menu bar sends SWT.Show with its shell and stays shown.
            if (event.widget instanceof Menu menu && (menu.getStyle() & SWT.BAR) == 0) {
              if (event.type == SWT.Show) {
                shown.add(menu);
              } else {
                shown.remove(menu);
              }
            }
          }
        };
    display.addFilter(SWT.Show, track);
    display.addFilter(SWT.Hide, track);
  }

  /**
   * Reports whether a menu shows over a top-level shell or a shell it owns.
   *
   * @param shell a top-level shell
   * @return true while one shows
   */
  boolean shownOver(Shell shell) {
    shown.removeIf(Menu::isDisposed);
    for (Menu menu : shown) {
      if (visible.test(menu) && NativeDialogs.top(menu.getShell()) == shell) {
        return true;
      }
    }
    return false;
  }
}
