package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.search.HyperSearch;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Latencies and retained memory of a search of a {@link SearchTree}, which JMH's time per operation
 * does not separate. Arguments: any of {@code first}, {@code cancel}, {@code heap}; none runs all
 * three. Runs at the shipped launchers' heap as {@link StorageHarness} does.
 *
 * <ul>
 *   <li>{@code first}: from the start of a search to its first result, then canceled.
 *   <li>{@code cancel}: from a cancel {@value #CANCEL_AFTER_MILLIS} ms into a search to its end, on
 *       the whole tree and on the large files alone.
 *   <li>{@code heap}: live heap a finished {@link ResultSet} holds against its charge.
 * </ul>
 */
public final class SearchHarness {
  private static final int ROUNDS = 30;

  /** Rounds dropped first, for the JIT. */
  private static final int WARM_ROUNDS = 10;

  private static final long CANCEL_AFTER_MILLIS = 50;
  private static final Duration BOUND = Duration.ofMinutes(5);

  private SearchHarness() {}

  /** A sink with unlimited room that ignores what it is given. */
  abstract static class CountingSink implements FileSearch.Sink {
    @Override
    public int room() {
      return Integer.MAX_VALUE;
    }

    @Override
    public void skipped(Path file, FileSearch.Skip reason) {
      // Not counted.
    }

    @Override
    public void progress(int searched, int total) {
      // Not counted.
    }
  }

  /**
   * Runs the harness.
   *
   * @param args the workloads
   * @throws IOException when the tree cannot be written or the relaunch cannot start
   * @throws InterruptedException when interrupted while waiting
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    List<String> options = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (StorageHarness.heapOption(options) == null
        && !"jvm".equals(System.getProperty("qcaret.bench.heap"))) {
      System.exit(StorageHarness.relaunch(SearchHarness.class, options, args));
    }
    String[] workloads = args.length > 0 ? args : new String[] {"first", "cancel", "heap"};
    System.out.printf(
        "max heap %d MiB, args %s%n", Runtime.getRuntime().maxMemory() / Corpus.MIB, options);
    try (SearchTree tree =
            SearchTree.create(
                SearchTree.SMALL_FILES, SearchTree.LARGE_FILES, SearchTree.LARGE_CHARS);
        Workers workers = workers()) {
      for (String w : workloads) {
        switch (w) {
          case "first" -> first(tree, workers);
          case "cancel" -> cancel(tree, workers);
          case "heap" -> heap(tree, workers);
          default -> throw new IllegalArgumentException("unknown workload " + w);
        }
      }
    }
  }

  /** Returns workers with a CPU thread per processor, as the application creates. */
  static Workers workers() {
    return new Workers(
        Runtime.getRuntime().availableProcessors(),
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread thread, Throwable error) {
            error.printStackTrace();
          }
        });
  }

  /** Starts a HyperSearch of a folder at the default limits. */
  static HyperSearch start(
      ResultSet set,
      Path folder,
      Workers workers,
      ManualUiDispatcher ui,
      MemoryBudget budget,
      HyperSearch.Listener listener) {
    return HyperSearch.start(
        set,
        set.request().query().compile(),
        List.<HyperSearch.Source>of(),
        new HyperSearch.Folder(folder, true, SearchTree.filter()),
        Set.<Path>of(),
        HyperSearch.Limits.DEFAULTS,
        workers,
        ui,
        budget,
        listener == null ? IGNORED : listener);
  }

  private static final HyperSearch.Listener IGNORED =
      new HyperSearch.Listener() {
        @Override
        public void changed() {
          // Nothing shows the set.
        }

        @Override
        public void finished() {
          // The caller polls isFinished.
        }
      };

  private static void first(SearchTree tree, Workers workers) throws InterruptedException {
    MemoryBudget budget = MemoryBudget.fromRuntime();
    for (SearchTree.Query query : SearchTree.Query.values()) {
      long[] direct = new long[ROUNDS];
      long[] hyper = new long[ROUNDS];
      for (int i = 0; i < ROUNDS; i++) {
        direct[i] = firstDirect(tree, query, workers, budget);
        hyper[i] = firstHyper(tree, query, workers, budget);
      }
      report("first result FileSearch " + query, direct);
      report("first result HyperSearch " + query, hyper);
    }
  }

  /** Runs {@link FileSearch#run} on a search worker; the returned latch counts its end. */
  private static CountDownLatch runDirect(
      final Path root,
      final SearchTree tree,
      final SearchTree.Query query,
      final Workers workers,
      final MemoryBudget budget,
      final FileSearch.Sink sink,
      final CancellationToken token) {
    final CountDownLatch ended = new CountDownLatch(1);
    workers.start(
        Workers.Kind.SEARCH,
        new Runnable() {
          @Override
          public void run() {
            try {
              FileSearch.run(
                  workers, root, true, Set.<Path>of(), tree.options(query, budget), sink, token);
            } finally {
              ended.countDown();
            }
          }
        });
    return ended;
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException("search did not end");
    }
  }

  private static long firstDirect(
      SearchTree tree, SearchTree.Query query, Workers workers, MemoryBudget budget)
      throws InterruptedException {
    final AtomicLong first = new AtomicLong();
    final CancellationToken token = new CancellationToken();
    long start = System.nanoTime();
    await(
        runDirect(
            tree.root(),
            tree,
            query,
            workers,
            budget,
            new CountingSink() {
              @Override
              public void found(FileSearch.FileHits hits) {
                first.compareAndSet(0, System.nanoTime());
                token.cancel();
              }
            },
            token));
    return first.get() - start;
  }

  private static long firstHyper(
      SearchTree tree, SearchTree.Query query, Workers workers, MemoryBudget budget)
      throws InterruptedException {
    ManualUiDispatcher ui = new ManualUiDispatcher();
    final ResultSet set = new ResultSet(1, query.request(tree.root()), 0);
    long start = System.nanoTime();
    HyperSearch search = start(set, tree.root(), workers, ui, budget, null);
    boolean arrived =
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !set.files().isEmpty();
              }
            },
            BOUND);
    long first = System.nanoTime() - start;
    search.cancel();
    if (!arrived || !ui.runUntil(search::isFinished, BOUND)) {
      throw new IllegalStateException("search did not finish");
    }
    set.release();
    return first;
  }

  private static void cancel(SearchTree tree, Workers workers) throws InterruptedException {
    MemoryBudget budget = MemoryBudget.fromRuntime();
    for (SearchTree.Query query :
        new SearchTree.Query[] {SearchTree.Query.LITERAL_FEW, SearchTree.Query.REGEX_MANY}) {
      for (Path root : new Path[] {tree.root(), tree.large()}) {
        String name = query + (root.equals(tree.root()) ? " tree" : " large files");
        long[] direct = new long[ROUNDS];
        long[] hyper = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
          direct[i] = cancelDirect(root, tree, query, workers, budget);
          hyper[i] = cancelHyper(root, query, workers, budget);
        }
        report("cancel FileSearch " + name, direct);
        report("cancel HyperSearch " + name, hyper);
      }
    }
  }

  private static long cancelDirect(
      Path root, SearchTree tree, SearchTree.Query query, Workers workers, MemoryBudget budget)
      throws InterruptedException {
    CancellationToken token = new CancellationToken();
    CountDownLatch ended =
        runDirect(
            root,
            tree,
            query,
            workers,
            budget,
            new CountingSink() {
              @Override
              public void found(FileSearch.FileHits hits) {
                // Dropped.
              }
            },
            token);
    Thread.sleep(CANCEL_AFTER_MILLIS);
    long start = System.nanoTime();
    token.cancel();
    await(ended);
    return System.nanoTime() - start;
  }

  private static long cancelHyper(
      Path root, SearchTree.Query query, Workers workers, MemoryBudget budget)
      throws InterruptedException {
    ManualUiDispatcher ui = new ManualUiDispatcher();
    ResultSet set = new ResultSet(1, query.request(root), 0);
    HyperSearch search = start(set, root, workers, ui, budget, null);
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CANCEL_AFTER_MILLIS);
    while (System.nanoTime() - deadline < 0) {
      ui.runPending();
      Thread.sleep(1);
    }
    long start = System.nanoTime();
    search.cancel();
    if (!ui.runUntil(search::isFinished, BOUND)) {
      throw new IllegalStateException("search did not finish");
    }
    long latency = System.nanoTime() - start;
    set.release();
    return latency;
  }

  private static void heap(SearchTree tree, Workers workers) throws InterruptedException {
    for (SearchTree.Query query : SearchTree.Query.values()) {
      MemoryBudget budget = MemoryBudget.fromRuntime();
      ManualUiDispatcher ui = new ManualUiDispatcher();
      long base = StorageHarness.liveHeap();
      ResultSet set = new ResultSet(1, query.request(tree.root()), 0);
      HyperSearch search = start(set, tree.root(), workers, ui, budget, null);
      if (!ui.runUntil(search::isFinished, BOUND)) {
        throw new IllegalStateException("search did not finish");
      }
      long live = StorageHarness.liveHeap() - base;
      System.out.printf(
          Locale.ROOT,
          "heap %s: %s, %d matches in %d groups, %.1f MiB live, %.1f MiB charged, %.2f live/charge%n",
          query,
          set.state(),
          set.matches(),
          set.files().size(),
          live / (double) Corpus.MIB,
          set.reserved() / (double) Corpus.MIB,
          live / (double) Math.max(1, set.reserved()));
      set.release();
    }
  }

  private static void report(String name, long[] times) {
    long[] sorted = Arrays.copyOfRange(times, WARM_ROUNDS, times.length);
    Arrays.sort(sorted);
    System.out.printf(
        Locale.ROOT,
        "%s: n=%d p50=%.2f ms max=%.2f ms%n",
        name,
        sorted.length,
        sorted[sorted.length / 2] / 1e6,
        sorted[sorted.length - 1] / 1e6);
  }
}
