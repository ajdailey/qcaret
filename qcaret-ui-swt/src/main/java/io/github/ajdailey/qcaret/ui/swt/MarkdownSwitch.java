package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The toolbar's {@code Source | Split | Preview} switch: a separator and three radio items at the
 * toolbar's end, present only while the selected tab is a Markdown tab. Each item runs its Markdown
 * View command; the checked one follows {@link CommandStates}. Call on the UI thread.
 */
final class MarkdownSwitch {
  /** The modes in toolbar order. */
  static final CommandId[] MODES = {
    CommandId.MARKDOWN_SOURCE, CommandId.MARKDOWN_SPLIT, CommandId.MARKDOWN_PREVIEW
  };

  private final ToolBar bar;
  private final ApplicationController controller;
  private final CommandStates states;
  private Keymap keymap;
  private ToolItem separator;
  private final ToolItem[] items = new ToolItem[MODES.length];

  MarkdownSwitch(ToolBar bar, ApplicationController controller, CommandStates states) {
    this.bar = bar;
    this.controller = controller;
    this.states = states;
  }

  /** Sets the items' tool tips from the key bindings. */
  void setKeymap(Keymap value) {
    keymap = value;
    tips();
  }

  /** Adds or removes the switch for the selected tab and checks its mode. */
  void sync() {
    if (bar.isDisposed()) {
      return;
    }
    boolean shown = states.enabled(CommandId.MARKDOWN_SPLIT);
    if (shown && separator == null) {
      create();
    } else if (!shown && separator != null) {
      separator.dispose();
      separator = null;
      for (int i = 0; i < items.length; i++) {
        items[i].dispose();
        items[i] = null;
      }
      bar.getParent().layout(true);
    }
    if (separator != null) {
      for (int i = 0; i < items.length; i++) {
        boolean checked = states.checked(MODES[i]);
        if (items[i].getSelection() != checked) {
          items[i].setSelection(checked);
        }
      }
    }
  }

  private void create() {
    separator = new ToolItem(bar, SWT.SEPARATOR);
    for (int i = 0; i < items.length; i++) {
      final CommandId command = MODES[i];
      ToolItem item = new ToolItem(bar, SWT.RADIO);
      item.setText(command.title());
      item.setData(command);
      // A click sends the item it unchecks a Selection event first, then checks the clicked item
      // and sends it one (SWT 3.135.0 on GTK, win32 and cocoa). Only the checked item's event acts:
      // a sync on the first event would uncheck the clicked item before its own event.
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (((ToolItem) event.widget).getSelection()) {
                controller.execute(command);
                sync();
              }
            }
          });
      items[i] = item;
    }
    tips();
    bar.getParent().layout(true);
  }

  private void tips() {
    if (separator == null || keymap == null) {
      return;
    }
    for (int i = 0; i < items.length; i++) {
      String keys = keymap.keysText(MODES[i]);
      String title = MODES[i].qualifiedTitle();
      items[i].setToolTipText(keys.isEmpty() ? title : title + " (" + keys + ")");
    }
  }

  /**
   * Returns the switch's items.
   *
   * @return Source, Split and Preview, or null entries while hidden
   */
  ToolItem[] items() {
    return items.clone();
  }
}
