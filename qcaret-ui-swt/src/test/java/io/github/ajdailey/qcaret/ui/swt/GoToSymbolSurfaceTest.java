package io.github.ajdailey.qcaret.ui.swt;

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
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edit > Go to Symbol in the window: the popup opens with {@code @}, lists the document's symbols
 * and moves the caret on Enter; with {@code -Dqcaret.screenshots=<dir>} it saves the popup in both
 * themes.
 */
@Tag("swt")
class GoToSymbolSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private static final String SHELF =
      "package mock.inventory;\n"
          + "\n"
          + "/** Keeps the mock items of one shelf. */\n"
          + "public final class Shelf {\n"
          + "  private final List<String> items = new ArrayList<>();\n"
          + "\n"
          + "  public boolean add(String name) {\n"
          + "    return items.add(name);\n"
          + "  }\n"
          + "\n"
          + "  public boolean remove(String name) {\n"
          + "    return items.remove(name);\n"
          + "  }\n"
          + "\n"
          + "  public List<String> sorted() {\n"
          + "    return List.copyOf(items);\n"
          + "  }\n"
          + "\n"
          + "  enum Kind { BOX, BAG }\n"
          + "}\n";

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  private void open(boolean dark) throws Exception {
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
    window = new MainWindow(display, dark ? Palette.DARK : Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, dark ? ThemeMode.DARK : ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 1000, 680), null);
    window.open();
    controller.loadGrammars();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.grammars().set() != null;
          }
        },
        "grammars read");
    Path file = Files.writeString(temp.resolve("Shelf.java"), SHELF, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1;
          }
        },
        "Shelf.java open");
  }

  @AfterEach
  void close() {
    if (controller == null) {
      return;
    }
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

  private QuickOpenWindow goToSymbol(final int rows) {
    controller.execute(CommandId.GO_TO_SYMBOL);
    final QuickOpenWindow popup = window.quickOpen();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return popup.table().getItemCount() == rows;
          }
        },
        rows + " symbol rows");
    return popup;
  }

  @Test
  void theFieldStartsWithAtAndEnterMovesTheCaretToTheSymbol() throws Exception {
    open(false);
    QuickOpenWindow popup = goToSymbol(5);
    assertEquals("@", popup.filter().getText());
    assertEquals("Shelf", popup.table().getItem(0).getText(0));
    assertEquals("class, line 4", popup.table().getItem(0).getText(1));
    assertEquals("Kind and line", popup.table().getColumn(1).getText());
    assertEquals("5 of 5 symbols in Shelf.java", popup.summary());
    popup.filter().setText("@rem");
    assertEquals("remove", popup.table().getItem(0).getText(0));
    popup.filter().notifyListeners(SWT.DefaultSelection, new Event());
    assertTrue(popup.shell().isDisposed());
    final StyledText text = window.activeEditor().text();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text.getCaretOffset() > 0;
          }
        },
        "caret moved");
    assertEquals(10, text.getLineAtOffset(text.getCaretOffset()));
  }

  /** Saves the popup over the document as {@code go-to-symbol-light.png}. */
  @Test
  void lightScreenshotWhenAsked() throws Exception {
    screenshot(false);
  }

  /** Saves the popup over the document as {@code go-to-symbol-dark.png}. */
  @Test
  void darkScreenshotWhenAsked() throws Exception {
    screenshot(true);
  }

  private void screenshot(boolean dark) throws Exception {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    open(dark);
    if (dark) {
      controller.execute(CommandId.THEME_DARK);
    }
    QuickOpenWindow popup = goToSymbol(5);
    popup.filter().setText("@s");
    popup.filter().setSelection(2);
    capture(dir, "go-to-symbol-" + (dark ? "dark" : "light"), window.shell().getBounds());
    popup.shell().close();
  }

  private void capture(String dir, String name, Rectangle bounds) {
    final long until = System.nanoTime() + 700_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
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
