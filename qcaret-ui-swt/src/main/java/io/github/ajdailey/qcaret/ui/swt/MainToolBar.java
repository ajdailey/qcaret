package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import java.util.EnumMap;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The main toolbar's buttons: each runs its menu command, takes its enabled state from {@link
 * CommandStates}, and shows the command and its keys as the tool tip. A command missing an icon
 * shows its title. After a click that left the focus on the toolbar the focus returns to the
 * editor. Call on the UI thread.
 */
final class MainToolBar {
  /** The buttons in order; null is a separator. */
  static final CommandId[] COMMANDS = {
    CommandId.NEW,
    CommandId.OPEN,
    CommandId.SAVE,
    CommandId.SAVE_ALL,
    null,
    CommandId.UNDO,
    CommandId.REDO,
    null,
    CommandId.CUT,
    CommandId.COPY,
    CommandId.PASTE,
    null,
    CommandId.FIND,
    CommandId.REPLACE,
    CommandId.HYPERSEARCH,
    null,
    CommandId.ZOOM_IN,
    CommandId.ZOOM_OUT
  };

  private final ToolBar bar;
  private final ToolIcons icons;
  private final CommandStates states;
  private final Map<CommandId, ToolItem> items = new EnumMap<CommandId, ToolItem>(CommandId.class);

  /**
   * Fills a toolbar.
   *
   * @param bar the empty toolbar
   * @param icons the icon set
   * @param controller runs the commands
   * @param states the commands' enabled state
   * @param focusEditor returns the focus to the editor
   */
  MainToolBar(
      ToolBar bar,
      ToolIcons icons,
      final ApplicationController controller,
      CommandStates states,
      final Runnable focusEditor) {
    this.bar = bar;
    this.icons = icons;
    this.states = states;
    for (final CommandId command : COMMANDS) {
      if (command == null) {
        new ToolItem(bar, SWT.SEPARATOR);
        continue;
      }
      ToolItem item = new ToolItem(bar, SWT.PUSH);
      if (!icons.apply(item, MenuIcons.icon(command))) {
        item.setText(command.title());
      }
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              controller.execute(command);
              if (!MainToolBar.this.bar.isDisposed()) {
                sync();
                if (MainToolBar.this.bar.isFocusControl()) {
                  focusEditor.run();
                }
              }
            }
          });
      items.put(command, item);
    }
    sync();
  }

  /**
   * Sets each button's tool tip from the key bindings.
   *
   * @param keymap the bindings
   */
  void setKeymap(Keymap keymap) {
    for (Map.Entry<CommandId, ToolItem> entry : items.entrySet()) {
      CommandId command = entry.getKey();
      String keys = keymap.keysText(command);
      entry
          .getValue()
          .setToolTipText(keys.isEmpty() ? command.title() : command.title() + " (" + keys + ")");
    }
  }

  /** Enables each button from {@link CommandStates}. */
  void sync() {
    for (Map.Entry<CommandId, ToolItem> entry : items.entrySet()) {
      sync(entry.getKey(), entry.getValue());
    }
  }

  /** Enables the buttons of {@link CommandStates#EDIT_STATE}. */
  void syncEditState() {
    for (CommandId command : CommandStates.EDIT_STATE) {
      ToolItem item = items.get(command);
      if (item != null) {
        sync(command, item);
      }
    }
  }

  private void sync(CommandId command, ToolItem item) {
    boolean enabled = states.enabled(command);
    if (item.getEnabled() != enabled) {
      item.setEnabled(enabled);
      icons.showState(item);
    }
  }

  /**
   * Returns the toolbar.
   *
   * @return the widget
   */
  ToolBar bar() {
    return bar;
  }

  /**
   * Returns a command's button.
   *
   * @param command the command
   * @return the button, or null when the toolbar does not hold it
   */
  ToolItem item(CommandId command) {
    return items.get(command);
  }
}
