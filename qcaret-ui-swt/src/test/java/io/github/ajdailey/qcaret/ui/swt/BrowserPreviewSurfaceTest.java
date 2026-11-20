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
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Browser renderer in a real window: the choice renders through the Browser pane and back, a
 * browser that fails to create leaves Basic with the notice and the reason, the cycle key runs with
 * the browser focused, and the Command Palette lists Cycle Markdown View. With {@code
 * -Dqcaret.rm.out=<dir>} the light and dark page is saved there as PNG.
 */
@Tag("swt")
class BrowserPreviewSurfaceTest {
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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 900, 600), null);
    window.open();
    controller.loadGrammars();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.grammars().set() != null;
          }
        },
        "grammars");
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

  private void flush() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void openMarkdown(String text) throws Exception {
    Path file = Files.writeString(temp.resolve("guide.md"), text, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().activeMarkdown();
          }
        },
        "Markdown tab");
    flush();
  }

  private PreviewSlot slot() {
    return ((EditorTab) window.tabs().getSelection().getData()).preview;
  }

  private void awaitBrowser(final boolean shown) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            PreviewSlot slot = slot();
            boolean browser =
                slot != null && slot.browserPane() != null && slot.browserPane().content() != null;
            return browser == shown && controller.documents().markdownMode() != null;
          }
        },
        "Browser pane shown " + shown);
    flush();
  }

  private void awaitBasic(final String text) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            PreviewSlot slot = slot();
            return slot != null
                && slot.browserPane() == null
                && slot.created() != null
                && slot.created().text().getText().contains(text);
          }
        },
        "Basic shows " + text);
    flush();
  }

  @Test
  void theBrowserChoiceRendersThroughTheBrowserPaneAndBack() throws Exception {
    assumeTrue(PlatformTraits.PLATFORM.opensBrowser(), "no Browser under GTK 4");
    openMarkdown("# Mock guide\n\nBody text.\n");
    controller.execute(CommandId.PREVIEW_BROWSER);
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitBrowser(true);
    assertTrue(slot().browserPane().content().html().body().contains("Mock guide"));
    assertEquals(
        UiText.STATUS_PREVIEW.format(UiText.PREVIEW_RENDERER_BROWSER.text()),
        window.previewField().getText());
    assertTrue(slot().browserPane().control().isVisible());
    controller.execute(CommandId.PREVIEW_BASIC);
    awaitBasic("Mock guide");
    assertTrue(slot().created().control().isVisible());
  }

  @Test
  void aBrowserThatFailsToCreateLeavesBasicWithTheReason() throws Exception {
    assumeTrue(PlatformTraits.PLATFORM.opensBrowser(), "no Browser under GTK 4");
    window.setPreviewBrowserFactory(
        new GuideWindow.BrowserFactory() {
          @Override
          public Browser create(Composite parent) {
            throw new SWTError("Mock browser failure");
          }
        });
    openMarkdown("# Mock guide\n");
    controller.execute(CommandId.PREVIEW_BROWSER);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitBasic("Mock guide");
    String reason = window.previewBrowserUnavailable();
    assertNotNull(reason);
    assertTrue(reason.contains("Mock browser failure"), reason);
    assertEquals(
        UiText.STATUS_PREVIEW.format(UiText.PREVIEW_RENDERER_BASIC.text()),
        window.previewField().getText());
    MenuItem browser = window.menuBar().item(CommandId.PREVIEW_BROWSER);
    window.menuBar().syncAll();
    assertFalse(browser.getEnabled());
    assertEquals(reason, browser.getToolTipText());
  }

  @Test
  void theCycleKeyRunsWhileTheBrowserHasFocus() throws Exception {
    assumeTrue(
        "gtk".equals(SWT.getPlatform()) && PlatformTraits.PLATFORM.opensBrowser(),
        "keys posted through Display.post: GTK 3 only");
    openMarkdown("# Mock guide\n");
    controller.execute(CommandId.PREVIEW_BROWSER);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitBrowser(true);
    window.shell().forceActive();
    slot().browserPane().focus();
    flush();
    key(SWT.KeyDown, SWT.CTRL);
    key(SWT.KeyDown, SWT.SHIFT);
    key(SWT.KeyDown, 'm');
    key(SWT.KeyUp, 'm');
    key(SWT.KeyUp, SWT.SHIFT);
    key(SWT.KeyUp, SWT.CTRL);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().markdownMode() == MarkdownViewMode.SOURCE;
          }
        },
        "Ctrl+Shift+M in the browser, mode " + controller.documents().markdownMode());
  }

  @Test
  void browserShotsInBothThemes() throws Exception {
    String out = System.getProperty("qcaret.rm.out");
    assumeTrue(out != null && PlatformTraits.PLATFORM.opensBrowser(), "-Dqcaret.rm.out unset");
    openMarkdown(
        "# Mock guide\n\n[[_TOC_]]\n\n## Code\n\n```java\nint x = 1; // note\n```\n\n"
            + "## Table\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n> [!NOTE]\n> A mock note.\n\n"
            + "- [x] done\n- [ ] open\n");
    controller.execute(CommandId.PREVIEW_BROWSER);
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitBrowser(true);
    settle();
    shot(Path.of(out, "browser-light.png"));
    controller.execute(CommandId.THEME_DARK);
    flush();
    settle();
    shot(Path.of(out, "browser-dark.png"));
  }

  private void settle() {
    final long until = System.nanoTime() + 2_000_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
  }

  private void shot(Path file) throws Exception {
    Files.createDirectories(file.getParent());
    Rectangle bounds = window.shell().getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
      ImageLoader loader = new ImageLoader();
      loader.data = new org.eclipse.swt.graphics.ImageData[] {image.getImageData()};
      loader.save(file.toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private void key(int type, int key) {
    Event event = new Event();
    event.type = type;
    if (key == SWT.CTRL || key == SWT.SHIFT) {
      event.keyCode = key;
    } else {
      event.character = (char) key;
    }
    assertTrue(display.post(event));
  }
}
