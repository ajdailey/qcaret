package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Reads a {@link SWT#MULTI} workspace browser tree's selected rows and its focused row: the row a
 * {@link SWT#Selection} last named (a click, a Ctrl/Cmd or Shift click, or a key), or the selected
 * row a context menu opened on. An unselected focused row falls back to the first selected.
 */
final class BrowserSelection {
  private final Tree tree;
  private TreeItem focus;

  /**
   * Follows a tree's selection events.
   *
   * @param tree the browser tree; each item's data is its {@link WorkspaceNode}
   */
  BrowserSelection(Tree tree) {
    this.tree = tree;
    tree.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.item instanceof TreeItem item) {
              focus = item;
            }
          }
        });
  }

  /**
   * Makes a selected row the focused one; does nothing for a row not selected.
   *
   * @param item the row, or null
   */
  void focus(TreeItem item) {
    if (item != null && selected(item)) {
      focus = item;
    }
  }

  /**
   * Returns the selected rows.
   *
   * @return their nodes, in tree order; empty when none is selected
   */
  List<WorkspaceNode> nodes() {
    List<WorkspaceNode> nodes = new ArrayList<WorkspaceNode>();
    for (TreeItem item : tree.getSelection()) {
      if (item.getData() instanceof WorkspaceNode node) {
        nodes.add(node);
      }
    }
    return nodes;
  }

  /**
   * Returns the focused row.
   *
   * @return its node, the first selected row's when the focused row is not selected, or null
   */
  WorkspaceNode focused() {
    if (focus != null && !focus.isDisposed() && selected(focus)) {
      return focus.getData() instanceof WorkspaceNode node ? node : null;
    }
    List<WorkspaceNode> nodes = nodes();
    return nodes.isEmpty() ? null : nodes.get(0);
  }

  private boolean selected(TreeItem item) {
    for (TreeItem selected : tree.getSelection()) {
      if (selected == item) {
        return true;
      }
    }
    return false;
  }
}
