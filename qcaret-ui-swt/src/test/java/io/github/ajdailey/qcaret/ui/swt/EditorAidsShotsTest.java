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
 * Writes screenshots of the indent guides and modified-line marks (light and dark), and measures
 * page scrolls on an indented 1,000,000-line file with the guides shown and hidden, to the
 * directory {@code qcaret.rm.out} names. Skipped without it.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.rm.out", matches = ".+")
class EditorAidsShotsTest {
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
    Path file = temp.resolve("Sample.java");
    StringBuilder b = new StringBuilder();
    b.append("class Sample {\n");
    for (int m = 0; m < 3; m++) {
      b.append("    void step").append(m).append("(int count) {\n");
      b.append("        for (int i = 0; i < count; i++) {\n");
      b.append("            if (i % 2 == 0) {\n");
      b.append("                total += i;\n");
      b.append("            }\n");
      b.append("\n");
      b.append("            log(i);\n");
      b.append("        }\n");
      b.append("    }\n");
      b.append("\n");
    }
    b.append("}\n");
    Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
    controller.execute(CommandId.INDENT_GUIDES);
    controller.execute(CommandId.LINE_CHANGES);
    openFile(file);
    final EditorController editor = controller.documents().activeEditor();
    String text = editor.document().text().toString();
    int changed = text.indexOf("total += i;");
    editor.selections().set(SelectionSet.caret(changed + "total".length()));
    editor.type("Count");
    int added = editor.document().text().toString().indexOf("log(i);");
    editor.selections().set(SelectionSet.caret(added + "log(i);".length()));
    editor.newline();
    editor.type("trace(i);");
    String now = editor.document().text().toString();
    int removed = now.indexOf("    void step2");
    int removedEnd = now.indexOf('\n', now.indexOf("for (int", removed)) + 1;
    editor
        .selections()
        .set(SelectionSet.of(List.of(Selection.of(now.indexOf('\n', removed) + 1, removedEnd)), 0));
    editor.backspace();
    final EditorPane pane = window.activeEditor();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.lineChanges().revision() == editor.document().revision();
          }
        },
        "marks");
    save(window.shell(), "aids-light.png");
    controller.execute(CommandId.THEME_DARK);
    save(window.shell(), "aids-dark.png");
  }

  @Test
  void scrollWithAndWithoutTheGuides() throws Exception {
    Path file = temp.resolve("large.txt");
    int lines = 1_000_000;
    try (BufferedWriter writer = Files.newBufferedWriter(file)) {
      for (int i = 0; i < lines; i++) {
        writer.write("            ".substring(0, 4 * (i % 4)));
        writer.write("line " + i + " lorem ipsum dolor sit amet, consectetur adipiscing elit, sed");
        writer.write(" do eiusmod tempor\n");
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
      boolean guides = round % 2 == 1;
      pane.setIndentGuides(guides);
      text.update();
      for (int i = 0; i < 100; i++) {
        top = (top + page) % (lines - page);
        long start = System.nanoTime();
        text.setTopIndex(top);
        text.update();
        long took = System.nanoTime() - start;
        if (round >= 2) {
          (guides ? shown : hidden).add(took);
        }
      }
    }
    String report =
        String.format(
            Locale.ROOT,
            "page scroll on %d indented lines, %d scrolls each: guides shown median %.3f ms p90"
                + " %.3f ms; hidden median %.3f ms p90 %.3f ms%n",
            lines,
            shown.size(),
            percentile(shown, 50),
            percentile(shown, 90),
            percentile(hidden, 50),
            percentile(hidden, 90));
    Files.writeString(out.resolve("scroll-guides.txt"), report, StandardCharsets.UTF_8);
  }

  private static double percentile(List<Long> nanos, int p) {
    List<Long> sorted = new ArrayList<Long>(nanos);
    Collections.sort(sorted);
    return sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100)) / 1e6;
  }
}
