package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.ui.swt.platform.NativeNames;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * Names a toolbar's items for screen readers through the toolbar's {@code Accessible}: the item at
 * the event's child id is named by the title of the {@link CommandId} or {@link ToolMenu} in its
 * data. SWT asks this listener for the items on win32, where a child id is an item index. Cocoa
 * names an item by its text or tool tip. SWT on GTK does not consult Java listeners for the native
 * items, so {@link #attach} also names each item through {@link NativeNames}.
 */
final class ToolBarNames extends AccessibleAdapter {
  private static final String KEY = "qcaret.toolBarNames";

  private final ToolBar bar;

  private ToolBarNames(ToolBar bar) {
    this.bar = bar;
  }

  /**
   * Names a toolbar's items; call after the items are added.
   *
   * @param bar the toolbar
   * @return the listener
   */
  static ToolBarNames attach(ToolBar bar) {
    ToolBarNames names = new ToolBarNames(bar);
    bar.getAccessible().addAccessibleListener(names);
    bar.setData(KEY, names);
    for (ToolItem item : bar.getItems()) {
      if (item.getData() instanceof CommandId command) {
        NativeNames.PLATFORM.name(item, command.title());
      }
    }
    return names;
  }

  /**
   * Names an item added after {@link #attach} on GTK, where the listener is not consulted.
   *
   * @param item the item, its data a {@link ToolMenu} or {@link CommandId}
   * @param title the name
   */
  static void name(ToolItem item, String title) {
    NativeNames.PLATFORM.name(item, title);
  }

  /**
   * Returns the listener attached to a toolbar.
   *
   * @param bar the toolbar
   * @return the listener, or null when none is attached
   */
  static ToolBarNames of(ToolBar bar) {
    return bar.getData(KEY) instanceof ToolBarNames names ? names : null;
  }

  @Override
  public void getName(AccessibleEvent event) {
    // ACC.CHILDID_SELF, -1, names the toolbar itself.
    if (event.childID < 0 || event.childID >= bar.getItemCount()) {
      return;
    }
    ToolItem item = bar.getItem(event.childID);
    if (item.getData() instanceof CommandId command) {
      event.result = command.title();
    } else if (item.getData() instanceof ToolMenu menu) {
      event.result = menu.title();
    }
  }
}
