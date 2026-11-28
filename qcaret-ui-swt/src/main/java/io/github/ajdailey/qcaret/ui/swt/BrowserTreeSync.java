package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Brings a workspace browser tree's rows in line with a {@link WorkspaceInput}'s rows in place. A
 * row whose node remains keeps its item, and with it its expansion, selection and scroll place, and
 * takes the new Size and Modified texts; a row that went is disposed and a new one is inserted
 * where it lists. An expanded directory's rows are brought in line the same way. A selected row
 * that went leaves the selection on a row with its path at its level, else the row listed at its
 * place, else the last row of its level, else its parent row. The sync sets no scroll position: the
 * tree keeps a remaining top row on top. A sync that changes nothing touches no item and holds no
 * redraw. Call on the UI thread.
 */
final class BrowserTreeSync {
  private final Tree tree;
  private final BrowserColumns columns;
  private boolean held;

  /**
   * A selected row before the sync: its item and node, its parent row and its place in its level.
   */
  private record Selected(TreeItem item, WorkspaceNode node, TreeItem parent, int index) {}

  /**
   * Creates the sync of a browser tree.
   *
   * @param tree the tree; each row's data is its {@link WorkspaceNode}, a placeholder row has none
   * @param columns widens Size and Modified to the rows added
   */
  BrowserTreeSync(Tree tree, BrowserColumns columns) {
    this.tree = tree;
    this.columns = columns;
  }

  /**
   * Brings the rows under a parent in line with the input's rows.
   *
   * @param workspace the rows' source
   * @param parent the parent row, or null for the top level
   * @param rows the rows to show
   * @param expanded the directories to expand where a new row lists one
   */
  void sync(
      WorkspaceInput workspace,
      TreeItem parent,
      List<WorkspaceNode> rows,
      Set<WorkspaceNode> expanded) {
    held = false;
    List<Selected> selected = selected();
    TreeItem top = tree.getTopItem();
    int topY = top == null ? 0 : top.getBounds().y;
    try {
      level(workspace, expanded, parent, rows);
    } finally {
      if (held) {
        tree.setRedraw(true);
      }
    }
    if (!held) {
      return;
    }
    // After the hold: cocoa applies held item changes to the outline view when redraw resumes.
    keepSelection(selected);
    // The tree keeps its scroll offset, so a row added or removed above moves the top row. GTK 3
    // measures a new row after this turn: the top row reads at its old place here and moves later.
    if (top != null && !top.isDisposed() && top.getBounds().y != topY) {
      tree.setTopItem(top);
    }
  }

  private void hold() {
    if (!held) {
      held = true;
      tree.setRedraw(false);
    }
  }

  private void level(
      WorkspaceInput input, Set<WorkspaceNode> open, TreeItem parent, List<WorkspaceNode> rows) {
    TreeItem[] old = parent == null ? tree.getItems() : parent.getItems();
    Map<WorkspaceNode, Integer> at = new HashMap<WorkspaceNode, Integer>();
    for (int i = 0; i < old.length; i++) {
      if (old[i].getData() instanceof WorkspaceNode node) {
        at.put(node, i);
      }
    }
    columns.widen(rows);
    int next = 0;
    for (int index = 0; index < rows.size(); index++) {
      WorkspaceNode row = rows.get(index);
      Integer found = at.get(row);
      if (found != null && found >= next) {
        for (int i = next; i < found; i++) {
          dispose(old[i]);
        }
        keep(input, open, old[found], row);
        next = found + 1;
      } else {
        add(input, open, parent, index, row);
      }
    }
    for (int i = next; i < old.length; i++) {
      dispose(old[i]);
    }
  }

  private void dispose(TreeItem item) {
    hold();
    item.dispose();
  }

  private void keep(
      WorkspaceInput input, Set<WorkspaceNode> open, TreeItem item, WorkspaceNode row) {
    String[] texts = texts(row);
    for (int i = 0; i < texts.length; i++) {
      if (!texts[i].equals(item.getText(i))) {
        hold();
        item.setText(texts);
        break;
      }
    }
    item.setData(row);
    if (!row.expands() || placeholder(item)) {
      return;
    }
    List<WorkspaceNode> children = input.children(row);
    if (children.isEmpty()) {
      hold();
      for (TreeItem child : item.getItems()) {
        child.dispose();
      }
      item.setExpanded(false);
      new TreeItem(item, SWT.NONE);
    } else {
      level(input, open, item, children);
    }
  }

  private void add(
      WorkspaceInput input,
      Set<WorkspaceNode> open,
      TreeItem parent,
      int index,
      WorkspaceNode row) {
    hold();
    TreeItem item =
        parent == null
            ? new TreeItem(tree, SWT.NONE, index)
            : new TreeItem(parent, SWT.NONE, index);
    item.setText(texts(row));
    item.setData(row);
    if (!row.expands()) {
      return;
    }
    List<WorkspaceNode> children = input.children(row);
    if (open.contains(row) && !children.isEmpty()) {
      columns.widen(children);
      for (int i = 0; i < children.size(); i++) {
        add(input, open, item, i, children.get(i));
      }
      item.setExpanded(true);
    } else {
      new TreeItem(item, SWT.NONE);
    }
  }

  private static String[] texts(WorkspaceNode row) {
    return new String[] {
      row.kind() == WorkspaceNode.Kind.DIRECTORY ? row.name() + "/" : row.name(),
      row.size(),
      row.modified()
    };
  }

  /**
   * Reports whether a directory row holds only its placeholder.
   *
   * @param item the row
   * @return true for a row never listed
   */
  static boolean placeholder(TreeItem item) {
    TreeItem[] children = item.getItems();
    return children.length == 1 && children[0].getData() == null;
  }

  private List<Selected> selected() {
    List<Selected> rows = new ArrayList<Selected>();
    for (TreeItem item : tree.getSelection()) {
      TreeItem parent = item.getParentItem();
      int index = parent == null ? tree.indexOf(item) : parent.indexOf(item);
      WorkspaceNode node = item.getData() instanceof WorkspaceNode data ? data : null;
      rows.add(new Selected(item, node, parent, index));
    }
    return rows;
  }

  /** Selects each row selected before the sync, or the row nearest a selected row that went. */
  private void keepSelection(List<Selected> before) {
    List<TreeItem> wanted = new ArrayList<TreeItem>();
    for (Selected row : before) {
      TreeItem item = row.item().isDisposed() ? nearest(row) : row.item();
      if (item != null && !wanted.contains(item)) {
        wanted.add(item);
      }
    }
    TreeItem[] shown = tree.getSelection();
    boolean same = shown.length == wanted.size();
    for (int i = 0; same && i < shown.length; i++) {
      same = wanted.contains(shown[i]);
    }
    if (same) {
      return;
    }
    tree.setSelection(wanted.toArray(new TreeItem[0]));
  }

  /**
   * Returns the row for a selected row that went: a row with its path at its level, else the row
   * listed at its place, else the last row of its level, else its parent row.
   */
  private TreeItem nearest(Selected row) {
    TreeItem parent = row.parent();
    if (parent != null && parent.isDisposed()) {
      return null;
    }
    TreeItem[] level = parent == null ? tree.getItems() : parent.getItems();
    for (TreeItem item : level) {
      if (item.getData() instanceof WorkspaceNode node
          && row.node() != null
          && node.path().equals(row.node().path())) {
        return item;
      }
    }
    if (level.length == 0) {
      return parent;
    }
    TreeItem item = level[Math.min(row.index(), level.length - 1)];
    return item.getData() == null ? parent : item;
  }
}
