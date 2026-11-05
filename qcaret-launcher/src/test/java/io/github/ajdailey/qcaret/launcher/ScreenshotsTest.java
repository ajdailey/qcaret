package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes the README screenshots: the window on generated mock files, a highlighted Java file in
 * front, once per palette, at 1100 x 700 unless {@code -Dqcaret.screenshots.width} and {@code
 * -Dqcaret.screenshots.height} say otherwise, painted through {@code Shell.print}. Runs only with
 * {@code -Dqcaret.screenshots=<dir>} ({@code tools/screenshots.sh}). The files are written to
 * {@code -Dqcaret.screenshots.files=<dir>} when given, else to a test temporary directory.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.screenshots", matches = ".+")
class ScreenshotsTest {
  private static final int WIDTH = Integer.getInteger("qcaret.screenshots.width", 1100);
  private static final int HEIGHT = Integer.getInteger("qcaret.screenshots.height", 700);
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(30);

  private static final String JAVA =
      """
      package mock.inventory;

      import java.util.ArrayList;
      import java.util.List;

      /** Keeps the mock items of one shelf, newest last. */
      public final class Shelf {
        private static final int LIMIT = 0x40;
        private final List<String> items = new ArrayList<>();

        /**
         * Adds an item unless the shelf is full.
         *
         * @param name the item, such as "EXAMPLE-042"
         * @return false when the shelf holds LIMIT items
         */
        @Override
        public boolean add(String name) {
          if (items.size() >= LIMIT) {
            return false; // Full: the caller retries later.
          }
          items.add(name.strip() + '\\n');
          double ratio = items.size() / 64.0;
          System.out.printf("shelf %.2f full%n", ratio);
          return true;
        }

        /* Sorted copy for the report. */
        List<String> sorted() {
          List<String> copy = new ArrayList<>(items);
          copy.sort(null);
          return copy;
        }
      }
      """;

  @TempDir Path temp;

  @Test
  void lightAndDark() throws Exception {
    Path out = Path.of(System.getProperty("qcaret.screenshots"));
    Files.createDirectories(out);
    String files = System.getProperty("qcaret.screenshots.files", "");
    Path dir = files.isEmpty() ? temp : Files.createDirectories(Path.of(files));
    List<FileRequest> requests = new ArrayList<FileRequest>();
    requests.add(
        new FileRequest(
            Files.writeString(
                dir.resolve("settings.yaml"),
                "# Mock service settings\nservice:\n  name: mock-shelf\n  port: 8080\n"
                    + "  enabled: true\n",
                StandardCharsets.UTF_8)));
    requests.add(
        new FileRequest(
            Files.writeString(dir.resolve("Shelf.java"), JAVA, StandardCharsets.UTF_8)));
    try {
      shoot(requests, ThemeMode.LIGHT, out.resolve("qcaret-light.png"));
      shoot(requests, ThemeMode.DARK, out.resolve("qcaret-dark.png"));
    } finally {
      for (FileRequest request : requests) {
        Files.deleteIfExists(request.path());
      }
    }
  }

  private void shoot(List<FileRequest> files, ThemeMode theme, final Path png) throws Exception {
    Files.createDirectories(temp.resolve("settings"));
    StartupRequest request = new StartupRequest(files, WIDTH, HEIGHT, 0, 0, null, true);
    final boolean[] saved = new boolean[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            final int[] step = new int[1];
            display.timerExec(
                50,
                new Runnable() {
                  @Override
                  public void run() {
                    if (System.nanoTime() > deadline) {
                      controller.quit();
                      return;
                    }
                    EditorController editor = controller.documents().activeEditor();
                    boolean ready =
                        controller.documents().count() == 2
                            && controller.documents().loading() == 0
                            && editor != null;
                    if (!ready) {
                      display.timerExec(50, this);
                      return;
                    }
                    if (step[0] == 0) {
                      // Loads end in any order: show the Java file.
                      for (int i = 0; i < 2 && editor.document().text().length() < 200; i++) {
                        controller.documents().select(i);
                        editor = controller.documents().activeEditor();
                      }
                      editor.goTo(18, 15);
                      int caret = editor.selections().selections().primary().caret();
                      editor
                          .selections()
                          .set(SelectionSet.of(List.of(Selection.of(caret, caret + 5)), 0));
                    }
                    if (++step[0] < 30) {
                      display.timerExec(50, this);
                      return;
                    }
                    save(window, png);
                    saved[0] = true;
                    controller.quit();
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(
        0,
        new Gui(
                request,
                new ConfigStore(
                    new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
                Preferences.DEFAULTS.withTheme(theme),
                err,
                observer,
                LogFiles.NONE)
            .run());
    assertTrue(saved[0], "screenshot not taken");
  }

  /** Paints the shell into an image through the public print API and writes a PNG. */
  private static void save(MainWindow window, Path png) {
    Display display = window.shell().getDisplay();
    while (display.readAndDispatch()) {
      // Flush pending paints so the image shows the latest rows.
    }
    Point size = window.shell().getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      window.shell().print(gc);
      ImageData data = image.getImageData();
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {data};
      loader.save(png.toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
