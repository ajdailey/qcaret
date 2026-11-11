package io.github.ajdailey.qcaret.ui.swt;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The menu of every tab in a {@code CTabFolder} and the shift of the run of tabs the strip shows.
 * One menu serves the folder; each show refills it.
 */
final class TabList {
  private final CTabFolder tabs;
  private Menu menu;

  /**
   * Creates the list over a folder; the menu is disposed with the folder.
   *
   * @param tabs the folder
   */
  TabList(CTabFolder tabs) {
    this.tabs = tabs;
    tabs.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (menu != null) {
              menu.dispose();
            }
          }
        });
  }

  /**
   * Returns the menu.
   *
   * @return the menu, null before the first show
   */
  Menu menu() {
    return menu;
  }

  /**
   * Opens the menu below a control: every tab in strip order, the selected one checked, with
   * separators between the tabs shown in the strip and the hidden ones.
   *
   * @param anchor the control the menu opens below
   */
  void show(Control anchor) {
    if (menu == null) {
      menu = new Menu(anchor.getShell(), SWT.POP_UP);
    }
    for (MenuItem item : menu.getItems()) {
      item.dispose();
    }
    CTabItem[] items = tabs.getItems();
    CTabItem selected = tabs.getSelection();
    List<String> names = new ArrayList<String>(items.length);
    for (CTabItem item : items) {
      names.add(name(item.getText()));
    }
    for (int i = 0; i < items.length; i++) {
      final CTabItem tab = items[i];
      if (i > 0 && tab.isShowing() != items[i - 1].isShowing()) {
        new MenuItem(menu, SWT.SEPARATOR);
      }
      MenuItem entry = new MenuItem(menu, SWT.RADIO);
      String label = tab.getText();
      if (names.indexOf(names.get(i)) != names.lastIndexOf(names.get(i))) {
        String folder = folder(tab.getToolTipText());
        if (!folder.isEmpty()) {
          label = label + "  (" + folder + ")";
        }
      }
      entry.setText(label);
      entry.setSelection(tab == selected);
      entry.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (((MenuItem) event.widget).getSelection()) {
                choose(tab);
              }
            }
          });
    }
    Point size = anchor.getSize();
    menu.setLocation(anchor.toDisplay(0, size.y));
    menu.setVisible(true);
  }

  /** Returns a tab label without the dirty mark. */
  private static String name(String label) {
    return label.startsWith("*") ? label.substring(1) : label;
  }

  /** Returns the folder of the path on a tab tool tip's first line, empty without one. */
  private static String folder(String tip) {
    if (tip == null) {
      return "";
    }
    int end = tip.indexOf('\n');
    String path = end < 0 ? tip : tip.substring(0, end);
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash <= 0 ? "" : path.substring(0, slash);
  }

  /**
   * Selects a tab as a click on it does: the folder shows it and sends {@code Selection}.
   *
   * @param tab the tab
   */
  void choose(CTabItem tab) {
    if (tab.isDisposed()) {
      return;
    }
    tabs.setSelection(tab);
    Event event = new Event();
    event.item = tab;
    tabs.notifyListeners(SWT.Selection, event);
  }

  /**
   * Shifts the run of shown tabs one tab toward a side, keeping the selected tab shown.
   *
   * <p>{@code CTabFolder.showItem} reorders only in MRU mode; outside it the folder keeps the run
   * that holds the selection. So the run is chosen in MRU mode, by showing the tabs from the end
   * down to the new first one, laid out at once through a {@code Resize}, and kept when MRU mode
   * ends, which starts the run at the first tab shown.
   *
   * @param direction -1 toward the first tab, 1 toward the last
   */
  void scroll(int direction) {
    int count = tabs.getItemCount();
    int first = -1;
    int last = -1;
    for (int i = 0; i < count; i++) {
      if (tabs.getItem(i).isShowing()) {
        first = first < 0 ? i : first;
        last = i;
      }
    }
    int next = first + direction;
    if (first < 0
        || next < 0
        || (direction > 0 && last == count - 1)
        || (direction > 0 && next > tabs.getSelectionIndex())) {
      return;
    }
    tabs.setMRUVisible(true);
    for (int i = count - 1; i >= next; i--) {
      tabs.showItem(tabs.getItem(i));
    }
    tabs.notifyListeners(SWT.Resize, new Event());
    tabs.setMRUVisible(false);
  }
}
