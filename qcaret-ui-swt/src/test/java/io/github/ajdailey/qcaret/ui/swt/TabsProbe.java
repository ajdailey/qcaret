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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Close All: the command's own UI task, the queued UI work after it, and the longest single queued
 * task. {@code -Dqcaret.bench.repeat} (1 by default) repeats each count. The UI thread's stack is
 * sampled every millisecond during each Close All task; the counts over every repeat print once per
 * count.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.bench.tabs", matches = ".+")
class TabsProbe {
  private static final Duration BOUND = Duration.ofMinutes(10);

  @TempDir Path temp;

  @Test
  void closeAllWithTheFirstTabShown() throws Exception {
    int lines = Integer.getInteger("qcaret.bench.lines", 2000);
    int repeat = Integer.getInteger("qcaret.bench.repeat", 1);
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
        Sampler sampler = new Sampler(Thread.currentThread());
        for (int round = 0; round < repeat; round++) {
          closeAll(display, window, controller, files, sampler);
        }
        sampler.halt();
        sampler.print(tabs);
      }
    } finally {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    }
  }

  private static void closeAll(
      Display display,
      final MainWindow window,
      final ApplicationController controller,
      List<Path> files,
      Sampler sampler) {
    final int tabs = files.size();
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
    sampler.sampling = true;
    long start = System.nanoTime();
    controller.execute(CommandId.CLOSE_ALL);
    long task = System.nanoTime() - start;
    sampler.sampling = false;
    long longest = 0;
    long before = System.nanoTime();
    while (display.readAndDispatch()) {
      long now = System.nanoTime();
      longest = Math.max(longest, now - before);
      before = now;
    }
    long all = System.nanoTime() - start;
    assertEquals(1, window.tabs().getItemCount());
    System.out.println(
        String.format(
            Locale.ROOT,
            "probe.closeAll n=%d task=%.1fms withQueued=%.1fms longestQueued=%.1fms",
            tabs,
            task / 1e6,
            all / 1e6,
            longest / 1e6));
    controller.documents().closeAll(null);
    drain(display);
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

  /**
   * Samples a thread's stack every millisecond while {@code sampling} is set and counts, per
   * sample, the innermost frame, the innermost QCaret frame with its line, the innermost {@value
   * #CHAIN_FRAMES} QCaret frames and the innermost {@value #LEAF_FRAMES} frames.
   */
  private static final class Sampler extends Thread {
    private static final int CHAIN_FRAMES = 5;
    private static final int LEAF_FRAMES = 8;
    private final Thread target;
    private final Map<String, Integer> own = new HashMap<String, Integer>();
    private final Map<String, Integer> top = new HashMap<String, Integer>();
    private final Map<String, Integer> chain = new HashMap<String, Integer>();
    private final Map<String, Integer> leaf = new HashMap<String, Integer>();
    private volatile boolean running = true;
    volatile boolean sampling;
    private int samples;

    Sampler(Thread target) {
      this.target = target;
      setDaemon(true);
      start();
    }

    @Override
    public void run() {
      while (running) {
        if (sampling) {
          sample(target.getStackTrace());
        }
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    private void sample(StackTraceElement[] stack) {
      if (stack.length == 0 || !sampling) {
        return;
      }
      samples++;
      count(top, stack[0].getClassName() + "." + stack[0].getMethodName());
      StringBuilder calls = new StringBuilder();
      int kept = 0;
      for (StackTraceElement frame : stack) {
        String name = frame.getClassName();
        if (kept < CHAIN_FRAMES
            && name.startsWith("io.github.ajdailey.qcaret.")
            && !name.contains("TabsProbe")) {
          if (kept == 0) {
            count(own, brief(frame) + ":" + frame.getLineNumber());
          }
          calls.insert(0, kept == 0 ? brief(frame) : brief(frame) + " > ");
          kept++;
        }
      }
      if (kept > 0) {
        count(chain, calls.toString());
      }
      StringBuilder inner = new StringBuilder();
      for (int i = Math.min(LEAF_FRAMES, stack.length) - 1; i >= 0; i--) {
        inner.append(brief(stack[i])).append(i == 0 ? "" : " > ");
      }
      count(leaf, inner.toString());
    }

    private static String brief(StackTraceElement frame) {
      String name = frame.getClassName();
      return name.substring(name.lastIndexOf('.') + 1) + "." + frame.getMethodName();
    }

    private static void count(Map<String, Integer> counts, String key) {
      Integer now = counts.get(key);
      counts.put(key, now == null ? 1 : now + 1);
    }

    void halt() throws InterruptedException {
      running = false;
      join();
    }

    void print(int tabs) {
      System.out.println("probe.closeAll.samples n=" + tabs + " samples=" + samples);
      print("own", own, 25);
      print("top", top, 15);
      print("chain", chain, 20);
      print("leaf", leaf, 20);
    }

    private static void print(String kind, Map<String, Integer> counts, int limit) {
      List<Map.Entry<String, Integer>> entries =
          new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
      entries.sort(
          new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
              return Integer.compare(b.getValue(), a.getValue());
            }
          });
      for (int i = 0; i < Math.min(limit, entries.size()); i++) {
        Map.Entry<String, Integer> entry = entries.get(i);
        System.out.println(
            "probe.closeAll." + kind + " " + entry.getValue() + " " + entry.getKey());
      }
    }
  }
}
