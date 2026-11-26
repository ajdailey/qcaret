package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.text.ControlChars;
import io.github.ajdailey.qcaret.ui.swt.editor.ControlBoxes;
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
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Control chars in the editor and the comparison window on a real display: each draws as a boxed
 * hex code two columns wide whose pixels are read back, keeps the row height, takes a find match's
 * background, puts a click at the nearer edge, separates words, appears at once when typed, and
 * copies as the original char, also through the GTK selection clipboard and a comparison's copy
 * key.
 */
@Tag("swt")
class ControlBoxSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** Line 1: {@code a}, SOH, {@code b}, U+0081, {@code c}, DEL, {@code d}, ESC. */
  private static final String TEXT =
      "caret here\na\u0001b\u0081c\u007fd\u001b\nab\u0002cd\n"
          + "Plain text keeps the file under the binary detector's control byte share.\n";

  /** The model offset of line 1. */
  private static final int LINE_1 = 11;

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
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
    window.place(new WindowGeometry.Request(0, 0, 800, 500), null);
    window.open();
    Path file = temp.resolve("controls.txt");
    Files.writeString(file, TEXT, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        },
        "opened");
    final StyledText text = window.activeText();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Point size = text.getSize();
            return size.x > window.activeEditor().gutterWidth() && size.y > 0;
          }
        },
        "laid out");
    drain();
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
    } finally {
      display.dispose();
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Paint.
    }
  }

  private EditorController editor() {
    return controller.documents().activeEditor();
  }

  /** Returns {@code text} with each {@code #} as the placeholder. */
  private static String shown(String text) {
    return text.replace('#', ControlChars.PLACEHOLDER);
  }

  /** Returns the widget offset of a model offset. */
  private int shown(int model) {
    return editor().projection().toDisplay(model);
  }

  /**
   * Checks a width against its columns: box widths are whole points, one point apart, mixed so the
   * text after each box is within 1 px of its column.
   */
  private static void assertNear(int expected, int actual, String what) {
    assertTrue(Math.abs(expected - actual) <= 1, what + ": " + actual + " for " + expected);
  }

  private int cell() {
    return window.activeEditor().metrics().x(2);
  }

  @Test
  void eachControlCharIsAPlaceholderTwoColumnsWideAndKeepsTheRowHeight() {
    StyledText text = window.activeText();
    EditorPane pane = window.activeEditor();
    assertEquals(shown("a#b#c#d#"), text.getLine(1));
    assertEquals("\u0001", text.getTextRange(shown(LINE_1 + 1), 1));
    int[] controls = {1, 3, 5, 7};
    int start = text.getLocationAtOffset(shown(LINE_1)).x;
    for (int k = 0; k < controls.length; k++) {
      int after = text.getLocationAtOffset(shown(LINE_1 + controls[k] + 1)).x;
      assertNear(pane.metrics().x(controls[k] + k + 2), after - start, "text after box " + k);
    }
    assertEquals(text.getLineHeight(), text.getLineHeight(shown(LINE_1)));
    assertEquals(text.getLineHeight(), text.getLinePixel(2) - text.getLinePixel(1));
    StyleRange[] styles = pane.rowStyles(1);
    int boxes = 0;
    for (StyleRange style : styles) {
      if (style.metrics != null) {
        boxes++;
        assertEquals(1, style.length);
        assertNear(cell(), style.metrics.width, "box width");
      }
    }
    assertEquals(4, boxes);
  }

  @Test
  void theBoxPaintsItsOutlineAndDigitsInTheReducedInkInLightAndDark() {
    assertBox(Palette.LIGHT, "control-boxes-light");
    controller.theme().setEditorColors(EditorSchemes.DARK);
    drain();
    assertBox(Palette.DARK, "control-boxes-dark");
  }

  /**
   * Reads the box of line 1's SOH from the printed editor; with {@code -Dqcaret.screenshots=<dir>}
   * also writes the editor as {@code name.png}.
   */
  private void assertBox(Palette palette, String name) {
    StyledText text = window.activeText();
    Image image = printedImage(text);
    assumeTrue(image != null, "Control.print not supported here");
    ImageData data;
    try {
      data = Pixels.read(image, text);
      String dir = System.getProperty("qcaret.screenshots");
      if (dir != null) {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {image.getImageData()};
        loader.save(Path.of(dir, name + ".png").toString(), SWT.IMAGE_PNG);
      }
    } finally {
      image.dispose();
    }
    Rgb ink = ControlBoxes.ink(palette.text(), palette.background());
    RGB background = rgb(palette.background());
    Point at = text.getLocationAtOffset(shown(LINE_1 + 1));
    int height = text.getLineHeight();
    int edge = 0;
    for (int y = at.y + 2; y < at.y + height - 2; y++) {
      if (near(TextPaint.at(data, at.x, y), rgb(ink))) {
        edge++;
      }
    }
    assertTrue(edge >= height - 6, "outline pixels " + edge + " of " + height);
    int digits = 0;
    for (int y = at.y + 3; y < at.y + height - 3; y++) {
      for (int x = at.x + 3; x < at.x + cell() - 3; x++) {
        if (!near(TextPaint.at(data, x, y), background)) {
          digits++;
        }
      }
    }
    assertTrue(digits > 4, "digit pixels " + digits);
    assertTrue(near(TextPaint.at(data, at.x - 1, at.y + 2), background));
  }

  @Test
  void aFindMatchColorsTheCellUnderTheBox() {
    StyledText text = window.activeText();
    window.activeEditor().showMatches(new int[] {LINE_1 + 3, LINE_1 + 4});
    drain();
    ImageData data = printed(text);
    assumeTrue(data != null, "Control.print not supported here");
    Point at = text.getLocationAtOffset(shown(LINE_1 + 3));
    RGB match = rgb(Palette.LIGHT.findMatch());
    int height = text.getLineHeight();
    assertEquals(match, TextPaint.at(data, at.x + 2, at.y + height - 3));
    assertEquals(match, TextPaint.at(data, at.x + cell() - 3, at.y + height - 3));
  }

  @Test
  void aClickInsideTheBoxPutsTheCaretAtTheNearerEdge() {
    StyledText text = window.activeText();
    Point at = text.getLocationAtOffset(shown(LINE_1 + 3));
    int y = at.y + text.getLineHeight() / 2;
    click(text, at.x + cell() / 4, y, 1);
    assertEquals(LINE_1 + 3, editor().selections().selections().primary().caret());
    click(text, at.x + cell() * 3 / 4, y, 1);
    assertEquals(LINE_1 + 4, editor().selections().selections().primary().caret());
  }

  @Test
  void aDoubleClickTakesTheWordUpToTheBox() {
    StyledText text = window.activeText();
    int line2 = TEXT.indexOf("ab\u0002");
    Point at = text.getLocationAtOffset(shown(line2 + 1));
    click(text, at.x + 1, at.y + text.getLineHeight() / 2, 2);
    Selection primary = editor().selections().selections().primary();
    assertEquals("ab", TEXT.substring(primary.start(), primary.end()));
  }

  @Test
  void aTypedControlCharDrawsAsABoxAtOnceAndCopiesAsItself() {
    StyledText text = window.activeText();
    editor().selections().set(SelectionSet.caret(1));
    editor().type("\u001b");
    drain();
    assertEquals(shown("c#aret here"), text.getLine(0));
    int x = text.getLocationAtOffset(shown(1)).x;
    assertNear(cell(), text.getLocationAtOffset(shown(2)).x - x, "typed box");
    text.setSelection(shown(1), shown(2));
    assertEquals("\u001b", text.getSelectionText());
  }

  @Test
  void theEditorsSelectionClipboardGetsTheOriginalChars() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "only GTK has a selection clipboard");
    StyledText text = window.activeText();
    text.setSelection(shown(LINE_1), shown(LINE_1 + 3));
    drain();
    Event up = new Event();
    up.button = 1;
    text.notifyListeners(SWT.MouseUp, up);
    drain();
    Clipboard clipboard = new Clipboard(display);
    try {
      assertEquals(
          "a\u0001b", clipboard.getContents(TextTransfer.getInstance(), DND.SELECTION_CLIPBOARD));
    } finally {
      clipboard.dispose();
    }
  }

  @Test
  void theComparisonWindowDrawsBoxesAndCopiesTheOriginal() {
    Comparison comparison =
        new Comparison(
            "left",
            "right",
            "x\u0001y\nsame",
            "x\u0002y\nsame",
            List.of(Comparison.Row.CHANGED, Comparison.Row.SAME),
            List.of(0),
            "");
    DiffWindow diff =
        new DiffWindow(
            window.shell(), comparison, Palette.LIGHT, window.activeText().getFont().getFontData());
    try {
      diff.open();
      drain();
      StyledText left = diff.left();
      assertEquals(shown("x#y"), left.getLine(0));
      assertEquals("x\u0001y", left.getTextRange(0, 3));
      int x = left.getLocationAtOffset(1).x;
      assertNear(cell(), left.getLocationAtOffset(2).x - x, "compared box");
      assertEquals(left.getLineHeight(), left.getLineHeight(0));
      left.setSelection(1, 3);
      Event copy = new Event();
      copy.keyCode = 'c';
      copy.stateMask = SWT.MOD1;
      left.notifyListeners(SWT.KeyDown, copy);
      assertEquals("\u0001y", window.clipboardText());
    } finally {
      diff.shell().dispose();
    }
  }

  private static void click(StyledText text, int x, int y, int count) {
    int[] types =
        count == 2
            ? new int[] {SWT.MouseDown, SWT.MouseDoubleClick, SWT.MouseUp}
            : new int[] {SWT.MouseDown, SWT.MouseUp};
    for (int type : types) {
      Event event = new Event();
      event.button = 1;
      event.count = count;
      event.x = x;
      event.y = y;
      text.notifyListeners(type, event);
    }
  }

  /** Returns the control's printed pixels, or null where {@code Control.print} draws nothing. */
  private ImageData printed(Control control) {
    Image image = printedImage(control);
    if (image == null) {
      return null;
    }
    try {
      return Pixels.read(image, control);
    } finally {
      image.dispose();
    }
  }

  /** Returns the control printed to an image the caller disposes, or null. */
  private Image printedImage(Control control) {
    drain();
    Point size = control.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    boolean done;
    try {
      done = control.print(gc);
    } finally {
      gc.dispose();
    }
    if (!done) {
      image.dispose();
      return null;
    }
    return image;
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  private static boolean near(RGB a, RGB b) {
    return Math.abs(a.red - b.red) + Math.abs(a.green - b.green) + Math.abs(a.blue - b.blue) <= 60;
  }
}
