package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * The Bookmarks list's context menu, {@link BookmarksInput#MENU}, built on the first {@code
 * MenuDetect} of each table the dock creates and disposed with it. A pointer request first selects
 * the row under the pointer, or clears the selection over empty space. Each item acts on the
 * selected row; Clear All is enabled while the list has rows. Call on the UI thread.
 */
final class BookmarksMenu {
  private final BookmarksDock dock;
  private Menu menu;

  /**
   * Creates the menu's owner; {@link #attach} hooks a table.
   *
   * @param dock the dock whose model and buttons the items use
   */
  BookmarksMenu(BookmarksDock dock) {
    this.dock = dock;
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
              if (item == null) {
                table.deselectAll();
              } else {
                table.setSelection(item);
              }
              dock.syncButtons();
            }
            show(table);
          }
        });
  }

  /** Builds the menu once per table and enables its items for the selected row. */
  private void show(final Table table) {
    if (menu == null || menu.isDisposed()) {
      menu = new Menu(table);
      for (final BookmarksInput.RowAction action : BookmarksInput.MENU) {
        if (action == null) {
          new MenuItem(menu, SWT.SEPARATOR);
          continue;
        }
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(action.label().text());
        item.setData(action);
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                run(action, table.getSelectionIndex());
              }
            });
      }
      table.setMenu(menu);
    }
    BookmarksInput input = dock.input();
    boolean any = table.getSelectionIndex() >= 0;
    boolean rows = input != null && input.rowCount() > 0;
    for (MenuItem item : menu.getItems()) {
      if (item.getData() instanceof BookmarksInput.RowAction action) {
        item.setEnabled(action == BookmarksInput.RowAction.CLEAR_ALL ? rows : any);
      }
    }
  }

  /**
   * Runs an item on a row.
   *
   * @param action the item
   * @param at the selected row, or -1
   */
  private void run(BookmarksInput.RowAction action, int at) {
    BookmarksInput input = dock.input();
    if (input == null || (at < 0 && action != BookmarksInput.RowAction.CLEAR_ALL)) {
      return;
    }
    switch (action) {
      case GO_TO -> input.goTo(at);
      case EDIT -> input.editComment(at);
      case COPY_LINE -> input.copyLine(at);
      case REMOVE -> input.remove(at);
      case CLEAR_ALL -> input.clearAll();
    }
  }
}
