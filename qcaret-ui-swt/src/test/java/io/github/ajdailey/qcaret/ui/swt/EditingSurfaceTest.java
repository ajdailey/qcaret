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
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.editor.CaretInk;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.IME;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The editing core in a real window: keys move every caret, Alt+drag and Mod+click from the mouse,
 * a block painted into virtual space at column 150, composition text from the widget, Go to Line
 * and the Keyboard Shortcuts window. With {@code -Dqcaret.screenshots=<dir>} the multi-caret and
 * block tests save a screenshot of the window there.
 */
@Tag("swt")
class EditingSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private boolean posting;

  @BeforeEach
  void open() {
    display = new Display();
    // The tests post every mouse event. A real pointer left over the text by an earlier test
    // sends a motion without a button, which ends a posted drag; added first, this filter runs
    // before the editor's and drops it.
    display.addFilter(
        SWT.MouseMove,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!posting) {
              event.type = SWT.None;
            }
          }
        });
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
            Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
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

  private void openFile(String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0;
          }
        },
        "opened " + name);
    drain();
  }

  private EditorController editor() {
    return controller.documents().activeEditor();
  }

  private static void key(StyledText text, int keyCode, int stateMask) {
    Event event = new Event();
    event.keyCode = keyCode;
    event.stateMask = stateMask;
    text.notifyListeners(SWT.KeyDown, event);
  }

  private void mouse(StyledText text, int type, int x, int y, int stateMask) {
    Event event = new Event();
    event.button = type == SWT.MouseMove ? 0 : 1;
    event.count = 1;
    event.x = x;
    event.y = y;
    event.stateMask = stateMask;
    posting = true;
    try {
      text.notifyListeners(type, event);
    } finally {
      posting = false;
    }
  }

  /** Saves the window to {@code <qcaret.screenshots>/<name>.png} when the property is set. */
  private void screenshot(String name) {
    String dir = System.getProperty("qcaret.screenshots");
    if (dir == null) {
      return;
    }
    // Let the window system apply a resize and repaint before the copy.
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
    capture(name);
  }

  /** Saves the window without waiting when {@code qcaret.screenshots} is set. */
  private void capture(String name) {
    String dir = System.getProperty("qcaret.screenshots");
    if (dir == null) {
      return;
    }
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

  @Test
  void arrowKeysAndModClickMoveEveryCaret() throws Exception {
    openFile("carets.txt", "alpha one\nbeta two\ngamma three\n");
    StyledText text = window.activeText();
    editor().selections().set(SelectionSet.caret(0));
    int addCaret = SwtKeys.mac() ? SWT.MOD4 | SWT.MOD2 : SWT.MOD1 | SWT.MOD2;
    key(text, SWT.ARROW_DOWN, addCaret);
    key(text, SWT.ARROW_DOWN, addCaret);
    assertEquals(3, editor().selections().selections().size());
    int word = SwtKeys.mac() ? SWT.MOD3 : SWT.MOD1;
    key(text, SWT.ARROW_RIGHT, word);
    key(text, SWT.ARROW_RIGHT, word | SWT.MOD2);
    assertEquals(
        List.of(Selection.of(6, 9), Selection.of(15, 18), Selection.of(25, 30)),
        editor().selections().selections().selections());
    Point at = text.getLocationAtOffset(editor().projection().toDisplay(3));
    mouse(text, SWT.MouseDown, at.x + 1, at.y + 2, SWT.MOD1);
    mouse(text, SWT.MouseUp, at.x + 1, at.y + 2, SWT.MOD1);
    assertEquals(4, editor().selections().selections().size());
    assertEquals(Selection.caret(3), editor().selections().selections().primary());
    screenshot("multi-caret");
    key(text, SWT.ESC, 0);
    assertEquals(1, editor().selections().selections().size());
  }

  /** TEST-10: a block reaching past short lines at column 150 lines up with the measured text. */
  @Test
  void altDragSelectsABlockPaintedIntoVirtualSpaceAtColumn150() throws Exception {
    StringBuilder content = new StringBuilder();
    String digits = "0123456789".repeat(20);
    for (int i = 0; i < 8; i++) {
      content.append(i % 2 == 0 ? digits : "short line " + i).append('\n');
    }
    openFile("block.txt", content.toString());
    StyledText text = window.activeText();
    EditorPane pane = window.activeEditor();
    int x150 = text.getLocationAtOffset(150).x;
    window.shell().setSize(Math.max(window.shell().getSize().x, x150 + 260), 420);
    drain();
    // A runner screen narrower than column 150 clamps the window; scroll the block's columns in.
    if (x150 + 120 > text.getClientArea().width) {
      text.setHorizontalPixel(x150 + 120 - text.getClientArea().width);
      drain();
    }
    int line6 = editor().projection().lineStart(6);
    Point from = text.getLocationAtOffset(150);
    int half = text.getLineHeight() / 2;
    mouse(text, SWT.MouseDown, from.x, from.y + half, SWT.MOD3);
    // A scroll during the drag keeps the anchor's column (the drag auto-scrolls at an edge).
    int before = text.getHorizontalPixel();
    text.setHorizontalPixel(before + pane.metrics().x(4));
    drain();
    assertTrue(text.getHorizontalPixel() > before, "no room to scroll");
    Point to = text.getLocationAtOffset(line6 + 156);
    mouse(text, SWT.MouseMove, to.x, to.y + half, SWT.MOD3 | SWT.BUTTON1);
    mouse(text, SWT.MouseUp, to.x, to.y + half, SWT.MOD3);
    assertEquals(new BlockSelection(0, 150, 6, 156), editor().selections().block());
    editor().copy();
    assertEquals(
        String.join("\n", List.of("012345", "", "012345", "", "012345", "", "012345")),
        window.clipboardText().replace("\r\n", "\n"));
    // GTK 4 keeps the 700-pixel window: scroll column 150 to the middle of the view.
    text.setHorizontalIndex(0);
    drain();
    int width = text.getClientArea().width;
    if (text.getLocationAtOffset(150).x + 60 >= width) {
      text.setHorizontalPixel(text.getLocationAtOffset(150).x - width / 2);
      drain();
    }
    Point at = text.getLocationAtOffset(150);
    assertTrue(at.x + 60 < width, "column 150 at " + at.x + " of " + width);
    ImageData data = print(text);
    Rgb back = Palette.LIGHT.selection();
    RGB selection = new RGB(back.red(), back.green(), back.blue());
    ColumnMetrics metrics = pane.metrics();
    // One pixel below a row's top, above the glyphs.
    int longRow = text.getLocationAtOffset(editor().projection().lineStart(2)).y + 1;
    int shortRow = text.getLocationAtOffset(editor().projection().lineStart(3)).y + 1;
    int expected = at.x;
    int longEdge = firstSelected(data, selection, longRow, expected - 6, expected + 6);
    int shortEdge = firstSelected(data, selection, shortRow, expected - 6, expected + 6);
    assertTrue(Math.abs(longEdge - expected) <= 1, "long row edge " + longEdge + " at " + expected);
    assertTrue(Math.abs(shortEdge - longEdge) <= 1, "short " + shortEdge + ", long " + longEdge);
    int inside = expected + metrics.x(3);
    assertEquals(selection, data.palette.getRGB(data.getPixel(inside, shortRow + half)));
    // A resize and a horizontal scroll keep the block and its painting past the line ends.
    window.shell().setSize(980, 420);
    text.setHorizontalPixel(Math.max(0, at.x + text.getHorizontalPixel() - 620));
    screenshot("block-selection");
    drain();
    assertEquals(new BlockSelection(0, 150, 6, 156), editor().selections().block());
    ImageData scrolled = print(text);
    int x = text.getLocationAtOffset(150).x + metrics.x(3);
    assertEquals(selection, scrolled.palette.getRGB(scrolled.getPixel(x, shortRow + half)));
  }

  /**
   * Paints the widget into an image. Where {@code Control.print} fails (GTK 4, SWT issue 2819), a
   * Paint event over the client area runs the widget's own painting and the editor's paint
   * listeners on the image instead.
   */
  private ImageData print(StyledText text) {
    Point size = text.getSize();
    Image image = new Image(display, size.x, size.y);
    try {
      GC gc = new GC(image);
      try {
        if (!text.print(gc)) {
          Rectangle area = text.getClientArea();
          Event paint = new Event();
          paint.gc = gc;
          paint.x = area.x;
          paint.y = area.y;
          paint.width = area.width;
          paint.height = area.height;
          text.notifyListeners(SWT.Paint, paint);
        }
      } finally {
        gc.dispose();
      }
      return image.getImageData();
    } finally {
      image.dispose();
    }
  }

  private static int firstSelected(ImageData data, RGB color, int y, int from, int to) {
    for (int x = Math.max(0, from); x < to; x++) {
      if (color.equals(data.palette.getRGB(data.getPixel(x, y)))) {
        return x;
      }
    }
    return -1;
  }

  /** Dispatches events for a time measured by a Display timer. */
  private void dispatchFor(int millis) {
    final boolean[] done = new boolean[1];
    display.timerExec(
        millis,
        new Runnable() {
          @Override
          public void run() {
            done[0] = true;
          }
        });
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return done[0];
          }
        },
        millis + " ms");
  }

  /** Alt+presses at one point, moves to another outside the view and waits for the scroll. */
  private void holdOutside(
      final StyledText text, Point down, Point held, BooleanSupplier scrolled, String what) {
    mouse(text, SWT.MouseDown, down.x, down.y, SWT.MOD3);
    mouse(text, SWT.MouseMove, held.x, held.y, SWT.MOD3 | SWT.BUTTON1);
    await(scrolled, what);
    assertTrue(window.activeEditor().autoscrolling(), what);
  }

  /** QLogg f380b90: a block drag held outside the view keeps scrolling until it ends. */
  @Test
  void aBlockDragHeldOutsideTheViewScrollsUntilReleaseFocusLossOrClose() throws Exception {
    StringBuilder content = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      content.append("row ").append(i).append(' ').append("x".repeat(300)).append('\n');
    }
    openFile("drag.txt", content.toString());
    final StyledText text = window.activeText();
    final EditorPane pane = window.activeEditor();
    final int half = text.getLineHeight() / 2;
    final int left = text.getLeftMargin() + pane.metrics().x(2);
    final int height = text.getClientArea().height;

    // Below the view, ended by the release.
    holdOutside(
        text,
        new Point(left, half),
        new Point(left, height + 10),
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text.getTopIndex() >= 3;
          }
        },
        "scrolled down");
    assertTrue(editor().selections().block().caretLine() > text.getTopIndex());
    mouse(text, SWT.MouseUp, left, height + 10, SWT.MOD3);
    assertFalse(pane.autoscrolling());
    int top = text.getTopIndex();
    dispatchFor(4 * 50);
    assertEquals(top, text.getTopIndex());

    // Right of the view, ended by focus loss.
    text.setTopIndex(0);
    drain();
    final int width = text.getClientArea().width;
    holdOutside(
        text,
        new Point(left, half),
        new Point(width + 10, half),
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text.getHorizontalPixel() > pane.metrics().x(3);
          }
        },
        "scrolled right");
    text.notifyListeners(SWT.FocusOut, new Event());
    assertFalse(pane.autoscrolling());
    final int scrolled = text.getHorizontalPixel();
    dispatchFor(4 * 50);
    assertEquals(scrolled, text.getHorizontalPixel());

    // Left of the text area, then above the view, ended by closing the tab.
    holdOutside(
        text,
        new Point(width / 2, half),
        new Point(0, half),
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text.getHorizontalPixel() < scrolled;
          }
        },
        "scrolled left");
    mouse(text, SWT.MouseUp, 0, half, SWT.MOD3);
    text.setTopIndex(100);
    drain();
    final int from = text.getTopIndex();
    holdOutside(
        text,
        new Point(left, half),
        new Point(left, -10),
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text.getTopIndex() <= from - 3;
          }
        },
        "scrolled up");
    controller.execute(CommandId.CLOSE);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text.isDisposed();
          }
        },
        "tab closed");
    assertFalse(pane.autoscrolling());
    dispatchFor(4 * 50);
  }

  private static RGB rgb(Rgb value) {
    return new RGB(value.red(), value.green(), value.blue());
  }

  /** Copies one screen pixel. */
  /** Within 2 per channel: cocoa's color matching moves a composited pixel by one step. */
  private static boolean near(RGB a, RGB b) {
    return Math.abs(a.red - b.red) <= 2
        && Math.abs(a.green - b.green) <= 2
        && Math.abs(a.blue - b.blue) <= 2;
  }

  /**
   * Saves the window and a 40 by 40 screen square around a point to {@code target/diagnostics/},
   * which CI uploads when a test job fails.
   */
  private void diagnose(String name, int x, int y) {
    try {
      Path dir = Files.createDirectories(Path.of("target", "diagnostics"));
      Rectangle bounds = window.shell().getBounds();
      saveScreen(dir.resolve(name + "-window.png"), bounds);
      saveScreen(dir.resolve(name + "-square.png"), new Rectangle(x - 20, y - 20, 40, 40));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void saveScreen(Path file, Rectangle area) {
    Image image = new Image(display, area.width, area.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, area.x, area.y);
    } finally {
      gc.dispose();
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(file.toString(), SWT.IMAGE_PNG);
    image.dispose();
  }

  private RGB screenPixel(int x, int y) {
    Image image = new Image(display, 1, 1);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, x, y);
    } finally {
      gc.dispose();
    }
    ImageData data = image.getImageData();
    image.dispose();
    return data.palette.getRGB(data.getPixel(0, 0));
  }

  /**
   * The primary caret's image shows the scheme's caret color over the current line, and the screen
   * shows it while the caret blinks on.
   */
  @Test
  void thePrimaryCaretTakesTheSchemeCaretColorInBothThemes() throws Exception {
    openFile("caret.txt", "\nsecond line\nthird line\n");
    final StyledText text = window.activeText();
    editor().selections().set(SelectionSet.caret(0));
    window.shell().forceActive();
    text.setFocus();
    drain();
    CaretInk.Blend blend = CaretInk.platform();
    Palette[] palettes = {Palette.LIGHT, Palette.DARK};
    for (final Palette palette : palettes) {
      controller.execute(palette.dark() ? CommandId.THEME_DARK : CommandId.THEME_LIGHT);
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.palette().equals(palette);
            }
          },
          "palette");
      drain();
      EditorPane pane = window.activeEditor();
      RGB line = rgb(palette.currentLine());
      RGB source = CaretInk.source(line, rgb(palette.caret()), blend);
      ImageData image = pane.caretImage().getImageData();
      assertEquals(EditorPane.CARET_WIDTH, image.width);
      assertEquals(text.getLineHeight(), image.height);
      assertEquals(source, image.palette.getRGB(image.getPixel(1, image.height / 2)));
      assertEquals(
          new Point(EditorPane.CARET_WIDTH, text.getLineHeight()), text.getCaret().getSize());
      final RGB expected = CaretInk.shown(line, source, blend);
      // Windows shows a caret only in the focused window; a runner desktop may not give the test
      // window the foreground.
      text.setFocus();
      drain();
      assumeTrue(
          display.getActiveShell() == text.getShell() && text.isFocusControl(),
          "the test window does not have focus");
      Point at = display.map(text, null, text.getCaret().getLocation());
      final int x = at.x;
      final int y = at.y + text.getLineHeight() / 2;
      ScreenProbe.assumeShows(text.getShell());
      final RGB[] seen = new RGB[1];
      // The caret blinks; poll the screen until it shows.
      boolean shown =
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  seen[0] = screenPixel(x, y);
                  return near(expected, seen[0]);
                }
              },
              Duration.ofSeconds(5));
      // Under GTK 4 and Xvfb a Display GC copy never showed the caret (measured; no SWT issue
      // found).
      assumeTrue(shown || !"1".equals(System.getenv("SWT_GTK4")), "no screen copy under GTK 4");
      if (!shown) {
        diagnose("caret-" + (palette.dark() ? "dark" : "light"), x, y);
      }
      assertTrue(
          shown,
          palette.dark()
              + ": expected "
              + expected
              + ", last "
              + seen[0]
              + " at "
              + x
              + ","
              + y
              + "; caret "
              + text.getCaret().getLocation()
              + " visible "
              + text.getCaret().isVisible()
              + ", shell "
              + window.shell().getBounds()
              + ", zoom "
              + text.getMonitor().getZoom());
      if (palette.dark()) {
        capture("dark-caret");
      }
    }
  }

  @Test
  void compositionTextFromTheWidgetStaysOutOfUndo() throws Exception {
    openFile("ime.txt", "ab");
    StyledText text = window.activeText();
    editor().selections().set(SelectionSet.caret(2));
    text.getContent().replaceTextRange(2, 0, "k");
    text.getContent().replaceTextRange(2, 1, "ka");
    assertEquals("abka", editor().document().text().toString());
    assertEquals("abka", text.getText());
    assertFalse(editor().document().isDirty());
    text.getContent().replaceTextRange(2, 2, "");
    editor().type("x");
    assertEquals("abx", editor().document().text().toString());
    controller.execute(CommandId.UNDO);
    assertEquals("ab", text.getText());
  }

  /** Sets a field the platform's {@code IME} keeps; the three share these names. */
  private static void setIme(StyledText text, String field, int value) throws Exception {
    Field f = IME.class.getDeclaredField(field);
    f.setAccessible(true);
    f.setInt(text.getIME(), value);
  }

  /** Sends one composition event on the {@code IME}, where every platform sends it. */
  private static void imeEvent(StyledText text, int start, int end, String composed) {
    Event event = new Event();
    event.detail = SWT.COMPOSITION_CHANGED;
    event.start = start;
    event.end = end;
    event.text = composed;
    text.getIME().notifyListeners(SWT.ImeComposition, event);
  }

  /** Starts a composition as {@code IME} does: asks the widget for the range, then changes it. */
  private static void compose(StyledText text, String composed) throws Exception {
    Event ask = new Event();
    ask.detail = SWT.COMPOSITION_SELECTION;
    text.getIME().notifyListeners(SWT.ImeComposition, ask);
    setIme(text, "startOffset", ask.start);
    imeEvent(text, ask.start, ask.end, composed);
  }

  /** Cancels as {@code IME} does under IBus: one empty change over the composition. */
  private static void cancel(StyledText text, int start, int length) {
    imeEvent(text, start, start + length, "");
  }

  /**
   * Commits as {@code IME} does under IBus: an empty change removes the composition, an empty range
   * carries the commit count, then the chars are keys. {@code IBusCompositionTest} pins it.
   */
  private static void commit(StyledText text, int start, String composed) throws Exception {
    cancel(text, start, composed.length());
    setIme(text, "commitCount", composed.length());
    imeEvent(text, start, start, composed);
    setIme(text, "commitCount", 0);
    setIme(text, "startOffset", -1);
    for (char c : composed.toCharArray()) {
      Event key = new Event();
      key.character = c;
      text.notifyListeners(SWT.KeyDown, key);
    }
  }

  @Test
  void aCanceledCompositionOverASelectionKeepsTheTextAndTheSelection() throws Exception {
    openFile("imecancel.txt", "hello world");
    StyledText text = window.activeText();
    Selection selected = Selection.of(0, 5);
    editor().selections().set(SelectionSet.of(List.of(selected), 0));
    drain();
    assertEquals("hello", text.getSelectionText());
    compose(text, "n");
    drain();
    assertEquals("nhello world", text.getText());
    cancel(text, 0, 1);
    drain();
    assertEquals("hello world", editor().document().text().toString());
    assertFalse(editor().document().isDirty());
    assertEquals(selected, editor().selections().selections().primary());
    assertEquals("hello", text.getSelectionText());
  }

  @Test
  void aCommittedCompositionReplacesTheSelectionAsOneUndoStep() throws Exception {
    openFile("imecommit.txt", "hello world");
    StyledText text = window.activeText();
    editor().selections().set(SelectionSet.of(List.of(Selection.of(0, 5)), 0));
    drain();
    compose(text, "ka");
    drain();
    commit(text, 0, "ka");
    drain();
    assertEquals("ka world", editor().document().text().toString());
    assertEquals("ka world", text.getText());
    controller.execute(CommandId.UNDO);
    drain();
    assertEquals("hello world", text.getText());
    assertEquals("hello", text.getSelectionText());
  }

  /** A change reports its whole row, which {@code StyledText} reads as text moving the offset. */
  @Test
  void aCompositionPastColumnZeroKeepsItsOffset() throws Exception {
    openFile("imecolumn.txt", "ab");
    StyledText text = window.activeText();
    editor().selections().set(SelectionSet.caret(2));
    drain();
    compose(text, "k");
    assertEquals(2, text.getIME().getCompositionOffset());
    imeEvent(text, 2, 3, "ka");
    assertEquals(2, text.getIME().getCompositionOffset());
    drain();
    assertEquals("abka", text.getText());
    commit(text, 2, "ka");
    drain();
    assertEquals("abka", editor().document().text().toString());
    controller.execute(CommandId.UNDO);
    drain();
    assertEquals("ab", text.getText());
  }

  @Test
  void goToLineMovesTheCaretFromItsDialog() throws Exception {
    openFile("goto.txt", "one\ntwo\nthree\n");
    controller.execute(CommandId.GO_TO_LINE);
    drain();
    Shell dialog = shellTitled(UiText.GO_TO_TITLE.text());
    assertNotNull(dialog);
    child(dialog, Text.class).setText("3:3");
    for (Control button : children(dialog, Button.class)) {
      if (((Button) button).getText().equals(UiText.BUTTON_OK.text())) {
        button.notifyListeners(SWT.Selection, new Event());
        break;
      }
    }
    assertTrue(dialog.isDisposed());
    assertEquals(SelectionSet.caret(10), editor().selections().selections());
  }

  @Test
  void keyboardShortcutsFilterAndCopy() throws Exception {
    controller.execute(CommandId.KEYBOARD_SHORTCUTS);
    drain();
    ShortcutsWindow shortcuts = window.shortcutsWindow();
    assertNotNull(shortcuts);
    int all = shortcuts.table().getItemCount();
    shortcuts.filter().setText("block select");
    assertEquals(4, shortcuts.table().getItemCount());
    assertTrue(all > 60, "rows " + all);
    controller.execute(CommandId.KEYBOARD_SHORTCUTS);
    drain();
    assertTrue(shortcuts == window.shortcutsWindow());
    for (Control button : children(shortcuts.shell(), Button.class)) {
      if (((Button) button).getText().equals(UiText.BUTTON_COPY.text())) {
        button.notifyListeners(SWT.Selection, new Event());
      }
    }
    assertTrue(window.clipboardText().contains("Block Select Left"), window.clipboardText());
    shortcuts.shell().dispose();
  }

  private Shell shellTitled(String title) {
    for (Shell shell : display.getShells()) {
      for (Shell child : shell.getShells()) {
        if (title.equals(child.getText()) && !child.isDisposed()) {
          return child;
        }
      }
    }
    return null;
  }

  private static <T extends Control> T child(Composite parent, Class<T> type) {
    List<Control> found = children(parent, type);
    return found.isEmpty() ? null : type.cast(found.get(0));
  }

  private static List<Control> children(Composite parent, Class<?> type) {
    List<Control> out = new ArrayList<Control>();
    for (Control control : parent.getChildren()) {
      if (type.isInstance(control)) {
        out.add(control);
      }
      if (control instanceof Composite composite) {
        out.addAll(children(composite, type));
      }
    }
    return out;
  }
}
