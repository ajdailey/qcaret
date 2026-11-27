package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EncodingChoices;
import io.github.ajdailey.qcaret.ui.swt.platform.MenuBehavior;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * A main window's menus on its tool windows, built when {@link MenuBehavior#toolWindowMenus} asks
 * (cocoa). Each tool window gets a {@link MainMenuBar} over the main window's controller, so its
 * items act on the main window, except Close, which closes the tool window, and the tool window's
 * {@link ToolCommands}, which run on it. A keymap change reaches every bar through {@link
 * MainMenuBar#apply} and the main window's enablement sync syncs theirs. Confined to the UI thread.
 */
final class ToolMenuBars implements MenuBehavior.ToolMenus {
  /** Shell data of a main window: its tool menu bars. */
  static final String KEY = ToolMenuBars.class.getName();

  /** Builds nothing, for a window no main window owns. */
  static final MenuBehavior.ToolMenus NONE =
      new MenuBehavior.ToolMenus() {
        @Override
        public void build(Shell tool) {}
      };

  private final ApplicationController controller;
  private final MenuIcons icons;
  private final List<MainMenuBar> bars = new ArrayList<MainMenuBar>();
  private Keymap keymap;
  private EncodingChoices choices;

  /**
   * Creates the bars' owner and records it on the main window's shell.
   *
   * @param shell the main window's shell
   * @param controller the main window's controller
   * @param icons the main window's menu icons
   * @param keymap the key bindings the items show
   */
  ToolMenuBars(Shell shell, ApplicationController controller, MenuIcons icons, Keymap keymap) {
    this.controller = controller;
    this.icons = icons;
    this.keymap = keymap;
    shell.setData(KEY, this);
  }

  /**
   * Returns the tool menus of the main window that owns a shell.
   *
   * @param owner the shell a tool window is created under, or null
   * @return the first {@link #KEY} on {@code owner} and its parents, else {@link #NONE}
   */
  static MenuBehavior.ToolMenus of(Shell owner) {
    Composite shell = owner;
    while (shell != null) {
      if (shell.getData(KEY) instanceof ToolMenuBars bars) {
        return bars;
      }
      shell = shell.getParent();
    }
    return NONE;
  }

  @Override
  public void build(Shell tool) {
    final MainMenuBar bar = new MainMenuBar(tool, controller, icons);
    bar.closesToolWindow(tool);
    bar.build(keymap);
    if (choices != null) {
      bar.encodingsLoaded(choices);
    }
    bars.add(bar);
    tool.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            bars.remove(bar);
          }
        });
  }

  /**
   * Rebuilds every bar with new key bindings.
   *
   * @param next the key bindings
   */
  void rebuild(Keymap next) {
    keymap = next;
    for (MainMenuBar bar : bars) {
      bar.apply(next);
    }
  }

  /**
   * Fills every bar's Other encoding cascades, and each bar built later.
   *
   * @param loaded the runtime's choices
   */
  void encodingsLoaded(EncodingChoices loaded) {
    choices = loaded;
    for (MainMenuBar bar : bars) {
      bar.encodingsLoaded(loaded);
    }
  }

  /** Enables every bar's items from the command states. */
  void syncEnablement() {
    for (MainMenuBar bar : bars) {
      bar.syncEnablement();
    }
  }

  /**
   * Returns the open bars.
   *
   * @return one per tool window with the main menus
   */
  List<MainMenuBar> bars() {
    return new ArrayList<MainMenuBar>(bars);
  }
}
