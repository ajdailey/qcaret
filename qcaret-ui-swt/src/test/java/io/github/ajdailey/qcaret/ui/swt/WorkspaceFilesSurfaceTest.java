package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser's row menu, F2 and Delete in the tree, the name dialog and the delete
 * prompt; with {@code -Dqcaret.screenshots=<dir>}, the menu, the rename dialog and a folder's
 * delete prompt as PNG in the light and the dark theme.
 */
@Tag("swt")
class WorkspaceFilesSurfaceTest {
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
    write("docs/guide.txt", "Mock guide\n");
    write("docs/drafts/one.txt", "one\n");
    write("docs/drafts/two.txt", "two\n");
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

  private void write(String relative, String text) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
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

  private WorkspaceDock browser(final List<String> rows) {
    controller.execute(CommandId.WORKSPACE_BROWSER);
    final WorkspaceDock dock = window.workspaceDock();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).equals(rows);
          }
        },
        "root rows " + rows);
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

  /** Runs the menu's detect step for the selection, as a keyboard menu request does. */
  private static Menu menuFor(WorkspaceDock dock, TreeItem item) {
    if (item == null) {
      dock.tree().deselectAll();
    } else {
      dock.tree().setSelection(item);
    }
    Event event = new Event();
    event.detail = SWT.MENU_KEYBOARD;
    dock.tree().notifyListeners(SWT.MenuDetect, event);
    return dock.tree().getMenu();
  }

  private static boolean enabled(Menu menu, UiText label) {
    for (MenuItem item : menu.getItems()) {
      if (item.getText().equals(label.text())) {
        return item.getEnabled();
      }
    }
    throw new AssertionError(label.text());
  }

  private static void key(WorkspaceDock dock, int code) {
    Event event = new Event();
    event.keyCode = code;
    dock.tree().notifyListeners(SWT.KeyDown, event);
  }

  @Test
  void theRowMenuOffersFileOperationsAndSkipsRowEditsOnTheRoot() {
    WorkspaceDock dock = browser(List.of("docs/", "README.txt"));
    Menu menu = menuFor(dock, row(dock, "README.txt"));
    assertTrue(enabled(menu, UiText.WORKSPACE_SEARCH_FOLDER));
    assertFalse(enabled(menu, UiText.WORKSPACE_NEW_FILE));
    assertTrue(enabled(menu, UiText.WORKSPACE_RENAME));
    assertTrue(enabled(menu, UiText.WORKSPACE_DELETE));
    menu = menuFor(dock, row(dock, "docs/"));
    assertTrue(enabled(menu, UiText.WORKSPACE_NEW_FILE));
    assertTrue(enabled(menu, UiText.WORKSPACE_NEW_FOLDER));
    assertTrue(enabled(menu, UiText.WORKSPACE_DELETE));
    menu = menuFor(dock, null);
    assertTrue(enabled(menu, UiText.WORKSPACE_NEW_FILE));
    assertTrue(enabled(menu, UiText.WORKSPACE_SEARCH_FOLDER));
    assertFalse(enabled(menu, UiText.WORKSPACE_SET_FOLDER));
    assertFalse(enabled(menu, UiText.WORKSPACE_RENAME));
    assertFalse(enabled(menu, UiText.WORKSPACE_DELETE));
  }

  @Test
  void f2RenamesTheSelectedRowAndAnOpenDocumentFollows() {
    final WorkspaceDock dock = browser(List.of("docs/", "README.txt"));
    controller.documents().open(List.of(root.resolve("README.txt")));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1;
          }
        },
        "opened");
    dock.tree().setSelection(row(dock, "README.txt"));
    key(dock, SWT.F2);
    FileNameDialog dialog = window.fileNameDialog();
    assertNotNull(dialog);
    assertEquals("README.txt", dialog.field().getText());
    assertEquals(new Point(0, "README".length()), dialog.field().getSelection());
    dialog.field().setText("NOTES.txt");
    dialog.ok().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).equals(List.of("docs/", "NOTES.txt"))
                && window.tabs().getItem(0).getText().equals("NOTES.txt");
          }
        },
        "renamed row and tab");
    assertEquals(root.resolve("NOTES.txt"), controller.documents().activePath());
    assertTrue(window.shell().getText().contains("NOTES.txt"), window.shell().getText());
  }

  @Test
  void deleteAsksWithCancelAsDefaultThenDeletesTheFolder() {
    final WorkspaceDock dock = browser(List.of("docs/", "README.txt"));
    dock.tree().setSelection(row(dock, "docs/"));
    key(dock, SWT.DEL);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.deleteDialog() != null;
          }
        },
        "asked");
    DeleteDialog dialog = window.deleteDialog();
    assertEquals(dialog.cancel(), dialog.shell().getDefaultButton());
    dialog.cancel().notifyListeners(SWT.Selection, new Event());
    assertTrue(Files.isDirectory(root.resolve("docs")));

    key(dock, SWT.DEL);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.deleteDialog() != null && !window.deleteDialog().shell().isDisposed();
          }
        },
        "asked again");
    window.deleteDialog().delete().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).equals(List.of("README.txt"));
          }
        },
        "docs deleted");
    assertFalse(Files.exists(root.resolve("docs")));
  }

  /**
   * Saves the row menu ({@code workspace-menu-<theme>.png}), the rename dialog ({@code
   * workspace-rename-<theme>.png}) and a folder's delete prompt ({@code
   * workspace-delete-<theme>.png}).
   */
  @Test
  void screenshotsWhenAsked() {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    final WorkspaceDock dock = browser(List.of("docs/", "README.txt"));
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
      TreeItem docs = row(dock, "docs/");
      Menu menu = menuFor(dock, docs);
      Rectangle at = docs.getBounds();
      Point point = dock.tree().toDisplay(at.x + 20, at.y + at.height);
      menu.setLocation(point);
      menu.setVisible(true);
      capture(dir, "workspace-menu-" + theme, window.shell().getBounds());
      menu.setVisible(false);

      dock.tree().setSelection(docs);
      key(dock, SWT.F2);
      Shell rename = window.fileNameDialog().shell();
      capture(dir, "workspace-rename-" + theme, window.shell().getBounds());
      rename.close();

      key(dock, SWT.DEL);
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.deleteDialog() != null && !window.deleteDialog().shell().isDisposed();
            }
          },
          "delete prompt");
      capture(dir, "workspace-delete-" + theme, window.shell().getBounds());
      window.deleteDialog().shell().close();
    }
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
