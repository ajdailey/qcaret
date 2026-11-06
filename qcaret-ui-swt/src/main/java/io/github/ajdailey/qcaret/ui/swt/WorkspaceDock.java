package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * The workspace browser dock: the folder and three buttons (Refresh, Reveal Active File, Hide) over
 * a {@link Tree} of a {@link WorkspaceInput}'s rows. A directory row holds an empty placeholder
 * child until it first expands, which asks the input to list it; its rows replace the placeholder
 * when the input reports the change. A refill keeps the expanded state of rows that remain. Enter
 * or a double click opens a file, shows more entries on a "more" row, and expands or collapses a
 * directory. One context menu, built on the first right click, offers Open, Open in Default
 * Application, Set as Workspace Folder and Copy Path for the row under the pointer.
 */
final class WorkspaceDock {
  private final Composite root;
  private final Label title;
  private final Tree tree;
  private Menu menu;
  private WorkspaceInput input;

  /** What a button or menu item does. */
  private enum Action {
    REFRESH,
    REVEAL,
    HIDE,
    OPEN,
    OPEN_DEFAULT,
    SET_FOLDER,
    COPY_PATH
  }

  /**
   * Creates the dock.
   *
   * @param parent the window's side area
   */
  WorkspaceDock(Composite parent) {
    root = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    root.setLayout(layout);
    Composite bar = new Composite(root, SWT.NONE);
    GridLayout barLayout = new GridLayout(3, false);
    barLayout.marginHeight = 2;
    barLayout.marginWidth = 0;
    barLayout.horizontalSpacing = 4;
    bar.setLayout(barLayout);
    bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    button(bar, UiText.WORKSPACE_REFRESH, Action.REFRESH);
    button(bar, UiText.WORKSPACE_REVEAL, Action.REVEAL);
    button(bar, UiText.WORKSPACE_HIDE, Action.HIDE);
    title = new Label(root, SWT.NONE);
    title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    tree = new Tree(root, SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
    tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
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
            showMenu();
          }
        });
  }

  private void button(Composite parent, UiText label, final Action action) {
    Button button = new Button(parent, SWT.PUSH);
    button.setText(label.text());
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            act(action, selected());
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
   * Returns the folder line, for tests.
   *
   * @return the text
   */
  String title() {
    return title.getText();
  }

  /**
   * Shows a model.
   *
   * @param workspace the model
   */
  void setInput(WorkspaceInput workspace) {
    input = workspace;
    rebuild();
  }

  /** Rebuilds the whole tree from the root's rows. */
  void rebuild() {
    if (tree.isDisposed()) {
      return;
    }
    title.setText(input == null ? "" : input.title());
    title.setToolTipText(title.getText());
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
    for (WorkspaceNode row : rows) {
      TreeItem item =
          parent == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parent, SWT.NONE);
      item.setText(row.kind() == WorkspaceNode.Kind.DIRECTORY ? row.name() + "/" : row.name());
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

  private WorkspaceNode selected() {
    TreeItem[] selection = tree.getSelection();
    return selection.length == 0 ? null : node(selection[0]);
  }

  /** Shows the one context menu for the selected row, building it on first use. */
  private void showMenu() {
    if (menu == null) {
      menu = new Menu(tree);
      menuItem(UiText.WORKSPACE_OPEN, Action.OPEN);
      menuItem(UiText.WORKSPACE_OPEN_DEFAULT, Action.OPEN_DEFAULT);
      menuItem(UiText.WORKSPACE_SET_FOLDER, Action.SET_FOLDER);
      menuItem(UiText.WORKSPACE_COPY_PATH, Action.COPY_PATH);
      tree.setMenu(menu);
    }
    WorkspaceNode node = selected();
    boolean file = node != null && node.kind() == WorkspaceNode.Kind.FILE;
    boolean directory = node != null && node.expands();
    MenuItem[] items = menu.getItems();
    items[0].setEnabled(file);
    items[1].setEnabled(file);
    items[2].setEnabled(directory);
    items[3].setEnabled(file || directory);
  }

  private void menuItem(UiText label, final Action action) {
    MenuItem item = new MenuItem(menu, SWT.PUSH);
    item.setText(label.text());
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            act(action, selected());
          }
        });
  }

  private void act(Action action, WorkspaceNode node) {
    if (input == null) {
      return;
    }
    switch (action) {
      case REFRESH -> input.refresh();
      case REVEAL -> input.revealActive();
      case HIDE -> input.hide();
      default -> {
        if (node != null) {
          actOn(action, node);
        }
      }
    }
  }

  private void actOn(Action action, WorkspaceNode node) {
    switch (action) {
      case OPEN -> input.activate(node);
      case OPEN_DEFAULT -> input.openInDefaultApplication(node);
      case SET_FOLDER -> input.setWorkspace(node);
      case COPY_PATH -> input.copyPath(node);
      default -> {
        // The buttons act without a row.
      }
    }
  }

  /**
   * Selects the row of a node and presses a button or runs a context menu item, for tests.
   *
   * @param node the row, or null for a button
   * @param label the button's or menu item's label
   */
  void press(WorkspaceNode node, UiText label) {
    TreeItem item = node == null ? null : find(tree.getItems(), node);
    if (item != null) {
      tree.setSelection(item);
    }
    for (Action action : Action.values()) {
      if (labelOf(action) == label) {
        act(action, node);
      }
    }
  }

  private static UiText labelOf(Action action) {
    return switch (action) {
      case REFRESH -> UiText.WORKSPACE_REFRESH;
      case REVEAL -> UiText.WORKSPACE_REVEAL;
      case HIDE -> UiText.WORKSPACE_HIDE;
      case OPEN -> UiText.WORKSPACE_OPEN;
      case OPEN_DEFAULT -> UiText.WORKSPACE_OPEN_DEFAULT;
      case SET_FOLDER -> UiText.WORKSPACE_SET_FOLDER;
      case COPY_PATH -> UiText.WORKSPACE_COPY_PATH;
    };
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
