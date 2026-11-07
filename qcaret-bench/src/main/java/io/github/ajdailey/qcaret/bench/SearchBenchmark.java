package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.search.HyperSearch;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Time to complete a search of a {@link SearchTree}, page cache warm, one run per iteration: {@link
 * FileSearch} into a sink that counts, and {@link HyperSearch} into a {@link ResultSet} at the
 * default limits, which end a many-hits search at 100,000 matches. The UI thread is the benchmark
 * thread on a {@link ManualUiDispatcher}, which runs the 100 ms flush timer without its delay.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5)
@Measurement(iterations = 10)
public class SearchBenchmark {
  /** The tree and the workers. */
  @State(Scope.Benchmark)
  public static class Tree {
    /** What is searched for. */
    @Param({"LITERAL_FEW", "LITERAL_MANY", "REGEX_FEW", "REGEX_MANY"})
    public SearchTree.Query query;

    SearchTree tree;
    Workers workers;
    MemoryBudget budget;

    /**
     * Generates the tree.
     *
     * @throws IOException when a write fails
     */
    @Setup
    public void open() throws IOException {
      tree =
          SearchTree.create(SearchTree.SMALL_FILES, SearchTree.LARGE_FILES, SearchTree.LARGE_CHARS);
      workers = SearchHarness.workers();
      budget = MemoryBudget.fromRuntime();
    }

    /**
     * Stops the workers and deletes the tree.
     *
     * @throws IOException when a delete fails
     */
    @TearDown
    public void close() throws IOException {
      workers.close();
      tree.close();
    }
  }

  /**
   * {@link FileSearch#run} to its end.
   *
   * @param s the tree
   * @return the matches
   */
  @Benchmark
  public long fileSearch(Tree s) {
    final AtomicLong matches = new AtomicLong();
    FileSearch.run(
        s.workers,
        s.tree.root(),
        true,
        Set.<Path>of(),
        s.tree.options(s.query, s.budget),
        new SearchHarness.CountingSink() {
          @Override
          public void found(FileSearch.FileHits hits) {
            matches.addAndGet(hits.outcome().hits().size());
          }
        },
        new CancellationToken());
    return matches.get();
  }

  /**
   * {@link HyperSearch} until its set is finished.
   *
   * @param s the tree
   * @return the matches
   * @throws InterruptedException when interrupted while waiting
   */
  @Benchmark
  public int hyperSearch(Tree s) throws InterruptedException {
    ManualUiDispatcher ui = new ManualUiDispatcher();
    ResultSet set = new ResultSet(1, s.query.request(s.tree.root()), 0);
    HyperSearch search = SearchHarness.start(set, s.tree.root(), s.workers, ui, s.budget, null);
    if (!ui.runUntil(search::isFinished, Duration.ofMinutes(5))) {
      throw new IllegalStateException("search did not finish");
    }
    int matches = set.matches();
    set.release();
    return matches;
  }
}
