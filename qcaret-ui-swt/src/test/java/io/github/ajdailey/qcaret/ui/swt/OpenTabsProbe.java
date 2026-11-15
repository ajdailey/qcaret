package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opens N files in one window in one request, per count in {@code -Dqcaret.bench.open} (comma
 * list), as a handoff of N files does; runs only when that property is set. Prints the longest
 * single UI dispatch, the total, the tab strip's paint count, and the lag: the longest wait of a UI
 * task posted every 10 ms from another thread, which is what {@code EventLoopWatchdog} reports. The
 * UI thread's stack is sampled during each open.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.bench.open", matches = ".+")
class OpenTabsProbe {
  @TempDir Path temp;

  @Test
  void openManyInOneRequest() throws Exception {
    Display display = new Display();
    Workers workers =
        new Workers(
            Runtime.getRuntime().availableProcessors(),
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                error.printStackTrace();
              }
            });
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    final ApplicationController controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            MemoryBudget.fromRuntime(),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    controller.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    final int[] paints = new int[1];
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
      window.open();
      window
          .tabs()
          .addListener(
              SWT.Paint,
              new Listener() {
                @Override
                public void handleEvent(Event event) {
                  paints[0]++;
                }
              });
      drain(display);
      for (String count : System.getProperty("qcaret.bench.open").split(",")) {
        int n = Integer.parseInt(count.strip());
        Path dir = Files.createDirectories(temp.resolve("n" + n + "-" + System.nanoTime()));
        List<Path> files = new ArrayList<Path>();
        for (int i = 0; i < n; i++) {
          files.add(
              Files.writeString(
                  dir.resolve("generated-file-" + i + ".txt"), "mock\n", StandardCharsets.UTF_8));
        }
        paints[0] = 0;
        int before = window.tabs().getItemCount();
        TabsProbe.Sampler sampler = new TabsProbe.Sampler(Thread.currentThread(), "probe.open");
        Lag lag = new Lag(display);
        long start = System.nanoTime();
        long longest = 0;
        sampler.sampling = true;
        lag.start();
        controller.documents().open(files);
        while (window.tabs().getItemCount() < before + n || controller.documents().loading() > 0) {
          long at = System.nanoTime();
          if (display.readAndDispatch()) {
            longest = Math.max(longest, System.nanoTime() - at);
          } else {
            display.sleep();
          }
        }
        long at = System.nanoTime();
        while (display.readAndDispatch()) {
          longest = Math.max(longest, System.nanoTime() - at);
          at = System.nanoTime();
        }
        long total = System.nanoTime() - start;
        lag.halt();
        sampler.sampling = false;
        sampler.halt();
        sampler.print(n);
        System.out.println(
            String.format(
                Locale.ROOT,
                "probe.open n=%d total=%.1fms longest=%.1fms lag=%.1fms paints=%d",
                n,
                total / 1e6,
                longest / 1e6,
                lag.longest.get() / 1e6,
                paints[0]));
        controller.documents().closeAll(null);
        drain(display);
      }
    } finally {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    }
  }

  private static void drain(Display display) {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  /** Posts a UI task every 10 ms after the last one ran, and keeps the longest wait. */
  private static final class Lag extends Thread {
    final AtomicLong longest = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Display display;

    Lag(Display display) {
      this.display = display;
      setDaemon(true);
    }

    @Override
    public void run() {
      while (running.get()) {
        final long posted = System.nanoTime();
        final AtomicBoolean ran = new AtomicBoolean();
        display.asyncExec(
            new Runnable() {
              @Override
              public void run() {
                longest.accumulateAndGet(System.nanoTime() - posted, Math::max);
                ran.set(true);
              }
            });
        while (!ran.get() && running.get()) {
          Thread.onSpinWait();
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    void halt() throws InterruptedException {
      running.set(false);
      join();
    }
  }
}
