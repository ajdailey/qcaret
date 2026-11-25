package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.OutlineEntry;
import io.github.ajdailey.qcaret.app.view.OutlineInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.AccessibleName;
import io.github.ajdailey.qcaret.ui.swt.platform.ChromeColors;
import io.github.ajdailey.qcaret.ui.swt.platform.NativeNames;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * The Outline dock: Hide and a summary, wrapped within the dock ({@link DockBarLayout}), over a
 * {@link Tree} of an {@link OutlineInput}'s headings, every level expanded. A click on a heading's
 * text goes to it and keeps the focus in the tree; Enter and a double click go to it and focus the
 * editor. Arrows and type-ahead move the selection only, as the platform's tree does. A refresh
 * creates the missing items in slices of {@value #SLICE_ITEMS}, one per UI turn, so a long heading
 * list never holds the UI thread; a newer refresh abandons the running fill. The window's {@link
 * ChromeColors} color the tree under the theme.
 */
final class OutlineDock {
  /** Items created per UI turn. */
  static final int SLICE_ITEMS = 500;

  /** Items removed one by one at most; a larger cut clears the tree, as GTK removes one by one. */
  static final int REMOVE_ITEMS = 2000;

  private final Composite root;
  private final Label summary;
  private final Tree tree;
  private final UiDispatcher ui;
  private OutlineInput input;
  private List<OutlineEntry> entries = List.of();
  private TreeItem[] items = new TreeItem[0];
  private int filled;
  private long generation;
  private int selected = -1;
  private int slices;
  private int created;
  private int[] plan = new int[0];
  private int[] planAt = new int[0];

  /**
   * Creates the dock.
   *
   * @param parent the window's outline column
   * @param ui runs the fill's later slices
   */
  OutlineDock(Composite parent, UiDispatcher ui) {
    this.ui = ui;
    root = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    root.setLayout(layout);
    Composite bar = new Composite(root, SWT.NONE);
    bar.setLayout(new DockBarLayout());
    bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Button hide = new Button(bar, SWT.PUSH);
    hide.setText(UiText.OUTLINE_HIDE.text());
    hide.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (input != null) {
              input.hide();
            }
          }
        });
    summary = new Label(bar, SWT.WRAP);
    tree = new Tree(root, SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
    tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    AccessibleName.attachKeepingRole(tree, UiText.ACCESSIBLE_OUTLINE.text());
    NativeNames.PLATFORM.name(tree, UiText.ACCESSIBLE_OUTLINE.text());
    tree.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              Point point = new Point(event.x, event.y);
              TreeItem item = tree.getItem(point);
              if (item != null && item.getBounds().contains(point)) {
                go(item, false);
              }
            }
          }
        });
    tree.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.item instanceof TreeItem item) {
              go(item, true);
            }
          }
        });
  }

  private void go(TreeItem item, boolean focus) {
    if (input != null && item.getData() instanceof Integer index) {
      input.goTo(index, focus);
    }
  }

  /**
   * Shows a model.
   *
   * @param value the model
   */
  void setInput(OutlineInput value) {
    input = value;
    refresh();
  }

  /**
   * Follows the model's entries and summary. Items before the first entry whose parent changed are
   * kept, with their expansion, and take the new text; the rest are created again.
   */
  void refresh() {
    if (input == null || tree.isDisposed()) {
      return;
    }
    summary.setText(input.summary());
    root.layout(new Control[] {summary});
    List<OutlineEntry> next = input.entries();
    int keep = 0;
    int same = Math.min(filled == plan.length ? items.length : created, next.size());
    while (keep < same && next.get(keep).parent() == entries.get(keep).parent()) {
      keep++;
    }
    generation++;
    tree.setRedraw(false);
    try {
      if (items.length - keep > REMOVE_ITEMS) {
        tree.removeAll();
        keep = 0;
      } else {
        for (int i = items.length - 1; i >= keep; i--) {
          if (items[i] != null && !items[i].isDisposed()) {
            items[i].dispose();
          }
        }
      }
      for (int i = 0; i < keep; i++) {
        if (!next.get(i).text().equals(entries.get(i).text())) {
          items[i].setText(label(next.get(i)));
        }
      }
    } finally {
      tree.setRedraw(true);
    }
    TreeItem[] nextItems = new TreeItem[next.size()];
    System.arraycopy(items, 0, nextItems, 0, keep);
    items = nextItems;
    entries = next;
    created = keep;
    plan(keep);
    filled = 0;
    selected = input.selected();
    slices = 0;
    fill(generation);
  }

  private static String label(OutlineEntry entry) {
    return entry.text().isEmpty() ? UiText.OUTLINE_EMPTY_HEADING.text() : entry.text();
  }

  /**
   * Orders the creation of the entries from {@code keep} on. GTK appends a row by walking its
   * siblings, so a long sibling list built by appends is quadratic; each parent's new children are
   * created last first, each inserted after the parent's kept children, which for a parent without
   * kept children is a prepend. A parent comes before its children.
   */
  private void plan(int keep) {
    int n = entries.size();
    // Per parent, slot parent + 1: kept children, then the first new child and the new count.
    int[] kept = new int[n + 1];
    int[] count = new int[n + 1];
    for (int i = 0; i < n; i++) {
      int slot = entries.get(i).parent() + 1;
      if (i < keep) {
        kept[slot]++;
      } else {
        count[slot]++;
      }
    }
    int[] first = new int[n + 2];
    for (int slot = 0; slot <= n; slot++) {
      first[slot + 1] = first[slot] + count[slot];
    }
    int[] children = new int[n - keep];
    int[] put = first.clone();
    for (int i = keep; i < n; i++) {
      children[put[entries.get(i).parent() + 1]++] = i;
    }
    plan = new int[n - keep];
    planAt = new int[n - keep];
    int[] parents = new int[n - keep + 1];
    int queued = 0;
    for (int slot = 0; slot <= keep; slot++) {
      if (count[slot] > 0) {
        parents[queued++] = slot;
      }
    }
    int planned = 0;
    for (int head = 0; head < queued; head++) {
      int slot = parents[head];
      for (int c = first[slot + 1] - 1; c >= first[slot]; c--) {
        int child = children[c];
        plan[planned] = child;
        planAt[planned++] = kept[slot];
        if (count[child + 1] > 0) {
          parents[queued++] = child + 1;
        }
      }
    }
  }

  /** Creates the next slice of items, then queues the rest. */
  private void fill(final long fill) {
    if (fill != generation || tree.isDisposed()) {
      return;
    }
    int end = Math.min(filled + SLICE_ITEMS, plan.length);
    tree.setRedraw(false);
    try {
      for (int k = filled; k < end; k++) {
        int i = plan[k];
        OutlineEntry entry = entries.get(i);
        TreeItem item =
            entry.parent() < 0
                ? new TreeItem(tree, SWT.NONE, planAt[k])
                : new TreeItem(items[entry.parent()], SWT.NONE, planAt[k]);
        item.setText(label(entry));
        item.setData(Integer.valueOf(i));
        items[i] = item;
      }
    } finally {
      tree.setRedraw(true);
    }
    filled = end;
    slices++;
    if (filled == plan.length) {
      expand();
      if (selected >= 0) {
        showSelected();
      } else {
        tree.deselectAll();
      }
    } else {
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              fill(fill);
            }
          });
    }
  }

  /**
   * Expands the parents of the items this fill created, and a kept item that took its first
   * children. Rows fill collapsed and expand once at the end.
   */
  private void expand() {
    tree.setRedraw(false);
    try {
      for (int i = Math.max(created, 1); i < items.length; i++) {
        // A first child directly follows its parent.
        if (entries.get(i).parent() == i - 1) {
          items[i - 1].setExpanded(true);
        }
      }
    } finally {
      tree.setRedraw(true);
    }
  }

  /**
   * Selects the entry holding the caret, once the fill finished.
   *
   * @param index the entry's index, or -1 for none
   */
  void select(int index) {
    if (tree.isDisposed()) {
      return;
    }
    selected = index;
    if (index < 0 || index >= entries.size()) {
      tree.deselectAll();
    } else if (filled == plan.length) {
      showSelected();
    }
  }

  private void showSelected() {
    TreeItem item = items[selected];
    if (tree.getSelectionCount() != 1 || tree.getSelection()[0] != item) {
      tree.setSelection(item);
    }
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
   * Returns the summary label, for tests.
   *
   * @return the label
   */
  Label summary() {
    return summary;
  }

  /**
   * Reports whether every entry has its item.
   *
   * @return true once the fill finished
   */
  boolean filled() {
    return filled == plan.length;
  }

  /**
   * Counts the slices the last refresh took, for tests.
   *
   * @return the count
   */
  int slices() {
    return slices;
  }
}
