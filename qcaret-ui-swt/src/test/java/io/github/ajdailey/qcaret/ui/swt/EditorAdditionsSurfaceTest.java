package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.editor.TextStatistics;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.StatisticsReport;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
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
 * The right margin line's position and color in a real window, through a scroll, zoom, word wrap
 * and a theme switch; the Document Statistics dialog and the date format chooser.
 */
@Tag("swt")
class EditorAdditionsSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

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
                && controller.documents().loading() == 0
                && window.activeText().getSize().x > 0;
          }
        },
        "opened");
    flush();
  }

  private void flush() {
    while (display.readAndDispatch()) {
      // Paint.
    }
  }

  /** A first line of {@code columns} digits, then short lines. */
  private static String text(int columns) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < columns; i++) {
      b.append((char) ('0' + i % 10));
    }
    b.append('\n');
    for (int i = 0; i < 30; i++) {
      b.append("short\n");
    }
    return b.toString();
  }

  /** Paints the widget into an image; null where Control.print fails (GTK 4, SWT issue 2819). */
  private ImageData print(StyledText text) {
    flush();
    return ControlPrint.of(display, text);
  }

  /** Counts the rows below the first line whose pixel at x has a color. */
  private static int inked(ImageData data, int x, int from, Rgb color) {
    RGB ink = new RGB(color.red(), color.green(), color.blue());
    int count = 0;
    for (int y = from; y < data.height; y++) {
      if (ink.equals(data.palette.getRGB(data.getPixel(x, y)))) {
        count++;
      }
    }
    return count;
  }

  private int expectedX(EditorPane pane, int column) {
    StyledText text = pane.text();
    return text.getLeftMargin() - text.getHorizontalPixel() + pane.metrics().x(column);
  }

  @Test
  void theLineSitsAtItsColumnThroughScrollZoomWrapAndThemes() throws Exception {
    openFile("a.txt", text(300));
    EditorPane pane = window.activeEditor();
    StyledText text = window.activeText();
    assertEquals(-1, pane.rightMarginX());
    controller.execute(CommandId.RIGHT_MARGIN);
    assertEquals(expectedX(pane, 80), pane.rightMarginX());
    int lineHeight = text.getLineHeight();
    ImageData data = print(text);
    assumeTrue(data != null, "Control.print not supported here");
    int x = pane.rightMarginX();
    assertTrue(x < data.width, x + " past " + data.width);
    assertTrue(inked(data, x, lineHeight, Palette.LIGHT.rightMargin()) > data.height / 2, "line");
    assertEquals(0, inked(data, x - 2, lineHeight, Palette.LIGHT.rightMargin()));

    // Column 150 matches the glyph there once scrolled into view.
    pane.setRightMargin(150);
    text.setHorizontalPixel(pane.metrics().x(100));
    flush();
    int glyph = text.getLocationAtOffset(150).x;
    assertTrue(Math.abs(pane.rightMarginX() - glyph) <= 1, pane.rightMarginX() + " vs " + glyph);
    assertEquals(expectedX(pane, 150), pane.rightMarginX());
    data = print(text);
    assertNotNull(data, "scrolled print");
    assertTrue(
        inked(data, pane.rightMarginX(), lineHeight, Palette.LIGHT.rightMargin()) > data.height / 2,
        "scrolled line");
    pane.setRightMargin(80);
    text.setHorizontalPixel(0);

    controller.execute(CommandId.ZOOM_IN);
    flush();
    assertEquals(expectedX(pane, 80), pane.rightMarginX());

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
    assertEquals(0, text.getHorizontalPixel());
    assertEquals(text.getLeftMargin() + pane.metrics().x(80), pane.rightMarginX());

    controller.execute(CommandId.THEME_DARK);
    flush();
    data = print(text);
    assertNotNull(data, "dark print");
    if (pane.rightMarginX() < data.width) {
      assertTrue(
          inked(data, pane.rightMarginX(), lineHeight, Palette.DARK.rightMargin())
              > data.height / 2,
          "dark line");
    }
    controller.execute(CommandId.RIGHT_MARGIN);
    assertEquals(-1, pane.rightMarginX());
  }

  @Test
  void aSplitViewAndALaterTabShowTheLine() throws Exception {
    controller.execute(CommandId.RIGHT_MARGIN);
    openFile("a.txt", text(10));
    assertEquals(expectedX(window.activeEditor(), 80), window.activeEditor().rightMarginX());
    controller.execute(CommandId.SPLIT_HORIZONTAL);
    flush();
    List<EditorPane> panes = window.activeEditors();
    assertEquals(2, panes.size());
    for (EditorPane pane : panes) {
      assertEquals(expectedX(pane, 80), pane.rightMarginX());
    }
  }

  @Test
  void theStatisticsDialogCountsTheSelectionAndRefreshes() throws Exception {
    openFile("a.txt", "one two\nthree\n");
    EditorController editor = controller.documents().activeEditor();
    editor.selections().set(SelectionSet.of(List.of(Selection.of(4, 11)), 0));
    controller.execute(CommandId.DOCUMENT_STATISTICS);
    final StatisticsDialog dialog = window.statisticsDialog();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !dialog.values()[0].isEmpty();
          }
        },
        "counted");
    assertEquals(
        List.of("3", "3", "14", "11", "14", "2", "2", "7", "6", "7"), List.of(dialog.values()));
    assertEquals("", dialog.status());
    editor.selections().set(SelectionSet.of(List.of(Selection.caret(0)), 0));
    editor.type("zero ");
    window.statisticsDialog().showProgress(0);
    controller.execute(CommandId.DOCUMENT_STATISTICS);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dialog.status().isEmpty();
          }
        },
        "refreshed");
    assertEquals("4", dialog.values()[1]);
    assertEquals("", dialog.values()[5]);
    Event escape = new Event();
    escape.detail = SWT.TRAVERSE_ESCAPE;
    dialog.shell().notifyListeners(SWT.Traverse, escape);
    assertTrue(dialog.shell().isDisposed());
  }

  @Test
  void theStatisticsDialogShowsUnknownBytes() throws Exception {
    openFile("a.txt", "one\n");
    controller.execute(CommandId.DOCUMENT_STATISTICS);
    final StatisticsDialog dialog = window.statisticsDialog();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !dialog.values()[0].isEmpty();
          }
        },
        "counted");
    TextStatistics counts = new TextStatistics(2, 1, 4, 3, TextStatistics.UNKNOWN_BYTES);
    dialog.showReport(new StatisticsReport("a.txt", "x-JISAutoDetect", counts, counts));
    assertEquals(
        List.of("2", "1", "4", "3", UiText.STATISTICS_BYTES_UNKNOWN.text()),
        List.of(dialog.values()).subList(0, 5));
    assertEquals(UiText.STATISTICS_BYTES_UNKNOWN.text(), dialog.values()[9]);
  }

  @Test
  void theDateChooserInsertsTheChosenFormat() throws Exception {
    openFile("a.txt", "");
    controller.execute(CommandId.INSERT_DATE_TIME);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.dateFormatChooser() != null;
          }
        },
        "chooser");
    assertEquals(0, window.dateFormatChooser().selected());
    window.dateFormatChooser().choose(1);
    String inserted = controller.documents().activeEditor().document().text().toString();
    assertTrue(inserted.matches("\\d{4}-\\d{2}-\\d{2}"), inserted);
    assertTrue(window.dateFormatChooser().shell().isDisposed());
  }
}
