package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The main toolbar's row: the toolbar at its full width inside a clipping composite, and a More
 * button at the row's end, shown only while a button lies past the clip. The More button's menu
 * lists the hidden buttons in toolbar order, with the menus' icons, checked and enabled state, and
 * a disabled one's reason as the menus give it; choosing one runs the button's own Selection. A
 * hidden drop-down ({@link ToolMenu}) shows as a cascade of its menu. Call on the UI thread.
 *
 * <p>The toolbar is never allocated less than its preferred width: a narrower GTK 3 toolbar shows
 * its own overflow arrow, whose items carry no command.
 */
final class ToolbarOverflow {
  private final Composite row;
  private final Composite clip;
  private final ToolBar bar;
  private ToolItem more;
  private CommandStates states;
  private MenuIcons menuIcons;
  private Menu menu;
  private boolean laying;

  /**
   * Creates the row as the parent's next child, filling its width.
   *
   * @param parent the shell, laid out by a {@code GridLayout}
   * @param style the toolbar's style
   */
  ToolbarOverflow(Composite parent, int style) {
    row = new Composite(parent, SWT.NONE);
    row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    clip = new Composite(row, SWT.NONE);
    bar = new ToolBar(clip, style);
    row.setLayout(new RowLayout());
    clip.setLayout(
        new Layout() {
          @Override
          protected Point computeSize(Composite composite, int wHint, int hHint, boolean flush) {
            return bar.computeSize(SWT.DEFAULT, SWT.DEFAULT, flush);
          }

          @Override
          protected void layout(Composite composite, boolean flush) {
            // An item added or removed changes the bar's width and height: lay out from the shell.
            if (!laying && !row.getParent().isDisposed()) {
              row.getParent().layout(true);
              row.layout(true);
            }
          }
        });
  }

  /**
   * Creates the More button.
   *
   * @param icons the toolbar's icon set
   * @param menuIcon the menus' icons
   * @param commandStates the commands' enabled state and reasons
   */
  void attach(ToolIcons icons, MenuIcons menuIcon, CommandStates commandStates) {
    states = commandStates;
    menuIcons = menuIcon;
    more = icons.item(row, ToolIcons.TAB_LIST_DOWN, UiText.TOOLBAR_MORE.text());
    more.getParent().setVisible(false);
    more.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Menu shown = menu();
            Rectangle bounds = ((ToolItem) event.widget).getParent().getBounds();
            shown.setLocation(row.toDisplay(bounds.x, bounds.y + bounds.height));
            shown.setVisible(true);
          }
        });
    row.layout(true);
  }

  /**
   * Returns the toolbar.
   *
   * @return the toolbar
   */
  ToolBar bar() {
    return bar;
  }

  /**
   * Returns the row, which shows or hides the toolbar as a whole.
   *
   * @return the row
   */
  Composite row() {
    return row;
  }

  /**
   * Returns whether the More button shows.
   *
   * @return true while a button is hidden
   */
  boolean moreShown() {
    return more != null && more.getParent().getVisible();
  }

  /**
   * Returns the More button.
   *
   * @return the button, or null before {@link #attach}
   */
  ToolItem more() {
    return more;
  }

  /**
   * Returns the buttons past the clip, in toolbar order, separators left out.
   *
   * @return the hidden buttons
   */
  List<ToolItem> hidden() {
    List<ToolItem> hidden = new ArrayList<ToolItem>();
    int width = clip.getClientArea().width;
    for (ToolItem item : bar.getItems()) {
      Rectangle bounds = item.getBounds();
      if ((item.getStyle() & SWT.SEPARATOR) == 0 && bounds.x + bounds.width > width) {
        hidden.add(item);
      }
    }
    return hidden;
  }

  /**
   * Builds the More menu from the hidden buttons, replacing the last one.
   *
   * @return the menu, not yet visible
   * @throws IllegalStateException before {@link #attach}
   */
  Menu menu() {
    if (states == null || menuIcons == null) {
      throw new IllegalStateException("not attached");
    }
    if (menu != null && !menu.isDisposed()) {
      menu.dispose();
    }
    menu = new Menu(row.getShell(), SWT.POP_UP);
    for (final ToolItem item : hidden()) {
      if (item.getData() instanceof ToolMenu drop) {
        MenuItem holder = new MenuItem(menu, SWT.CASCADE);
        holder.setText(drop.title().replace("&", "&&"));
        Menu cascade = new Menu(menu);
        holder.setMenu(cascade);
        holder.setData(drop);
        drop.fill(cascade);
        continue;
      }
      if (!(item.getData() instanceof CommandId command)) {
        continue;
      }
      int style = item.getStyle();
      int kind = (style & SWT.RADIO) != 0 ? SWT.RADIO : (style & SWT.CHECK) != 0 ? SWT.CHECK : 0;
      MenuItem entry = new MenuItem(menu, kind == 0 ? SWT.PUSH : kind);
      String label = command.title();
      String reason = item.getEnabled() ? null : states.unavailable(command);
      if (reason != null && !PlatformTraits.PLATFORM.showsMenuToolTips()) {
        label = UiText.MENU_ITEM_REASON.format(label, reason);
      }
      entry.setText(label.replace("&", "&&"));
      entry.setToolTipText(reason);
      entry.setEnabled(item.getEnabled());
      entry.setData(command);
      if (kind != 0) {
        entry.setSelection(item.getSelection());
      }
      entry.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              choose(item);
            }
          });
    }
    menuIcons.decorate(menu);
    return menu;
  }

  /** Runs a button as a click does: a check button flips first, a radio button checks itself. */
  private static void choose(ToolItem item) {
    if (item.isDisposed() || !item.getEnabled()) {
      return;
    }
    int style = item.getStyle();
    if ((style & SWT.CHECK) != 0) {
      item.setSelection(!item.getSelection());
    } else if ((style & SWT.RADIO) != 0) {
      if (item.getSelection()) {
        return;
      }
      item.setSelection(true);
    }
    item.notifyListeners(SWT.Selection, new Event());
  }

  /** Places the clip and the More button; the toolbar keeps its preferred width inside the clip. */
  private final class RowLayout extends Layout {
    @Override
    protected Point computeSize(Composite composite, int wHint, int hHint, boolean flush) {
      Point size = bar.computeSize(SWT.DEFAULT, SWT.DEFAULT, flush);
      if (wHint != SWT.DEFAULT) {
        size.x = wHint;
      }
      return size;
    }

    @Override
    protected void layout(Composite composite, boolean flush) {
      if (laying) {
        return;
      }
      laying = true;
      try {
        Rectangle area = composite.getClientArea();
        Point preferred = bar.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        boolean overflow = more != null && preferred.x > area.width;
        int clipWidth = area.width;
        if (more != null) {
          Control button = more.getParent();
          if (overflow) {
            Point size = button.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            clipWidth = Math.max(0, area.width - size.x);
            button.setBounds(
                area.x + clipWidth, area.y + (area.height - size.y) / 2, size.x, size.y);
          }
          if (button.getVisible() != overflow) {
            button.setVisible(overflow);
          }
        }
        clip.setBounds(area.x, area.y, clipWidth, area.height);
        bar.setBounds(0, 0, Math.max(preferred.x, clipWidth), area.height);
      } finally {
        laying = false;
      }
    }
  }
}
