package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.EnumMap;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The Markdown buttons after the toolbar's {@link MarkdownSwitch}, created and disposed with it:
 * the renderer toggle, checked for Browser; the Sync Scrolling and Outline toggles; Export as HTML;
 * Print Rendered Markdown. Each runs its menu command and shows that item's checked and enabled
 * state from {@link CommandStates}. The renderer toggle's tool tip names the renderer shown and the
 * one a click picks, with that command's keys; where Browser cannot run the toggle is disabled with
 * the reason as its tool tip. Call on the UI thread.
 */
final class MarkdownToolItems {
  /** The buttons in toolbar order. */
  static final CommandId[] COMMANDS = {
    CommandId.PREVIEW_BROWSER,
    CommandId.MARKDOWN_SYNC_SCROLL,
    CommandId.MARKDOWN_OUTLINE,
    CommandId.EXPORT_HTML,
    CommandId.PRINT_MARKDOWN
  };

  private final ToolBar bar;
  private final ToolIcons icons;
  private final ApplicationController controller;
  private final CommandStates states;
  private final Map<CommandId, ToolItem> items = new EnumMap<CommandId, ToolItem>(CommandId.class);
  private ToolItem separator;
  private Keymap keymap;

  MarkdownToolItems(
      ToolBar bar, ToolIcons icons, ApplicationController controller, CommandStates states) {
    this.bar = bar;
    this.icons = icons;
    this.controller = controller;
    this.states = states;
  }

  /** Adds the buttons at the toolbar's end. */
  void create() {
    separator = new ToolItem(bar, SWT.SEPARATOR);
    for (final CommandId command : COMMANDS) {
      boolean push = command == CommandId.EXPORT_HTML || command == CommandId.PRINT_MARKDOWN;
      ToolItem item = new ToolItem(bar, push ? SWT.PUSH : SWT.CHECK);
      item.setData(command);
      if (!icons.apply(item, icon(command))) {
        item.setText(command.title());
      }
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              ToolItem clicked = (ToolItem) event.widget;
              boolean browser = clicked.getSelection();
              if (command == CommandId.PREVIEW_BROWSER) {
                controller.execute(browser ? CommandId.PREVIEW_BROWSER : CommandId.PREVIEW_BASIC);
              } else {
                controller.execute(command);
              }
              if (!clicked.isDisposed()) {
                sync();
              }
            }
          });
      items.put(command, item);
    }
    sync();
  }

  /** Removes the buttons. */
  void dispose() {
    if (separator == null) {
      return;
    }
    separator.dispose();
    separator = null;
    for (ToolItem item : items.values()) {
      item.dispose();
    }
    items.clear();
  }

  /**
   * Sets the tool tips' keys.
   *
   * @param value the key bindings
   */
  void setKeymap(Keymap value) {
    keymap = value;
    sync();
  }

  /** Shows each command's checked and enabled state and its tool tip. */
  void sync() {
    for (Map.Entry<CommandId, ToolItem> entry : items.entrySet()) {
      CommandId command = entry.getKey();
      ToolItem item = entry.getValue();
      boolean enabled = states.enabled(command);
      if (item.getEnabled() != enabled) {
        item.setEnabled(enabled);
        icons.showState(item);
      }
      if ((item.getStyle() & SWT.CHECK) != 0) {
        boolean checked = states.checked(command);
        if (item.getSelection() != checked) {
          item.setSelection(checked);
        }
      }
      String tip = tip(command, enabled);
      if (!tip.equals(item.getToolTipText())) {
        item.setToolTipText(tip);
      }
    }
  }

  private String tip(CommandId command, boolean enabled) {
    String title = command.qualifiedTitle();
    String reason = enabled ? null : states.unavailable(command);
    if (reason != null) {
      return UiText.MENU_ITEM_REASON.format(title, reason);
    }
    CommandId runs = command;
    if (command == CommandId.PREVIEW_BROWSER) {
      boolean browser = states.checked(CommandId.PREVIEW_BROWSER);
      title = (browser ? UiText.PREVIEW_TOGGLE_BROWSER : UiText.PREVIEW_TOGGLE_BASIC).text();
      runs = browser ? CommandId.PREVIEW_BASIC : CommandId.PREVIEW_BROWSER;
    }
    String keys = keymap == null ? "" : keymap.keysText(runs);
    return keys.isEmpty() ? title : title + " (" + keys + ")";
  }

  /** The button's icon; the menu items keep none ({@link MenuIcons#icon} returns null). */
  private static String icon(CommandId command) {
    return switch (command) {
      case PREVIEW_BROWSER -> ToolIcons.PREVIEW_BROWSER;
      case MARKDOWN_SYNC_SCROLL -> ToolIcons.SYNC_SCROLL;
      case MARKDOWN_OUTLINE -> ToolIcons.OUTLINE;
      case EXPORT_HTML -> ToolIcons.EXPORT_HTML;
      default -> ToolIcons.PRINT;
    };
  }

  /**
   * Returns a command's button.
   *
   * @param command one of {@link #COMMANDS}
   * @return the button, or null while hidden
   */
  ToolItem item(CommandId command) {
    return items.get(command);
  }
}
