package io.github.ajdailey.qcaret.ui.swt.search;

import io.github.ajdailey.qcaret.app.view.ResultsInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * The results table's context menu, {@link ResultsInput#MENU}, built on the first {@code
 * MenuDetect} of each table the dock creates and disposed with it. A pointer request first selects
 * the row under the pointer, or clears the selection over empty space. Each item acts on the
 * selected row, and Rerun, Pin and Remove Set on its set or the newest set, as the dock's buttons
 * do. Expand All and Collapse All change the set and every group in it without checking the groups'
 * files. Call on the UI thread.
 */
final class ResultsMenu {
  private final ResultsDock dock;
  private final ResultRows rows;
  private Menu menu;

  /**
   * Creates the menu's owner; {@link #attach} hooks a table.
   *
   * @param dock the dock whose selection and buttons the items use
   * @param rows the dock's rows
   */
  ResultsMenu(ResultsDock dock, ResultRows rows) {
    this.dock = dock;
    this.rows = rows;
  }

  /**
   * Opens the menu on a table's {@code MenuDetect}.
   *
   * @param table a table the dock created
   */
  void attach(final Table table) {
    table.addListener(
        SWT.MenuDetect,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.MENU_MOUSE) {
              TableItem item = table.getItem(table.toControl(event.x, event.y));
              int at = item == null ? -1 : table.indexOf(item);
              dock.select(at < 0 ? new int[0] : rows.path(at));
              dock.syncButtons();
            }
            show(table);
          }
        });
  }

  /** Builds the menu once per table and enables its items for the selected row. */
  private void show(Table table) {
    if (menu == null || menu.isDisposed()) {
      menu = new Menu(table);
      for (final ResultsInput.RowAction action : ResultsInput.MENU) {
        if (action == null) {
          new MenuItem(menu, SWT.SEPARATOR);
          continue;
        }
        MenuItem item =
            new MenuItem(menu, action == ResultsInput.RowAction.PIN ? SWT.CHECK : SWT.PUSH);
        item.setText(action.label().text());
        item.setData(action);
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                run(action);
              }
            });
      }
      table.setMenu(menu);
    }
    int set = dock.selectedSet();
    boolean row = dock.selected().length > 0;
    for (MenuItem item : menu.getItems()) {
      if (item.getData() instanceof ResultsInput.RowAction action) {
        item.setEnabled(set >= 0 && (row || !rowOnly(action)));
        if (action == ResultsInput.RowAction.PIN) {
          item.setSelection(set >= 0 && dock.input().pinned(set));
        }
      }
    }
  }

  /** Reports whether an action needs a selected row rather than the newest set. */
  private static boolean rowOnly(ResultsInput.RowAction action) {
    return action == ResultsInput.RowAction.OPEN || action == ResultsInput.RowAction.COPY;
  }

  /**
   * Runs an item on the selected row.
   *
   * @param action the item
   */
  void run(ResultsInput.RowAction action) {
    ResultsInput input = dock.input();
    int[] selected = dock.selected();
    int set = dock.selectedSet();
    if (input == null || set < 0 || (rowOnly(action) && selected.length == 0)) {
      return;
    }
    int file = selected.length > 1 ? selected[1] : -1;
    int match = selected.length > 2 ? selected[2] : -1;
    switch (action) {
      case OPEN -> {
        if (file >= 0 && (match >= 0 || input.matchCount(set, file) > 0)) {
          input.open(set, file, Math.max(match, 0));
        }
      }
      case COPY -> input.copy(set, file, match);
      case COPY_ALL -> input.copyAll();
      case RERUN -> dock.act(ResultsDock.Action.RERUN);
      case PIN -> dock.act(ResultsDock.Action.PIN);
      case REMOVE_SET -> dock.act(ResultsDock.Action.CLEAR);
      case EXPAND_ALL, COLLAPSE_ALL -> {
        boolean expand = action == ResultsInput.RowAction.EXPAND_ALL;
        rows.setAllExpanded(set, expand);
        if (!expand) {
          dock.select(new int[] {set});
        }
        dock.refresh(false);
      }
    }
  }
}
