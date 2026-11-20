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
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes screenshots of the Outline dock beside a Split preview with a table and fenced code (light
 * and dark) and measures the fill of a 10,000-heading tree, to the directory {@code qcaret.rm.out}
 * names. Skipped without it.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.rm.out", matches = ".+")
class OutlineShotsTest {
  private static final Duration BOUND = Duration.ofSeconds(120);

  private static final String GUIDE =
      "# Mock guide\n\nA mock document for the outline.\n\n## Install\n\nSteps.\n\n"
          + "### From the archive\n\nUnpack it.\n\n### From source\n\nBuild it.\n\n"
          + "## Usage\n\nOpen a file.\n\n| Target | Status |\n|---|---|\n| Linux | done |\n"
          + "| Windows | pending |\n\n```java\nint total = items.size(); // mock\n```\n\n"
          + "Keys\n----\n\nPress keys.\n\n"
          + "```markdown\n# not a heading\n```\n\n## Usage\n\nA repeated heading.\n";

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
    window.place(new WindowGeometry.Request(null, null, 1200, 700), null);
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

  private void openFile(String name, String text) throws Exception {
    Path file = Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().loading() == 0 && controller.documents().activeMarkdown();
          }
        },
        name);
  }

  @Test
  void screenshots() throws Exception {
    openFile("guide.md", GUIDE);
    controller.documents().setMarkdownMode(MarkdownViewMode.SPLIT);
    controller.execute(CommandId.MARKDOWN_OUTLINE);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            MarkdownPane pane = window.activePreview();
            return pane != null
                && pane.text().getText().contains("Mock guide")
                && window.outlineDock().tree().getItemCount() == 1
                && window.outlineDock().filled();
          }
        },
        "outline and preview");
    controller.documents().activeEditor().goTo(12, 0);
    save(window.shell(), "outline-light.png");
    controller.execute(CommandId.THEME_DARK);
    save(window.shell(), "outline-dark.png");
  }

  @Test
  void fillMeasurement() throws Exception {
    StringBuilder text = new StringBuilder("# Top\n");
    for (int i = 1; i < 10_000; i++) {
      text.append("## Heading ").append(i).append('\n');
    }
    openFile("many.md", text.toString());
    final OutlineDock dock = window.outlineDock();
    long start = System.nanoTime();
    controller.execute(CommandId.MARKDOWN_OUTLINE);
    long longest = 0;
    StringBuilder turns = new StringBuilder();
    while (!(dock.filled() && dock.tree().getItemCount() == 1)) {
      long turn = System.nanoTime();
      boolean ran = display.readAndDispatch();
      longest = Math.max(longest, System.nanoTime() - turn);
      if (System.nanoTime() - turn > 20_000_000L) {
        turns.append(String.format(Locale.ROOT, " %.0f", (System.nanoTime() - turn) / 1e6));
      }
      if (!ran) {
        display.sleep();
      }
      assertTrue(System.nanoTime() - start < BOUND.toNanos(), "filled");
    }
    long total = System.nanoTime() - start;
    int slices = dock.slices();
    long refresh = System.nanoTime();
    dock.refresh();
    long same = System.nanoTime() - refresh;
    Files.writeString(
        out.resolve("outline-fill.txt"),
        String.format(
                Locale.ROOT,
                "headings 10000, slices %d, show to filled %.1f ms, longest turn %.1f ms,"
                    + " unchanged refresh %.1f ms%n",
                slices,
                total / 1e6,
                longest / 1e6,
                same / 1e6)
            + "turns over 20 ms:"
            + turns
            + System.lineSeparator(),
        StandardCharsets.UTF_8);
  }
}
