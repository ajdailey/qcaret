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
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command palette, the completion list, the bracket and occurrence highlights and the
 * whitespace marks in a real window, driven through the controllers and the editor's key routing.
 */
@Tag("swt")
class ProductivitySurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

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
    window.place(new WindowGeometry.Request(null, null, 700, 500), null);
    window.open();
    Path file =
        Files.writeString(
            temp.resolve("p.txt"), "alpha alpine\nf(alpha)\n\tx  y\n", StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        },
        "opened");
    drain();
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

  private static void key(EditorPane pane, int keyCode, char character) {
    Event event = new Event();
    event.keyCode = keyCode;
    event.character = character;
    pane.text().notifyListeners(SWT.KeyDown, event);
  }

  private EditorPane pane() {
    return window.activeEditor();
  }

  @Test
  void thePaletteFiltersAndRunsTheChosenCommand() {
    controller.execute(CommandId.COMMAND_PALETTE);
    drain();
    final CommandPaletteWindow palette = window.commandPalette();
    palette.filter().setText("word wrap");
    assertEquals("Word Wrap", palette.table().getItem(0).getText(0));
    palette.filter().notifyListeners(SWT.DefaultSelection, new Event());
    assertTrue(palette.shell().isDisposed());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return new CommandStates(controller).checked(CommandId.WORD_WRAP);
          }
        },
        "word wrap on");
  }

  @Test
  void completionListsWordsAndInsertsTheChoiceFromTheKeys() {
    final EditorPane pane = pane();
    pane.text().setFocus();
    pane.text().setSelection(pane.text().getCharCount());
    drain();
    key(pane, 'a', 'a');
    key(pane, 'l', 'l');
    controller.execute(CommandId.COMPLETE_WORD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.completionWords().size() == 2;
          }
        },
        "list shown");
    assertEquals(List.of("alpha", "alpine"), pane.completionWords());
    key(pane, SWT.ARROW_DOWN, (char) 0);
    key(pane, SWT.CR, '\r');
    drain();
    assertEquals(List.of(), pane.completionWords());
    assertTrue(pane.text().getText().endsWith("\nalpine"), pane.text().getText());
  }

  @Test
  void theBracketAndOccurrencesShowAndWhitespaceMarksToggle() {
    final EditorPane pane = pane();
    int open = pane.text().getText().indexOf('(');
    pane.text().setSelection(open + 1);
    pane.text().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.brackets().length == 4;
          }
        },
        "bracket pair");
    assertArrayEquals(new int[] {open, open + 1, open + 6, open + 7}, pane.brackets());
    assertBackground(pane, open + 6, Palette.LIGHT.bracket());
    pane.text().setSelection(open + 3);
    pane.text().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.occurrences().length == 4;
          }
        },
        "alpha twice");
    assertBackground(pane, 1, Palette.LIGHT.occurrence());
    controller.execute(CommandId.SHOW_WHITESPACE);
    controller.execute(CommandId.SHOW_LINE_ENDINGS);
    pane.text().redraw();
    pane.text().update();
    assertArrayEquals(new boolean[] {true, true}, new boolean[] {pane.marks()[0], pane.marks()[1]});
    controller.execute(CommandId.SHOW_WHITESPACE);
    assertEquals(false, pane.marks()[0]);
  }

  @Test
  void theBookmarkBarStaysLeftOfTheFoldMarker() throws Exception {
    Path file = Files.writeString(temp.resolve("f.txt"), "head\n\tbody\n\tbody\ntail\n");
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 2
                && controller.documents().activeEditor().projection().foldRegionCount() == 1;
          }
        },
        "fold region");
    controller.execute(CommandId.TOGGLE_BOOKMARK);
    ImageData data = print(pane());
    StyledText text = pane().text();
    RGB bar = rgb(Palette.LIGHT.caret());
    RGB gutter = rgb(Palette.LIGHT.gutter());
    int barRight = -1;
    boolean marker = false;
    int top = text.getLinePixel(0);
    for (int y = top; y < top + text.getLineHeight(); y++) {
      for (int x = 0; x < pane().gutterWidth(); x++) {
        RGB at = data.palette.getRGB(data.getPixel(x, y));
        if (at.equals(bar)) {
          barRight = Math.max(barRight, x);
        } else if (x >= pane().foldColumn() && !at.equals(gutter)) {
          marker = true;
        }
      }
    }
    assertTrue(barRight >= 0, "bookmark bar");
    assertTrue(marker, "fold marker");
    assertTrue(barRight < pane().foldColumn(), barRight + " reaches the fold column");
  }

  /**
   * Saves a Java file with a collapsed region, the occurrences of a word and a matched bracket as
   * {@code merge.png} under the property's dir.
   */
  @Test
  void syntaxFoldAndHighlightScreenshotWhenAsked() throws Exception {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    controller.loadGrammars();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.grammars().set() != null;
          }
        },
        "grammars");
    Path file =
        Files.writeString(
            temp.resolve("Mock.java"),
            "/** Counts mock values. */\n"
                + "public final class Mock {\n"
                + "  private int total;\n"
                + "\n"
                + "  int count(String text) {\n"
                + "    for (int i = 0; i < text.length(); i++) {\n"
                + "      total += text.charAt(i) == ' ' ? 1 : 0;\n"
                + "    }\n"
                + "    return total;\n"
                + "  }\n"
                + "\n"
                + "  void reset() {\n"
                + "    total = 0; // \"(\" in a comment\n"
                + "  }\n"
                + "}\n",
            StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 2
                && new CommandStates(controller).status().language().equals("Java")
                && controller.documents().activeEditor().projection().foldRegionCount() >= 4;
          }
        },
        "Java with bracket regions");
    final EditorPane pane = pane();
    String text = pane.text().getText();
    pane.text().setSelection(text.indexOf("void reset"));
    pane.text().notifyListeners(SWT.Selection, new Event());
    controller.execute(CommandId.FOLD);
    drain();
    text = pane.text().getText();
    // After "text" and before ")": the word's occurrences and the bracket pair both show.
    pane.text().setSelection(text.indexOf("String text)") + 11);
    pane.text().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.occurrences().length >= 6 && pane.brackets().length == 4;
          }
        },
        "occurrences and bracket pair");
    capture(dir, "merge");
  }

  /** Saves the marks, highlights and bookmarks, then the open palette, under the property's dir. */
  @Test
  void screenshotsWhenAsked() throws Exception {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    Path file =
        Files.writeString(
            temp.resolve("sample.c"),
            "int count(const char *text) {\r\n"
                + "\tint total = 0;  \r\n"
                + "    for (int i = 0; text[i]; i++) {\n"
                + "        total += text[i] == ' ';\n"
                + "    }\n"
                + "\treturn total;\n"
                + "}\n",
            StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 2 && controller.documents().loading() == 0;
          }
        },
        "sample");
    drain();
    final EditorPane pane = pane();
    controller.execute(CommandId.SHOW_WHITESPACE);
    controller.execute(CommandId.SHOW_LINE_ENDINGS);
    String text = pane.text().getText();
    pane.text().setSelection(text.indexOf("total"));
    pane.text().notifyListeners(SWT.Selection, new Event());
    controller.execute(CommandId.TOGGLE_BOOKMARK);
    pane.text().setSelection(text.indexOf('{') + 1);
    pane.text().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.brackets().length == 4;
          }
        },
        "bracket pair");
    pane.text().setSelection(text.indexOf("total") + 2);
    pane.text().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.occurrences().length > 4;
          }
        },
        "occurrences");
    capture(dir, "whitespace-marks");
    controller.execute(CommandId.COMMAND_PALETTE);
    drain();
    window.commandPalette().filter().setText("sort");
    drain();
    capture(dir, "palette");
    window.commandPalette().shell().close();
  }

  private static RGB rgb(Rgb value) {
    return new RGB(value.red(), value.green(), value.blue());
  }

  /** Prints the pane's widget; skips the test where {@code Control.print} is not supported. */
  private ImageData print(EditorPane pane) {
    StyledText text = pane.text();
    drain();
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
    try {
      assumeTrue(printed, "Control.print not supported here");
      return image.getImageData();
    } finally {
      image.dispose();
    }
  }

  /** Checks the background just inside the char at a model offset of the first rows. */
  private void assertBackground(EditorPane pane, int offset, Rgb expected) {
    StyledText text = pane.text();
    ImageData data = print(pane);
    Point at = text.getLocationAtOffset(offset);
    RGB back = new RGB(expected.red(), expected.green(), expected.blue());
    int found = 0;
    for (int y = at.y; y < at.y + text.getLineHeight(); y++) {
      if (back.equals(data.palette.getRGB(data.getPixel(at.x + 1, y)))) {
        found++;
      }
    }
    assertTrue(found > 0, "background " + back + " at " + at);
  }

  private void capture(String dir, String name) {
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
