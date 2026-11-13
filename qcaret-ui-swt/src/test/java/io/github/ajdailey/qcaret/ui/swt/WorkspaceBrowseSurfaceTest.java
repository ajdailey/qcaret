package io.github.ajdailey.qcaret.ui.swt;

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
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
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
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser's path field and its tool tip, Parent and Workspace buttons, filter field,
 * Hidden check box, Favorites menu and sortable Size and Modified columns; with {@code
 * -Dqcaret.screenshots=<dir>}, the browser with a filter and a size sort as PNG in the light and
 * the dark theme.
 */
@Tag("swt")
class WorkspaceBrowseSurfaceTest {
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
    write("README.txt", 1_536);
    write("build.xml", 40_000);
    write("docs/guide.txt", 12);
    write("docs/drafts/one.txt", 4);
    write(".settings.txt", 3);
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

  private WorkspaceDock browser(List<String> rows) {
    controller.execute(CommandId.WORKSPACE_BROWSER);
    WorkspaceDock dock = window.workspaceDock();
    awaitRows(dock, rows);
    return dock;
  }

  private void awaitRows(final WorkspaceDock dock, final List<String> rows) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).equals(rows);
          }
        },
        "rows " + rows);
  }

  private static void enter(Text field, String text) {
    field.setText(text);
    field.notifyListeners(SWT.DefaultSelection, new Event());
  }

  private static List<String> menuTexts(BrowseBar bar) {
    bar.fillFavorites();
    List<String> texts = new ArrayList<String>();
    for (MenuItem item : bar.favoritesMenu().getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) != 0) {
        texts.add("|");
      } else {
        texts.add((item.getEnabled() ? "" : "-") + item.getText());
      }
    }
    return texts;
  }

  private static void choose(BrowseBar bar, UiText label) {
    bar.fillFavorites();
    for (MenuItem item : bar.favoritesMenu().getItems()) {
      if (item.getText().equals(label.text())) {
        item.notifyListeners(SWT.Selection, new Event());
        return;
      }
    }
    throw new AssertionError(label.text());
  }

  @Test
  void thePathFieldParentAndWorkspaceButtonsBrowseAndReturn() {
    final WorkspaceDock dock = browser(List.of("docs/", "build.xml", "README.txt"));
    BrowseBar bar = dock.bar();
    assertEquals(root.toString(), bar.path().getText());
    assertEquals(UiText.BROWSE_PATH_WORKSPACE.text(), bar.path().getToolTipText());
    assertFalse(bar.workspaceItem().getEnabled());
    enter(bar.path(), "docs");
    awaitRows(dock, List.of("drafts/", "guide.txt"));
    assertEquals(root.resolve("docs").toString(), bar.path().getText());
    assertEquals(UiText.BROWSE_PATH_OTHER.format(root.toString()), bar.path().getToolTipText());
    assertTrue(bar.workspaceItem().getEnabled());
    bar.press(UiText.BROWSE_PARENT);
    awaitRows(dock, List.of("docs/", "build.xml", "README.txt"));
    bar.press(UiText.BROWSE_PARENT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(null).contains("mock-project/");
          }
        },
        "the parent lists");
    bar.press(UiText.BROWSE_WORKSPACE);
    awaitRows(dock, List.of("docs/", "build.xml", "README.txt"));
    enter(bar.path(), "missing");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.noticeText().endsWith("missing does not exist");
          }
        },
        "missing path notice");
  }

  @Test
  void columnsShowSizeAndTimeAndAHeaderClickSorts() {
    final WorkspaceDock dock = browser(List.of("docs/", "build.xml", "README.txt"));
    assertEquals(3, dock.tree().getColumnCount());
    assertTrue(dock.tree().getHeaderVisible());
    List<String> readme = dock.columnTexts("README.txt");
    assertTrue(readme.get(0).endsWith(" KiB"), readme.toString());
    assertFalse(readme.get(1).isEmpty());
    assertEquals("", dock.columnTexts("docs/").get(0));
    assertEquals(dock.tree().getColumn(0), dock.tree().getSortColumn());
    assertEquals(SWT.UP, dock.tree().getSortDirection());

    dock.tree().getColumn(1).notifyListeners(SWT.Selection, new Event());
    awaitRows(dock, List.of("docs/", "README.txt", "build.xml"));
    assertEquals(dock.tree().getColumn(1), dock.tree().getSortColumn());
    dock.tree().getColumn(1).notifyListeners(SWT.Selection, new Event());
    awaitRows(dock, List.of("docs/", "build.xml", "README.txt"));
    assertEquals(SWT.DOWN, dock.tree().getSortDirection());
  }

  @Test
  void aDockWithoutAWorkspaceShowsNoSortColumn() {
    WorkspaceDock dock = browser(List.of("docs/", "build.xml", "README.txt"));
    assertEquals(dock.tree().getColumn(0), dock.tree().getSortColumn());
    dock.setInput(null);
    assertNull(dock.tree().getSortColumn());
    assertEquals(List.of(), dock.texts(null));
  }

  @Test
  void theFilterFieldAndHiddenCheckBoxListAgain() {
    final WorkspaceDock dock = browser(List.of("docs/", "build.xml", "README.txt"));
    BrowseBar bar = dock.bar();
    enter(bar.filter(), "*.txt");
    awaitRows(dock, List.of("docs/", "README.txt"));
    bar.hidden().setSelection(true);
    bar.hidden().notifyListeners(SWT.Selection, new Event());
    awaitRows(dock, List.of("docs/", ".settings.txt", "README.txt"));
    assertEquals("*.txt", bar.filter().getText());
    assertTrue(bar.hidden().getSelection());
  }

  @Test
  void theFavoritesMenuAddsListsOpensAndRemoves() {
    final WorkspaceDock dock = browser(List.of("docs/", "build.xml", "README.txt"));
    BrowseBar bar = dock.bar();
    assertEquals(
        List.of(
            "-" + UiText.BROWSE_NO_FAVORITES.text(),
            "|",
            UiText.BROWSE_ADD_FAVORITE.text(),
            "-" + UiText.BROWSE_REMOVE_FAVORITE.text()),
        menuTexts(bar));
    enter(bar.path(), "docs");
    awaitRows(dock, List.of("drafts/", "guide.txt"));
    choose(bar, UiText.BROWSE_ADD_FAVORITE);
    bar.press(UiText.BROWSE_WORKSPACE);
    awaitRows(dock, List.of("docs/", "build.xml", "README.txt"));
    String docs = root.resolve("docs").toString();
    assertEquals(
        List.of(
            docs,
            "|",
            UiText.BROWSE_ADD_FAVORITE.text(),
            "-" + UiText.BROWSE_REMOVE_FAVORITE.text()),
        menuTexts(bar));
    bar.favoritesMenu().getItem(0).notifyListeners(SWT.Selection, new Event());
    awaitRows(dock, List.of("drafts/", "guide.txt"));
    choose(bar, UiText.BROWSE_REMOVE_FAVORITE);
    assertEquals(List.of(), controller.settings().current().state().favorites());
  }

  /** Saves the browser with a filter and a size sort ({@code workspace-browse-<theme>.png}). */
  @Test
  void screenshotsWhenAsked() {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    final WorkspaceDock dock = browser(List.of("docs/", "build.xml", "README.txt"));
    window.setSideWeights(new int[] {2, 3});
    enter(dock.bar().filter(), "*.txt;*.xml");
    dock.tree().getColumn(1).notifyListeners(SWT.Selection, new Event());
    awaitRows(dock, List.of("docs/", "README.txt", "build.xml"));
    final WorkspaceNode docs =
        new WorkspaceNode(root.resolve("docs"), "docs", WorkspaceNode.Kind.DIRECTORY);
    dock.expand(docs);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.texts(docs).contains("guide.txt");
          }
        },
        "docs expanded");
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
      capture(dir, "workspace-browse-" + (palette.dark() ? "dark" : "light"));
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
