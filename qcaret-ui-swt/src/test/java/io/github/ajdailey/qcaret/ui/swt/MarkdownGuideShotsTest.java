package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.ajdailey.qcaret.ui.swt.markdown.MarkdownPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes the user guide's Markdown preview images to {@code -Dqcaret.guide.shots=<dir>} ({@code
 * tools/screenshots.sh}): a generated mock document in Split with a wrapped table, drawn by the
 * Basic renderer ({@code markdown-basic.png}) and by the Browser renderer ({@code
 * markdown-browser.png}), and the Markdown actions menu open ({@code markdown-actions.png}), light
 * theme. Each is copied from the screen, since {@code Shell.print} leaves the {@code Browser} blank
 * and draws no menu. With {@code -Dqcaret.rm.out=<dir>} the dark theme's three and a narrow window
 * with the toolbar's More menu open are written there as well. Fails when the Browser renderer
 * cannot run.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.guide.shots", matches = ".+")
class MarkdownGuideShotsTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

  private static final String GUIDE =
      """
      # Mock service notes

      The *mock* service reads shelf records and writes a **daily report**.
      See [Settings](#settings) for the keys.

      ## Components

      | Component | Owner | Notes |
      |:---|:---:|---|
      | `mock-reader` | Team A | Reads each shelf record and skips a line that does not match the mock schema |
      | `mock-report` | Team B | Writes the daily report as HTML and keeps the last seven |
      | `mock-alerts` | Team A | Sends no mail; logs a warning per empty shelf |

      ## Settings

      ```yaml
      service:
        name: mock-shelf
        port: 8080
      ```

      - [x] Reader
      - [ ] Alerts
      """;

  @TempDir Path temp;

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Display display;
  private MainWindow window;
  private ApplicationController controller;

  @Test
  void markdownShots() throws Exception {
    Path guide = Files.createDirectories(Path.of(System.getProperty("qcaret.guide.shots")));
    String review = System.getProperty("qcaret.rm.out", "");
    String files = System.getProperty("qcaret.screenshots.files", "");
    Path dir = files.isEmpty() ? temp : Files.createDirectories(Path.of(files));
    Path notes = Files.createDirectories(dir.resolve("notes"));
    Path file = Files.writeString(notes.resolve("service.md"), GUIDE, StandardCharsets.UTF_8);
    display = new Display();
    try (Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            })) {
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
      controller.loadGrammars();
      window.place(new WindowGeometry.Request(0, 0, 960, 600), null);
      window.open();
      display.setCursorLocation(new Point(1270, 1010));
      open(file);
      controller.execute(CommandId.MARKDOWN_SPLIT);
      shoot(guide.resolve("markdown-basic.png"), guide.resolve("markdown-browser.png"));
      shootMenu(guide.resolve("markdown-actions.png"));
      if (!review.isEmpty()) {
        Path out = Files.createDirectories(Path.of(review));
        controller.execute(CommandId.THEME_DARK);
        shoot(out.resolve("markdown-basic-dark.png"), out.resolve("markdown-browser-dark.png"));
        shootMenu(out.resolve("markdown-actions-dark.png"));
        controller.execute(CommandId.THEME_LIGHT);
        shootNarrow(out.resolve("toolbar-narrow.png"));
      }
      controller.documents().dispose();
    } finally {
      display.dispose();
    }
    assertNull(failure.get());
  }

  /** Saves the window with the Markdown actions menu open below its button. */
  private void shootMenu(Path file) {
    MarkdownToolItems tools = window.markdownSwitch().tools();
    Menu menu = tools.menu();
    Rectangle bounds = tools.item().getBounds();
    menu.setLocation(tools.item().getParent().toDisplay(bounds.x, bounds.y + bounds.height));
    menu.setVisible(true);
    settle();
    capture(file);
    menu.setVisible(false);
    settle();
  }

  /** Saves a 480 px wide window with the toolbar's More menu open, then restores the width. */
  private void shootNarrow(Path file) {
    Rectangle before = window.shell().getBounds();
    window.shell().setSize(480, before.height);
    settle();
    ToolbarOverflow row = window.toolbarRow();
    Menu menu = row.menu();
    Rectangle bounds = row.more().getParent().getBounds();
    // Right-aligned to the button, so the capture of the window holds the menu.
    menu.setLocation(row.row().toDisplay(bounds.x + bounds.width - 220, bounds.y + bounds.height));
    menu.setVisible(true);
    settle();
    capture(file);
    menu.setVisible(false);
    window.shell().setBounds(before);
    settle();
  }

  /** Saves the Basic preview, then the Browser preview, and returns to Basic. */
  private void shoot(Path basic, Path browser) {
    controller.execute(CommandId.PREVIEW_BASIC);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            MarkdownPane pane = window.activePreview();
            return slot().browserPane() == null
                && pane != null
                && pane.text().getText().contains("mock-reader\t");
          }
        });
    settle();
    capture(basic);
    controller.execute(CommandId.PREVIEW_BROWSER);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            BrowserPane pane = slot().browserPane();
            return pane != null && pane.content() != null;
          }
        });
    settle();
    assertNull(BrowserPreview.unavailable(display));
    capture(browser);
    controller.execute(CommandId.PREVIEW_BASIC);
  }

  private PreviewSlot slot() {
    return ((EditorTab) window.tabs().getSelection().getData()).preview;
  }

  private void open(Path file) {
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().activeMarkdown() && controller.documents().loading() == 0;
          }
        });
  }

  /** Copies the main window's area of the screen to a PNG, without the trim past the window. */
  private void capture(Path file) {
    Rectangle bounds = window.shell().getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
    } finally {
      gc.dispose();
    }
    try {
      ImageData data = Pixels.read(image, window.shell());
      int width = GuideScreenshotsTest.contentWidth(data);
      int height = GuideScreenshotsTest.contentHeight(data);
      Image cut = new Image(display, width, height);
      GC cutGc = new GC(cut);
      try {
        cutGc.drawImage(image, 0, 0, width, height, 0, 0, width, height);
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {cut.getImageData()};
        loader.save(file.toString(), SWT.IMAGE_PNG);
      } finally {
        cutGc.dispose();
        cut.dispose();
      }
    } finally {
      image.dispose();
    }
  }

  private void await(BooleanSupplier condition) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND));
  }

  /** Runs the event loop for two seconds, so table turns and the page load reach the screen. */
  private void settle() {
    final long until = System.nanoTime() + 2_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
  }
}
