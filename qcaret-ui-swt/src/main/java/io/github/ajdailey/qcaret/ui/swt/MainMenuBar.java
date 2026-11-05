package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.controller.EncodingChoices;
import io.github.ajdailey.qcaret.app.controller.SyntaxMenu;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * The main window's menu bar: one menu per {@link CommandGroup} with its {@link MenuTable}
 * commands, the Encoding menu's Reopen with Encoding and Save with Encoding cascades, and the
 * Window menu's open documents. Item state comes from {@link CommandStates}; this class only
 * applies it.
 *
 * <p>Showing a top menu, or running one of its items, syncs that menu only. Enablement of every
 * item also follows {@link #syncEnablement}, which the window runs on each document and focus
 * change, because a disabled item's accelerator does not fire. Each encoding cascade holds {@link
 * EncodingChoices#commonCharsets()} from the start and an Other cascade, disabled and empty until
 * {@link #encodingsLoaded} fills it once; neither is rebuilt. The View menu's Syntax cascade holds
 * the {@link SyntaxMenu} entries above Reload Grammars, rebuilt each time the View menu shows;
 * {@link #showSyntaxMenu} pops up the same entries. Call on the UI thread.
 */
final class MainMenuBar {
  /** Widget data of the Window menu's document entries. */
  private static final String LISTED = "qcaret.listed";

  /** Widget data of the Syntax cascade's grammar entries. */
  private static final String SYNTAX = "qcaret.syntax";

  /** The last language popup; replaced by the next and disposed with the shell. */
  private Menu syntaxPopup;

  private final Shell shell;
  private final ApplicationController controller;
  private final CommandStates states;
  private final Map<CommandId, MenuItem> items = new EnumMap<CommandId, MenuItem>(CommandId.class);
  private final Map<CommandGroup, Menu> menus = new EnumMap<CommandGroup, Menu>(CommandGroup.class);
  private final EncodingCascade reopen = new EncodingCascade(true);
  private final EncodingCascade saveAs = new EncodingCascade(false);

  /** One encoding cascade: the common charsets, then Other. */
  private final class EncodingCascade {
    final boolean reopens;
    final Map<String, MenuItem> byName = new HashMap<String, MenuItem>();
    final List<MenuItem> radios = new ArrayList<MenuItem>();
    MenuItem holder;
    MenuItem other;
    boolean loaded;

    EncodingCascade(boolean reopens) {
      this.reopens = reopens;
    }

    void build(Menu menu, UiText label) {
      byName.clear();
      radios.clear();
      loaded = false;
      holder = new MenuItem(menu, SWT.CASCADE);
      holder.setText(label.text());
      Menu cascade = new Menu(shell, SWT.DROP_DOWN);
      holder.setMenu(cascade);
      for (Charset charset : EncodingChoices.commonCharsets()) {
        item(cascade, charset);
      }
      new MenuItem(cascade, SWT.SEPARATOR);
      other = new MenuItem(cascade, SWT.CASCADE);
      other.setText(UiText.MENU_ENCODING_OTHER.text());
      other.setEnabled(false);
      other.setMenu(new Menu(shell, SWT.DROP_DOWN));
    }

    void fill(EncodingChoices choices) {
      if (loaded || other == null || other.isDisposed()) {
        return;
      }
      Menu all = other.getMenu();
      for (Charset charset : choices.other()) {
        if (!byName.containsKey(charset.name())) {
          item(all, charset);
        }
      }
      loaded = true;
      other.setEnabled(true);
    }

    private void item(Menu menu, final Charset charset) {
      MenuItem item = new MenuItem(menu, reopens ? SWT.PUSH : SWT.RADIO);
      item.setText(charset.name().replace("&", "&&"));
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (reopens) {
                controller.reopenWithEncoding(charset);
              } else if (((MenuItem) event.widget).getSelection()) {
                controller.saveWithEncoding(charset);
              }
            }
          });
      byName.put(charset.name(), item);
      if (!reopens) {
        radios.add(item);
      }
    }

    /** Enables the cascade and, for Save with Encoding, checks the next save's charset. */
    void sync(boolean enabled, String checked) {
      if (holder == null || holder.isDisposed()) {
        return;
      }
      setEnabled(holder, enabled);
      for (MenuItem item : radios) {
        if (!item.isDisposed()) {
          boolean selected = item == byName.get(checked);
          if (item.getSelection() != selected) {
            item.setSelection(selected);
          }
        }
      }
    }
  }

  /**
   * Creates the menu bar's owner; {@link #build} creates the menus.
   *
   * @param shell the main window's shell
   * @param controller the window's controller
   */
  MainMenuBar(Shell shell, ApplicationController controller) {
    this.shell = shell;
    this.controller = controller;
    this.states = new CommandStates(controller);
  }

  /**
   * Returns the state the items apply.
   *
   * @return the command states over this window's controller
   */
  CommandStates states() {
    return states;
  }

  /**
   * Creates the menu bar with the keys of a keymap, sets it on the shell and disposes the previous
   * one. Each item shows every key of its command; only window bindings become accelerators.
   *
   * @param keymap the key bindings
   */
  void build(Keymap keymap) {
    Menu old = shell.getMenuBar();
    items.clear();
    menus.clear();
    Menu bar = new Menu(shell, SWT.BAR);
    boolean appMenu = AppMenu.present(shell.getDisplay());
    for (final CommandGroup group : CommandGroup.values()) {
      if (!group.menu()) {
        continue;
      }
      MenuItem header = new MenuItem(bar, SWT.CASCADE);
      header.setText(group.label());
      Menu menu = new Menu(shell, SWT.DROP_DOWN);
      header.setMenu(menu);
      menus.put(group, menu);
      if (group == CommandGroup.ENCODING) {
        reopen.build(menu, UiText.MENU_REOPEN_ENCODING);
        saveAs.build(menu, UiText.MENU_SAVE_ENCODING);
      }
      Menu target = menu;
      String cascade = null;
      for (final CommandId command : MenuTable.commands(group, appMenu)) {
        String submenu = command.submenu();
        if (submenu == null) {
          target = menu;
          cascade = null;
        } else if (!submenu.equals(cascade)) {
          if (command.separatorBefore() && menu.getItemCount() > 0) {
            new MenuItem(menu, SWT.SEPARATOR);
          }
          MenuItem holder = new MenuItem(menu, SWT.CASCADE);
          holder.setText(submenu);
          target = new Menu(shell, SWT.DROP_DOWN);
          holder.setMenu(target);
          cascade = submenu;
        }
        if (command.separatorBefore() && target.getItemCount() > 0) {
          new MenuItem(target, SWT.SEPARATOR);
        }
        MenuItem item = new MenuItem(target, command.check() ? SWT.CHECK : SWT.PUSH);
        item.setData(command);
        String keys = keymap.keysText(command);
        item.setText(keys.isEmpty() ? command.label() : command.label() + "\t" + keys);
        KeyStroke accelerator = keymap.accelerator(command);
        if (accelerator != null) {
          item.setAccelerator(SwtKeys.accelerator(accelerator));
        }
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                controller.execute(command);
                if (!shell.isDisposed()) {
                  sync(group);
                }
              }
            });
        items.put(command, item);
      }
      menu.addListener(
          SWT.Show,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              sync(group);
            }
          });
    }
    shell.setMenuBar(bar);
    if (old != null) {
      old.dispose();
    }
    syncAll();
  }

  /**
   * Syncs one top menu: its items' checked and enabled state and the entries it lists.
   *
   * @param group the menu's group
   */
  void sync(CommandGroup group) {
    for (Map.Entry<CommandId, MenuItem> entry : items.entrySet()) {
      CommandId command = entry.getKey();
      MenuItem item = entry.getValue();
      if (command.group() != group || item.isDisposed()) {
        continue;
      }
      setEnabled(item, states.enabled(command));
      if (command.check()) {
        boolean checked = states.checked(command);
        if (item.getSelection() != checked) {
          item.setSelection(checked);
        }
      }
    }
    if (group == CommandGroup.ENCODING) {
      syncEncodings();
    }
    if (group == CommandGroup.WINDOW) {
      syncDocuments();
    }
    if (group == CommandGroup.VIEW) {
      syncSyntax();
    }
  }

  /** Lists the {@link SyntaxMenu} entries above Reload Grammars. */
  private void syncSyntax() {
    MenuItem reload = items.get(CommandId.RELOAD_GRAMMARS);
    if (reload == null || reload.isDisposed()) {
      return;
    }
    Menu menu = reload.getParent();
    for (MenuItem item : menu.getItems()) {
      if (SYNTAX.equals(item.getData())) {
        if (item.getMenu() != null) {
          item.getMenu().dispose();
        }
        item.dispose();
      }
    }
    int count = fillSyntax(menu, states.syntaxMenu());
    if (count > 0) {
      new MenuItem(menu, SWT.SEPARATOR, count).setData(SYNTAX);
    }
  }

  /**
   * Inserts the recent grammars, a separator, Plain Text and the category cascades at the top of a
   * menu, each item tagged {@link #SYNTAX}.
   *
   * @return the number of items inserted
   */
  private int fillSyntax(Menu menu, SyntaxMenu model) {
    int index = 0;
    for (CommandStates.SyntaxChoice choice : model.recent()) {
      syntaxItem(menu, index++, choice);
    }
    if (index > 0) {
      new MenuItem(menu, SWT.SEPARATOR, index++).setData(SYNTAX);
    }
    if (model.plain() != null) {
      syntaxItem(menu, index++, model.plain());
    }
    for (SyntaxMenu.Category category : model.categories()) {
      MenuItem holder = new MenuItem(menu, SWT.CASCADE, index++);
      holder.setText(category.label().text());
      holder.setData(SYNTAX);
      Menu cascade = new Menu(shell, SWT.DROP_DOWN);
      holder.setMenu(cascade);
      List<CommandStates.SyntaxChoice> choices = category.choices();
      for (int i = 0; i < choices.size(); i++) {
        syntaxItem(cascade, i, choices.get(i));
      }
    }
    return index;
  }

  private void syntaxItem(Menu menu, int index, final CommandStates.SyntaxChoice choice) {
    MenuItem item = new MenuItem(menu, SWT.RADIO, index);
    item.setText(choice.name().replace("&", "&&"));
    item.setSelection(choice.checked());
    item.setData(SYNTAX);
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (((MenuItem) event.widget).getSelection()) {
              controller.setSyntax(choice.id());
            }
          }
        });
  }

  /**
   * Pops up the {@link SyntaxMenu} entries below a control; choosing a grammar sets it. The popup
   * replaces the previous one.
   *
   * @param below the control the menu opens at
   */
  void showSyntaxMenu(Control below) {
    SyntaxMenu model = states.syntaxMenu();
    if (model.isEmpty()) {
      return;
    }
    if (syntaxPopup != null && !syntaxPopup.isDisposed()) {
      syntaxPopup.dispose();
    }
    Menu popup = new Menu(shell, SWT.POP_UP);
    syntaxPopup = popup;
    fillSyntax(popup, model);
    Point at = below.toDisplay(0, below.getSize().y);
    popup.setLocation(at.x, at.y);
    popup.setVisible(true);
  }

  /** Syncs every menu, as showing each would. */
  void syncAll() {
    for (CommandGroup group : menus.keySet()) {
      sync(group);
    }
  }

  /** Enables every item and encoding cascade from {@link CommandStates}. */
  void syncEnablement() {
    for (Map.Entry<CommandId, MenuItem> entry : items.entrySet()) {
      if (!entry.getValue().isDisposed()) {
        setEnabled(entry.getValue(), states.enabled(entry.getKey()));
      }
    }
    if (reopen.holder != null && !reopen.holder.isDisposed()) {
      setEnabled(reopen.holder, states.reopenEnabled());
      setEnabled(saveAs.holder, states.saveEncodingEnabled());
    }
  }

  private void syncEncodings() {
    reopen.sync(states.reopenEnabled(), "");
    saveAs.sync(states.saveEncodingEnabled(), states.saveEncoding());
  }

  /** Lists the open documents after the Window commands, the selected one checked. */
  private void syncDocuments() {
    Menu menu = menus.get(CommandGroup.WINDOW);
    if (menu == null || menu.isDisposed()) {
      return;
    }
    for (MenuItem item : menu.getItems()) {
      if (LISTED.equals(item.getData())) {
        item.dispose();
      }
    }
    List<CommandStates.OpenedDocument> documents = states.openedDocuments();
    if (!documents.isEmpty()) {
      new MenuItem(menu, SWT.SEPARATOR).setData(LISTED);
    }
    for (int i = 0; i < documents.size(); i++) {
      final int index = i;
      MenuItem item = new MenuItem(menu, SWT.RADIO);
      item.setText(documents.get(i).text().replace("&", "&&"));
      item.setSelection(documents.get(i).shown());
      item.setData(LISTED);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (((MenuItem) event.widget).getSelection()) {
                controller.documents().select(index);
              }
            }
          });
    }
  }

  /**
   * Fills each Other encoding cascade once and enables it.
   *
   * @param choices the runtime's choices
   */
  void encodingsLoaded(EncodingChoices choices) {
    reopen.fill(choices);
    saveAs.fill(choices);
    syncEncodings();
  }

  private static void setEnabled(MenuItem item, boolean enabled) {
    if (item.getEnabled() != enabled) {
      item.setEnabled(enabled);
    }
  }

  /**
   * Returns a command's item.
   *
   * @param command the command
   * @return the item, or null when no menu lists it
   */
  MenuItem item(CommandId command) {
    return items.get(command);
  }

  /**
   * Returns a top menu.
   *
   * @param group the menu's group
   * @return the menu
   */
  Menu menu(CommandGroup group) {
    return menus.get(group);
  }

  /**
   * Returns an encoding cascade's Other item.
   *
   * @param reopens true for Reopen with Encoding, false for Save with Encoding
   * @return the cascade item
   */
  MenuItem otherEncodings(boolean reopens) {
    return reopens ? reopen.other : saveAs.other;
  }
}
