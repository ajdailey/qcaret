package io.github.ajdailey.qcaret.ui.swt.markdown;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import io.github.ajdailey.qcaret.ui.swt.SwtThemeService;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes screenshots of the Basic preview of a fixture holding every element in Split (light and
 * dark), and measures the UI-thread apply of 100 KiB and 1 MiB generated documents, to the
 * directory {@code qcaret.rm.out} names. Skipped without it.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.rm.out", matches = ".+")
class MarkdownPreviewShotsTest {
  private static final Duration BOUND = Duration.ofSeconds(120);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Path out;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
    out = Files.createDirectories(Path.of(System.getProperty("qcaret.rm.out")));
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
    window.place(new WindowGeometry.Request(null, null, 1200, 900), null);
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

  private void settle() {
    final long until = System.nanoTime() + 1_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        "settled");
  }

  private void save(Shell shell, String name) {
    settle();
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(out.resolve(name).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  @Test
  void screenshots() throws Exception {
    ImageData shot = new ImageData(160, 90, 24, new PaletteData(0xff0000, 0xff00, 0xff));
    for (int y = 0; y < shot.height; y++) {
      for (int x = 0; x < shot.width; x++) {
        shot.setPixel(x, y, shot.palette.getPixel(new RGB(40 + x, 120, 200 - y)));
      }
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {shot};
    loader.save(temp.resolve("shot.png").toString(), SWT.IMAGE_PNG);
    Files.writeString(temp.resolve("logo.svg"), "<svg/>", StandardCharsets.UTF_8);
    Path file =
        Files.writeString(
            temp.resolve("guide.md"), MarkdownPaneTest.FIXTURE, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().activeMarkdown();
          }
        },
        "markdown tab");
    controller.execute(CommandId.MARKDOWN_SPLIT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            MarkdownPane pane = window.activePreview();
            return pane != null && pane.images().live() == 1;
          }
        },
        "image decoded");
    MarkdownPane pane = window.activePreview();
    assertTrue(pane.decoder().thread().startsWith("qcaret-io-"), pane.decoder().thread());
    save(window.shell(), "preview-light.png");
    controller.execute(CommandId.THEME_DARK);
    save(window.shell(), "preview-dark.png");
    controller.execute(CommandId.THEME_LIGHT);
    pane.text().setTopIndex(pane.text().getLineCount() - 1);
    save(window.shell(), "preview-light-end.png");
  }

  @Test
  void applyTimes() throws Exception {
    Shell shell = new Shell(display);
    shell.setLayout(new FillLayout());
    shell.setSize(900, 700);
    MarkdownPane pane =
        new MarkdownPane(
            shell,
            new MarkdownPreviewInput() {
              @Override
              public void attach(MarkdownPreviewView view) {
                // Not attached.
              }

              @Override
              public void renderRequested() {
                // No notice.
              }

              @Override
              public void previewScrolled(int line) {
                // Not mapped.
              }

              @Override
              public void clicked(int offset) {
                // No links followed.
              }

              @Override
              public void linkFollowed(String location) {
                // No links followed.
              }

              @Override
              public void browserFailed() {
                // Basic only.
              }
            },
            Palette.LIGHT,
            new SwtUiDispatcher(display),
            "Monospace");
    shell.open();
    StringBuilder report = new StringBuilder();
    for (int kib : new int[] {100, 1024}) {
      String text = document(kib * 1024);
      RenderedMarkdown r = new MarkdownRenderer().render(text, temp, new CancellationToken());
      MarkdownPreviewContent content =
          new MarkdownPreviewContent(
              r, List.of(), MarkdownSpans.of(r, List.of()), new ArrayList<MarkdownImageRequest>());
      long[] show = new long[5];
      long[] paint = new long[5];
      long[] longest = new long[5];
      for (int round = -2; round < 5; round++) {
        long start = System.nanoTime();
        pane.showRendered(content);
        long shown = System.nanoTime();
        pane.text().redraw();
        pane.text().update();
        long painted = System.nanoTime();
        long max = 0;
        while (pane.tables().measured(r.tables().size() - 1) == null) {
          long task = System.nanoTime();
          boolean ran = display.readAndDispatch();
          max = Math.max(max, System.nanoTime() - task);
          if (!ran) {
            display.sleep();
          }
        }
        if (round >= 0) {
          show[round] = shown - start;
          paint[round] = painted - shown;
          longest[round] = max;
        }
      }
      report.append(
          String.format(
              "%5d KiB, %8d chars, %6d lines, %4d tables: showRendered median %.1f max %.1f ms;"
                  + " first paint median %.1f max %.1f ms; longest UI task while measuring"
                  + " tables median %.1f max %.1f ms; longest measuring turn %.1f ms; measuring"
                  + " turns %d%n",
              kib,
              text.length(),
              r.lineCount(),
              r.tables().size(),
              median(show),
              max(show),
              median(paint),
              max(paint),
              median(longest),
              max(longest),
              pane.tables().longestTurn() / 1e6,
              pane.tables().turns()));
    }
    Files.writeString(out.resolve("markdown-apply.txt"), report.toString());
    shell.dispose();
  }

  private static double median(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2] / 1e6;
  }

  private static double max(long[] values) {
    long max = 0;
    for (long v : values) {
      max = Math.max(max, v);
    }
    return max / 1e6;
  }

  /**
   * {@code MarkdownHarness.document} of qcaret-bench: a seeded document of at least {@code chars}.
   */
  private static String document(int chars) {
    Random random = new Random(42);
    StringBuilder out = new StringBuilder(chars + 2048);
    int section = 0;
    while (out.length() < chars) {
      section++;
      out.append("## Section ").append(section).append(" of the mock guide\n\n");
      for (int p = 0; p < 2; p++) {
        out.append("The *mock* text of paragraph ").append(p).append(" holds `code` and a ");
        out.append("[link](docs/page").append(random.nextInt(100)).append(".md) and **strong");
        out.append(" words** that wrap\nover two source lines with ~~struck~~ text.\n\n");
      }
      out.append("- item one\n  - nested [x] task\n- [ ] open task\n- item three\n\n");
      out.append("> A quoted line with *emphasis*.\n\n");
      out.append("| Name | Value | Note |\n|:-----|------:|:----:|\n");
      for (int r = 0; r < 4; r++) {
        out.append("| row").append(r).append(" | ").append(random.nextInt(1000));
        out.append(" | **b** |\n");
      }
      out.append("\n```java\nclass Mock").append(section).append(" {\n");
      out.append("  // a comment\n  int value = ").append(random.nextInt(100)).append(";\n");
      out.append("  String name = \"mock\";\n}\n```\n\n");
    }
    return out.toString();
  }
}
