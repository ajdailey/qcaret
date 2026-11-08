package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
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
 * posted clicks on their expanders, controls and columns that fit the dock, and the Modified cell's
 * tool tip.
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
    Files.write(file, new byte[bytes]);
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
      if (control instanceof Button) {
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
  void theModifiedCellShowsTheDateAndTimeAsItsToolTip() {
    WorkspaceDock dock = browser();
    Tree tree = dock.tree();
    TreeItem notes = row(dock, "notes.txt");
    WorkspaceNode node = (WorkspaceNode) notes.getData();
    assertTrue(node.modifiedTime().startsWith(node.modified()), node.toString());
    assertTrue(node.modifiedTime().length() > node.modified().length(), node.toString());
    move(tree, notes.getBounds(2));
    assertEquals(node.modifiedTime(), tree.getToolTipText());
    move(tree, notes.getBounds(0));
    assertNull(tree.getToolTipText());
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
