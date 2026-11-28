package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.MacroBench;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.PlaybackTimer;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Macro playback in a shown window, per workload in {@code -Dqcaret.bench.macro} (comma list of
 * {@link MacroBench.Workload} names) at {@code -Dqcaret.bench.kib} (256 by default) and {@code
 * -Dqcaret.bench.count} (0, to the end, by default); runs only when the workload property is set.
 * Each workload runs twice after two untimed runs of 300 iterations, once as shipped and once with
 * every {@code StyledText} of the window under {@code setRedraw(false)} ({@code redrawOff}). Prints
 * the {@link PlaybackTimer} summary of the UI tasks, {@code events}: event loop time outside those
 * tasks (paints, layout, input), and the UI thread sampled every millisecond by its innermost SWT
 * or QCaret frame: {@code swt}, {@code view} ({@code ui.swt}), {@code model} (app and core), {@code
 * idle} in {@code Display.sleep}, with the QCaret caller of the {@code swt} samples and, for
 * samples outside the timed tasks ({@code untimed}), the outermost and innermost QCaret frames.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.bench.macro", matches = ".+")
class MacroPlaybackProbe {
  @TempDir Path temp;

  @Test
  void playWorkloads() throws Exception {
    int kib = Integer.getInteger("qcaret.bench.kib", 256);
    int count = Integer.getInteger("qcaret.bench.count", 0);
    for (String name : System.getProperty("qcaret.bench.macro").split(",")) {
      MacroBench.Workload workload = MacroBench.Workload.valueOf(name.strip());
      play(workload, 64, 300, false, false);
      play(workload, 64, 300, true, false);
      play(workload, kib, count, false, true);
      play(workload, kib, count, true, true);
    }
  }

  private void play(
      MacroBench.Workload workload, int kib, int count, boolean redrawOff, boolean print)
      throws Exception {
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
    PlaybackTimer timer = new PlaybackTimer(new SwtUiDispatcher(display));
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    Path dir = Files.createTempDirectory(temp, "run");
    final ApplicationController controller =
        new ApplicationController(
            workers,
            timer,
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, dir.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            MemoryBudget.fromRuntime(),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    controller.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    try {
      window.attach(controller);
      window.place(new WindowGeometry.Request(0, 0, 1100, 700), null);
      window.open();
      Path file =
          Files.writeString(
              dir.resolve("bench.java"), MacroBench.text(kib * 1024, 42), StandardCharsets.UTF_8);
      controller.documents().open(List.of(file));
      BooleanSupplier settled =
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return controller.documents().count() == 1 && MacroBench.settled(controller);
            }
          };
      runUntil(display, settled);
      MacroBench.wrap(controller, workload.wrap(), 0);
      runUntil(display, settled);
      drain(display);
      List<StyledText> texts = new ArrayList<StyledText>();
      collect(window.shell(), texts);
      if (redrawOff) {
        for (StyledText text : texts) {
          text.setRedraw(false);
        }
      }
      Shares shares = new Shares(Thread.currentThread());
      timer.start();
      long start = System.nanoTime();
      shares.sampling = true;
      long loop = 0;
      final MacroBench.Run run =
          MacroBench.play(controller, workload.macro(), count, timer.clock());
      while (!run.ended()) {
        long at = System.nanoTime();
        if (display.readAndDispatch()) {
          loop += System.nanoTime() - at;
        } else {
          display.sleep();
        }
      }
      long wall = System.nanoTime() - start;
      shares.sampling = false;
      shares.halt();
      timer.stop();
      if (redrawOff) {
        for (StyledText text : texts) {
          text.setRedraw(true);
        }
      }
      PlaybackTimer.Summary summary = timer.summary();
      long events = loop - summary.turnSum() - summary.otherSum();
      if (print) {
        System.out.println(
            String.format(
                Locale.ROOT,
                "probe.macro workload=%s kib=%d count=%s redrawOff=%b iterations=%d wall=%.1fms"
                    + " events=%.1fms %s notice=\"%s\"",
                workload,
                kib,
                count == 0 ? "end" : Integer.toString(count),
                redrawOff,
                run.iterations(),
                wall / 1e6,
                events / 1e6,
                summary,
                run.notice()));
        shares.print();
      }
    } finally {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    }
  }

  /** Samples the UI thread every millisecond and sorts each stack by its innermost owner. */
  private static final class Shares extends Thread {
    private final Thread target;
    private final Map<String, Integer> callers = new HashMap<String, Integer>();
    private final Map<String, Integer> untimed = new HashMap<String, Integer>();
    private volatile boolean running = true;
    volatile boolean sampling;
    private int swt;
    private int view;
    private int model;
    private int idle;

    Shares(Thread target) {
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
      int owner = -1;
      boolean timed = false;
      for (int i = 0; i < stack.length; i++) {
        String name = stack[i].getClassName();
        if (name.equals("org.eclipse.swt.widgets.Display")
            && stack[i].getMethodName().equals("sleep")) {
          idle++;
          return;
        }
        timed |= name.contains("PlaybackTimer");
        if (owner < 0
            && (name.startsWith("org.eclipse.swt.") || name.startsWith("io.github.ajdailey."))) {
          owner = i;
        }
      }
      String name = owner < 0 ? "" : stack[owner].getClassName();
      if (name.startsWith("io.github.ajdailey.qcaret.ui.swt.")) {
        view++;
      } else if (!name.startsWith("org.eclipse.swt.")) {
        model++;
      } else {
        swt++;
        String caller = "(event loop)";
        for (int i = owner + 1; i < stack.length; i++) {
          String frame = stack[i].getClassName();
          if (frame.startsWith("io.github.ajdailey.")
              && !frame.contains("MacroPlaybackProbe")
              && !frame.contains("PlaybackTimer")) {
            caller = frame.substring(frame.lastIndexOf('.') + 1) + "." + stack[i].getMethodName();
            break;
          }
        }
        count(callers, caller);
      }
      if (!timed) {
        count(untimed, entry(stack));
      }
    }

    /** Names the outermost QCaret frame under the event loop and the innermost QCaret frame. */
    private static String entry(StackTraceElement[] stack) {
      String inner = null;
      String outer = "(event loop)";
      for (int i = 0; i < stack.length; i++) {
        String frame = stack[i].getClassName();
        if (frame.startsWith("io.github.ajdailey.") && !frame.contains("MacroPlaybackProbe")) {
          String name =
              frame.substring(frame.lastIndexOf('.') + 1) + "." + stack[i].getMethodName();
          if (inner == null) {
            inner = name;
          }
          outer = name;
        }
      }
      if (inner != null) {
        return outer + " > " + inner;
      }
      // No QCaret frame: the innermost frame and the innermost SWT event handler.
      String handler = "-";
      for (int i = 0; i < stack.length; i++) {
        String method = stack[i].getMethodName();
        if (method.startsWith("gtk_") || method.startsWith("handle") || method.startsWith("WM")) {
          handler = stack[i].getClassName() + "." + method;
          break;
        }
      }
      return outer
          + " > "
          + stack[0].getClassName()
          + "."
          + stack[0].getMethodName()
          + " < "
          + handler;
    }

    private static void count(Map<String, Integer> map, String key) {
      Integer now = map.get(key);
      map.put(key, now == null ? 1 : now + 1);
    }

    void halt() throws InterruptedException {
      running = false;
      join();
    }

    void print() {
      System.out.println(
          String.format(
              Locale.ROOT,
              "probe.macro.samples swt=%d view=%d model=%d idle=%d",
              swt,
              view,
              model,
              idle));
      top("probe.macro.swt ", callers, 12);
      top("probe.macro.untimed ", untimed, 20);
    }

    private static void top(String prefix, Map<String, Integer> map, int limit) {
      List<Map.Entry<String, Integer>> entries =
          new ArrayList<Map.Entry<String, Integer>>(map.entrySet());
      entries.sort(
          new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
              return Integer.compare(b.getValue(), a.getValue());
            }
          });
      for (int i = 0; i < Math.min(limit, entries.size()); i++) {
        System.out.println(prefix + entries.get(i).getValue() + " " + entries.get(i).getKey());
      }
    }
  }

  private static void collect(Control control, List<StyledText> texts) {
    if (control instanceof StyledText text) {
      texts.add(text);
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        collect(child, texts);
      }
    }
  }

  private static void runUntil(Display display, BooleanSupplier condition) {
    while (!condition.getAsBoolean()) {
      if (!display.readAndDispatch()) {
        display.sleep();
      }
    }
  }

  private static void drain(Display display) {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }
}
