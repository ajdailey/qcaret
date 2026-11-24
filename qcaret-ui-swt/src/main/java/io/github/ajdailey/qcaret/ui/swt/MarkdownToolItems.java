package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The Markdown actions button after the toolbar's {@link MarkdownSwitch}, created and disposed with
 * it. The button is a push item whose icon carries a down arrow in the toolbar's ink: an {@code
 * SWT.DROP_DOWN} item's arrow is drawn by win32 in black whatever the toolbar's colors, 1.36:1 on
 * the dark toolbar. A click opens a menu of {@link #COMMANDS}: Basic and Browser as radio items,
 * Sync Scrolling and Outline as check items, Export as HTML and Print Rendered Markdown. Each item
 * shows its title and keys, takes its checked and enabled state from {@link CommandStates} when the
 * menu opens, gives a disabled item's reason as the menus do, and runs its menu command. Call on
 * the UI thread.
 */
final class MarkdownToolItems implements ToolMenu {
  /** The menu's commands in order; null is a separator. */
  static final CommandId[] COMMANDS = {
    CommandId.PREVIEW_BASIC,
    CommandId.PREVIEW_BROWSER,
    null,
    CommandId.MARKDOWN_SYNC_SCROLL,
    CommandId.MARKDOWN_OUTLINE,
    null,
    CommandId.EXPORT_HTML,
    CommandId.PRINT_MARKDOWN
  };

  private final ToolBar bar;
  private final ToolIcons icons;
  private final MenuIcons menuIcons;
  private final ApplicationController controller;
  private final CommandStates states;
  private ToolItem separator;
  private ToolItem item;
  private Menu menu;
  private Keymap keymap;

  MarkdownToolItems(
      ToolBar bar,
      ToolIcons icons,
      MenuIcons menuIcons,
      ApplicationController controller,
      CommandStates states) {
    this.bar = bar;
    this.icons = icons;
    this.menuIcons = menuIcons;
    this.controller = controller;
    this.states = states;
  }

  /** Adds the button at the toolbar's end. */
  void create() {
    separator = new ToolItem(bar, SWT.SEPARATOR);
    item = new ToolItem(bar, SWT.PUSH);
    item.setData(this);
    String title = title();
    if (!icons.apply(item, ToolIcons.MARKDOWN_ACTIONS)) {
      item.setText(title);
    }
    item.setToolTipText(title);
    ToolBarNames.name(item, title);
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Menu shown = menu();
            Rectangle bounds = ((ToolItem) event.widget).getBounds();
            shown.setLocation(bar.toDisplay(bounds.x, bounds.y + bounds.height));
            shown.setVisible(true);
          }
        });
  }

  /** Removes the button. */
  void dispose() {
    if (separator == null || item == null) {
      return;
    }
    if (menu != null && !menu.isDisposed()) {
      menu.dispose();
    }
    separator.dispose();
    item.dispose();
    separator = null;
    item = null;
  }

  /**
   * Sets the menu's keys.
   *
   * @param value the key bindings
   */
  void setKeymap(Keymap value) {
    keymap = value;
  }

  @Override
  public String title() {
    return UiText.MARKDOWN_ACTIONS.text();
  }

  /**
   * Builds the button's menu from the current command states, replacing the last one.
   *
   * @return the menu, not yet visible
   */
  Menu menu() {
    if (menu != null && !menu.isDisposed()) {
      menu.dispose();
    }
    menu = new Menu(bar.getShell(), SWT.POP_UP);
    fill(menu);
    return menu;
  }

  @Override
  public void fill(Menu target) {
    for (final CommandId command : COMMANDS) {
      if (command == null) {
        new MenuItem(target, SWT.SEPARATOR);
        continue;
      }
      boolean radio = command == CommandId.PREVIEW_BASIC || command == CommandId.PREVIEW_BROWSER;
      int style = radio ? SWT.RADIO : command.check() ? SWT.CHECK : SWT.PUSH;
      MenuItem entry = new MenuItem(target, style);
      boolean enabled = states.enabled(command);
      String reason = enabled ? null : states.unavailable(command);
      String label = command.label();
      if (reason != null && !PlatformTraits.PLATFORM.showsMenuToolTips()) {
        label = UiText.MENU_ITEM_REASON.format(label, reason);
      }
      String keys = keymap == null ? "" : keymap.menuKeyText(command);
      entry.setText(keys.isEmpty() ? label : label + "\t" + keys);
      entry.setToolTipText(reason);
      entry.setEnabled(enabled);
      entry.setData(command);
      if (style != SWT.PUSH) {
        entry.setSelection(states.checked(command));
      }
      entry.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              MenuItem chosen = (MenuItem) event.widget;
              // A radio choice also sends the item it unchecks an event; only the checked one acts.
              if ((chosen.getStyle() & SWT.RADIO) == 0 || chosen.getSelection()) {
                controller.execute(command);
              }
            }
          });
    }
    menuIcons.decorate(target);
  }

  /**
   * Returns the button.
   *
   * @return the item, or null while hidden
   */
  ToolItem item() {
    return item;
  }
}
