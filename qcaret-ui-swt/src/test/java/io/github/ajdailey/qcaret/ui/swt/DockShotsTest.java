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
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Search Results, Bookmarks and Outline docks in a real window, after a light and a dark start
 * and a live switch to the other side. With {@code -Dqcaret.screenshots=<dir>} it saves the window
 * at each step, {@code docks-<start>-<step>.png}; on win32 it reads the dock tables' drawn
 * background on the dark side.
 */
@Tag("swt")
class DockShotsTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @AfterEach
  void close() {
    try {
      if (controller != null) {
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
      }
      if (display != null) {
        display.dispose();
      }
    } finally {
      assertNull(failure.get());
    }
  }

  @Test
  void docksAfterALightStart() throws Exception {
    run(ThemeMode.LIGHT);
  }

  @Test
  void docksAfterADarkStart() throws Exception {
    run(ThemeMode.DARK);
  }

  private void run(ThemeMode start) throws Exception {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    boolean dark = start == ThemeMode.DARK;
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
    SwtThemeService theme = SwtThemeService.open(display, start);
    window = new MainWindow(display, dark ? Palette.DARK : Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS.withTheme(start),
            theme,
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
    window.open();
    controller.loadGrammars();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.grammars().set() != null;
          }
        },
        "grammars");

    Path tree = Files.createDirectories(temp.resolve("tree"));
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 40; i++) {
      text.append("mock line ").append(i).append('\n');
    }
    Path plain = Files.writeString(tree.resolve("f19.txt"), text, StandardCharsets.UTF_8);
    Path markdown =
        Files.writeString(
            tree.resolve("doc.md"), "# Mock doc\n\n## Setup\n\n## Usage\n", StandardCharsets.UTF_8);
    open(plain, 1);
    controller.documents().activeEditor().goTo(1, 0);
    controller.execute(CommandId.TOGGLE_BOOKMARK);
    controller.execute(CommandId.BOOKMARKS_LIST);
    final ResultsInput results = controller.search();
    controller
        .search()
        .run(
            new SearchForm(
                "line 1",
                false,
                false,
                false,
                SearchScope.TREE,
                tree.toString(),
                "",
                "",
                false,
                "",
                List.of(),
                List.of(),
                List.of()));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return results.setCount() == 1 && !results.running(0);
          }
        },
        "search ends");
    open(markdown, 2);
    controller.execute(CommandId.MARKDOWN_OUTLINE);
    drain();
    assertTrue(window.outlineShown());
    select(window.bookmarksDock().table(), 0);
    select(window.resultsDock().table(), 1);

    String side = dark ? "dark" : "light";
    capture(dir, start, "markdown-" + side);
    checkTables(dark);
    controller.execute(CommandId.PREVIOUS_TAB);
    drain();
    assertFalse(window.outlineShown());
    select(window.bookmarksDock().table(), 0);
    capture(dir, start, "plain-" + side);

    controller.execute(dark ? CommandId.THEME_LIGHT : CommandId.THEME_DARK);
    drain();
    pause(1000);
    String other = dark ? "light" : "dark";
    capture(dir, start, "plain-" + other);
    checkTables(!dark);
  }

  private void open(Path file, final int count) {
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == count && controller.documents().loading() == 0;
          }
        },
        file.getFileName().toString());
    drain();
  }

  private void select(Table table, int row) {
    if (table.getItemCount() > row) {
      table.setSelection(row);
      table.notifyListeners(SWT.Selection, new Event());
    }
  }

  /**
   * On win32 under dark, reads a pixel in each dock table's empty area: the table draws {@code
   * Palette.DARK}'s background, not the native white. Skipped where another window covers the shell
   * ({@link ScreenProbe}).
   */
  private void checkTables(boolean dark) {
    if (!dark
        || !"win32".equals(PlatformTraits.PLATFORM.name())
        || !ScreenProbe.shows(window.shell())) {
      return;
    }
    RGB field =
        new RGB(
            Palette.DARK.background().red(),
            Palette.DARK.background().green(),
            Palette.DARK.background().blue());
    for (Table table : new Table[] {window.bookmarksDock().table(), window.resultsDock().table()}) {
      Rectangle client = table.getClientArea();
      Point at = table.toDisplay(client.x + 3, client.y + client.height - 4);
      assertEquals(field, ScreenProbe.pixel(window.shell(), at.x, at.y), table + " at " + at);
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
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

  /** Copies the window's area to a PNG. */
  private void capture(String dir, ThemeMode start, String step) {
    pause(500);
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
    String name = "docks-" + start.name().toLowerCase(Locale.ROOT) + "-" + step + ".png";
    loader.save(Path.of(dir, name).toString(), SWT.IMAGE_PNG);
    image.dispose();
  }
}
