package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import io.github.ajdailey.qcaret.ui.swt.search.ResultsDock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Slider;
import org.eclipse.swt.widgets.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HyperSearch and the overview strip in a real window: the dialog runs a tree search into the dock,
 * two sets stay listed newest first, Enter on a match opens its file with the match selected, F4
 * steps on; the strip marks find matches, scrolls on a click, hides from the View menu, recolors on
 * a theme switch and exists in each view of a split; the slider follows wheel, page and fold
 * scrolling and scrolls the text; the find bar's fields offer the saved history. With {@code
 * -Dqcaret.screenshots=<dir>} it saves {@code find-history.png} there, the list open.
 */
@Tag("swt")
class SearchSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() {
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
    window.place(new WindowGeometry.Request(null, null, 700, 500), null);
    window.open();
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

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private EditorPane openFile(Path file) {
    final int before = window.tabs().getItemCount();
    controller.documents().open(java.util.List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0;
          }
        },
        String.valueOf(file.getFileName()));
    drain();
    return window.activeEditor();
  }

  /** Runs the dialog with a text and scope, and waits for the newest set to end. */
  private void search(String text, SearchScope scope, Path directory) {
    controller.execute(CommandId.HYPERSEARCH);
    drain();
    SearchDialog dialog = window.searchDialog();
    assertNotNull(dialog);
    dialog.text().setText(text);
    dialog.scope().select(scope.ordinal());
    dialog.scope().notifyListeners(SWT.Selection, new Event());
    if (directory != null) {
      dialog.directory().setText(directory.toString());
    }
    final ResultsInput results = controller.search();
    final int before = results.setCount();
    dialog.search();
    assertTrue(dialog.shell().isDisposed());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return results.setCount() == before + 1 && !results.running(0);
          }
        },
        "search for " + text);
    drain();
  }

  @Test
  void theDockListsRetainedSetsAndOpensAMatch() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Files.writeString(tree.resolve("a.txt"), "one key\nkey two\n", StandardCharsets.UTF_8);
    Files.createDirectories(tree.resolve("sub"));
    Files.writeString(tree.resolve("sub").resolve("b.txt"), "key\n", StandardCharsets.UTF_8);
    search("key", SearchScope.TREE, tree);
    assertTrue(window.resultsShown());
    search("two", SearchScope.TREE, tree);
    ResultsDock dock = window.resultsDock();
    Table view = dock.table();
    assertEquals(5, view.getItemCount());
    assertTrue(dock.text(0).startsWith("- \"two\""), dock.text(0));
    assertEquals(2, dock.row(1));
    assertTrue(dock.text(2).startsWith("- \"key\""), dock.text(2));
    assertTrue(dock.text(dock.row(1, 0)).startsWith("    + a.txt"), dock.text(3));
    assertTrue(dock.text(dock.row(1, 1)).startsWith("    + b.txt"), dock.text(4));
    dock.activate(dock.row(1, 0));
    assertEquals(7, view.getItemCount());
    assertEquals("        2: key two", dock.text(dock.row(1, 0, 1)));
    dock.activate(dock.row(1, 0, 1));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1
                && controller.documents().loading() == 0
                && controller.documents().activeEditor() != null
                && !controller
                    .documents()
                    .activeEditor()
                    .selections()
                    .selections()
                    .primary()
                    .isEmpty();
          }
        },
        "a.txt opens");
    EditorController editor = controller.documents().activeEditor();
    assertEquals(Selection.of(8, 11), editor.selections().selections().primary());
    controller.execute(CommandId.NEXT_RESULT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 2 && controller.documents().loading() == 0;
          }
        },
        "b.txt opens");
    assertEquals(
        Selection.of(0, 3),
        controller.documents().activeEditor().selections().selections().primary());
    assertEquals(dock.row(1, 1, 0), view.getSelectionIndex());
    assertEquals("        1: key", dock.text(view.getSelectionIndex()));
    dock.press(UiText.RESULTS_CLEAR_ALL);
    assertEquals(0, view.getItemCount());
    controller.execute(CommandId.SEARCH_RESULTS);
    assertFalse(window.resultsShown());
  }

  private static String lines(int count, int... needles) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      boolean needle = false;
      for (int n : needles) {
        needle |= n == i;
      }
      text.append(needle ? "needle " : "line ").append(i).append('\n');
    }
    return text.toString();
  }

  private void awaitFindRows(final EditorPane pane, final int... lines) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            OverviewMarks marks = pane.overviewMarks();
            if (marks.height() == 0) {
              return false;
            }
            for (int line : lines) {
              int row = OverviewMarks.rowOf(line, marks.lineCount(), marks.height());
              if ((marks.rows()[row] & OverviewMarks.FIND) == 0) {
                return false;
              }
            }
            return true;
          }
        },
        "find marks");
  }

  @Test
  void theStripMarksFindMatchesScrollsAndFollowsTheView() throws Exception {
    final EditorPane pane =
        openFile(Files.writeString(temp.resolve("strip.txt"), lines(1000, 100, 900)));
    Canvas strip = pane.overviewStrip();
    assertTrue(strip.isVisible());
    assertTrue(strip.getSize().y > 100);
    controller.execute(CommandId.FIND);
    window.findBar().field().setText("needle");
    awaitFindRows(pane, 100, 900);
    StyledText text = pane.text();
    Event click = new Event();
    click.button = 1;
    click.y = OverviewMarks.rowOf(900, 1001, strip.getClientArea().height);
    strip.notifyListeners(SWT.MouseDown, click);
    strip.notifyListeners(SWT.MouseUp, click);
    int visible = text.getClientArea().height / text.getLineHeight();
    assertTrue(
        Math.abs(text.getTopIndex() - (900 - visible / 2)) <= 12, "top " + text.getTopIndex());
    Slider bar = pane.verticalBar();
    assertEquals(text.getTopIndex(), bar.getSelection());
    assertEquals(1001, bar.getMaximum());
    bar.setSelection(10);
    bar.notifyListeners(SWT.Selection, new Event());
    assertEquals(10, text.getTopIndex());
    Event wheel = new Event();
    wheel.count = -3;
    text.notifyListeners(SWT.MouseVerticalWheel, wheel);
    assertEquals(13, text.getTopIndex());
    assertEquals(13, bar.getSelection());
    controller.execute(CommandId.OVERVIEW_STRIP);
    drain();
    assertFalse(strip.isVisible());
    controller.execute(CommandId.OVERVIEW_STRIP);
    drain();
    assertTrue(strip.isVisible());
    Color[] light = pane.overviewColors();
    assertEquals(rgb(Palette.LIGHT.gutter()), light[0].getRGB());
    controller.execute(CommandId.THEME_DARK);
    drain();
    Color[] dark = pane.overviewColors();
    assertEquals(rgb(Palette.DARK.gutter()), dark[0].getRGB());
    assertEquals(rgb(Palette.DARK.overviewFind()), dark[2].getRGB());
    controller.execute(CommandId.SPLIT_HORIZONTAL);
    drain();
    assertEquals(2, window.activeEditors().size());
    for (EditorPane view : window.activeEditors()) {
      assertTrue(view.overviewStrip().isVisible());
      awaitFindRows(view, 100, 900);
    }
  }

  /** Presses Enter in a find bar field and waits for the search to end. */
  private void pressEnter(Combo field) {
    Event enter = new Event();
    enter.keyCode = SWT.CR;
    field.notifyListeners(SWT.KeyDown, enter);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !controller.find().state().running();
          }
        },
        "search ends");
    drain();
  }

  @Test
  void theFindBarOffersTheSavedHistory() throws Exception {
    openFile(Files.writeString(temp.resolve("find.txt"), lines(20, 3, 9)));
    controller.execute(CommandId.REPLACE);
    drain();
    Combo field = window.findBar().field();
    Combo replace = window.findBar().replaceField();
    Point size = field.getSize();
    field.setText("needle");
    pressEnter(field);
    field.setText("line");
    pressEnter(field);
    assertArrayEquals(new String[] {"line", "needle"}, field.getItems());
    assertEquals("line", field.getText());
    replace.setText("thread");
    pressEnter(replace);
    assertArrayEquals(new String[] {"thread"}, replace.getItems());
    assertEquals("thread", replace.getText());
    field.select(1);
    assertEquals("needle", field.getText());
    assertEquals("needle", controller.find().state().text());
    historyScreenshot(field);
    field.setText("z".repeat(UiState.MAX_ENTRY_CHARS));
    pressEnter(field);
    assertEquals(3, field.getItemCount());
    window.shell().layout(true, true);
    assertEquals(size, field.getSize());
    controller.execute(CommandId.HYPERSEARCH);
    drain();
    assertArrayEquals(field.getItems(), window.searchDialog().text().getItems());
    window.searchDialog().shell().close();
  }

  /** Saves the screen as {@code find-history.png} under {@code qcaret.screenshots}, when set. */
  private void historyScreenshot(Combo field) {
    String dir = System.getProperty("qcaret.screenshots");
    if (dir == null) {
      return;
    }
    field.setListVisible(true);
    final long until = System.nanoTime() + 500_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        "the list shows");
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
    loader.save(Path.of(dir, "find-history.png").toString(), SWT.IMAGE_PNG);
    image.dispose();
    field.setListVisible(false);
  }

  private static org.eclipse.swt.graphics.RGB rgb(Rgb value) {
    return new org.eclipse.swt.graphics.RGB(value.red(), value.green(), value.blue());
  }

  @Test
  void theSliderFollowsPagesAndFolds() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      text.append("block ").append(i).append(" {\n  a\n  b\n}\n");
    }
    final EditorPane pane = openFile(Files.writeString(temp.resolve("fold.txt"), text.toString()));
    final Slider bar = pane.verticalBar();
    controller.execute(CommandId.PAGE_DOWN);
    controller.execute(CommandId.PAGE_DOWN);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.text().getTopIndex() > 0 && pane.text().getTopIndex() == bar.getSelection();
          }
        },
        "slider after Page Down");
    final EditorController editor = controller.documents().activeEditor();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.projection().foldRegionCount() > 0;
          }
        },
        "fold regions");
    controller.execute(CommandId.FOLD_ALL);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return bar.getMaximum() == editor.projection().rowCount()
                && editor.projection().rowCount() < 300;
          }
        },
        "slider after Fold All");
  }
}
