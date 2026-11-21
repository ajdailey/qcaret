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
import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
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
import org.eclipse.swt.widgets.Control;
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
 * theme switch reloads the page in the new palette, a browser that fails to create leaves Basic
 * with the notice and the reason, the cycle key runs with the browser focused, and the Command
 * Palette lists Cycle Markdown View. The panes' dispatcher never schedules a start or load bound,
 * so no real load races one. A test that needs a real browser skips, with the reason, where {@link
 * BrowserHosts#assumeBrowser} finds none. With {@code -Dqcaret.rm.out=<dir>} the light and dark
 * page is saved there as PNG.
 */
@Tag("swt")
class BrowserPreviewSurfaceTest {
  /** Covers the WebView2 start of the first browser control on a CI runner. */
  private static final Duration BOUND = Duration.ofSeconds(90);

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
    window.setPreviewBrowserDispatcher(new UnboundDispatcher(new SwtUiDispatcher(display)));
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
    BrowserHosts.assumeBrowser(window.shell());
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
  void aThemeSwitchShowsThePageInTheNewPalette() throws Exception {
    BrowserHosts.assumeBrowser(window.shell());
    openMarkdown("# Mock guide\n");
    controller.execute(CommandId.PREVIEW_BROWSER);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitBrowser(true);
    assertTrue(
        slot()
            .browserPane()
            .content()
            .html()
            .page()
            .contains(MarkdownPage.stylesheet(Palette.LIGHT)));
    controller.execute(CommandId.THEME_DARK);
    final Palette dark = controller.theme().palette();
    assertTrue(dark.dark());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return slot()
                .browserPane()
                .content()
                .html()
                .page()
                .contains(MarkdownPage.stylesheet(dark));
          }
        },
        "the Dark page");
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

  /**
   * The mode steps once per press. On a win32 CI runner a posted key reaches the {@code Browser}'s
   * own window and its menu accelerator, not WebView2, so the window filter must not run it again;
   * the WebView2 path is {@link #aWindowKeyTheBrowserReportsRunsItsCommandOnce}.
   */
  @Test
  void theCycleKeyRunsWhileTheBrowserHasFocus() throws Exception {
    String platform = SWT.getPlatform();
    assumeTrue(
        ("gtk".equals(platform) && PlatformTraits.PLATFORM.opensBrowser())
            || "win32".equals(platform),
        "keys posted through Display.post: GTK 3 and win32 only");
    assumeTrue(
        !"win32".equals(platform) || !"aarch64".equals(System.getProperty("os.arch")),
        "the windows-aarch64 runner delivers no posted key");
    BrowserHosts.assumeBrowser(window.shell());
    openMarkdown("# Mock guide\n");
    controller.execute(CommandId.PREVIEW_BROWSER);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitBrowser(true);
    window.shell().forceActive();
    slot().browserPane().focus();
    flush();
    PostedInput.ctrlShift(display, 'm');
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().markdownMode() == MarkdownViewMode.SOURCE;
          }
        },
        "Ctrl+Shift+M in the browser, mode " + controller.documents().markdownMode());
    settle();
    assertEquals(MarkdownViewMode.SOURCE, controller.documents().markdownMode());
  }

  /**
   * SWT's Edge sends a key pressed in WebView2 to the {@code Browser}'s {@code KeyDown} listeners
   * from {@code AcceleratorKeyPressed}, with the key's letter as its key code and no character, and
   * marks it handled when a listener clears {@code doit}. The window's accelerators never see it,
   * so the window's key filter runs the command. Regression: Ctrl+Shift+M and Ctrl+F did nothing
   * with the page focused on Windows.
   */
  @Test
  void aWindowKeyTheBrowserReportsRunsItsCommandOnce() throws Exception {
    assumeTrue(PlatformTraits.PLATFORM.browserKeysSkipAccelerators(), "Edge's key path: win32");
    BrowserHosts.assumeBrowser(window.shell());
    openMarkdown("# Mock guide\n");
    controller.execute(CommandId.PREVIEW_BROWSER);
    controller.execute(CommandId.MARKDOWN_SPLIT);
    awaitBrowser(true);
    Event key = new Event();
    key.keyCode = 'm';
    key.stateMask = SWT.CTRL | SWT.SHIFT;
    slot().browserPane().browser().notifyListeners(SWT.KeyDown, key);
    assertFalse(key.doit, "the key is marked handled");
    assertEquals(MarkdownViewMode.PREVIEW, controller.documents().markdownMode());
    flush();
    assertEquals(MarkdownViewMode.PREVIEW, controller.documents().markdownMode());
  }

  /**
   * WebKit cancels a {@code file:} navigation from a {@code setText} page before the location
   * listener sees it, so the page's file links use a scheme of their own.
   */
  @Test
  void aClickOnALocalFileLinkOpensTheFile() throws Exception {
    PostedInput.assumeGtk();
    assumeTrue(PlatformTraits.PLATFORM.opensBrowser(), "no Browser under GTK 4");
    BrowserHosts.assumeBrowser(window.shell());
    Files.writeString(temp.resolve("other.txt"), "mock other\n", StandardCharsets.UTF_8);
    Path absolute =
        Files.writeString(temp.resolve("abs.txt"), "mock abs\n", StandardCharsets.UTF_8);
    controller.execute(CommandId.PREVIEW_BROWSER);
    clickOpens("[" + "GO ".repeat(800) + "](other.txt)\n", "other.txt");
    clickOpens("[" + "GO ".repeat(800) + "](" + absolute.toUri() + ")\n", "abs.txt");
  }

  private void clickOpens(String markdown, final String name) throws Exception {
    openMarkdown(markdown);
    controller.execute(CommandId.MARKDOWN_PREVIEW);
    awaitBrowser(true);
    window.shell().forceActive();
    settle();
    final int before = window.tabs().getItemCount();
    Control browser = slot().browserPane().browser();
    PostedInput.click(display, browser.toDisplay(browser.getSize().x / 2, browser.getSize().y / 3));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && window.tabs().getSelection().getText().contains(name);
          }
        },
        name + " opened in a tab");
    controller.documents().select(before - 1);
    controller.execute(CommandId.CLOSE);
    flush();
  }

  @Test
  void browserShotsInBothThemes() throws Exception {
    String out = System.getProperty("qcaret.rm.out");
    assumeTrue(out != null, "-Dqcaret.rm.out unset");
    BrowserHosts.assumeBrowser(window.shell());
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
}
