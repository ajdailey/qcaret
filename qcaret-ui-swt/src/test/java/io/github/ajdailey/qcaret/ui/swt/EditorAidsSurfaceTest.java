package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
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
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Indent guide positions with spaces and tabs at several tab widths, under wrap and a horizontal
 * scroll; modified-line marks painted after an edit and cleared by a save; the tooltip of a
 * document over the mark limit.
 */
@Tag("swt")
class EditorAidsSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

  /** Lines: plain, 8 spaces, 2 tabs, blank, 4 spaces, 2 spaces and a tab, a long indented line. */
  private static final String TEXT =
      "top\n        eight\n\t\ttabs\n\n    four\n  \tmixed\n    " + "word ".repeat(60) + "\n";

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
    window.place(new WindowGeometry.Request(null, null, 1000, 500), null);
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

  private void openFile(Path file) {
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0
                && window.activeText().getSize().x > 0;
          }
        },
        "opened");
    flush();
  }

  private Path write(String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }

  private void flush() {
    while (display.readAndDispatch()) {
      // Paint.
    }
  }

  /** Paints the widget into an image; null where Control.print fails (GTK 4, SWT issue 2819). */
  private ImageData print(StyledText text) {
    flush();
    Image image = new Image(display, text.getSize().x, text.getSize().y);
    GC gc = new GC(image);
    try {
      if (!text.print(gc)) {
        return null;
      }
    } finally {
      gc.dispose();
    }
    ImageData data = image.getImageData();
    image.dispose();
    return data;
  }

  /** Counts the pixels of a column, within a row band, that have a color. */
  private static int inked(ImageData data, int x, int top, int height, Rgb color) {
    RGB ink = new RGB(color.red(), color.green(), color.blue());
    int count = 0;
    for (int y = Math.max(0, top); y < Math.min(data.height, top + height); y++) {
      if (ink.equals(data.palette.getRGB(data.getPixel(x, y)))) {
        count++;
      }
    }
    return count;
  }

  /** Returns the x of each char offset of a row. */
  private static int[] at(StyledText text, DisplayProjection projection, int row, int... offsets) {
    int[] xs = new int[offsets.length];
    for (int i = 0; i < offsets.length; i++) {
      xs[i] = text.getLocationAtOffset(projection.rowStart(row) + offsets[i]).x;
    }
    return xs;
  }

  @Test
  void guidesSitAtEachIndentLevelForEveryTabWidth() throws Exception {
    openFile(write("a.txt", TEXT));
    EditorPane pane = window.activeEditor();
    StyledText text = pane.text();
    DisplayProjection projection = controller.documents().activeEditor().projection();
    assertArrayEquals(new int[0], pane.indentGuides(1));
    controller.execute(CommandId.INDENT_GUIDES);
    assertTrue(controller.preferences().state().indentGuides());

    controller.execute(CommandId.TAB_WIDTH_4);
    flush();
    assertArrayEquals(new int[0], pane.indentGuides(0));
    assertArrayEquals(at(text, projection, 1, 0, 4), pane.indentGuides(1));
    assertArrayEquals(at(text, projection, 2, 0, 1), pane.indentGuides(2));
    assertArrayEquals(at(text, projection, 2, 0), pane.indentGuides(3));
    assertArrayEquals(at(text, projection, 4, 0), pane.indentGuides(4));
    assertArrayEquals(at(text, projection, 5, 0), pane.indentGuides(5));

    controller.execute(CommandId.TAB_WIDTH_2);
    flush();
    assertArrayEquals(at(text, projection, 1, 0, 2, 4, 6), pane.indentGuides(1));
    assertArrayEquals(at(text, projection, 2, 0, 1), pane.indentGuides(2));
    assertArrayEquals(at(text, projection, 2, 0, 1), pane.indentGuides(3));
    assertArrayEquals(at(text, projection, 5, 0, 2), pane.indentGuides(5));

    controller.execute(CommandId.TAB_WIDTH_8);
    flush();
    assertArrayEquals(at(text, projection, 1, 0), pane.indentGuides(1));
    assertArrayEquals(at(text, projection, 2, 0, 1), pane.indentGuides(2));
    assertArrayEquals(at(text, projection, 5, 0), pane.indentGuides(5));

    controller.execute(CommandId.TAB_WIDTH_4);
    ImageData data = print(text);
    assumeTrue(data != null, "Control.print not supported here");
    int lineHeight = text.getLineHeight();
    int top = text.getLinePixel(1);
    int x = pane.indentGuides(1)[1];
    assertEquals(lineHeight, inked(data, x, top, lineHeight, Palette.LIGHT.indentGuide()));
    assertEquals(0, inked(data, x, text.getLinePixel(0), lineHeight, Palette.LIGHT.indentGuide()));

    text.setHorizontalPixel(pane.metrics().x(2));
    flush();
    assertArrayEquals(at(text, projection, 1, 0, 4), pane.indentGuides(1));
    text.setHorizontalPixel(0);

    controller.execute(CommandId.INDENT_GUIDES);
    assertArrayEquals(new int[0], pane.indentGuides(1));
  }

  @Test
  void aWrappedLineHasGuidesOnItsFirstRowOnly() throws Exception {
    controller.execute(CommandId.INDENT_GUIDES);
    openFile(write("a.txt", TEXT));
    EditorPane pane = window.activeEditor();
    controller.execute(CommandId.WORD_WRAP);
    final EditorController editor = controller.documents().activeEditor();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !editor.projection().rewrapPending();
          }
        },
        "rewrap");
    flush();
    DisplayProjection projection = editor.projection();
    int row = projection.rowOfLine(6);
    assertEquals(1, projection.subRow(row + 1));
    assertArrayEquals(at(pane.text(), projection, row, 0), pane.indentGuides(row));
    assertArrayEquals(new int[0], pane.indentGuides(row + 1));
  }

  @Test
  void marksShowAfterAnEditAndClearOnSave() throws Exception {
    Path file = write("a.txt", "one\ntwo\nthree\n");
    controller.execute(CommandId.LINE_CHANGES);
    openFile(file);
    final EditorPane pane = window.activeEditor();
    final EditorController editor = controller.documents().activeEditor();
    editor.selections().set(SelectionSet.caret(4));
    editor.type("2");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.lineChanges().revision() == editor.document().revision();
          }
        },
        "marks");
    assertEquals(LineChanges.CHANGED, pane.lineChanges().kind(1));
    StyledText text = pane.text();
    ImageData data = print(text);
    if (data != null) {
      int lineHeight = text.getLineHeight();
      int x = pane.lineChangeColumn() + 1;
      Rgb changed = Palette.LIGHT.lineChanged();
      assertEquals(lineHeight, inked(data, x, text.getLinePixel(1), lineHeight, changed));
      assertEquals(0, inked(data, x, text.getLinePixel(0), lineHeight, changed));
      assertTrue(x < text.getLeftMargin(), x + " in the text");
    }
    controller.execute(CommandId.SAVE);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !editor.document().isDirty() && !pane.lineChanges().any();
          }
        },
        "cleared");
    controller.execute(CommandId.LINE_CHANGES);
    assertEquals(LineChanges.NONE, pane.lineChanges());
  }

  @Test
  void aDocumentOverTheLimitSaysSoInTheGutterTooltip() throws Exception {
    Path file = temp.resolve("big.txt");
    try (BufferedWriter writer = Files.newBufferedWriter(file)) {
      for (int i = 0; i <= LineChanges.MAX_LINES; i++) {
        writer.write("x\n");
      }
    }
    controller.execute(CommandId.LINE_CHANGES);
    openFile(file);
    final EditorPane pane = window.activeEditor();
    final EditorController editor = controller.documents().activeEditor();
    editor.type("y");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.lineChanges().overLimit();
          }
        },
        "over limit");
    Event move = new Event();
    move.x = 2;
    move.y = 2;
    pane.text().notifyListeners(SWT.MouseMove, move);
    assertEquals(
        UiText.LINE_CHANGES_OVER_LIMIT.format(LineChanges.MAX_LINES, LineChanges.MAX_CHARS),
        pane.text().getToolTipText());
  }
}
