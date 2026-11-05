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
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
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

  private static void mouse(StyledText text, int type, int x, int y, int stateMask) {
    Event event = new Event();
    event.button = type == SWT.MouseMove ? 0 : 1;
    event.count = 1;
    event.x = x;
    event.y = y;
    event.stateMask = stateMask;
    text.notifyListeners(type, event);
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
    key(text, SWT.ARROW_DOWN, SWT.MOD1 | SWT.MOD3);
    key(text, SWT.ARROW_DOWN, SWT.MOD1 | SWT.MOD3);
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
    int line6 = editor().projection().lineStart(6);
    Point from = text.getLocationAtOffset(150);
    Point to = text.getLocationAtOffset(line6 + 156);
    int half = text.getLineHeight() / 2;
    mouse(text, SWT.MouseDown, from.x, from.y + half, SWT.MOD3);
    mouse(text, SWT.MouseMove, to.x, to.y + half, SWT.MOD3 | SWT.BUTTON1);
    mouse(text, SWT.MouseUp, to.x, to.y + half, SWT.MOD3);
    assertEquals(new BlockSelection(0, 150, 6, 156), editor().selections().block());
    editor().copy();
    assertEquals(
        String.join("\n", List.of("012345", "", "012345", "", "012345", "", "012345")),
        window.clipboardText().replace("\r\n", "\n"));
    text.setHorizontalIndex(0);
    drain();
    Point at = text.getLocationAtOffset(150);
    assumeTrue(at.x + 60 < text.getClientArea().width, "column 150 is off screen");
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
    text.setHorizontalPixel(Math.max(0, at.x - 620));
    screenshot("block-selection");
    drain();
    assertEquals(new BlockSelection(0, 150, 6, 156), editor().selections().block());
    ImageData scrolled = print(text);
    int x = text.getLocationAtOffset(150).x + metrics.x(3);
    assertEquals(selection, scrolled.palette.getRGB(scrolled.getPixel(x, shortRow + half)));
  }

  private ImageData print(StyledText text) {
    Point size = text.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      text.print(gc);
    } finally {
      gc.dispose();
    }
    ImageData data = image.getImageData();
    image.dispose();
    return data;
  }

  private static int firstSelected(ImageData data, RGB color, int y, int from, int to) {
    for (int x = Math.max(0, from); x < to; x++) {
      if (color.equals(data.palette.getRGB(data.getPixel(x, y)))) {
        return x;
      }
    }
    return -1;
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
