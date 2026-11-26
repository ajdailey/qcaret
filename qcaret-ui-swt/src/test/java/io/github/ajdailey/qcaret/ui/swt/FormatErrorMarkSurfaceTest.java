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
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The format error mark: a wavy line under the token at the error and an overview strip mark after
 * a failed Pretty Print, both gone after an edit. With {@code qcaret.rm.out} set, also writes light
 * and dark screenshots there.
 */
@Tag("swt")
class FormatErrorMarkSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

  /** Tag names joined to attributes, as after deleting the tabs between them. */
  private static final String TEXT =
      "<?xml version=\"1.0\"?>\n<catalog>\n  <bookid=\"1\">\n    <title>Mock</title>\n"
          + "  </book>\n</catalog>\n";

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
    window.place(new WindowGeometry.Request(null, null, 900, 400), null);
    window.open();
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

  private void flush() {
    while (display.readAndDispatch()) {
      // Paint.
    }
  }

  private EditorPane openFailed() throws Exception {
    Path file = temp.resolve("catalog.xml");
    Files.writeString(file, TEXT, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1
                && controller.documents().loading() == 0
                && window.activeText().getSize().x > 0;
          }
        },
        "opened");
    final EditorPane pane = window.activeEditor();
    controller.execute(CommandId.PRETTY_PRINT_XML);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.errorMark()[0] >= 0 && pane.errorMarkPainted().length > 0;
          }
        },
        "the error mark painted");
    return pane;
  }

  @Test
  void theMarkSitsUnderTheTokenAtTheErrorUntilAnEdit() throws Exception {
    final EditorPane pane = openFailed();
    final EditorController editor = controller.documents().activeEditor();
    int start = pane.errorMark()[0];
    int end = pane.errorMark()[1];
    assertEquals(TEXT.indexOf("bookid"), start);
    assertEquals(TEXT.indexOf("=\"1\"") + 1, end);
    int line = editor.document().text().lineAt(start);
    assertEquals(2, line, "the error is on the joined tag's line");
    flush();
    StyledText text = pane.text();
    DisplayProjection projection = editor.projection();
    Point left = text.getLocationAtOffset(projection.toDisplay(start));
    Point right = text.getLocationAtOffset(projection.toDisplay(end));
    int[] painted = pane.errorMarkPainted();
    assertArrayEquals(
        new int[] {left.x, right.x, left.y + text.getLineHeight() - 1},
        painted,
        "the wave's x span and bottom");
    ImageData data = ControlPrint.of(display, text);
    if (data != null) {
      Rgb invalid = Palette.LIGHT.style(TokenRole.INVALID).foreground();
      assertTrue(inked(data, left.x, right.x, left.y, text.getLineHeight(), invalid) > 0, "inked");
      assertEquals(0, inked(data, 0, data.width - 1, text.getLinePixel(0), 1, invalid));
    }
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return errorRows(pane.overviewMarks()) > 0;
          }
        },
        "the overview error mark");
    editor.selections().set(SelectionSet.caret(0));
    editor.type(" ");
    assertArrayEquals(new int[] {-1, -1}, pane.errorMark());
    // The display server delivers the repaint later; wait for the paint that drops the wave.
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.errorMarkPainted().length == 0;
          }
        },
        "the wave repainted away");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return errorRows(pane.overviewMarks()) == 0
                && pane.overviewMarks().revision() == editor.document().revision();
          }
        },
        "the overview error mark cleared");
  }

  @Test
  void screenshots() throws Exception {
    String dir = System.getProperty("qcaret.rm.out");
    assumeTrue(dir != null && !dir.isEmpty(), "qcaret.rm.out names no directory");
    openFailed();
    shoot(dir, "format-error-light");
    controller.execute(CommandId.THEME_DARK);
    flush();
    shoot(dir, "format-error-dark");
  }

  private static int errorRows(OverviewMarks marks) {
    int count = 0;
    for (byte row : marks.rows()) {
      if ((row & OverviewMarks.ERROR) != 0) {
        count++;
      }
    }
    return count;
  }

  /** Counts the pixels within a band, from x0 to x1 inclusive, that have a color. */
  private static int inked(ImageData data, int x0, int x1, int top, int height, Rgb color) {
    RGB ink = new RGB(color.red(), color.green(), color.blue());
    int count = 0;
    for (int y = Math.max(0, top); y < Math.min(data.height, top + height); y++) {
      for (int x = Math.max(0, x0); x <= Math.min(data.width - 1, x1); x++) {
        if (ink.equals(data.palette.getRGB(data.getPixel(x, y)))) {
          count++;
        }
      }
    }
    return count;
  }

  private void shoot(String dir, String name) throws Exception {
    long until = System.nanoTime() + 500_000_000L;
    while (System.nanoTime() < until) {
      if (!display.readAndDispatch()) {
        display.sleep();
      }
    }
    Files.createDirectories(Path.of(dir));
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
