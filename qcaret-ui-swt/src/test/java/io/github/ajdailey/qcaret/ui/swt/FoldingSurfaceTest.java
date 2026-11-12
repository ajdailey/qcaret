package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Folding in a real window: the gutter marker click, the widget's rows against the projection,
 * caret keys over a collapsed region, split views, and Enter after an opener. With {@code
 * -Dqcaret.screenshots=<dir>} the gutter test saves {@code folding.png} there.
 */
@Tag("swt")
class FoldingSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final String SOURCE =
      String.join(
          "\n",
          "class Sample {",
          "    int count;",
          "",
          "    void first() {",
          "        if (count > 0) {",
          "            count--;",
          "        }",
          "        log(\"first\");",
          "    }",
          "",
          "    void second() {",
          "        for (int i = 0; i < 3; i++) {",
          "            log(\"second\");",
          "        }",
          "    }",
          "",
          "    void third() {",
          "        count = 0;",
          "    }",
          "}",
          "");

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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys(), PlatformTraits.PLATFORM.linuxKeys()),
            false);
    controller.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 700, 500), null);
    window.open();
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
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

  private void openSource() throws Exception {
    Path file = temp.resolve("Sample.java");
    Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            EditorController editor = controller.documents().activeEditor();
            return editor != null && editor.projection().foldRegionCount() > 0;
          }
        },
        "fold regions");
    drain();
  }

  private EditorController editor() {
    return controller.documents().activeEditor();
  }

  /** Checks the widget's lines against the projection's rows. */
  private static void sameRows(StyledText text, DisplayProjection p) {
    assertEquals(p.rowCount(), text.getLineCount());
    assertEquals(p.textRange(0, p.length()), text.getText());
    for (int row = 0; row < p.rowCount(); row++) {
      assertEquals(p.rowText(row), text.getLine(row), "row " + row);
    }
  }

  private static void click(StyledText text, int x, int y) {
    Event event = new Event();
    event.button = 1;
    event.count = 1;
    event.x = x;
    event.y = y;
    text.notifyListeners(SWT.MouseDown, event);
    text.notifyListeners(SWT.MouseUp, event);
  }

  private static void key(StyledText text, int keyCode) {
    Event event = new Event();
    event.keyCode = keyCode;
    text.notifyListeners(SWT.KeyDown, event);
  }

  @Test
  void aGutterClickTogglesTheRegionAndTheWidgetFollows() throws Exception {
    openSource();
    StyledText text = window.activeText();
    EditorPane pane = window.activeEditor();
    DisplayProjection p = editor().projection();
    int lineHeight = text.getLineHeight();
    int x = pane.foldColumn() + 2;
    click(text, x, text.getLinePixel(p.rowOfLine(3)) + lineHeight / 2);
    assertTrue(p.collapsed(3));
    sameRows(text, p);
    click(text, x, text.getLinePixel(p.rowOfLine(11)) + lineHeight / 2);
    assertTrue(p.collapsed(11));
    assertEquals(0, text.getCaretOffset());
    sameRows(text, p);
    screenshot("folding");
    click(text, x, text.getLinePixel(p.rowOfLine(3)) + lineHeight / 2);
    assertFalse(p.collapsed(3));
    sameRows(text, p);
  }

  @Test
  void theOverviewStripScalesByModelLinesAndAClickOnAHiddenLineShowsItsFold() throws Exception {
    StringBuilder source = new StringBuilder("class Long {\n");
    for (int i = 0; i < 200; i++) {
      source.append("  void m").append(i).append("() {\n    int a;\n    int b;\n  }\n");
    }
    Path file = temp.resolve("Long.java");
    Files.writeString(file, source.append("}\n").toString(), StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            EditorController editor = controller.documents().activeEditor();
            return editor != null && editor.projection().foldRegionCount() > 1;
          }
        },
        "fold regions");
    drain();
    final EditorPane pane = window.activeEditor();
    StyledText text = window.activeText();
    DisplayProjection p = editor().projection();
    int header = 401;
    final int hidden = header + 1;
    editor().selections().set(SelectionSet.caret(p.lineStart(hidden)));
    controller.execute(CommandId.TOGGLE_BOOKMARK);
    editor().selections().set(SelectionSet.caret(0));
    assertTrue(p.fold(header));
    final int lines = p.lineCount();
    final Canvas strip = pane.overviewStrip();
    final int height = strip.getClientArea().height;
    assertTrue(height > 0);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            byte[] rows = pane.overviewMarks().rows();
            int row = OverviewMarks.rowOf(hidden, lines, height);
            return row < rows.length && (rows[row] & OverviewMarks.BOOKMARK) != 0;
          }
        },
        "the hidden line's mark");
    Event click = new Event();
    click.button = 1;
    click.y = OverviewMarks.rowOf(hidden, lines, height);
    strip.notifyListeners(SWT.MouseDown, click);
    strip.notifyListeners(SWT.MouseUp, click);
    drain();
    int visible = text.getClientArea().height / text.getLineHeight();
    int shown = p.rowOfLine(header);
    assertTrue(
        text.getTopIndex() <= shown && shown <= text.getTopIndex() + visible,
        "top " + text.getTopIndex() + ", fold row " + shown);
  }

  @Test
  void caretKeysStepOverACollapsedRegion() throws Exception {
    openSource();
    StyledText text = window.activeText();
    DisplayProjection p = editor().projection();
    editor().goTo(3, 0);
    controller.execute(CommandId.FOLD);
    drain();
    assertTrue(p.collapsed(3));
    assertEquals(3, p.lineAt(editor().selections().selections().primary().caret()));
    key(text, SWT.ARROW_DOWN);
    assertEquals(8, p.lineAt(editor().selections().selections().primary().caret()));
    key(text, SWT.ARROW_UP);
    key(text, SWT.END);
    key(text, SWT.ARROW_RIGHT);
    assertEquals(8, p.lineAt(editor().selections().selections().primary().caret()));
    assertEquals(text.getCaretOffset(), p.toDisplay(p.lineStart(8)));
    sameRows(text, p);
  }

  @Test
  void splitViewsFoldIndependently() throws Exception {
    openSource();
    EditorController first = editor();
    controller.execute(CommandId.SPLIT_HORIZONTAL);
    drain();
    final EditorController second = editor();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.projection().foldRegionCount() > 0;
          }
        },
        "second view's regions");
    controller.execute(CommandId.FOLD_ALL);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.projection().collapsedCount() > 0;
          }
        },
        "Fold All");
    drain();
    List<EditorPane> panes = window.activeEditors();
    assertEquals(2, panes.size());
    int collapsedRows = second.projection().rowCount();
    assertTrue(collapsedRows < SOURCE.split("\n", -1).length);
    boolean full = false;
    boolean folded = false;
    for (EditorPane pane : panes) {
      full |= pane.text().getLineCount() == SOURCE.split("\n", -1).length;
      folded |= pane.text().getLineCount() == collapsedRows;
    }
    assertTrue(full && folded);
    // Typing on a collapsed region's first line keeps it collapsed.
    second.type("x");
    assertEquals(6, second.projection().collapsedCount());
    assertEquals(0, first.projection().collapsedCount());
    for (EditorPane pane : panes) {
      int rows = pane.text().getLineCount();
      assertTrue(rows == first.projection().rowCount() || rows == second.projection().rowCount());
    }
  }

  @Test
  void enterAfterAnOpenerIndentsOneLevel() throws Exception {
    openSource();
    StyledText text = window.activeText();
    editor().document().setInsertSpaces(true);
    editor().document().setTabWidth(4);
    editor().selections().set(SelectionSet.caret(SOURCE.indexOf("{") + 1));
    key(text, SWT.CR);
    DisplayProjection p = editor().projection();
    assertEquals("class Sample {\n    \n    int count;", p.modelText(0, p.lineStart(3) - 1));
  }

  /** Saves the window to {@code <qcaret.screenshots>/<name>.png} when the property is set. */
  private void screenshot(String name) {
    String dir = System.getProperty("qcaret.screenshots");
    if (dir == null) {
      return;
    }
    final long until = System.nanoTime() + 500_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
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
