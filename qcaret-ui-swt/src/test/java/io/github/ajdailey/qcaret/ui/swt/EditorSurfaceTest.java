package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.io.BufferedWriter;
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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The editor surface in a real window: open, type, undo and save through the model, edits the
 * widget makes on its own, word wrap on a large generated file while the UI thread keeps running,
 * the unsaved-changes prompt, a dragged tab and the painted gutter and secondary caret.
 */
@Tag("swt")
class EditorSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

  /** Longest gap between 10 ms timer ticks accepted while a large file rewraps. */
  private static final long RESPONSIVE_MILLIS = 1000;

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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
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

  private void awaitTabs(final int count) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == count && controller.documents().loading() == 0;
          }
        },
        count + " tabs");
  }

  private Path openFile(String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    awaitTabs(window.tabs().getItemCount() + 1);
    return file;
  }

  /** Sends a key press to the widget as the window system would. */
  private static void key(StyledText text, char character, int keyCode, int stateMask) {
    Event event = new Event();
    event.character = character;
    event.keyCode = keyCode;
    event.stateMask = stateMask;
    text.notifyListeners(SWT.KeyDown, event);
  }

  private static void type(StyledText text, String chars) {
    for (int i = 0; i < chars.length(); i++) {
      key(text, chars.charAt(i), chars.charAt(i), 0);
    }
  }

  private String modelText() {
    return controller.documents().activeEditor().document().text().toString();
  }

  @Test
  void openTypeUndoAndSaveGoThroughTheModel() throws Exception {
    final Path file = openFile("a.txt", "alpha\r\nbeta\n");
    StyledText text = window.activeText();
    type(text, "xy");
    key(text, '\r', SWT.CR, 0);
    assertEquals("xy\r\nalpha\r\nbeta\n", modelText());
    assertEquals("*a.txt", window.tabs().getSelection().getText());
    assertEquals(4, text.getCaretOffset());
    // Enter after a word starts its own undo step.
    key(text, (char) ('z' - 'a' + 1), 'z', SWT.MOD1);
    assertEquals("xyalpha\r\nbeta\n", modelText());
    key(text, (char) ('z' - 'a' + 1), 'z', SWT.MOD1);
    assertEquals("alpha\r\nbeta\n", modelText());
    assertEquals("alpha\r\nbeta\n", text.getText());
    // Mod+Shift+Z redoes on every platform; Mod+Y is not bound on macOS.
    key(text, (char) ('z' - 'a' + 1), 'z', SWT.MOD1 | SWT.SHIFT);
    key(text, (char) ('z' - 'a' + 1), 'z', SWT.MOD1 | SWT.SHIFT);
    // An edit the widget makes itself is canceled and applied to the model by the controller.
    text.setSelection(0);
    text.insert("Q");
    assertEquals("Qxy\r\nalpha\r\nbeta\n", modelText());
    controller.execute(CommandId.SAVE);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "a.txt".equals(window.tabs().getSelection().getText());
          }
        },
        "saved");
    assertEquals("Qxy\r\nalpha\r\nbeta\n", Files.readString(file));
  }

  @Test
  void aTabToolTipKeepsAnAmpersandOfThePath() throws Exception {
    Path file = openFile("a&b.txt", "text\n");
    String tip = file.toRealPath().toString().replace("&", "&&");
    assertEquals(tip, window.tabs().getSelection().getToolTipText());
    type(window.activeText(), "x");
    assertEquals("*a&&b.txt", window.tabs().getSelection().getText());
    assertEquals(tip, window.tabs().getSelection().getToolTipText());
  }

  @Test
  void wrapOnALargeFileKeepsTheUiThreadRunning() throws Exception {
    Path file = temp.resolve("large.txt");
    int lines = 200_000;
    try (BufferedWriter out = Files.newBufferedWriter(file)) {
      for (int i = 0; i < lines; i++) {
        out.write("line " + i + " lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do");
        out.write(" eiusmod tempor\n");
      }
    }
    controller.documents().open(List.of(file));
    awaitTabs(1);
    final EditorController editor = controller.documents().activeEditor();
    assertEquals(lines + 1, editor.projection().rowCount());
    final long[] last = {System.nanoTime()};
    final long[] worst = {0};
    final boolean[] stop = {false};
    Runnable tick =
        new Runnable() {
          @Override
          public void run() {
            long now = System.nanoTime();
            worst[0] = Math.max(worst[0], now - last[0]);
            last[0] = now;
            if (!stop[0] && !display.isDisposed()) {
              display.timerExec(10, this);
            }
          }
        };
    display.timerExec(10, tick);
    long started = System.nanoTime();
    controller.execute(CommandId.WORD_WRAP);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !editor.projection().rewrapPending();
          }
        },
        "rewrap");
    stop[0] = true;
    long millis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(
        editor.projection().rowCount() > lines + 1, "rows " + editor.projection().rowCount());
    assertTrue(
        worst[0] / 1_000_000 < RESPONSIVE_MILLIS,
        "UI thread gap " + worst[0] / 1_000_000 + " ms, rewrap " + millis + " ms");
    // The widget still reads rows that match the model after a scroll.
    StyledText text = window.activeText();
    text.setTopIndex(text.getLineCount() / 2);
    int row = text.getTopIndex();
    assertEquals(editor.projection().rowText(row), text.getLine(row));
  }

  private Shell prompt() {
    for (Shell shell : window.shell().getShells()) {
      if (!shell.isDisposed() && UiText.UNSAVED_TITLE.text().equals(shell.getText())) {
        return shell;
      }
    }
    return null;
  }

  /** Presses the button with a label; returns true once pressed. */
  private static boolean press(Control root, String label) {
    if (root instanceof Button button && label.equals(button.getText())) {
      button.notifyListeners(SWT.Selection, new Event());
      return true;
    }
    if (root instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        if (press(child, label)) {
          return true;
        }
      }
    }
    return false;
  }

  @Test
  void closingADirtyTabAsksFirst() throws Exception {
    controller.execute(CommandId.NEW);
    type(window.activeText(), "draft");
    controller.execute(CommandId.CLOSE);
    Shell prompt = prompt();
    assertNotNull(prompt);
    assertTrue(press(prompt, UiText.BUTTON_CANCEL.text()));
    assertTrue(prompt.isDisposed());
    assertEquals(1, window.tabs().getItemCount());
    controller.execute(CommandId.QUIT);
    assertTrue(press(prompt(), UiText.BUTTON_DONT_SAVE.text()));
    assertEquals(0, window.tabs().getItemCount());
    assertTrue(controller.isQuitting());
  }

  @Test
  void aDraggedTabKeepsItsDocumentShown() throws Exception {
    openFile("one.txt", "one");
    openFile("two.txt", "two");
    EditorPane shown = window.activeEditor();
    window.moveTab(window.tabs().getSelection(), 0);
    assertSame(shown, window.activeEditor());
    assertEquals("two.txt", window.tabs().getItem(0).getText());
    assertEquals("two", modelText());
  }

  @Test
  void theGutterAndASecondaryCaretPaintAtMeasuredPositions() throws Exception {
    openFile("cjk.txt", "abc\n一二 三 abc\n");
    StyledText text = window.activeText();
    EditorPane pane = window.activeEditor();
    EditorController editor = controller.documents().activeEditor();
    // A secondary caret after the CJK run and a space, primary on line 1.
    int secondary = 4 + 5;
    editor
        .selections()
        .set(SelectionSet.of(List.of(Selection.caret(1), Selection.caret(secondary)), 0));
    // GTK 4 can lay the tab out after the open returns.
    final StyledText shown = text;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Point now = shown.getSize();
            return now.x > pane.gutterWidth() && now.y > 0;
          }
        },
        "laid out");
    while (display.readAndDispatch()) {
      // Paint.
    }
    Point size = text.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    boolean printed;
    try {
      printed = text.print(gc);
    } finally {
      gc.dispose();
    }
    // Control.print returns false under GTK 4 (SWT issue 2819: no offscreen draw of a control).
    assumeTrue(printed, "Control.print not supported here");
    ImageData data = image.getImageData();
    image.dispose();
    RGB ink =
        new RGB(
            Palette.LIGHT.caret().red(),
            Palette.LIGHT.caret().green(),
            Palette.LIGHT.caret().blue());
    Point at = text.getLocationAtOffset(editor.projection().toDisplay(secondary));
    int lineHeight = text.getLineHeight();
    int dark = 0;
    for (int y = at.y + 1; y < at.y + lineHeight - 1; y++) {
      if (ink.equals(data.palette.getRGB(data.getPixel(at.x, y)))) {
        dark++;
      }
    }
    assertTrue(dark >= lineHeight - 3, "caret pixels " + dark + " of " + lineHeight);
    RGB numbers = new RGB(0x8a, 0x8a, 0x8a);
    int gutterInk = 0;
    for (int x = 0; x < pane.gutterWidth(); x++) {
      for (int y = 0; y < 2 * lineHeight; y++) {
        RGB pixel = data.palette.getRGB(data.getPixel(x, y));
        if (Math.abs(pixel.red - numbers.red) < 60 && pixel.red < 0xe0) {
          gutterInk++;
        }
      }
    }
    assertTrue(gutterInk > 4, "gutter ink " + gutterInk);
    assertFalse(text.getText().isEmpty());
  }

  /** QLogg 5a1a3c7: a double click at or past a wrapped row's right edge takes that row's word. */
  @Test
  void aDoubleClickPastAWrappedRowsEdgeSelectsTheWordAtItsEnd() throws Exception {
    openFile("wrap.txt", "alpha beta gamma delta epsilon zeta eta theta iota kappa\n");
    final EditorController editor = controller.documents().activeEditor();
    controller.execute(CommandId.WORD_WRAP);
    editor.viewColumnsChanged(12);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !editor.projection().rewrapPending() && editor.projection().rowCount() > 3;
          }
        },
        "wrapped");
    StyledText text = window.activeText();
    // Row 0 is "alpha beta ", row 1 starts with "gamma".
    assertEquals("alpha beta ", text.getLine(0));
    int y = text.getLinePixel(0) + text.getLineHeight() / 2;
    Event down = new Event();
    down.button = 1;
    down.count = 2;
    down.x = text.getClientArea().width - 4;
    down.y = y;
    text.notifyListeners(SWT.MouseDown, down);
    Event twice = new Event();
    twice.button = 1;
    twice.count = 2;
    twice.x = down.x;
    twice.y = y;
    text.notifyListeners(SWT.MouseDoubleClick, twice);
    Event up = new Event();
    up.button = 1;
    up.count = 2;
    up.x = down.x;
    up.y = y;
    text.notifyListeners(SWT.MouseUp, up);
    Selection primary = editor.selections().selections().primary();
    String selected = modelText().substring(primary.start(), primary.end()).trim();
    assertEquals(
        "beta",
        selected,
        "widget " + text.getSelection() + " caret " + text.getCaretOffset() + " model " + primary);
  }

  /** A double click takes the word by the rule of the word moves: marks join, {@code .} splits. */
  @Test
  void aDoubleClickSelectsTheWordOfTheWordMoves() throws Exception {
    openFile("marks.txt", "x cafe\u0301_b.q y\n");
    final EditorController editor = controller.documents().activeEditor();
    StyledText text = window.activeText();
    Point at = text.getLocationAtOffset(editor.projection().toDisplay(3));
    int x = at.x + 1;
    int y = at.y + text.getLineHeight() / 2;
    for (int type : new int[] {SWT.MouseDown, SWT.MouseDoubleClick, SWT.MouseUp}) {
      Event event = new Event();
      event.button = 1;
      event.count = 2;
      event.x = x;
      event.y = y;
      text.notifyListeners(type, event);
    }
    Selection primary = editor.selections().selections().primary();
    assertEquals(
        "cafe\u0301_b",
        modelText().substring(primary.start(), primary.end()),
        "widget " + text.getSelection() + " model " + primary);
  }
}
