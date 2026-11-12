package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Sets a workspace browser tree's tool tip to the entry under the pointer: its full path, then
 * "Size:" for a file, then "Modified:" with the date and time, one per line. The text comes from
 * the row's {@link WorkspaceNode}, so no file system call runs. The tip is set only when the
 * pointer moves to another row or off the rows, so it does not flicker while the pointer moves
 * within a row.
 */
final class BrowserRowTip {
  private final Tree tree;
  private WorkspaceNode shown;

  /**
   * Starts setting the tree's tool tip.
   *
   * @param tree the browser tree; each item's data is its {@link WorkspaceNode}
   */
  BrowserRowTip(Tree tree) {
    this.tree = tree;
    Listener listener =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            pointerAt(event.type == SWT.MouseExit ? null : new Point(event.x, event.y));
          }
        };
    tree.addListener(SWT.MouseMove, listener);
    tree.addListener(SWT.MouseExit, listener);
  }

  /**
   * Sets the tip for the row at a point, unless that row's tip is already set.
   *
   * @param at the point in the tree, or null when the pointer left it
   */
  void pointerAt(Point at) {
    TreeItem item = at == null ? null : tree.getItem(at);
    WorkspaceNode node = item != null && item.getData() instanceof WorkspaceNode row ? row : null;
    if (node == shown) {
      return;
    }
    shown = node;
    tree.setToolTipText(node == null ? null : text(node));
  }

  /**
   * Returns a row's tip.
   *
   * @param node the row
   * @return the path, then the labeled size and modified date and time, one per line, leaving out
   *     empty ones; null for a row that is not an entry
   */
  static String text(WorkspaceNode node) {
    switch (node.kind()) {
      case FILE, DIRECTORY, CYCLE:
        break;
      default:
        return null;
    }
    StringBuilder text = new StringBuilder(node.path().toString());
    if (!node.size().isEmpty()) {
      text.append('\n').append(UiText.WORKSPACE_TIP_SIZE.format(node.size()));
    }
    if (!node.modifiedTime().isEmpty()) {
      text.append('\n').append(UiText.WORKSPACE_TIP_MODIFIED.format(node.modifiedTime()));
    }
    return text.toString();
  }
}
