package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.BookmarkRow;
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * The Bookmarks list dock: a row of buttons and a summary over a virtual {@link Table} of a {@link
 * BookmarksInput}'s rows, with the line (from 1), the comment and the line's text in three columns.
 * Rows load as they show. Enter and a double click go to the selected row's line, Delete removes
 * its bookmark; Go To, Edit Comment and Remove act on the selected row, Hide hides the dock. The
 * selection keeps its index across changes, within the row count. A cut of over {@value
 * #REMOVE_ROWS} rows replaces the table, as GTK removes rows one by one.
 */
final class BookmarksDock {
  /** Rows removed one by one at most; a larger cut replaces the table. */
  static final int REMOVE_ROWS = 2000;

  private final Composite root;
  private final Label summary;
  private final Button goTo;
  private final Button edit;
  private final Button remove;
  private Table table;
  private BookmarksInput input;

  /** What a button does. */
  private enum Action {
    GO_TO,
    EDIT,
    REMOVE,
    HIDE
  }

  /**
   * Creates the dock.
   *
   * @param parent the window's dock area
   */
  BookmarksDock(Composite parent) {
    root = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    root.setLayout(layout);
    Composite bar = new Composite(root, SWT.NONE);
    GridLayout barLayout = new GridLayout(5, false);
    barLayout.marginHeight = 2;
    barLayout.marginWidth = 0;
    barLayout.horizontalSpacing = 4;
    bar.setLayout(barLayout);
    bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    goTo = button(bar, UiText.BOOKMARKS_GO_TO, Action.GO_TO);
    edit = button(bar, UiText.BOOKMARKS_EDIT, Action.EDIT);
    remove = button(bar, UiText.BOOKMARKS_REMOVE, Action.REMOVE);
    button(bar, UiText.BOOKMARKS_HIDE, Action.HIDE);
    summary = new Label(bar, SWT.NONE);
    summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    createTable();
    syncButtons();
  }

  private void createTable() {
    table =
        new Table(
            root, SWT.VIRTUAL | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    table.setHeaderVisible(true);
    column(UiText.BOOKMARKS_LINE, SWT.RIGHT, 70);
    column(UiText.BOOKMARKS_COMMENT, SWT.LEFT, 260);
    column(UiText.BOOKMARKS_TEXT, SWT.LEFT, 480);
    table.addListener(
        SWT.SetData,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            load((TableItem) event.item, event.index);
          }
        });
    table.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            syncButtons();
          }
        });
    table.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            act(Action.GO_TO);
          }
        });
    table.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.keyCode == SWT.DEL) {
              event.doit = false;
              act(Action.REMOVE);
            }
          }
        });
  }

  private void column(UiText title, int style, int width) {
    TableColumn column = new TableColumn(table, style);
    column.setText(title.text());
    column.setWidth(width);
  }

  private Button button(Composite parent, UiText label, final Action action) {
    Button button = new Button(parent, SWT.PUSH);
    button.setText(label.text());
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            act(action);
          }
        });
    return button;
  }

  /**
   * Returns the dock's top control.
   *
   * @return the composite
   */
  Composite control() {
    return root;
  }

  /**
   * Returns the table, for tests.
   *
   * @return the table
   */
  Table table() {
    return table;
  }

  /**
   * Returns the summary's text, for tests.
   *
   * @return the text
   */
  String summary() {
    return summary.getText();
  }

  /**
   * Shows a model.
   *
   * @param bookmarks the model
   */
  void setInput(BookmarksInput bookmarks) {
    input = bookmarks;
    refresh();
  }

  /** Reads the model again and reloads the visible rows. */
  void refresh() {
    if (table.isDisposed()) {
      return;
    }
    int count = input == null ? 0 : input.rowCount();
    int selected = table.getSelectionIndex();
    if (count < table.getItemCount() - REMOVE_ROWS) {
      boolean focused = table.isFocusControl();
      table.dispose();
      createTable();
      root.layout(true);
      if (focused) {
        table.setFocus();
      }
    }
    table.setItemCount(count);
    table.clearAll();
    if (selected >= 0 && count > 0) {
      table.setSelection(Math.min(selected, count - 1));
    }
    summary.setText(input == null ? "" : input.summary());
    summary.getParent().layout(true);
    syncButtons();
  }

  private void load(TableItem item, int index) {
    if (input == null || index >= input.rowCount()) {
      return;
    }
    BookmarkRow row = input.row(index);
    item.setText(new String[] {Integer.toString(row.line() + 1), row.comment(), row.text()});
  }

  private void act(Action action) {
    if (input == null) {
      return;
    }
    int at = table.getSelectionIndex();
    switch (action) {
      case GO_TO -> {
        if (at >= 0) {
          input.goTo(at);
        }
      }
      case EDIT -> {
        if (at >= 0) {
          input.editComment(at);
        }
      }
      case REMOVE -> {
        if (at >= 0) {
          input.remove(at);
        }
      }
      case HIDE -> input.hide();
    }
  }

  private void syncButtons() {
    boolean any = !table.isDisposed() && table.getSelectionIndex() >= 0;
    goTo.setEnabled(any);
    edit.setEnabled(any);
    remove.setEnabled(any);
  }

  /**
   * Selects a row and presses a button, for tests.
   *
   * @param row the row
   * @param label the button's label
   */
  void press(int row, UiText label) {
    table.setSelection(row);
    syncButtons();
    for (Action action : Action.values()) {
      if (labelOf(action) == label) {
        act(action);
      }
    }
  }

  private static UiText labelOf(Action action) {
    return switch (action) {
      case GO_TO -> UiText.BOOKMARKS_GO_TO;
      case EDIT -> UiText.BOOKMARKS_EDIT;
      case REMOVE -> UiText.BOOKMARKS_REMOVE;
      case HIDE -> UiText.BOOKMARKS_HIDE;
    };
  }

  /**
   * Returns a loaded row's cells, for tests.
   *
   * @param row the row
   * @return line, comment and text
   */
  String[] cells(int row) {
    TableItem item = table.getItem(row);
    return new String[] {item.getText(0), item.getText(1), item.getText(2)};
  }
}
