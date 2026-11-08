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
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
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
 * Writes screenshots of the right margin (light and dark, word wrap on) and the Document Statistics
 * dialog, and measures page scrolls on a 1,000,000-line file with the margin shown and hidden, to
 * the directory {@code qcaret.rm.out} names. Skipped without it.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.rm.out", matches = ".+")
class EditorAdditionsShotsTest {
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
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 1000, 560), null);
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
                && controller.documents().loading() == 0;
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

  private void save(Shell shell, String name) {
    final long until = System.nanoTime() + 1_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        "settled");
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
    Path file = temp.resolve("notes.txt");
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      b.append("Line ").append(i).append(": the quick brown fox jumps over the lazy dog, then the");
      b.append(" lazy dog naps in the sun while the fox reads the morning paper.\n");
      b.append("    short line ").append(i).append('\n');
    }
    Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
    openFile(file);
    controller.execute(CommandId.RIGHT_MARGIN);
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
    save(window.shell(), "margin-light.png");
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 200)), 0));
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
    save(dialog.shell(), "statistics-light.png");
    dialog.shell().dispose();
    controller.execute(CommandId.THEME_DARK);
    save(window.shell(), "margin-dark.png");
    controller.execute(CommandId.DOCUMENT_STATISTICS);
    final StatisticsDialog dark = window.statisticsDialog();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !dark.values()[0].isEmpty();
          }
        },
        "counted dark");
    save(dark.shell(), "statistics-dark.png");
  }

  @Test
  void scrollWithAndWithoutTheMargin() throws Exception {
    Path file = temp.resolve("large.txt");
    int lines = 1_000_000;
    try (BufferedWriter writer = Files.newBufferedWriter(file)) {
      for (int i = 0; i < lines; i++) {
        writer.write("line " + i + " lorem ipsum dolor sit amet, consectetur adipiscing elit, sed");
        writer.write(" do eiusmod tempor incididunt ut labore\n");
      }
    }
    openFile(file);
    EditorPane pane = window.activeEditor();
    StyledText text = pane.text();
    int page = Math.max(1, text.getClientArea().height / text.getLineHeight());
    List<Long> shown = new ArrayList<Long>();
    List<Long> hidden = new ArrayList<Long>();
    int top = 0;
    for (int round = 0; round < 12; round++) {
      boolean margin = round % 2 == 1;
      pane.setRightMargin(margin ? 80 : 0);
      text.update();
      for (int i = 0; i < 100; i++) {
        top = (top + page) % (lines - page);
        long start = System.nanoTime();
        text.setTopIndex(top);
        text.update();
        long took = System.nanoTime() - start;
        if (round >= 2) {
          (margin ? shown : hidden).add(took);
        }
      }
    }
    String report =
        String.format(
            Locale.ROOT,
            "page scroll on %d lines, %d scrolls each: margin shown median %.3f ms p90 %.3f ms;"
                + " hidden median %.3f ms p90 %.3f ms%n",
            lines,
            shown.size(),
            percentile(shown, 50),
            percentile(shown, 90),
            percentile(hidden, 50),
            percentile(hidden, 90));
    Files.writeString(out.resolve("scroll.txt"), report, StandardCharsets.UTF_8);
  }

  private static double percentile(List<Long> nanos, int p) {
    List<Long> sorted = new ArrayList<Long>(nanos);
    Collections.sort(sorted);
    return sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100)) / 1e6;
  }
}
