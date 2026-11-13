package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceBrowseInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * The workspace browser dock: a wrapping row of icon tool bars ({@link BrowseBar}'s four, then
 * Refresh, Reveal Active File and Hide) and the {@link BrowseBar}'s fields over a {@link Tree} of a
 * {@link WorkspaceInput}'s rows with Name, Size and Modified columns ({@link BrowserColumns});
 * Modified shows the date. A row's tool tip is its {@link BrowserRowTip}, and a drag of the
 * selected files is a {@link BrowserDrag}. A click on a column header sorts by it, a second click
 * reverses the order. A directory row holds an empty placeholder child until it first expands,
 * which asks the input to list it; its rows replace the placeholder when the input reports the
 * change. A refill keeps the expanded state of rows that remain. The tree selects several rows
 * ({@link BrowserSelection}). Enter or a double click opens a file, shows more entries on a "more"
 * row, and expands or collapses a directory, for the focused row. One context menu, built on the
 * first right click, offers {@link WorkspaceSelectionInput#MENU} for the selected rows, or for the
 * workspace folder over empty space; {@link WorkspaceSelectionInput} enables and runs them. Delete
 * and F2 delete and rename the selected rows while the tree has focus; the editor never sees them.
 */
final class WorkspaceDock {
  private final Composite root;
  private final Composite buttons;
  private final ToolIcons icons;
  private final BrowseBar browseBar;
  private final Tree tree;
  private final BrowserRowTip rowTip;
  private final BrowserDrag drag;
  private final BrowserSelection selection;
  private final BrowserColumns columns;
  private Menu menu;
  private WorkspaceInput input;

  /** What a button does. */
  private enum Action {
    REFRESH,
    REVEAL,
    HIDE
  }

  /**
   * Creates the dock.
   *
   * @param parent the window's side area
   * @param inkSource the main toolbar, whose drawn background sets the icons' ink
   */
  WorkspaceDock(Composite parent, Control inkSource) {
    root = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    root.setLayout(layout);
    Composite bar = new Composite(root, SWT.NONE);
    buttons = bar;
    RowLayout barLayout = new RowLayout(SWT.HORIZONTAL);
    barLayout.wrap = true;
    barLayout.spacing = 2;
    bar.setLayout(barLayout);
    bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    icons = new ToolIcons(bar, inkSource);
    browseBar = new BrowseBar(root, bar, icons);
    button(bar, ToolIcons.RELOAD, UiText.WORKSPACE_REFRESH, Action.REFRESH);
    button(bar, ToolIcons.REVEAL, UiText.WORKSPACE_REVEAL, Action.REVEAL);
    button(bar, ToolIcons.HIDE, UiText.WORKSPACE_HIDE, Action.HIDE);
    tree = new Tree(root, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
    tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    tree.setHeaderVisible(true);
    columns = new BrowserColumns(this, tree);
    rowTip = new BrowserRowTip(tree);
    drag = new BrowserDrag(tree);
    selection = new BrowserSelection(tree);
    tree.addListener(
        SWT.Expand,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            expanded((TreeItem) event.item);
          }
        });
    tree.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            TreeItem item = (TreeItem) event.item;
            WorkspaceNode node = item == null ? null : node(item);
            if (node == null || input == null) {
              return;
            }
            if (node.expands()) {
              boolean open = !item.getExpanded();
              item.setExpanded(open);
              if (open) {
                expanded(item);
              }
            } else {
              input.activate(node);
            }
          }
        });
    tree.addListener(
        SWT.MenuDetect,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.MENU_MOUSE) {
              TreeItem item = tree.getItem(tree.toControl(event.x, event.y));
              if (item == null) {
                tree.deselectAll();
              }
              selection.focus(item);
            }
            showMenu();
          }
        });
    tree.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if ((event.stateMask & SWT.MODIFIER_MASK) == 0
                && (event.keyCode == SWT.DEL || event.keyCode == SWT.F2)) {
              event.doit = false;
              run(
                  event.keyCode == SWT.DEL
                      ? WorkspaceSelectionInput.Action.DELETE
                      : WorkspaceSelectionInput.Action.RENAME);
            }
          }
        });
  }

  /**
   * Sorts by a column, reversing the order when it already sorts by it.
   *
   * @param column the column
   */
  void sort(WorkspaceBrowseInput.Column column) {
    WorkspaceBrowseInput browse = input == null ? null : input.browse();
    if (browse != null) {
      browse.sort(column, browse.sortColumn() == column && !browse.descending());
    }
  }

  private void button(Composite parent, String icon, UiText label, final Action action) {
    ToolItem item = icons.item(parent, icon, label.text());
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            act(action);
          }
        });
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
   * Returns the tree, for tests.
   *
   * @return the tree
   */
  Tree tree() {
    return tree;
  }

  /**
   * Returns the rows' tool tip, for tests.
   *
   * @return the tip
   */
  BrowserRowTip rowTip() {
    return rowTip;
  }

  /**
   * Returns the tree's selection, for tests.
   *
   * @return the selection
   */
  BrowserSelection selection() {
    return selection;
  }

  /**
   * Returns the tree's drag source, for tests.
   *
   * @return the drag
   */
  BrowserDrag drag() {
    return drag;
  }

  /** Re-reads the button row's background and re-inks the icons; see {@link ToolIcons#reink}. */
  void reink() {
    icons.reink();
  }

  /**
   * Returns the row of icon tool bars.
   *
   * @return the row
   */
  Composite buttons() {
    return buttons;
  }

  /**
   * Returns the navigation and filter rows, for tests.
   *
   * @return the rows
   */
  BrowseBar bar() {
    return browseBar;
  }

  /**
   * Shows a model.
   *
   * @param workspace the model
   */
  void setInput(WorkspaceInput workspace) {
    input = workspace;
    browseBar.setInput(workspace == null ? null : workspace.browse());
    rebuild();
  }

  /** Rebuilds the whole tree from the root's rows. */
  void rebuild() {
    if (tree.isDisposed()) {
      return;
    }
    browseBar.update(input == null ? UiText.WORKSPACE_NONE.text() : input.title());
    columns.showSort(input == null ? null : input.browse());
    WorkspaceNode top = input == null ? null : input.rootNode();
    tree.setRedraw(false);
    try {
      Set<WorkspaceNode> open = expandedNodes(tree.getItems());
      tree.removeAll();
      if (top != null) {
        fill(null, input.children(top), open);
      }
    } finally {
      tree.setRedraw(true);
    }
  }

  /**
   * Refills a directory's rows wherever the tree shows them.
   *
   * @param directory the directory, or null for the whole tree
   */
  void changed(WorkspaceNode directory) {
    if (tree.isDisposed() || input == null) {
      return;
    }
    WorkspaceNode top = input.rootNode();
    if (directory == null || top == null) {
      rebuild();
      return;
    }
    if (directory.equals(top)) {
      tree.setRedraw(false);
      try {
        Set<WorkspaceNode> open = expandedNodes(tree.getItems());
        tree.removeAll();
        fill(null, input.children(top), open);
      } finally {
        tree.setRedraw(true);
      }
      return;
    }
    TreeItem item = find(tree.getItems(), directory);
    if (item != null && !isPlaceholder(item)) {
      refill(item);
    }
  }

  /** Reports whether a directory row holds only its placeholder. */
  private static boolean isPlaceholder(TreeItem item) {
    TreeItem[] children = item.getItems();
    return children.length == 1 && children[0].getData() == null;
  }

  private void refill(TreeItem item) {
    tree.setRedraw(false);
    try {
      Set<WorkspaceNode> open = expandedNodes(item.getItems());
      for (TreeItem child : item.getItems()) {
        child.dispose();
      }
      fill(item, input.children(node(item)), open);
    } finally {
      tree.setRedraw(true);
    }
  }

  /** Adds rows under a parent row, or at the top for null; re-expands the rows in {@code open}. */
  private void fill(TreeItem parent, List<WorkspaceNode> rows, Set<WorkspaceNode> open) {
    columns.widen(rows);
    for (WorkspaceNode row : rows) {
      TreeItem item =
          parent == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parent, SWT.NONE);
      item.setText(
          new String[] {
            row.kind() == WorkspaceNode.Kind.DIRECTORY ? row.name() + "/" : row.name(),
            row.size(),
            row.modified()
          });
      item.setData(row);
      if (!row.expands()) {
        continue;
      }
      List<WorkspaceNode> children = input.children(row);
      if (open.contains(row) && !children.isEmpty()) {
        fill(item, children, open);
        item.setExpanded(true);
      } else {
        new TreeItem(item, SWT.NONE);
      }
    }
  }

  private static Set<WorkspaceNode> expandedNodes(TreeItem[] items) {
    Set<WorkspaceNode> open = new LinkedHashSet<WorkspaceNode>();
    collectExpanded(items, open);
    return open;
  }

  private static void collectExpanded(TreeItem[] items, Set<WorkspaceNode> open) {
    for (TreeItem item : items) {
      if (item.getExpanded() && item.getData() instanceof WorkspaceNode node) {
        open.add(node);
        collectExpanded(item.getItems(), open);
      }
    }
  }

  private static TreeItem find(TreeItem[] items, WorkspaceNode node) {
    for (TreeItem item : items) {
      Object data = item.getData();
      if (node.equals(data)) {
        return item;
      }
      if (data instanceof WorkspaceNode row
          && row.expands()
          && node.path().startsWith(row.path())
          && !isPlaceholder(item)) {
        TreeItem found = find(item.getItems(), node);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** Asks the input to list a directory row that expanded for the first time. */
  private void expanded(TreeItem item) {
    WorkspaceNode node = node(item);
    if (input == null || node == null || !node.expands()) {
      return;
    }
    if (isPlaceholder(item)) {
      input.expand(node);
      refill(item);
    }
  }

  /**
   * Expands each directory of a chain and selects its last row.
   *
   * @param chain directories below the root, then the row to select
   */
  void reveal(List<WorkspaceNode> chain) {
    TreeItem[] level = tree.getItems();
    TreeItem item = null;
    for (WorkspaceNode node : chain) {
      item = null;
      for (TreeItem candidate : level) {
        if (node.equals(candidate.getData())) {
          item = candidate;
          break;
        }
      }
      if (item == null) {
        return;
      }
      if (node.expands()) {
        if (isPlaceholder(item)) {
          refill(item);
        }
        item.setExpanded(true);
        level = item.getItems();
      }
    }
    if (item != null) {
      tree.setSelection(item);
      tree.showItem(item);
    }
  }

  private static WorkspaceNode node(TreeItem item) {
    return item.getData() instanceof WorkspaceNode node ? node : null;
  }

  /** Shows the one context menu for the selected rows, building it on first use. */
  private void showMenu() {
    if (menu == null) {
      menu = new Menu(tree);
      for (WorkspaceSelectionInput.Action action : WorkspaceSelectionInput.MENU) {
        if (action == null) {
          new MenuItem(menu, SWT.SEPARATOR);
        } else {
          menuItem(action);
        }
      }
      tree.setMenu(menu);
    }
    WorkspaceSelectionInput rows = input == null ? null : input.selection();
    for (MenuItem item : menu.getItems()) {
      if (item.getData() instanceof WorkspaceSelectionInput.Action action) {
        item.setEnabled(
            rows != null && rows.enabled(action, selection.nodes(), selection.focused()));
      }
    }
  }

  private void menuItem(final WorkspaceSelectionInput.Action action) {
    MenuItem item = new MenuItem(menu, SWT.PUSH);
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

  /** Runs a row action on the selected rows, or on the workspace folder without a selection. */
  private void run(WorkspaceSelectionInput.Action action) {
    if (input != null) {
      input.selection().run(action, selection.nodes(), selection.focused());
    }
  }

  private void act(Action action) {
    if (input == null) {
      return;
    }
    switch (action) {
      case REFRESH -> input.refresh();
      case REVEAL -> input.revealActive();
      case HIDE -> input.hide();
    }
  }

  /**
   * Presses a button, or selects the row of a node, or none, and runs a context menu item, for
   * tests.
   *
   * @param node the row, or null for a button or the workspace folder
   * @param label the button's or menu item's label
   */
  void press(WorkspaceNode node, UiText label) {
    for (Action action : Action.values()) {
      if (labelOf(action) == label) {
        act(action);
      }
    }
    for (WorkspaceSelectionInput.Action action : WorkspaceSelectionInput.Action.values()) {
      if (action.label() == label) {
        TreeItem item = node == null ? null : find(tree.getItems(), node);
        if (item == null) {
          tree.deselectAll();
        } else {
          tree.setSelection(item);
        }
        run(action);
      }
    }
  }

  private static UiText labelOf(Action action) {
    return switch (action) {
      case REFRESH -> UiText.WORKSPACE_REFRESH;
      case REVEAL -> UiText.WORKSPACE_REVEAL;
      case HIDE -> UiText.WORKSPACE_HIDE;
    };
  }

  /**
   * Returns the Size and Modified texts of a row under the top level, for tests.
   *
   * @param name the row's Name text
   * @return the two texts, or null when no such row shows
   */
  List<String> columnTexts(String name) {
    for (TreeItem item : tree.getItems()) {
      if (item.getText().equals(name)) {
        return List.of(item.getText(1), item.getText(2));
      }
    }
    return null;
  }

  /**
   * Returns the row texts under a directory row as shown, for tests.
   *
   * @param directory the directory, or null for the top level
   * @return the texts; empty when the row is not shown
   */
  List<String> texts(WorkspaceNode directory) {
    TreeItem[] items;
    if (directory == null) {
      items = tree.getItems();
    } else {
      TreeItem item = find(tree.getItems(), directory);
      if (item == null) {
        return List.of();
      }
      items = item.getItems();
    }
    String[] texts = new String[items.length];
    for (int i = 0; i < items.length; i++) {
      texts[i] = items[i].getText();
    }
    return List.of(texts);
  }

  /**
   * Returns the expanded rows.
   *
   * @return the directories, parents first
   */
  List<WorkspaceNode> expanded() {
    return new ArrayList<WorkspaceNode>(expandedNodes(tree.getItems()));
  }

  /**
   * Expands a directory row as a click on its arrow would.
   *
   * @param directory the row
   */
  void expand(WorkspaceNode directory) {
    TreeItem item = find(tree.getItems(), directory);
    if (item != null) {
      item.setExpanded(true);
      expanded(item);
    }
  }
}
