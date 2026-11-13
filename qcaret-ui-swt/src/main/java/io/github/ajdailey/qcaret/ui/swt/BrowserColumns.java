package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceBrowseInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;

/**
 * The workspace browser tree's Name, Size and Modified columns. Each starts as wide as its header;
 * Size and Modified widen to the widest text added and never narrow, and Name takes the rest of the
 * tree's width at each resize, down to its header's width. A header click runs {@link
 * WorkspaceDock#sort} with its column.
 */
final class BrowserColumns {
  /** Pixels added to a header text's width for its padding and sort arrow. */
  private static final int HEADER_PADDING = 24;

  /** Pixels added to a cell text's width for its padding. */
  private static final int CELL_PADDING = 16;

  private final Tree tree;
  private final TreeColumn[] columns = new TreeColumn[3];
  private int nameMinimum;

  BrowserColumns(final WorkspaceDock dock, Tree tree) {
    this.tree = tree;
    UiText[] labels = {
      UiText.BROWSE_COLUMN_NAME, UiText.BROWSE_COLUMN_SIZE, UiText.BROWSE_COLUMN_MODIFIED
    };
    GC gc = new GC(tree);
    try {
      for (int i = 0; i < columns.length; i++) {
        final WorkspaceBrowseInput.Column column = WorkspaceBrowseInput.Column.values()[i];
        columns[i] = new TreeColumn(tree, i == 1 ? SWT.RIGHT : SWT.LEFT);
        columns[i].setText(labels[i].text());
        columns[i].setWidth(gc.textExtent(labels[i].text()).x + HEADER_PADDING);
        columns[i].addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                dock.sort(column);
              }
            });
      }
    } finally {
      gc.dispose();
    }
    tree.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fitNameColumn();
          }
        });
  }

  /** Gives the Name column the tree's width less Size and Modified, down to its header's width. */
  private void fitNameColumn() {
    if (nameMinimum == 0) {
      nameMinimum = columns[0].getWidth();
    }
    int rest = tree.getClientArea().width - columns[1].getWidth() - columns[2].getWidth();
    columns[0].setWidth(Math.max(nameMinimum, rest));
  }

  /** Widens Size and Modified to fit the rows' texts; they never narrow while the dock lives. */
  void widen(List<WorkspaceNode> rows) {
    boolean wider = false;
    GC gc = new GC(tree);
    try {
      for (WorkspaceNode row : rows) {
        wider |= widen(columns[1], gc, row.size());
        wider |= widen(columns[2], gc, row.modified());
      }
    } finally {
      gc.dispose();
    }
    if (wider) {
      fitNameColumn();
    }
  }

  private static boolean widen(TreeColumn column, GC gc, String text) {
    int width = text.isEmpty() ? 0 : gc.textExtent(text).x + CELL_PADDING;
    if (width <= column.getWidth()) {
      return false;
    }
    column.setWidth(width);
    return true;
  }

  /** Shows a model's sort column and direction in the header; null shows none. */
  void showSort(WorkspaceBrowseInput browse) {
    if (browse == null) {
      tree.setSortColumn(null);
      return;
    }
    tree.setSortColumn(columns[browse.sortColumn().ordinal()]);
    tree.setSortDirection(browse.descending() ? SWT.DOWN : SWT.UP);
  }
}
