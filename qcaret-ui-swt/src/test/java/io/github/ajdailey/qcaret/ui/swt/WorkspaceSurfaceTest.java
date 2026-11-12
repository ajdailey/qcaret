package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
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
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
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
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser dock, the Quick Open popup and File > Recent Files in the window: rows
 * listed on expand, a file opened by Enter, a Quick Open match chosen, the recent entries, an empty
 * Quick Open's open documents in bold over the Recent Files not open, and, with {@code
 * -Dqcaret.screenshots=<dir>}, the browser and the popup as PNG.
 */
@Tag("swt")
class WorkspaceSurfaceTest {
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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1000, 680), null);
    window.open();
    root = Files.createDirectory(temp.resolve("mock-project")).toRealPath();
    write("README.txt", "Mock project\n");
    write("src/main/java/mock/Totals.java", "/** Mock totals. */\nfinal class Totals {}\n");
    write("src/main/java/mock/Report.java", "final class Report {\n  int total;\n}\n");
    write("src/test/java/mock/TotalsTest.java", "final class TotalsTest {}\n");
    write("docs/guide.txt", "Mock guide\n");
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

  private Path write(String relative, String text) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, text, StandardCharsets.UTF_8);
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

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private static WorkspaceNode directory(Path path) {
    return new WorkspaceNode(path, path.getFileName().toString(), WorkspaceNode.Kind.DIRECTORY);
  }

  /** Shows the browser and waits for the root's rows. */
  private WorkspaceDock browser() {
    controller.execute(CommandId.WORKSPACE_BROWSER);
    final WorkspaceDock dock = window.workspaceDock();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).equals(List.of("docs/", "src/", "README.txt"));
          }
        },
        "root rows");
    return dock;
  }

  private void expand(final WorkspaceDock dock, Path dir, final List<String> rows) {
    final WorkspaceNode node = directory(dir);
    dock.expand(node);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(node).equals(rows);
          }
        },
        "rows of " + dir.getFileName());
  }

  @Test
  void theBrowserButtonsShowInOneRowAtTheDefaultWidth() {
    window.place(new WindowGeometry.Request(0, 0, 960, 600), null);
    WorkspaceDock dock = browser();
    drain();
    assertEquals(1, rows(dock));
    window.place(new WindowGeometry.Request(0, 0, 640, 600), null);
    drain();
    assertEquals(1, rows(dock));
    window.setSideWeights(new int[] {1, 9});
    drain();
    assertTrue(rows(dock) > 1);
    Rectangle row = dock.buttons().getClientArea();
    for (Control bar : dock.buttons().getChildren()) {
      Rectangle bounds = bar.getBounds();
      assertEquals(bounds, row.intersection(bounds));
    }
  }

  /** Returns the number of rows the browser's tool bars take. */
  private static int rows(WorkspaceDock dock) {
    List<Integer> tops = new ArrayList<Integer>();
    for (Control bar : dock.buttons().getChildren()) {
      if (!tops.contains(bar.getLocation().y)) {
        tops.add(bar.getLocation().y);
      }
    }
    return tops.size();
  }

  @Test
  void theBrowserListsOnExpandAndOpensAFileOnEnter() {
    WorkspaceDock dock = browser();
    assertTrue(window.workspaceShown());
    TreeItem src = dock.tree().getItem(1);
    assertEquals(1, src.getItemCount());
    expand(dock, root.resolve("src"), List.of("main/", "test/"));
    expand(dock, root.resolve("src/main"), List.of("java/"));
    TreeItem readme = dock.tree().getItem(2);
    dock.tree().setSelection(readme);
    Event enter = new Event();
    enter.item = readme;
    dock.tree().notifyListeners(SWT.DefaultSelection, enter);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1;
          }
        },
        "README opened");
    dock.press(directory(root.resolve("docs")), UiText.WORKSPACE_COPY_PATH);
    assertEquals(root.resolve("docs").toString(), window.clipboardText());
    dock.press(null, UiText.WORKSPACE_HIDE);
    assertTrue(!window.workspaceShown());
  }

  @Test
  void revealExpandsTheActiveFilesDirectories() {
    final WorkspaceDock dock = browser();
    controller.documents().open(List.of(root.resolve("src/test/java/mock/TotalsTest.java")));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1;
          }
        },
        "opened");
    dock.press(null, UiText.WORKSPACE_REVEAL);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            TreeItem[] selection = dock.tree().getSelection();
            return selection.length == 1 && selection[0].getText().equals("TotalsTest.java");
          }
        },
        "revealed");
  }

  @Test
  void quickOpenOpensTheChosenFileAtItsLine() {
    controller.execute(CommandId.QUICK_OPEN);
    final QuickOpenWindow popup = window.quickOpen();
    popup.filter().setText("report:2");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return popup.table().getItemCount() == 1;
          }
        },
        "one match after the index");
    assertEquals("Report.java", popup.table().getItem(0).getText(0));
    assertEquals("src/main/java/mock/Report.java", popup.table().getItem(0).getText(1));
    assertTrue(popup.summary().startsWith("1 of 5"), popup.summary());
    popup.filter().notifyListeners(SWT.DefaultSelection, new Event());
    assertTrue(popup.shell().isDisposed());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1
                && window.activeEditor().text().getCaretOffset() > 0;
          }
        },
        "opened at line 2");
    StyledText text = window.activeEditor().text();
    assertEquals(1, text.getLineAtOffset(text.getCaretOffset()));

    window.menuBar().menu(CommandGroup.FILE).notifyListeners(SWT.Show, new Event());
    MenuItem clear = window.menuBar().item(CommandId.CLEAR_RECENT_FILES);
    MenuItem first = clear.getParent().getItem(0);
    assertEquals("&1 " + root.resolve("src/main/java/mock/Report.java"), first.getText());
    assertTrue(clear.getEnabled());
  }

  /** Opens a file and waits for its tab count. */
  private void openFile(String relative, final int tabs) {
    controller.documents().open(List.of(root.resolve(relative)));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == tabs;
          }
        },
        "opened " + relative);
  }

  private static boolean bold(TableItem item) {
    return (item.getFont().getFontData()[0].getStyle() & SWT.BOLD) != 0;
  }

  @Test
  void anEmptyQuickOpenListsOpenDocumentsInBoldThenRecentFiles() {
    openFile("src/main/java/mock/Totals.java", 1);
    openFile("README.txt", 2);
    controller.documents().closeActive();
    controller.execute(CommandId.QUICK_OPEN);
    QuickOpenWindow popup = window.quickOpen();
    Table table = popup.table();
    assertEquals(2, table.getItemCount());
    assertEquals("Totals.java", table.getItem(0).getText(0));
    assertEquals("src/main/java/mock/Totals.java", table.getItem(0).getText(1));
    assertTrue(bold(table.getItem(0)));
    assertEquals("README.txt", table.getItem(1).getText(1));
    assertFalse(bold(table.getItem(1)));
    popup.shell().close();
  }

  /**
   * Saves the browser ({@code workspace-browser.png}) and the Quick Open popup with an empty query
   * ({@code quick-open-empty.png}) and with a query ({@code quick-open.png}).
   */
  @Test
  void screenshotsWhenAsked() {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    WorkspaceDock dock = browser();
    expand(dock, root.resolve("src"), List.of("main/", "test/"));
    expand(dock, root.resolve("src/main"), List.of("java/"));
    expand(dock, root.resolve("src/main/java"), List.of("mock/"));
    expand(dock, root.resolve("src/main/java/mock"), List.of("Report.java", "Totals.java"));
    openFile("docs/guide.txt", 1);
    openFile("README.txt", 2);
    controller.documents().closeActive();
    openFile("src/main/java/mock/Totals.java", 2);
    dock.press(null, UiText.WORKSPACE_REVEAL);
    capture(dir, "workspace-browser", window.shell().getBounds());
    controller.execute(CommandId.QUICK_OPEN);
    final QuickOpenWindow popup = window.quickOpen();
    capture(dir, "quick-open-empty", window.shell().getBounds());
    popup.filter().setText("tot");
    capture(dir, "quick-open", window.shell().getBounds());
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

  private void capture(String dir, String name, Rectangle bounds) {
    pause(700);
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
