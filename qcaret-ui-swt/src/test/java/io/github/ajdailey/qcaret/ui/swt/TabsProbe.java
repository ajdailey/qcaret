package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Close All with the first tab shown, per tab count in {@code -Dqcaret.bench.tabs} (comma list);
 * runs only when that property is set. For each count N it opens N files of {@code
 * -Dqcaret.bench.lines} lines (2,000 by default) in one request, shows the first tab and times
 * Close All: the command's own UI task and the queued UI work after it.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.bench.tabs", matches = ".+")
class TabsProbe {
  private static final Duration BOUND = Duration.ofMinutes(10);

  @TempDir Path temp;

  @Test
  void closeAllWithTheFirstTabShown() throws Exception {
    int lines = Integer.getInteger("qcaret.bench.lines", 2000);
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
    System.out.println("probe.platform=" + SWT.getPlatform() + " " + SWT.getVersion());
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
      window.open();
      drain(display);
      for (String count : System.getProperty("qcaret.bench.tabs").split(",")) {
        final int tabs = Integer.parseInt(count.strip());
        List<Path> files = write(tabs, lines);
        controller.documents().open(files);
        assertTrue(
            EventLoop.runUntil(
                display,
                new BooleanSupplier() {
                  @Override
                  public boolean getAsBoolean() {
                    return window.tabs().getItemCount() >= tabs
                        && controller.documents().loading() == 0;
                  }
                },
                BOUND));
        drain(display);
        controller.documents().select(0);
        drain(display);
        long start = System.nanoTime();
        controller.execute(CommandId.CLOSE_ALL);
        long task = System.nanoTime() - start;
        drain(display);
        long all = System.nanoTime() - start;
        assertEquals(1, window.tabs().getItemCount());
        System.out.println(
            String.format(
                Locale.ROOT,
                "probe.closeAll n=%d task=%.1fms withQueued=%.1fms",
                tabs,
                task / 1e6,
                all / 1e6));
        controller.documents().closeAll(null);
        drain(display);
      }
    } finally {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    }
  }

  private List<Path> write(int count, int lines) throws Exception {
    Path dir = Files.createDirectories(temp.resolve("n" + count));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      text.append("line ").append(i).append(" of a generated file for the tab probe\n");
    }
    List<Path> files = new ArrayList<Path>(count);
    for (int i = 0; i < count; i++) {
      files.add(Files.writeString(dir.resolve("f" + i + ".txt"), text, StandardCharsets.UTF_8));
    }
    return files;
  }

  private static void drain(Display display) {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }
}
