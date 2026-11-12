package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser at the default window size and side weights: two directories expanded by
 * posted clicks on their expanders, controls and columns that fit the dock, the rows' tool tip, and
 * the tree's file drag, by its data and by posted drags onto the editor and the tab strip.
 */
@Tag("swt")
class WorkspaceDockLayoutTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

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
    write("alpha/one.txt", 1_536);
    write("beta/two.txt", 12);
    write("notes.txt", 40_000);
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

  private void write(String relative, int bytes) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    byte[] text = new byte[bytes];
    Arrays.fill(text, (byte) 'a');
    Files.write(file, text);
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
            return dock.texts(null).equals(List.of("alpha/", "beta/", "notes.txt"));
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
    return null;
  }

  /**
   * Clicks a top-level row's expander with posted mouse events. GTK 4 SWT does not implement {@link
   * Display#post}, so there the events go through the X server's XTest extension from {@code
   * python3}; its item bounds start below the header, GTK 3's at the header.
   */
  private void clickExpander(WorkspaceDock dock, String text) {
    TreeItem item = row(dock, text);
    assertNotNull(item, text);
    Rectangle bounds = item.getBounds();
    int header = gtk4() ? dock.tree().getHeaderHeight() : 0;
    Point at = dock.tree().toDisplay(bounds.x / 2, header + bounds.y + bounds.height / 2);
    post(SWT.MouseMove, at, 0);
    post(SWT.MouseDown, at, 1);
    post(SWT.MouseUp, at, 1);
  }

  /**
   * Posted pointer events land on the expander and cell geometry of GTK only: on win32 the click
   * misses the expander and on cocoa posting them ends the test JVM. The G1-G3 host rows click
   * there by hand.
   */
  private static void assumePostedClicks() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "posted clicks are checked on GTK only");
  }

  private static boolean gtk4() {
    return "1".equals(System.getenv("SWT_GTK4"));
  }

  private static final String XTEST =
      """
      import ctypes, sys
      x11 = ctypes.CDLL("libX11.so.6")
      xtst = ctypes.CDLL("libXtst.so.6")
      x11.XOpenDisplay.restype = ctypes.c_void_p
      d = ctypes.c_void_p(x11.XOpenDisplay(None))
      kind, x, y, b = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
      if kind == "move":
          xtst.XTestFakeMotionEvent(d, -1, x, y, 0)
      else:
          xtst.XTestFakeButtonEvent(d, b, kind == "down", 0)
      x11.XFlush(d)
      x11.XCloseDisplay(d)
      """;

  private void post(int type, Point at, int button) {
    Event event = new Event();
    event.type = type;
    event.x = at.x;
    event.y = at.y;
    event.button = button;
    if (!display.post(event)) {
      assertTrue(gtk4(), "Display.post failed");
      String kind = type == SWT.MouseMove ? "move" : type == SWT.MouseDown ? "down" : "up";
      int exit;
      try {
        Process python =
            new ProcessBuilder(
                    "python3",
                    "-I",
                    "-c",
                    XTEST,
                    kind,
                    String.valueOf(at.x),
                    String.valueOf(at.y),
                    String.valueOf(button))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        exit = python.waitFor();
      } catch (IOException e) {
        exit = -1;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
      assumeTrue(exit == 0, "no python3 with libXtst for posted clicks under GTK 4");
    }
    pause(20);
  }

  private WorkspaceNode directory(String name) {
    return new WorkspaceNode(root.resolve(name), name, WorkspaceNode.Kind.DIRECTORY);
  }

  /** Lists 1,000 entries per directory, so each expansion shows the loading row first. */
  @Test
  void twoDirectoriesExpandByClicksAndStayExpanded() throws IOException {
    assumePostedClicks();
    for (int i = 0; i < 1_000; i++) {
      write("alpha/f" + i + ".txt", 1);
      write("beta/g" + i + ".txt", 1);
    }
    final WorkspaceDock dock = browser();
    final WorkspaceNode alpha = directory("alpha");
    final WorkspaceNode beta = directory("beta");
    clickExpander(dock, "alpha/");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(alpha).contains("f0.txt");
          }
        },
        "alpha listed");
    clickExpander(dock, "beta/");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(beta).contains("g0.txt");
          }
        },
        "beta listed");
    pause(300);
    assertEquals(List.of(alpha, beta), dock.expanded());
    assertTrue(row(dock, "alpha/").getExpanded() && row(dock, "beta/").getExpanded());
  }

  @Test
  void controlsAndColumnsFitTheDockAtTheDefaultSash() {
    WorkspaceDock dock = browser();
    Composite top = dock.control();
    int width = top.getClientArea().width;
    assertTrue(width > 0, "dock width");
    List<Control> controls = new ArrayList<Control>();
    collect(top, controls);
    for (Control control : controls) {
      Rectangle bounds = display.map(control.getParent(), top, control.getBounds());
      String name = control + " " + control.getToolTipText() + " " + bounds;
      assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= width, name + " in " + width);
      if (control instanceof Button || control instanceof ToolBar) {
        assertTrue(
            bounds.width >= control.computeSize(SWT.DEFAULT, SWT.DEFAULT).x, name + " clipped");
      }
    }
    Tree tree = dock.tree();
    int used = 0;
    for (TreeColumn column : tree.getColumns()) {
      used += column.getWidth();
    }
    assertTrue(used <= tree.getClientArea().width, used + " in " + tree.getClientArea().width);
    GC gc = new GC(tree);
    try {
      for (TreeItem item : tree.getItems()) {
        for (int column = 1; column < 3; column++) {
          int text = gc.textExtent(item.getText(column)).x;
          assertTrue(
              tree.getColumn(column).getWidth() >= text + 8,
              item.getText(column) + " in " + tree.getColumn(column).getWidth());
        }
      }
    } finally {
      gc.dispose();
    }
  }

  @Test
  void aRowsToolTipIsItsPathSizeAndModifiedTimeAndChangesWithTheRow() {
    assumePostedClicks();
    WorkspaceDock dock = browser();
    Tree tree = dock.tree();
    TreeItem notes = row(dock, "notes.txt");
    WorkspaceNode file = (WorkspaceNode) notes.getData();
    assertTrue(file.modifiedTime().length() > file.modified().length(), file.toString());
    move(tree, notes.getBounds(0));
    String tip = tree.getToolTipText();
    assertEquals(
        file.path()
            + "\n"
            + UiText.WORKSPACE_TIP_SIZE.format(file.size())
            + "\n"
            + UiText.WORKSPACE_TIP_MODIFIED.format(file.modifiedTime()),
        tip);
    move(tree, notes.getBounds(2));
    assertSame(tip, tree.getToolTipText(), "set again within the row");
    TreeItem alpha = row(dock, "alpha/");
    WorkspaceNode folder = (WorkspaceNode) alpha.getData();
    move(tree, alpha.getBounds(1));
    assertEquals(
        folder.path() + "\n" + UiText.WORKSPACE_TIP_MODIFIED.format(folder.modifiedTime()),
        tree.getToolTipText());
    tree.notifyListeners(SWT.MouseExit, new Event());
    assertNull(tree.getToolTipText());
  }

  @Test
  void onlyEntriesHaveATip() {
    Path file = root.resolve("a.txt");
    assertEquals(
        file + "\nSize: 12 B\nModified: 2026-11-11 10:00",
        BrowserRowTip.text(
            new WorkspaceNode(
                file, "a.txt", WorkspaceNode.Kind.FILE, "12 B", "2026-11-11", "2026-11-11 10:00")));
    assertNull(BrowserRowTip.text(new WorkspaceNode(root, "more", WorkspaceNode.Kind.MORE)));
    assertNull(BrowserRowTip.text(new WorkspaceNode(root, "denied", WorkspaceNode.Kind.ERROR)));
  }

  @Test
  void aDragCarriesTheSelectedFileCopyOnlyAndAFolderStartsNone() throws Exception {
    WorkspaceDock dock = browser();
    Tree tree = dock.tree();
    BrowserDrag drag = dock.drag();
    assertEquals(
        DND.DROP_COPY, drag.source().getStyle() & (DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_LINK));
    assertArrayEquals(new Transfer[] {FileTransfer.getInstance()}, drag.source().getTransfer());
    tree.setSelection(row(dock, "alpha/"));
    assertArrayEquals(new String[0], drag.data());
    TreeItem notes = row(dock, "notes.txt");
    tree.setSelection(notes);
    String[] data = drag.data();
    assertArrayEquals(new String[] {root.resolve("notes.txt").toString()}, data);
    window.dropped(data);
    awaitTab("notes.txt");
  }

  @Test
  void aFileDraggedFromTheTreeOpensOverTheEditor() throws Exception {
    assumePostedClicks();
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
    StyledText text = window.activeText();
    Rectangle area = text.getClientArea();
    dragRow(dock, "notes.txt", text.toDisplay(area.width / 2, area.height / 2));
    awaitTab("notes.txt");
  }

  @Test
  void aFileDraggedFromTheTreeOpensOverTheTabStrip() throws Exception {
    assumePostedClicks();
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
    CTabFolder tabs = window.tabs();
    int height = tabs.getTabHeight();
    dragRow(dock, "notes.txt", tabs.toDisplay(tabs.getItem(0).getBounds().x + 10, height / 2));
    awaitTab("notes.txt");
  }

  private void awaitTab(final String name) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (CTabItem item : window.tabs().getItems()) {
              if (item.getText().equals(name)) {
                return true;
              }
            }
            return false;
          }
        },
        "a tab " + name);
  }

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
   * Drags a top-level row's name to a display point through XTest from a separate process, so the
   * pointer keeps moving while the UI thread runs SWT's drag detection.
   */
  private void dragRow(WorkspaceDock dock, String name, Point to) throws Exception {
    TreeItem item = row(dock, name);
    assertNotNull(item, name);
    Rectangle bounds = item.getBounds(0);
    int header = gtk4() ? dock.tree().getHeaderHeight() : 0;
    Point from =
        dock.tree().toDisplay(bounds.x + bounds.width / 2, header + bounds.y + bounds.height / 2);
    Process python;
    try {
      python =
          new ProcessBuilder(
                  "python3",
                  "-I",
                  "-c",
                  XTEST_DRAG,
                  String.valueOf(from.x),
                  String.valueOf(from.y),
                  String.valueOf(to.x),
                  String.valueOf(to.y))
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException e) {
      python = null;
    }
    assumeTrue(python != null, "no python3 for a posted drag");
    final Process gesture = python;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !gesture.isAlive();
          }
        },
        "the posted drag");
    assumeTrue(gesture.exitValue() == 0, "no python3 with libXtst for a posted drag");
  }

  private static void move(Tree tree, Rectangle cell) {
    Event move = new Event();
    move.x = cell.x + cell.width / 2;
    move.y = (gtk4() ? tree.getHeaderHeight() : 0) + cell.y + cell.height / 2;
    tree.notifyListeners(SWT.MouseMove, move);
  }

  private static void collect(Composite parent, List<Control> controls) {
    for (Control child : parent.getChildren()) {
      if (child instanceof Tree) {
        continue;
      }
      controls.add(child);
      if (child instanceof Composite composite) {
        collect(composite, controls);
      }
    }
  }
}
