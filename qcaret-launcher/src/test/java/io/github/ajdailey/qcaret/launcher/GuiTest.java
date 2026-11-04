package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import io.github.ajdailey.qcaret.ui.swt.MainWindow;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup to shutdown through the composed application, on the test thread as UI thread. The
 * position assertions need X11 (Xvfb), not Wayland, and are skipped on SWT GTK 4, which cannot move
 * a window.
 */
@Tag("swt")
class GuiTest {
  private static final long BOUND_NANOS = TimeUnit.SECONDS.toNanos(10);
  private static final boolean POSITIONED = !"1".equals(System.getenv("SWT_GTK4"));

  @TempDir Path temp;

  @BeforeEach
  void createSettings() throws Exception {
    Files.createDirectory(temp.resolve("settings"));
  }

  /** Waits for the client area, records size and location, then quits. */
  private static final class Recorder implements Gui.Observer {
    final int width;
    final int height;
    final List<String> observed = new ArrayList<String>();
    Workers workers;

    Recorder(int width, int height) {
      this.width = width;
      this.height = height;
    }

    @Override
    public void opened(
        final MainWindow window, final ApplicationController controller, Workers owned) {
      workers = owned;
      final Display display = window.shell().getDisplay();
      final long deadline = System.nanoTime() + BOUND_NANOS;
      display.timerExec(
          20,
          new Runnable() {
            @Override
            public void run() {
              Rectangle area = window.shell().getClientArea();
              boolean ready = area.width == width && area.height == height;
              if (ready || System.nanoTime() > deadline) {
                observed.add(window.shell().getText());
                observed.add(area.width + "x" + area.height);
                if (POSITIONED) {
                  observed.add(window.shell().getLocation().toString());
                }
                controller.quit();
              } else {
                display.timerExec(20, this);
              }
            }
          });
    }
  }

  private ConfigStore config() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
  }

  private static StartupRequest request(
      List<FileRequest> files, Integer width, Integer height, Integer x, Integer y, boolean multi) {
    return new StartupRequest(files, width, height, x, y, null, multi);
  }

  private int run(StartupRequest request, Preferences preferences, Recorder recorder) {
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    return new Gui(request, config(), preferences, err, recorder, LogFiles.NONE).run();
  }

  @Test
  void opensAtTheRequestedSizeThenShutsDownWithoutLeakingThreads() throws Exception {
    Path file = Files.createFile(temp.resolve("notes.txt"));
    final Recorder recorder = new Recorder(900, 600);
    StartupRequest request = request(List.of(new FileRequest(file)), 900, 600, 50, 40, true);
    assertEquals(0, run(request, Preferences.DEFAULTS, recorder));
    assertEquals(placed("900x600", "Point {50, 40}"), recorder.observed);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return recorder.workers.liveThreads() == 0;
              }
            },
            Duration.ofSeconds(10)));
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      assertTrue(!thread.getName().startsWith("qcaret-") || !thread.isAlive(), thread.getName());
    }
  }

  @Test
  void theRememberedPlacementOpensAndIsSavedAgain() {
    Preferences remembered =
        Preferences.DEFAULTS.withWindow(new WindowState(60, 50, 700, 500, false));
    Recorder recorder = new Recorder(700, 500);
    assertEquals(0, run(request(List.of(), null, null, null, null, false), remembered, recorder));
    assertEquals(placed("700x500", "Point {60, 50}"), recorder.observed);
    WindowState saved = config().load().window();
    assertEquals(remembered.window().width(), saved.width());
    assertEquals(remembered.window().height(), saved.height());
    if (POSITIONED) {
      assertEquals(remembered.window(), saved);
    }
  }

  @Test
  void aCommandLinePlacementIsNotSavedBack() {
    Preferences remembered =
        Preferences.DEFAULTS.withWindow(new WindowState(60, 50, 700, 500, false));
    assertTrue(config().save(remembered));
    Recorder recorder = new Recorder(900, 500);
    assertEquals(0, run(request(List.of(), 900, null, null, null, false), remembered, recorder));
    assertEquals("900x500", recorder.observed.get(1));
    assertEquals(remembered.window(), config().load().window());
  }

  @Test
  void aMultiInstanceDoesNotSaveThePlacement() {
    // A size smaller than the CI runners' 1024 x 768 screens, so the window is not clamped.
    Preferences remembered =
        Preferences.DEFAULTS.withWindow(new WindowState(60, 50, 700, 500, false));
    Recorder recorder = new Recorder(700, 500);
    assertEquals(0, run(request(List.of(), null, null, null, null, true), remembered, recorder));
    assertEquals("700x500", recorder.observed.get(1));
    assertEquals(WindowState.UNKNOWN, config().load().window());
  }

  /** The title, client size and, where positions apply, the location the recorder observes. */
  private static List<String> placed(String size, String location) {
    return POSITIONED ? List.of("QCaret", size, location) : List.of("QCaret", size);
  }
}
