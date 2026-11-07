package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.buffer.TextStorage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Latency percentiles (W2, W4) and live heap (W1, after W3) for the rope. Arguments: any of {@code
 * w1heap}, {@code w2}, {@code w3heap}, {@code w4}; none runs all four.
 *
 * <p>Runs at the shipped launchers' heap, {@value #LAUNCHER_HEAP}: a JVM started without a heap
 * option relaunches the harness with it and the launchers' {@value #LAUNCHER_GC}. A heap option on
 * the command line runs in place; {@code -Dqcaret.bench.heap=jvm} runs in place at the JVM default.
 */
public final class StorageHarness {
  /** The heap option of {@code qcaret-dist/src/bin/qcaret} and the other launchers. */
  static final String LAUNCHER_HEAP = "-XX:MaxRAMPercentage=50";

  /** The garbage collector option of the same launchers. */
  static final String LAUNCHER_GC = "-XX:GCTimeRatio=1";

  private static final String[] HEAP_OPTIONS = {
    "-Xmx", "-XX:MaxRAMPercentage=", "-XX:MaxRAM=", "-XX:MaxHeapSize="
  };

  /** W2 keystrokes measured per size. */
  private static final int W2_KEYS = 10_000;

  /** Measurement stops at this bound; the result then covers the keystrokes completed. */
  private static final long W2_BOUND_NANOS = 120_000_000_000L;

  private static final int W4_KEYS = 200;

  private StorageHarness() {}

  /**
   * Runs the harness.
   *
   * @param args the workloads
   * @throws IOException when the relaunch cannot start
   * @throws InterruptedException when interrupted waiting for the relaunched JVM
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    List<String> options = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (heapOption(options) == null && !"jvm".equals(System.getProperty("qcaret.bench.heap"))) {
      System.exit(relaunch(StorageHarness.class, options, args));
    }
    String[] workloads = args.length > 0 ? args : new String[] {"w1heap", "w2", "w3heap", "w4"};
    System.out.printf(
        "max heap %d MiB, args %s%n",
        Runtime.getRuntime().maxMemory() / Corpus.MIB,
        ManagementFactory.getRuntimeMXBean().getInputArguments());
    for (String w : workloads) {
      switch (w) {
        case "w1heap" -> w1Heap();
        case "w2" -> w2();
        case "w3heap" -> w3Heap();
        case "w4" -> w4();
        default -> throw new IllegalArgumentException("unknown workload " + w);
      }
    }
  }

  /**
   * Returns the heap option among JVM options.
   *
   * @param options the JVM's input arguments
   * @return the first heap option, or null
   */
  static String heapOption(List<String> options) {
    for (String argument : options) {
      for (String option : HEAP_OPTIONS) {
        if (argument.startsWith(option)) {
          return argument;
        }
      }
    }
    return null;
  }

  /**
   * Runs a harness in a new JVM with {@link #LAUNCHER_HEAP}, {@link #LAUNCHER_GC} and this JVM's
   * options.
   */
  static int relaunch(Class<?> harness, List<String> options, String[] args)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<String>();
    command.add(javaCommand());
    command.add(LAUNCHER_HEAP);
    command.add(LAUNCHER_GC);
    command.addAll(options);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(harness.getName());
    command.addAll(Arrays.asList(args));
    return new ProcessBuilder(command).inheritIO().start().waitFor();
  }

  /**
   * Returns this JVM's {@code java} binary.
   *
   * @return the path
   */
  static String javaCommand() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static void w1Heap() {
    Corpus[] corpora = {Corpus.ASCII, Corpus.MIXED, Corpus.CRLF, Corpus.MIXED_EOL, Corpus.LONGLINE};
    for (Corpus corpus : corpora) {
      int[] sizes = corpus == Corpus.LONGLINE ? new int[] {4} : new int[] {1, 10, 100};
      for (int size : sizes) {
        long base = liveHeap();
        TextStorage storage = RopeStorage.of(corpus.generate(size * Corpus.MIB, Workloads.SEED));
        long used = liveHeap() - base;
        System.out.printf(
            Locale.ROOT,
            "W1 heap %s %d MiB: %.1f MiB live, %.2f bytes/char%n",
            corpus,
            size,
            used / (double) Corpus.MIB,
            used / (double) storage.length());
      }
    }
  }

  private static void w3Heap() {
    for (Corpus corpus : new Corpus[] {Corpus.ASCII, Corpus.MIXED, Corpus.CRLF, Corpus.MIXED_EOL}) {
      long base = liveHeap();
      TextStorage storage = RopeStorage.of(corpus.generate(10 * Corpus.MIB, Workloads.SEED));
      long t0 = System.nanoTime();
      Workloads.apply(storage, Workloads.w3(storage.length()), null);
      long t1 = System.nanoTime();
      long used = liveHeap() - base;
      System.out.printf(
          Locale.ROOT,
          "W3 heap %s 10 MiB: %.1f MiB live after W3 (W3 took %d ms), %d chars%n",
          corpus,
          used / (double) Corpus.MIB,
          (t1 - t0) / 1_000_000,
          storage.length());
    }
  }

  private static void w2() {
    // JIT warm-up on a separate document.
    typing(Corpus.ASCII, 1, 3, 20_000_000_000L);
    for (Corpus corpus : new Corpus[] {Corpus.ASCII, Corpus.MIXED, Corpus.CRLF, Corpus.MIXED_EOL}) {
      typing(corpus, 10, 1, W2_BOUND_NANOS);
      typing(corpus, 100, 1, W2_BOUND_NANOS);
    }
  }

  private static void typing(Corpus corpus, int size, int rounds, long bound) {
    TextStorage storage = RopeStorage.of(corpus.generate(size * Corpus.MIB, Workloads.SEED));
    long[] times = new long[W2_KEYS];
    int done = 0;
    long sink = 0;
    long begin = System.nanoTime();
    for (int round = 0; round < rounds; round++) {
      Workloads.Typing typing = new Workloads.Typing(storage.length());
      done = 0;
      while (done < W2_KEYS && System.nanoTime() - begin < bound) {
        long t0 = System.nanoTime();
        typing.keystroke(storage);
        TextSnapshot snapshot = storage.snapshot();
        times[done++] = System.nanoTime() - t0;
        sink += snapshot.length();
      }
    }
    if (rounds == 1) {
      report("W2 " + corpus + " " + size + " MiB", Arrays.copyOf(times, done), sink);
    }
  }

  private static void w4() {
    for (int round = 0; round < 2; round++) {
      for (Corpus corpus :
          new Corpus[] {Corpus.ASCII, Corpus.MIXED, Corpus.CRLF, Corpus.MIXED_EOL}) {
        TextStorage storage = RopeStorage.of(corpus.generate(10 * Corpus.MIB, Workloads.SEED));
        int[] carets = Workloads.carets(storage.length());
        long[] times = new long[W4_KEYS];
        for (int k = 0; k < W4_KEYS; k++) {
          long t0 = System.nanoTime();
          Workloads.multiCaretKeystroke(storage, carets, k);
          times[k] = System.nanoTime() - t0;
        }
        // Round 0 is JIT warm-up.
        if (round == 1) {
          report("W4 " + corpus + " 10 MiB", times, storage.length());
        }
      }
    }
  }

  private static void report(String name, long[] times, long sink) {
    long[] sorted = times.clone();
    Arrays.sort(sorted);
    System.out.printf(
        Locale.ROOT,
        "%s: n=%d p50=%.1f us p99=%.1f us p99.9=%.1f us max=%.1f us (sink %d)%n",
        name,
        sorted.length,
        pct(sorted, 50),
        pct(sorted, 99),
        pct(sorted, 99.9),
        sorted[sorted.length - 1] / 1000.0,
        sink);
  }

  private static double pct(long[] sorted, double p) {
    int i = (int) Math.ceil(p / 100 * sorted.length) - 1;
    return sorted[Math.max(0, i)] / 1000.0;
  }

  /** Heap used after full collections: the live set. */
  static long liveHeap() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    for (int i = 0; i < 3; i++) {
      System.gc();
    }
    return memory.getHeapMemoryUsage().getUsed();
  }
}
