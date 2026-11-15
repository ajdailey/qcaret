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
 * The menu of every tab in a {@code CTabFolder} and the step to the tab beside the selected one.
 * One menu serves the folder; each show refills it.
 */
final class TabList {
  private final CTabFolder tabs;
  private Menu menu;
  private CTabItem repaired;

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
   * Shows the selected tab when the folder left it hidden, once per tab.
   *
   * <p>{@code CTabFolder} puts a new last item at the end of its show order. When the selected new
   * tab fits beside the run already shown, the folder keeps that order, measures no width for the
   * tab and does not show it. Ending MRU mode rebuilds the order from the first tab shown, and the
   * next layout measures the tab.
   */
  void showSelection() {
    CTabItem selected = tabs.getSelection();
    if (selected == null || selected.isShowing()) {
      repaired = null;
      return;
    }
    if (selected != repaired) {
      repaired = selected;
      tabs.setMRUVisible(true);
      tabs.setMRUVisible(false);
    }
  }

  /**
   * Selects the tab beside the selected one as a click does.
   *
   * @param direction -1 for the previous tab, 1 for the next
   * @return false, selecting nothing, when no tab lies that way
   */
  boolean step(int direction) {
    int next = tabs.getSelectionIndex() + direction;
    if (tabs.getSelectionIndex() < 0 || next < 0 || next >= tabs.getItemCount()) {
      return false;
    }
    choose(tabs.getItem(next));
    return true;
  }
}
