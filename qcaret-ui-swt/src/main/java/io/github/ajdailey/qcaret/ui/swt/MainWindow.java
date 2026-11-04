package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.PaletteListener;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
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
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

/**
 * The main window: one tab holding an empty {@link StyledText}, and the File, View and Help menus
 * built from {@link MenuTable}. The text view takes its colors from the {@link Palette}; native
 * controls follow {@link SwtThemeService}. {@link ToolIcons} owned by the window re-ink on the UI
 * turn after a palette change, once native controls have restyled.
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
  private final StyledText text;
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
    CTabItem tab = new CTabItem(tabs, SWT.NONE);
    tab.setText(UiText.TAB_UNTITLED.text());
    text = new StyledText(tabs, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
    font = new Font(display, Dialogs.monospaceName(), Dialogs.monospaceSize(), SWT.NORMAL);
    text.setFont(font);
    text.setEditable(false);
    tab.setControl(text);
    tabs.setSelection(tab);
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
    onCloseRequest(
        new Runnable() {
          @Override
          public void run() {
            controller.quit();
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
        item.setText(command.label());
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

  /** Checks the theme item that matches the setting. */
  private void syncChecks() {
    ThemeMode mode = controller.theme().mode();
    check(CommandId.THEME_LIGHT, mode == ThemeMode.LIGHT);
    check(CommandId.THEME_DARK, mode == ThemeMode.DARK);
    check(CommandId.THEME_SYSTEM, mode == ThemeMode.SYSTEM);
  }

  private void check(CommandId command, boolean selected) {
    MenuItem item = checkItems.get(command);
    if (item != null && !item.isDisposed()) {
      item.setSelection(selected);
    }
  }

  private void applyPalette(Palette next) {
    palette = next;
    if (!text.isDisposed()) {
      text.setForeground(color(next.text()));
      text.setBackground(color(next.background()));
      text.setSelectionBackground(color(next.selection()));
      text.setSelectionForeground(color(next.selectionText()));
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

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
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
   * Returns the text view.
   *
   * @return the view
   */
  public StyledText text() {
    return text;
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
   * applied where the window system ignores it (Wayland).
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
    boolean positionSupported = !"wayland".equals(System.getProperty(GDK_BACKEND));
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
