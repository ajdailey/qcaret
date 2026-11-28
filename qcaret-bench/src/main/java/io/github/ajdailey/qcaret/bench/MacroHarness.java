package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.MacroBench;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.PlaybackTimer;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Macro playback without a display: each {@link MacroBench.Workload} replayed over a generated
 * document of 256 KiB, 1 MiB and 4 MiB, {@value #COUNT} times and to the end, in a window over a
 * fake view with the bench thread as the UI thread. A {@link PlaybackTimer} times every UI task of
 * the run; the line per run gives the turns, the tasks that ran steps, against the 8 ms budget.
 * Arguments: workload names; none runs all. {@code -Dqcaret.bench.kib} (comma list) replaces the
 * sizes. Runs at the shipped launchers' heap as {@link StorageHarness} does.
 */
public final class MacroHarness {
  /** The counted runs' iterations. */
  static final int COUNT = 1000;

  private static final String KIB = "256,1024,4096";
  private static final int WRAP_COLUMNS = 60;
  private static final Duration BOUND = Duration.ofMinutes(30);

  private MacroHarness() {}

  /**
   * Runs the harness.
   *
   * @param args workload names
   * @throws IOException when the relaunch or a file write fails
   * @throws InterruptedException when interrupted while waiting
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    List<String> options = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (StorageHarness.heapOption(options) == null
        && !"jvm".equals(System.getProperty("qcaret.bench.heap"))) {
      System.exit(StorageHarness.relaunch(MacroHarness.class, options, args));
    }
    System.out.printf(
        "max heap %d MiB, args %s%n", Runtime.getRuntime().maxMemory() / Corpus.MIB, options);
    List<MacroBench.Workload> workloads = new ArrayList<MacroBench.Workload>();
    for (String name : args) {
      workloads.add(MacroBench.Workload.valueOf(name));
    }
    if (workloads.isEmpty()) {
      workloads.addAll(List.of(MacroBench.Workload.values()));
    }
    Path temp = Files.createTempDirectory("qcaret-macro-bench");
    try {
      for (MacroBench.Workload workload : workloads) {
        run(temp, workload, 256, 200, false);
      }
      for (MacroBench.Workload workload : workloads) {
        for (String kib : System.getProperty("qcaret.bench.kib", KIB).split(",")) {
          run(temp, workload, Integer.parseInt(kib.strip()), COUNT, true);
          run(temp, workload, Integer.parseInt(kib.strip()), 0, true);
        }
      }
    } finally {
      delete(temp);
    }
  }

  /** A run's iterations, end notice and task times. */
  record Result(int iterations, String notice, PlaybackTimer.Summary summary) {}

  /**
   * Plays one workload in a new window and prints its line when {@code print}.
   *
   * @return the result
   */
  static Result run(Path temp, MacroBench.Workload workload, int kib, int count, boolean print)
      throws IOException, InterruptedException {
    Path dir = Files.createTempDirectory(temp, "run");
    Path file =
        Files.writeString(
            dir.resolve("bench.java"), MacroBench.text(kib * 1024, 42), StandardCharsets.UTF_8);
    ManualUiDispatcher manual = new ManualUiDispatcher();
    PlaybackTimer timer = new PlaybackTimer(manual);
    final List<Throwable> failures = new ArrayList<Throwable>();
    Workers workers =
        new Workers(
            Runtime.getRuntime().availableProcessors(),
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                synchronized (failures) {
                  failures.add(error);
                }
              }
            });
    final ApplicationController window =
        MacroBench.fakeWindow(workers, timer, manual, dir.resolve("settings"));
    try {
      window.documents().open(List.of(file));
      await(
          manual,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.documents().count() == 1 && MacroBench.settled(window);
            }
          });
      MacroBench.wrap(window, workload.wrap(), WRAP_COLUMNS);
      await(
          manual,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return MacroBench.settled(window);
            }
          });
      manual.runPending();
      timer.start();
      long start = System.nanoTime();
      final MacroBench.Run run = MacroBench.play(window, workload.macro(), count, timer.clock());
      await(manual, run::ended);
      long wall = System.nanoTime() - start;
      timer.stop();
      PlaybackTimer.Summary summary = timer.summary();
      synchronized (failures) {
        if (!failures.isEmpty()) {
          throw new IllegalStateException("worker failed", failures.get(0));
        }
      }
      if (print) {
        System.out.println(
            String.format(
                Locale.ROOT,
                "macro workload=%s kib=%d count=%s iterations=%d wall=%.1fms %s notice=\"%s\"",
                workload,
                kib,
                count == 0 ? "end" : Integer.toString(count),
                run.iterations(),
                wall / 1e6,
                summary,
                run.notice()));
      }
      return new Result(run.iterations(), run.notice(), summary);
    } finally {
      window.documents().dispose();
      manual.runPending();
      workers.close();
      manual.dispose();
    }
  }

  private static void await(ManualUiDispatcher manual, BooleanSupplier condition)
      throws InterruptedException {
    if (!manual.runUntil(condition, BOUND)) {
      throw new IllegalStateException("timed out");
    }
  }

  private static void delete(Path root) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) {
              throw error;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
