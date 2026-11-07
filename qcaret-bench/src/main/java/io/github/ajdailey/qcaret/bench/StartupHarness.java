package io.github.ajdailey.qcaret.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches QCaret and times process start to the first paint of file text. Arguments: {@code TARGET
 * FILE SETTINGS [RUNS]}, 6 runs by default.
 */
public final class StartupHarness {
  /** The DEBUG line {@code FirstTextPaint} logs on the first paint that shows text. */
  private static final String FIRST_TEXT_PAINT = "First paint of file text";

  private static final long BOUND_SECONDS = 120;
  private static final int DEFAULT_RUNS = 6;

  private StartupHarness() {}

  /**
   * Runs the harness.
   *
   * @param args the arguments
   * @throws IOException when a launch fails or shows no text within the bound
   * @throws InterruptedException when interrupted
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length < 3 || args.length > 4) {
      System.err.println("usage: StartupHarness TARGET FILE SETTINGS [RUNS]");
      System.exit(2);
    }
    int runs = args.length == 4 ? Integer.parseInt(args[3]) : DEFAULT_RUNS;
    startup(args[0], Path.of(args[1]), Path.of(args[2]), runs);
  }

  /**
   * Launches QCaret {@code runs} times, one at a time, and times each from just before the process
   * starts to the {@link #FIRST_TEXT_PAINT} line in its output, then requests termination. The
   * first run is reported apart: it also fills the settings directory and, for {@code qcaret.jar},
   * extracts the SWT natives.
   *
   * @param target {@code qcaret.jar}, run with this JVM's {@code java}, {@link
   *     StorageHarness#LAUNCHER_HEAP} and {@link StorageHarness#LAUNCHER_GC}; anything else is an
   *     executable launcher such as a bundle's {@code bin/qcaret}
   * @param file the file opened
   * @param settings the {@code --settings} directory, created when missing; holds each run's output
   * @param runs launches, at least 1
   */
  private static void startup(String target, Path file, Path settings, int runs)
      throws IOException, InterruptedException {
    Files.createDirectories(settings);
    System.out.println("startup.target=" + target);
    System.out.println("startup.file=" + file);
    long[] millis = new long[Math.max(1, runs)];
    for (int i = 0; i < millis.length; i++) {
      List<String> command = new ArrayList<String>();
      if (target.endsWith(".jar")) {
        command.add(StorageHarness.javaCommand());
        command.add(StorageHarness.LAUNCHER_HEAP);
        command.add(StorageHarness.LAUNCHER_GC);
        command.add("-jar");
      }
      command.add(target);
      command.addAll(
          Arrays.asList("--settings", settings.toString(), "-m", "-d", "debug", file.toString()));
      Path output = settings.resolve("startup-" + i + ".out");
      Files.deleteIfExists(output);
      ProcessBuilder builder =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
      long start = System.nanoTime();
      Process process = builder.start();
      try {
        millis[i] = awaitFirstPaint(process, output, start);
      } finally {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly().waitFor();
        }
      }
      System.out.println("startup.run" + i + ".ms=" + millis[i] + " exit=" + process.exitValue());
    }
    System.out.println("startup.first.ms=" + millis[0]);
    if (millis.length > 1) {
      long[] later = Arrays.copyOfRange(millis, 1, millis.length);
      Arrays.sort(later);
      System.out.println("startup.later.runs=" + later.length);
      System.out.println("startup.later.p50.ms=" + later[later.length / 2]);
      System.out.println("startup.later.min.ms=" + later[0]);
      System.out.println("startup.later.max.ms=" + later[later.length - 1]);
    }
  }

  /** Polls the output file every millisecond for {@link #FIRST_TEXT_PAINT}; returns ms. */
  private static long awaitFirstPaint(Process process, Path output, long start)
      throws IOException, InterruptedException {
    long deadline = start + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
    long read = 0;
    String text = "";
    while (System.nanoTime() < deadline) {
      long size = Files.exists(output) ? Files.size(output) : 0;
      if (size > read) {
        byte[] bytes = Files.readAllBytes(output);
        long now = System.nanoTime();
        text = new String(bytes, StandardCharsets.UTF_8);
        read = bytes.length;
        if (text.contains(FIRST_TEXT_PAINT)) {
          return TimeUnit.NANOSECONDS.toMillis(now - start);
        }
      }
      if (!process.isAlive()) {
        throw new IOException("QCaret exited with " + process.exitValue() + ":\n" + text);
      }
      TimeUnit.MILLISECONDS.sleep(1);
    }
    throw new IOException("No first paint within " + BOUND_SECONDS + " s:\n" + text);
  }
}
