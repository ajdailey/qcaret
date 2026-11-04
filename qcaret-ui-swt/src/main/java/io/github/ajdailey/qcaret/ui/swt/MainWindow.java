package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.PaletteListener;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

/**
 * The main window: a tab per document, each holding an {@link EditorPane}, and the File, Edit, View
 * and Help menus built from {@link MenuTable}. The editors take their colors from the {@link
 * Palette}; native controls follow {@link SwtThemeService}. {@link ToolIcons} owned by the window
 * re-ink on the UI turn after a palette change, once native controls have restyled.
 *
 * <p>Tab close buttons and a middle click ask the controller to close; dragging a tab within the
 * strip moves it. A tab's item cannot change position, so a move disposes and recreates it, and the
 * {@code Selection} that {@code CTabFolder} sends for a neighbor while the selected item is
 * disposed is ignored (QLogg UI-14). The file choosers are native dialogs and run a nested event
 * loop; the unsaved-changes prompt does not.
 *
 * <p>{@link #place} sizes and positions the shell before {@link #open}; the normal bounds are kept
 * on every unmaximized move and resize for {@link #windowBounds}. Dialogs open without a nested
 * event loop; a text window opened over a modal dialog is modal and owned by it, because GTK gives
 * input only to the newest modal shell.
 */
public final class MainWindow implements MainView {
  /** System property SWT sets on GTK to {@code x11}, {@code wayland} or {@code unknown}. */
  static final String GDK_BACKEND = "org.eclipse.swt.internal.gdk.backend";

  /** System property SWT sets on GTK to the GTK version. */
  static final String GTK_VERSION = "org.eclipse.swt.internal.gtk.version";

  private final Shell shell;
  private final CTabFolder tabs;
  private final Font font;
  private final List<Image> icons;
  private final ToolIcons toolIcons;
  private final SwtUiDispatcher dispatcher;
  private final Map<CommandId, MenuItem> checkItems =
      new EnumMap<CommandId, MenuItem>(CommandId.class);
  private ApplicationController controller;
  private PaletteListener paletteListener;
  private Palette palette;
  private Clipboard clipboard;
  private WindowGeometry.Request normalBounds = new WindowGeometry.Request(null, null, null, null);
  private CTabItem dragged;
  private boolean movingTab;

  /** One document tab: its item, which a move recreates, and its editor. */
  private final class Tab implements TabView {
    final EditorPane pane;
    final TabInput input;
    String title = "";
    String toolTip = "";
    CTabItem item;

    Tab(EditorPane pane, TabInput input, int index) {
      this.pane = pane;
      this.input = input;
      this.item = newItem(this, index);
    }

    @Override
    public void setTitle(String value, String tip) {
      title = value;
      toolTip = tip;
      if (!item.isDisposed()) {
        item.setText(value.replace("&", "&&"));
        item.setToolTipText(tip);
      }
    }

    @Override
    public void select() {
      if (!item.isDisposed()) {
        tabs.setSelection(item);
        pane.focus();
        input.activated();
      }
    }

    @Override
    public void showCaret() {
      pane.showCaret();
    }

    @Override
    public void close() {
      if (!item.isDisposed()) {
        item.dispose();
      }
      pane.dispose();
      CTabItem next = tabs.getSelection();
      if (next != null && next.getData() instanceof Tab shown) {
        shown.pane.focus();
      }
    }
  }

  /**
   * Creates the window, not yet visible.
   *
   * @param display the Display
   * @param palette the palette the text view starts with
   */
  public MainWindow(Display display, Palette palette) {
    this.palette = palette;
    this.dispatcher = new SwtUiDispatcher(display);
    shell = new Shell(display, SWT.SHELL_TRIM);
    shell.setText(UiText.WINDOW_TITLE.text());
    icons = Icons.load(display, Icons.Variant.DARK);
    shell.setImages(icons.toArray(new Image[0]));
    shell.setLayout(new FillLayout());
    tabs = new CTabFolder(shell, SWT.TOP | SWT.FLAT);
    font = new Font(display, Dialogs.monospaceName(), Dialogs.monospaceSize(), SWT.NORMAL);
    toolIcons = new ToolIcons(shell);
    applyPalette(palette);
    trackWindowState();
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (controller != null) {
              controller.theme().removeListener(paletteListener);
            }
            font.dispose();
            for (Image image : icons) {
              image.dispose();
            }
            if (clipboard != null) {
              clipboard.dispose();
            }
          }
        });
  }

  /**
   * Connects the window to its controller: builds the menu bar, routes close requests to {@link
   * ApplicationController#quit}, and follows the controller's palette. Call once, before {@link
   * #open}.
   *
   * @param owner the controller
   */
  public void attach(ApplicationController owner) {
    controller = owner;
    buildMenuBar();
    listenToTabs();
    onCloseRequest(
        new Runnable() {
          @Override
          public void run() {
            controller.execute(CommandId.QUIT);
          }
        });
    paletteListener =
        new PaletteListener() {
          @Override
          public void paletteChanged(Palette next) {
            applyPalette(next);
          }
        };
    controller.theme().addListener(paletteListener);
    applyPalette(controller.theme().palette());
  }

  private void buildMenuBar() {
    Menu bar = new Menu(shell, SWT.BAR);
    boolean appMenu = AppMenu.present(shell.getDisplay());
    for (CommandGroup group : CommandGroup.values()) {
      if (!group.menu()) {
        continue;
      }
      List<CommandId> commands = MenuTable.commands(group, appMenu);
      if (commands.isEmpty()) {
        continue;
      }
      MenuItem header = new MenuItem(bar, SWT.CASCADE);
      header.setText(group.label());
      Menu menu = new Menu(shell, SWT.DROP_DOWN);
      header.setMenu(menu);
      Menu target = menu;
      String cascade = null;
      for (final CommandId command : commands) {
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
        int accelerator = Accelerators.parse(command.accelerator());
        if (accelerator != 0) {
          item.setText(command.label() + "\t" + Accelerators.text(command.accelerator()));
          item.setAccelerator(accelerator);
        } else {
          item.setText(command.label());
        }
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                controller.execute(command);
                syncChecks();
              }
            });
        if (command.check()) {
          checkItems.put(command, item);
        }
      }
      menu.addListener(
          SWT.Show,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              syncChecks();
            }
          });
    }
    shell.setMenuBar(bar);
    syncChecks();
  }

  /** Checks the items whose setting is on: word wrap and the theme. */
  private void syncChecks() {
    for (Map.Entry<CommandId, MenuItem> entry : checkItems.entrySet()) {
      if (!entry.getValue().isDisposed()) {
        entry.getValue().setSelection(controller.checked(entry.getKey()));
      }
    }
  }

  private void applyPalette(Palette next) {
    palette = next;
    for (CTabItem item : tabs.getItems()) {
      if (item.getData() instanceof Tab tab) {
        tab.pane.setPalette(next);
      }
    }
    // Native controls restyle after the theme call returns; re-ink the icons on the next turn.
    dispatcher.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!shell.isDisposed()) {
              toolIcons.reink();
            }
          }
        });
  }

  /**
   * Returns the palette the text view paints with.
   *
   * @return the palette
   */
  public Palette palette() {
    return palette;
  }

  /**
   * Returns the selected tab's editor.
   *
   * @return the editor, or null without a tab
   */
  public EditorPane activeEditor() {
    CTabItem item = tabs.getSelection();
    return item != null && item.getData() instanceof Tab tab ? tab.pane : null;
  }

  /**
   * Returns the selected tab's text widget.
   *
   * @return the widget, or null without a tab
   */
  public StyledText activeText() {
    EditorPane pane = activeEditor();
    return pane == null ? null : pane.text();
  }

  /**
   * Returns the tab strip.
   *
   * @return the folder holding one item per document
   */
  CTabFolder tabs() {
    return tabs;
  }

  @Override
  public TabView addTab(
      DisplayProjection projection, SelectionModel selections, EditorInput editor, TabInput input) {
    EditorPane pane = new EditorPane(tabs, projection, selections, editor, font, palette);
    Tab tab = new Tab(pane, input, tabs.getItemCount());
    tabs.setSelection(tab.item);
    pane.focus();
    return tab;
  }

  private CTabItem newItem(Tab tab, int index) {
    CTabItem item = new CTabItem(tabs, SWT.CLOSE, index);
    item.setControl(tab.pane.text());
    item.setData(tab);
    item.setText(tab.title.replace("&", "&&"));
    item.setToolTipText(tab.toolTip);
    return item;
  }

  /** Tab selection, close buttons, middle clicks and drags go to the controller. */
  private void listenToTabs() {
    tabs.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!movingTab && event.item != null && event.item.getData() instanceof Tab tab) {
              tab.pane.focus();
              tab.input.activated();
            }
          }
        });
    tabs.addCTabFolder2Listener(
        new CTabFolder2Adapter() {
          @Override
          public void close(CTabFolderEvent event) {
            event.doit = false;
            if (event.item.getData() instanceof Tab tab) {
              tab.input.closeRequested();
            }
          }
        });
    tabs.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            dragged = event.button == 1 ? tabs.getItem(new Point(event.x, event.y)) : null;
          }
        });
    tabs.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            CTabItem target = tabs.getItem(new Point(event.x, event.y));
            if (event.button == 2) {
              if (target != null && target.getData() instanceof Tab tab) {
                tab.input.closeRequested();
              }
              return;
            }
            CTabItem source = dragged;
            dragged = null;
            if (source != null && !source.isDisposed() && target != null && target != source) {
              moveTab(source, tabs.indexOf(target));
            }
          }
        });
  }

  /**
   * Recreates a tab at another index. Disposing the selected item makes {@code CTabFolder} select a
   * neighbor and send {@code Selection}; the listener ignores it while {@link #movingTab} is set.
   *
   * @param source the dragged item
   * @param index the drop index
   */
  void moveTab(CTabItem source, int index) {
    Tab tab = (Tab) source.getData();
    boolean selected = tabs.getSelection() == source;
    movingTab = true;
    try {
      source.dispose();
      tab.item = newItem(tab, index);
      if (selected) {
        tabs.setSelection(tab.item);
      }
    } finally {
      movingTab = false;
    }
    tab.input.moved(index);
  }

  @Override
  public void chooseFilesToOpen(Path directory, Consumer<List<Path>> chosen) {
    if (shell.isDisposed()) {
      return;
    }
    FileDialog dialog = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
    if (directory != null) {
      dialog.setFilterPath(directory.toString());
    }
    if (dialog.open() == null) {
      return;
    }
    List<Path> paths = new ArrayList<Path>();
    for (String name : dialog.getFileNames()) {
      paths.add(Path.of(dialog.getFilterPath(), name));
    }
    chosen.accept(paths);
  }

  @Override
  public void chooseSaveTarget(Path directory, String name, Consumer<Path> chosen) {
    if (shell.isDisposed()) {
      return;
    }
    FileDialog dialog = new FileDialog(shell, SWT.SAVE);
    dialog.setOverwrite(true);
    dialog.setFileName(name);
    if (directory != null) {
      dialog.setFilterPath(directory.toString());
    }
    String chosenPath = dialog.open();
    if (chosenPath != null) {
      chosen.accept(Path.of(chosenPath));
    }
  }

  @Override
  public void askUnsaved(String name, Consumer<UnsavedChoice> answer) {
    if (shell.isDisposed()) {
      answer.accept(UnsavedChoice.CANCEL);
      return;
    }
    new UnsavedDialog(shell, name, answer).open();
  }

  /**
   * Returns the icons drawn on the window's native controls.
   *
   * @return the icon set
   */
  ToolIcons toolIcons() {
    return toolIcons;
  }

  /**
   * Sizes and positions the window from a request; call before {@link #open}. The position is not
   * applied where the window system ignores it: Wayland, and SWT on GTK 4, which has no window
   * move.
   *
   * @param request command-line geometry; null fields are not given
   * @param persisted geometry from the previous session, or null
   * @return the placement applied
   */
  public WindowGeometry.Placement place(
      WindowGeometry.Request request, WindowGeometry.Request persisted) {
    Display display = shell.getDisplay();
    List<WindowGeometry.Screen> screens = new ArrayList<WindowGeometry.Screen>();
    for (Monitor monitor : display.getMonitors()) {
      screens.add(screen(monitor));
    }
    WindowGeometry.Screen primary = screen(display.getPrimaryMonitor());
    Rectangle trim = shell.computeTrim(0, 0, 100, 100);
    boolean positionSupported =
        !"wayland".equals(System.getProperty(GDK_BACKEND))
            && !"1".equals(System.getenv("SWT_GTK4"));
    WindowGeometry.Placement placement =
        WindowGeometry.place(
            request,
            persisted,
            screens,
            primary,
            trim.width - 100,
            trim.height - 100,
            positionSupported);
    apply(placement);
    // On win32, computeTrim overstates the height once a menu bar is set; place again with the
    // trim measured from the sized shell.
    if ("win32".equals(SWT.getPlatform())) {
      Point size = shell.getSize();
      Rectangle client = shell.getClientArea();
      int trimWidth = size.x - client.width;
      int trimHeight = size.y - client.height;
      if (trimWidth != trim.width - 100 || trimHeight != trim.height - 100) {
        placement =
            WindowGeometry.place(
                request, persisted, screens, primary, trimWidth, trimHeight, positionSupported);
        apply(placement);
      }
    }
    return placement;
  }

  private void apply(WindowGeometry.Placement placement) {
    WindowGeometry.Rect bounds = placement.bounds();
    if (placement.setLocation()) {
      shell.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    } else {
      shell.setSize(bounds.width(), bounds.height());
    }
  }

  private static WindowGeometry.Screen screen(Monitor monitor) {
    return new WindowGeometry.Screen(rect(monitor.getBounds()), rect(monitor.getClientArea()));
  }

  private static WindowGeometry.Rect rect(Rectangle r) {
    return new WindowGeometry.Rect(r.x, r.y, r.width, r.height);
  }

  /** Keeps the normal bounds whenever the window moves or resizes unmaximized. */
  private void trackWindowState() {
    Listener track =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (shell.isDisposed() || shell.getMaximized() || shell.getMinimized()) {
              return;
            }
            Point location = shell.getLocation();
            Rectangle client = shell.getClientArea();
            if (client.width > 0 && client.height > 0) {
              normalBounds =
                  new WindowGeometry.Request(location.x, location.y, client.width, client.height);
            }
          }
        };
    shell.addListener(SWT.Move, track);
    shell.addListener(SWT.Resize, track);
  }

  /** Shows the window. */
  public void open() {
    shell.open();
  }

  /**
   * Returns the SWT shell, for the event loop and tests.
   *
   * @return the shell
   */
  public Shell shell() {
    return shell;
  }

  /**
   * Routes window-system close requests to a handler instead of closing.
   *
   * @param handler runs on the UI thread for each close request
   */
  public void onCloseRequest(final Runnable handler) {
    shell.addListener(
        SWT.Close,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            handler.run();
          }
        });
  }

  @Override
  public void hide() {
    if (!shell.isDisposed()) {
      shell.setVisible(false);
    }
  }

  @Override
  public void dispose() {
    if (!shell.isDisposed()) {
      shell.dispose();
    }
  }

  @Override
  public void showInfo(String title, String message) {
    if (!shell.isDisposed()) {
      new ErrorDialog(shell, title, message).open();
    }
  }

  @Override
  public void showText(String title, String value) {
    if (shell.isDisposed()) {
      return;
    }
    Shell owner = modalOwner();
    new TextWindow(owner, title, value, this, owner != shell).open();
  }

  /** Returns the innermost visible modal dialog over the main window, or the main window. */
  private Shell modalOwner() {
    Shell owner = shell;
    boolean found = true;
    while (found) {
      found = false;
      for (Shell child : owner.getShells()) {
        if (child.isVisible() && (child.getStyle() & SWT.APPLICATION_MODAL) != 0) {
          owner = child;
          found = true;
          break;
        }
      }
    }
    return owner;
  }

  @Override
  public void showAbout(AboutInfo info) {
    if (shell.isDisposed()) {
      return;
    }
    Runnable licenses =
        new Runnable() {
          @Override
          public void run() {
            controller.showLicenses();
          }
        };
    new AboutDialog(shell, info, logoVariant(shell.getBackground()), licenses, this).open();
  }

  /**
   * Picks the About logo for a dialog background. Native chrome can differ from the palette (cocoa,
   * win32 before a restart), so the background decides, not the theme setting.
   *
   * @param background the dialog's background
   * @return the navy-tile logo on a dark background, the light logo otherwise
   */
  static Icons.Variant logoVariant(Color background) {
    return ToolIcons.dark(background) ? Icons.Variant.DARK : Icons.Variant.LIGHT;
  }

  @Override
  public String toolkitDescription() {
    String gtk = System.getProperty(GTK_VERSION);
    return "SWT "
        + SWT.getVersion()
        + " "
        + SWT.getPlatform()
        + (gtk == null ? "" : " (GTK " + gtk + ")");
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    return normalBounds;
  }

  @Override
  public boolean windowMaximized() {
    return !shell.isDisposed() && shell.getMaximized();
  }

  @Override
  public void setText(String value) {
    if (shell.isDisposed()) {
      return;
    }
    if (clipboard == null) {
      clipboard = new Clipboard(shell.getDisplay());
    }
    clipboard.setContents(new Object[] {value}, new Transfer[] {TextTransfer.getInstance()});
  }

  @Override
  public String clipboardText() {
    if (shell.isDisposed()) {
      return null;
    }
    if (clipboard == null) {
      clipboard = new Clipboard(shell.getDisplay());
    }
    Object value = clipboard.getContents(TextTransfer.getInstance());
    return value instanceof String string ? string : null;
  }
}
