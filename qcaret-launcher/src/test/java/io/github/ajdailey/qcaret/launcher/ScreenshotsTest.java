package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Writes the README screenshots: the window on a generated mock project with the Workspace Browser
 * open, a highlighted Java file in front and a search's results below it, once per palette, at 1100
 * x 700 unless {@code -Dqcaret.screenshots.width} and {@code -Dqcaret.screenshots.height} say
 * otherwise, painted through {@code Shell.print}. Runs only with {@code -Dqcaret.screenshots=<dir>}
 * ({@code tools/screenshots.sh}). The files are written to {@code -Dqcaret.screenshots.files=<dir>}
 * when given, else to a test temporary directory.
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

  private static final String CATALOG =
      """
      package mock.inventory;

      import java.util.List;

      /** Lists the items of every mock shelf. */
      final class Catalog {
        private final List<Shelf> shelves;

        Catalog(List<Shelf> shelves) {
          this.shelves = List.copyOf(shelves);
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
    Path project = mockProject(dir.resolve("inventory"));
    List<FileRequest> requests = new ArrayList<FileRequest>();
    requests.add(new FileRequest(project.resolve("src").resolve("Catalog.java")));
    shoot(requests, project, ThemeMode.LIGHT, out.resolve("qcaret-light.png"));
    shoot(requests, project, ThemeMode.DARK, out.resolve("qcaret-dark.png"));
  }

  /**
   * Opens {@code Catalog.java}, sets the project as the workspace, shows the browser, searches the
   * project for {@code LIMIT}, goes to the first match, which opens {@code Shelf.java} in a second
   * tab, reveals that file in the browser and saves the window.
   */
  private void shoot(List<FileRequest> files, final Path project, ThemeMode theme, final Path png)
      throws Exception {
    Path settings = Files.createDirectories(temp.resolve("settings-" + theme));
    StartupRequest request = new StartupRequest(files, WIDTH, HEIGHT, 0, 0, null, true);
    final boolean[] saved = new boolean[1];
    Gui.Observer observer =
        new Gui.Observer() {
          @Override
          public void opened(
              final MainWindow window, final ApplicationController controller, Workers workers) {
            final Display display = window.shell().getDisplay();
            final long deadline = System.nanoTime() + BOUND_NANOS;
            display.timerExec(
                50,
                new Runnable() {
                  private int phase;
                  private int ticks;

                  @Override
                  public void run() {
                    if (System.nanoTime() > deadline) {
                      controller.quit();
                      return;
                    }
                    if (!advance()) {
                      display.timerExec(50, this);
                      return;
                    }
                    save(window, png);
                    saved[0] = true;
                    controller.quit();
                  }

                  /** Takes the next step once the last one has finished; true when settled. */
                  private boolean advance() {
                    switch (phase) {
                      case 0 -> {
                        if (controller.documents().count() == 1
                            && controller.documents().loading() == 0) {
                          controller.workspace().set(project);
                          phase = 1;
                        }
                      }
                      case 1 -> {
                        if (controller.workspace().root() != null) {
                          controller.execute(CommandId.WORKSPACE_BROWSER);
                          controller.search().run(form(controller.workspace().root()));
                          phase = 2;
                        }
                      }
                      case 2 -> {
                        if (controller.search().setCount() == 1
                            && !controller.search().running(0)) {
                          controller.execute(CommandId.NEXT_RESULT);
                          phase = 3;
                        }
                      }
                      case 3 -> {
                        if (controller.documents().count() == 2
                            && controller.documents().loading() == 0) {
                          controller.workspace().revealActive();
                          phase = 4;
                        }
                      }
                      default -> {
                        return ++ticks >= 30;
                      }
                    }
                    return false;
                  }
                });
          }
        };
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    assertEquals(
        0,
        new Gui(
                request,
                new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)),
                Preferences.DEFAULTS.withTheme(theme),
                err,
                observer,
                LogFiles.NONE)
            .run());
    assertTrue(saved[0], "screenshot not taken");
  }

  /** Returns a case-sensitive whole-word search for {@code LIMIT} in the workspace's Java files. */
  private static SearchForm form(Path root) {
    return new SearchForm(
        "LIMIT",
        false,
        true,
        true,
        SearchScope.WORKSPACE,
        "",
        "*.java",
        "",
        false,
        root.toString(),
        List.of(),
        List.of(),
        List.of());
  }

  private static Path mockProject(Path root) throws IOException {
    Files.createDirectories(root.resolve("src"));
    Files.createDirectories(root.resolve("config"));
    Files.writeString(root.resolve("src").resolve("Shelf.java"), JAVA, StandardCharsets.UTF_8);
    Files.writeString(root.resolve("src").resolve("Catalog.java"), CATALOG, StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("config").resolve("settings.yaml"),
        "# Mock service settings\nservice:\n  name: mock-shelf\n  port: 8080\n",
        StandardCharsets.UTF_8);
    Files.writeString(root.resolve("notes.txt"), "Mock notes.\n", StandardCharsets.UTF_8);
    return root.toRealPath();
  }

  /**
   * Paints the shell into an image through the public print API and writes a PNG, without the blank
   * rows and columns the shell's estimated trim adds below and right of the window.
   */
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
      int width = contentWidth(data);
      int height = contentHeight(data);
      Image cut = new Image(display, width, height);
      GC cutGc = new GC(cut);
      try {
        cutGc.drawImage(image, 0, 0, width, height, 0, 0, width, height);
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {cut.getImageData()};
        loader.save(png.toString(), SWT.IMAGE_PNG);
      } finally {
        cutGc.dispose();
        cut.dispose();
      }
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Returns the image's height without the rows at its bottom that match its last row. */
  private static int contentHeight(ImageData data) {
    int[] last = new int[data.width];
    int[] row = new int[data.width];
    data.getPixels(0, data.height - 1, data.width, last, 0);
    for (int y = data.height - 2; y > 0; y--) {
      data.getPixels(0, y, data.width, row, 0);
      if (!Arrays.equals(last, row)) {
        return y + 1;
      }
    }
    return data.height;
  }

  /** Returns the image's width without the columns at its right that match its last column. */
  private static int contentWidth(ImageData data) {
    int[] last = column(data, data.width - 1);
    for (int x = data.width - 2; x > 0; x--) {
      if (!Arrays.equals(last, column(data, x))) {
        return x + 1;
      }
    }
    return data.width;
  }

  private static int[] column(ImageData data, int x) {
    int[] pixels = new int[data.height];
    for (int y = 0; y < data.height; y++) {
      pixels[y] = data.getPixel(x, y);
    }
    return pixels;
  }
}
