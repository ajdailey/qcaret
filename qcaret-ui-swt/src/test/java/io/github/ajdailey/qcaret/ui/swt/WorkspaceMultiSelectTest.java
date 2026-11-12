package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser's multiple selection: Shift and Ctrl clicks posted through XTest, the row
 * menu over several rows, Open, Copy Path, Enter on the focused row, Delete of several after one
 * prompt, and a drag of several files; with {@code -Dqcaret.screenshots=<dir>}, the menu over
 * several rows and their delete prompt as PNG in the light and the dark theme.
 */
@Tag("swt")
class WorkspaceMultiSelectTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** X keysyms of the left Shift and Control keys. */
  private static final int SHIFT = 0xffe1;

  private static final int CONTROL = 0xffe3;

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Path root;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(
        new WindowGeometry.Request(
            0, 0, WindowGeometry.DEFAULT_WIDTH, WindowGeometry.DEFAULT_HEIGHT),
        null);
    window.open();
    root = Files.createDirectory(temp.resolve("mock-project")).toRealPath();
    write("alpha/one.txt");
    write("beta/two.txt");
    write("notes.txt");
    write("todo.txt");
    controller.workspace().set(root);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(controller.workspace().root());
          }
        },
        "workspace set");
  }

  private void write(String relative) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "mock " + relative + "\n", StandardCharsets.UTF_8);
  }

  @AfterEach
  void close() {
    try {
      controller.quit();
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          },
          BOUND);
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void pause(int millis) {
    final long until = System.nanoTime() + millis * 1_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
  }

  private WorkspaceDock browser() {
    controller.execute(CommandId.WORKSPACE_BROWSER);
    final WorkspaceDock dock = window.workspaceDock();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).equals(List.of("alpha/", "beta/", "notes.txt", "todo.txt"));
          }
        },
        "rows");
    pause(300);
    return dock;
  }

  private static TreeItem row(WorkspaceDock dock, String text) {
    for (TreeItem item : dock.tree().getItems()) {
      if (item.getText().equals(text)) {
        return item;
      }
    }
    throw new AssertionError(text);
  }

  /** Selects rows, the last one focused, as clicks would. */
  private static void select(WorkspaceDock dock, String... texts) {
    TreeItem[] items = new TreeItem[texts.length];
    for (int i = 0; i < texts.length; i++) {
      items[i] = row(dock, texts[i]);
    }
    dock.tree().setSelection(items);
    dock.selection().focus(items[items.length - 1]);
  }

  private static List<String> selectedTexts(Tree tree) {
    List<String> texts = new ArrayList<String>();
    for (TreeItem item : tree.getSelection()) {
      texts.add(item.getText());
    }
    return texts;
  }

  /** Runs the menu's detect step for the selection, as a keyboard menu request does. */
  private static Menu menu(WorkspaceDock dock) {
    Event event = new Event();
    event.detail = SWT.MENU_KEYBOARD;
    dock.tree().notifyListeners(SWT.MenuDetect, event);
    return dock.tree().getMenu();
  }

  private static MenuItem item(Menu menu, UiText label) {
    for (MenuItem item : menu.getItems()) {
      if (item.getText().equals(label.text())) {
        return item;
      }
    }
    throw new AssertionError(label.text());
  }

  /** Waits until the tabs are the named files, in any order, besides an untitled one. */
  private void awaitTabs(final List<String> names) {
    BooleanSupplier shown =
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<String> open = tabTexts();
            open.remove(UiText.TAB_UNTITLED.format(1));
            return open.containsAll(names) && open.size() == names.size();
          }
        };
    assertTrue(EventLoop.runUntil(display, shown, BOUND), "tabs " + names + ", was " + tabTexts());
  }

  private List<String> tabTexts() {
    List<String> open = new ArrayList<String>();
    for (CTabItem item : window.tabs().getItems()) {
      open.add(item.getText());
    }
    return open;
  }

  @Test
  void shiftClickSelectsARangeAndCtrlClickTogglesARow() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "posted clicks are checked on GTK only");
    WorkspaceDock dock = browser();
    final Tree tree = dock.tree();
    assertTrue((tree.getStyle() & SWT.MULTI) != 0);
    click(dock, "alpha/", 0);
    awaitSelection(tree, List.of("alpha/"));
    click(dock, "notes.txt", SHIFT);
    awaitSelection(tree, List.of("alpha/", "beta/", "notes.txt"));
    assertEquals(root.resolve("notes.txt"), dock.selection().focused().path());
    click(dock, "beta/", CONTROL);
    awaitSelection(tree, List.of("alpha/", "notes.txt"));
    assertEquals(root.resolve("alpha"), dock.selection().focused().path(), "first selected");
    click(dock, "todo.txt", CONTROL);
    awaitSelection(tree, List.of("alpha/", "notes.txt", "todo.txt"));
    assertEquals(root.resolve("todo.txt"), dock.selection().focused().path());
  }

  private void awaitSelection(final Tree tree, final List<String> texts) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return selectedTexts(tree).equals(texts);
          }
        },
        "selected " + texts + ", was " + selectedTexts(tree));
  }

  @Test
  void theRowMenuOverSeveralRowsOffersWhatAppliesToThemAll() {
    WorkspaceDock dock = browser();
    select(dock, "alpha/", "notes.txt", "todo.txt");
    Menu menu = menu(dock);
    for (UiText label :
        List.of(
            UiText.WORKSPACE_OPEN,
            UiText.WORKSPACE_OPEN_DEFAULT,
            UiText.WORKSPACE_COPY_PATH,
            UiText.WORKSPACE_DELETE)) {
      assertTrue(item(menu, label).getEnabled(), label.text());
    }
    for (UiText label :
        List.of(
            UiText.WORKSPACE_SEARCH_FOLDER,
            UiText.WORKSPACE_RENAME,
            UiText.WORKSPACE_NEW_FILE,
            UiText.WORKSPACE_SET_FOLDER)) {
      assertFalse(item(menu, label).getEnabled(), label.text() + " on a focused file");
    }
    select(dock, "notes.txt", "alpha/");
    menu = menu(dock);
    assertTrue(item(menu, UiText.WORKSPACE_NEW_FOLDER).getEnabled(), "focused folder");
    assertTrue(item(menu, UiText.WORKSPACE_SET_FOLDER).getEnabled(), "focused folder");
    assertFalse(item(menu, UiText.WORKSPACE_SEARCH_FOLDER).getEnabled());
  }

  @Test
  void openAndCopyPathTakeEverySelectedRow() {
    WorkspaceDock dock = browser();
    select(dock, "alpha/", "notes.txt", "todo.txt");
    item(menu(dock), UiText.WORKSPACE_COPY_PATH).notifyListeners(SWT.Selection, new Event());
    assertEquals(
        String.join(
            System.lineSeparator(),
            root.resolve("alpha").toString(),
            root.resolve("notes.txt").toString(),
            root.resolve("todo.txt").toString()),
        window.clipboardText());
    item(menu(dock), UiText.WORKSPACE_OPEN).notifyListeners(SWT.Selection, new Event());
    awaitTabs(List.of("notes.txt", "todo.txt"));
  }

  @Test
  void enterOpensTheFocusedRowOnly() {
    WorkspaceDock dock = browser();
    select(dock, "notes.txt", "todo.txt");
    Event enter = new Event();
    enter.item = row(dock, "todo.txt");
    dock.tree().notifyListeners(SWT.DefaultSelection, enter);
    awaitTabs(List.of("todo.txt"));
    pause(300);
    awaitTabs(List.of("todo.txt"));
  }

  @Test
  void theDeleteKeyDeletesSeveralEntriesAfterOnePrompt() {
    final WorkspaceDock dock = browser();
    select(dock, "beta/", "notes.txt");
    Event key = new Event();
    key.keyCode = SWT.DEL;
    dock.tree().notifyListeners(SWT.KeyDown, key);
    awaitDeletePrompt();
    String text = String.join("\n", labels(window.deleteDialog().shell()));
    assertTrue(text.contains(UiText.DELETE_MANY_MESSAGE.format(2)), text);
    assertTrue(text.contains("beta, notes.txt"), text);
    assertTrue(
        text.contains(
            UiText.DELETE_MANY_ONE_FOLDER.format(
                "beta", UiText.count(1, UiText.COUNT_FILE, UiText.COUNT_FILES))),
        text);
    window.deleteDialog().delete().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).equals(List.of("alpha/", "todo.txt"));
          }
        },
        "beta and notes.txt deleted");
    assertFalse(Files.exists(root.resolve("beta")));
    assertFalse(Files.exists(root.resolve("notes.txt")));
  }

  @Test
  void theOpenManyPromptDefaultsToCancelAndOpenRunsTheOpen() {
    final AtomicReference<Boolean> opened = new AtomicReference<Boolean>(false);
    Runnable open =
        new Runnable() {
          @Override
          public void run() {
            opened.set(true);
          }
        };
    window.askOpenMany(11, open);
    OpenManyDialog dialog = window.openManyDialog();
    assertEquals(dialog.cancel(), dialog.shell().getDefaultButton());
    assertTrue(labels(dialog.shell()).contains(UiText.OPEN_MANY_MESSAGE.format(11)));
    dialog.cancel().notifyListeners(SWT.Selection, new Event());
    assertTrue(dialog.shell().isDisposed());
    assertFalse(opened.get());
    window.askOpenMany(11, open);
    window.openManyDialog().openButton().notifyListeners(SWT.Selection, new Event());
    assertTrue(opened.get());
  }

  private void awaitDeletePrompt() {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.deleteDialog() != null && !window.deleteDialog().shell().isDisposed();
          }
        },
        "delete prompt");
  }

  private static List<String> labels(Composite parent) {
    List<String> texts = new ArrayList<String>();
    for (Control child : parent.getChildren()) {
      if (child instanceof Label label) {
        texts.add(label.getText());
      } else if (child instanceof Composite composite) {
        texts.addAll(labels(composite));
      }
    }
    return texts;
  }

  @Test
  void aDragOfSeveralRowsCarriesEveryFileAndOpensThem() {
    WorkspaceDock dock = browser();
    select(dock, "alpha/", "notes.txt", "todo.txt");
    String[] data = dock.drag().data();
    assertArrayEquals(
        new String[] {root.resolve("notes.txt").toString(), root.resolve("todo.txt").toString()},
        data);
    window.dropped(data);
    awaitTabs(List.of("notes.txt", "todo.txt"));
  }

  /**
   * Drags two selected rows onto the editor through XTest, the pointer pressed on one of them. GTK
   * 3 only: GTK 4 SWT selects the pressed row alone before the drag starts, so one file goes
   * (measured).
   */
  @Test
  void twoSelectedFilesDraggedOntoTheEditorBothOpen() {
    assumeTrue(
        "gtk".equals(SWT.getPlatform()) && !gtk4(), "posted multi-row drags are checked on GTK 3");
    WorkspaceDock dock = browser();
    controller.documents().newDocument();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && window.activeText() != null;
          }
        },
        "the untitled tab");
    click(dock, "notes.txt", 0);
    click(dock, "todo.txt", CONTROL);
    awaitSelection(dock.tree(), List.of("notes.txt", "todo.txt"));
    StyledText text = window.activeText();
    Rectangle area = text.getClientArea();
    xtest(
        XTEST_DRAG, center(dock, "notes.txt"), text.toDisplay(area.width / 2, area.height / 2), 0);
    awaitTabs(List.of("notes.txt", "todo.txt"));
  }

  private static boolean gtk4() {
    return "1".equals(System.getenv("SWT_GTK4"));
  }

  /**
   * Returns a top-level row's name cell center on the display; GTK 4 bounds start below the header.
   */
  private static Point center(WorkspaceDock dock, String text) {
    Rectangle bounds = row(dock, text).getBounds(0);
    int header = gtk4() ? dock.tree().getHeaderHeight() : 0;
    return dock.tree()
        .toDisplay(bounds.x + bounds.width / 2, header + bounds.y + bounds.height / 2);
  }

  /** Clicks a row's name with a modifier key held, or none for 0. */
  private void click(WorkspaceDock dock, String text, int keysym) {
    Point at = center(dock, text);
    xtest(XTEST_CLICK, at, at, keysym);
  }

  private static final String XTEST_CLICK =
      """
      import ctypes, sys, time
      x11 = ctypes.CDLL("libX11.so.6")
      xtst = ctypes.CDLL("libXtst.so.6")
      x11.XOpenDisplay.restype = ctypes.c_void_p
      d = ctypes.c_void_p(x11.XOpenDisplay(None))
      x, y, key = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[5])
      code = x11.XKeysymToKeycode(d, key) if key else 0
      xtst.XTestFakeMotionEvent(d, -1, x, y, 0)
      x11.XFlush(d)
      time.sleep(0.05)
      if code:
          xtst.XTestFakeKeyEvent(d, code, True, 0)
          x11.XFlush(d)
          time.sleep(0.05)
      xtst.XTestFakeButtonEvent(d, 1, True, 0)
      xtst.XTestFakeButtonEvent(d, 1, False, 0)
      x11.XFlush(d)
      time.sleep(0.05)
      if code:
          xtst.XTestFakeKeyEvent(d, code, False, 0)
      x11.XFlush(d)
      x11.XCloseDisplay(d)
      """;

  private static final String XTEST_DRAG =
      """
      import ctypes, sys, time
      x11 = ctypes.CDLL("libX11.so.6")
      xtst = ctypes.CDLL("libXtst.so.6")
      x11.XOpenDisplay.restype = ctypes.c_void_p
      d = ctypes.c_void_p(x11.XOpenDisplay(None))
      x0, y0, x1, y1 = (int(v) for v in sys.argv[1:5])
      def move(x, y):
          xtst.XTestFakeMotionEvent(d, -1, x, y, 0)
          x11.XFlush(d)
          time.sleep(0.03)
      move(x0, y0)
      xtst.XTestFakeButtonEvent(d, 1, True, 0)
      x11.XFlush(d)
      time.sleep(0.1)
      for step in range(1, 21):
          move(x0 + (x1 - x0) * step // 20, y0 + (y1 - y0) * step // 20)
      time.sleep(0.3)
      move(x1 + 1, y1)
      xtst.XTestFakeButtonEvent(d, 1, False, 0)
      x11.XFlush(d)
      x11.XCloseDisplay(d)
      """;

  /**
   * Runs an XTest script from a separate process, so the UI thread keeps reading events, and waits
   * for it with the event loop running.
   */
  private void xtest(String script, Point from, Point to, int keysym) {
    Process python;
    try {
      python =
          new ProcessBuilder(
                  "python3",
                  "-I",
                  "-c",
                  script,
                  String.valueOf(from.x),
                  String.valueOf(from.y),
                  String.valueOf(to.x),
                  String.valueOf(to.y),
                  String.valueOf(keysym))
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException e) {
      python = null;
    }
    assumeTrue(python != null, "no python3 for posted input");
    final Process gesture = python;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !gesture.isAlive();
          }
        },
        "the posted input");
    assumeTrue(gesture.exitValue() == 0, "no python3 with libXtst for posted input");
    pause(100);
  }

  /**
   * Saves the row menu over three selected rows ({@code workspace-multi-menu-<theme>.png}) and
   * their delete prompt ({@code workspace-multi-delete-<theme>.png}).
   */
  @Test
  void screenshotsWhenAsked() {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    WorkspaceDock dock = browser();
    for (final Palette palette : new Palette[] {Palette.LIGHT, Palette.DARK}) {
      controller.execute(palette.dark() ? CommandId.THEME_DARK : CommandId.THEME_LIGHT);
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.palette().equals(palette);
            }
          },
          "palette");
      String theme = palette.dark() ? "dark" : "light";
      select(dock, "alpha/", "notes.txt", "todo.txt");
      Menu menu = menu(dock);
      Rectangle at = row(dock, "todo.txt").getBounds();
      menu.setLocation(dock.tree().toDisplay(at.x + 20, at.y + at.height));
      menu.setVisible(true);
      capture(dir, "workspace-multi-menu-" + theme);
      menu.setVisible(false);
      Event key = new Event();
      key.keyCode = SWT.DEL;
      dock.tree().notifyListeners(SWT.KeyDown, key);
      awaitDeletePrompt();
      capture(dir, "workspace-multi-delete-" + theme);
      window.deleteDialog().shell().close();
    }
  }

  private void capture(String dir, String name) {
    pause(700);
    Rectangle bounds = window.shell().getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
    } finally {
      gc.dispose();
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(Path.of(dir, name + ".png").toString(), SWT.IMAGE_PNG);
    image.dispose();
  }
}
