package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.buffer.TextStorage;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Arrays;
import java.util.Locale;

/**
 * Latency percentiles (W2, W4) and live heap (W1, after W3) for one candidate per run. Arguments:
 * the candidate name, then any of {@code w1heap}, {@code w2}, {@code w3heap}, {@code w4}; none runs
 * all four.
 */
public final class StorageHarness {
  /** W2 keystrokes measured per size. */
  private static final int W2_KEYS = 10_000;

  /** Measurement stops at this bound; the result then covers the keystrokes completed. */
  private static final long W2_BOUND_NANOS = 120_000_000_000L;

  private static final int W4_KEYS = 200;

  private StorageHarness() {}

  /**
   * Runs the harness.
   *
   * @param args the candidate, then the workloads
   */
  public static void main(String[] args) {
    Candidate candidate = Candidate.valueOf(args[0]);
    String[] workloads =
        args.length > 1
            ? Arrays.copyOfRange(args, 1, args.length)
            : new String[] {"w1heap", "w2", "w3heap", "w4"};
    System.out.printf(
        "candidate %s, max heap %d MiB, args %s%n",
        candidate,
        Runtime.getRuntime().maxMemory() / Corpus.MIB,
        ManagementFactory.getRuntimeMXBean().getInputArguments());
    for (String w : workloads) {
      switch (w) {
        case "w1heap" -> w1Heap(candidate);
        case "w2" -> w2(candidate);
        case "w3heap" -> w3Heap(candidate);
        case "w4" -> w4(candidate);
        default -> throw new IllegalArgumentException("unknown workload " + w);
      }
    }
  }

  private static void w1Heap(Candidate candidate) {
    Corpus[] corpora = {Corpus.ASCII, Corpus.MIXED, Corpus.LONGLINE};
    for (Corpus corpus : corpora) {
      int[] sizes = corpus == Corpus.LONGLINE ? new int[] {4} : new int[] {1, 10, 100};
      for (int size : sizes) {
        long base = liveHeap();
        TextStorage storage = candidate.create(corpus.generate(size * Corpus.MIB, Workloads.SEED));
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

  private static void w3Heap(Candidate candidate) {
    for (Corpus corpus : new Corpus[] {Corpus.ASCII, Corpus.MIXED}) {
      long base = liveHeap();
      TextStorage storage = candidate.create(corpus.generate(10 * Corpus.MIB, Workloads.SEED));
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

  private static void w2(Candidate candidate) {
    // JIT warm-up on a separate document.
    typing(candidate, Corpus.ASCII, 1, 3, 20_000_000_000L);
    for (Corpus corpus : new Corpus[] {Corpus.ASCII, Corpus.MIXED}) {
      typing(candidate, corpus, 10, 1, W2_BOUND_NANOS);
      typing(candidate, corpus, 100, 1, W2_BOUND_NANOS);
    }
  }

  private static void typing(Candidate candidate, Corpus corpus, int size, int rounds, long bound) {
    TextStorage storage = candidate.create(corpus.generate(size * Corpus.MIB, Workloads.SEED));
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

  private static void w4(Candidate candidate) {
    for (int round = 0; round < 2; round++) {
      for (Corpus corpus : new Corpus[] {Corpus.ASCII, Corpus.MIXED}) {
        TextStorage storage = candidate.create(corpus.generate(10 * Corpus.MIB, Workloads.SEED));
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
  private static long liveHeap() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    for (int i = 0; i < 3; i++) {
      System.gc();
    }
    return memory.getHeapMemoryUsage().getUsed();
  }
}
