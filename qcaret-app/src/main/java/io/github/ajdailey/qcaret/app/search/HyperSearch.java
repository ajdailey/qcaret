package io.github.ajdailey.qcaret.app.search;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import io.github.ajdailey.qcaret.core.search.TextSearch;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one HyperSearch into a {@link ResultSet}. Open documents are matched from snapshots taken
 * when it starts, one {@code qcaret-cpu-} task each; a folder is searched by {@link FileSearch}
 * from a {@code qcaret-search-} worker. Workers queue each group; the UI thread moves the queue
 * into the set at most every {@value #FLUSH_MILLIS} ms and calls {@link Listener#changed}, and
 * calls {@link Listener#finished} once every part has ended.
 *
 * <p>Bounds: at most {@link Limits#maxResults} matches; the group that crosses the limit is cut and
 * the search stops {@link ResultSet.State#TRUNCATED}. Each group's heap is reserved from the {@link
 * MemoryBudget} before it is queued; a refusal drops the group and stops the search the same way.
 * {@link #cancel} stops it {@link ResultSet.State#CANCELED}, keeping what arrived.
 */
public final class HyperSearch {
  /** Least time between two moves of the queue into the set. */
  public static final int FLUSH_MILLIS = 100;

  /**
   * The search's bounds.
   *
   * @param maxResults the most matches a set holds ({@code search.maxResults})
   * @param maxFileBytes the largest file read ({@code search.maxFileBytes})
   * @param maxLineChars the longest line searched
   * @param fallback the charset encoding detection falls back to
   */
  public record Limits(int maxResults, long maxFileBytes, int maxLineChars, Charset fallback) {
    /** 100,000 matches, 64 MiB files, 1,000,000-char lines, windows-1252. */
    public static final Limits DEFAULTS =
        new Limits(100_000, 64L << 20, 1_000_000, Charset.forName("windows-1252"));

    /** Default limits with a smaller match bound, for tests. */
    static Limits withMaxResults(int max) {
      return new Limits(max, DEFAULTS.maxFileBytes, DEFAULTS.maxLineChars, StandardCharsets.UTF_8);
    }
  }

  /**
   * An open document to search.
   *
   * @param document the document
   * @param path its file, or null when untitled
   * @param name its tab name
   * @param ranges model offset pairs to search in, or null for the whole text
   */
  public record Source(Document document, Path path, String name, int[] ranges) {}

  /**
   * A folder to search.
   *
   * @param root the folder
   * @param recursive true for its whole tree
   * @param filter the masks
   */
  public record Folder(Path root, boolean recursive, FileFilter filter) {}

  /** Receives the set's progress on the UI thread. */
  public interface Listener {
    /** Groups or counts arrived. */
    void changed();

    /** The search ended; the set has its final state. */
    void finished();
  }

  private final ResultSet set;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MemoryBudget budget;
  private final Listener listener;
  private final List<Document> order = new ArrayList<Document>();
  private final CancellationToken token = new CancellationToken();
  private final ConcurrentLinkedQueue<Queued> queue = new ConcurrentLinkedQueue<Queued>();
  private final AtomicBoolean pending = new AtomicBoolean();
  private final AtomicInteger parts = new AtomicInteger();
  private final AtomicInteger room;
  private final AtomicInteger searched = new AtomicInteger();
  private final AtomicInteger total = new AtomicInteger(-1);
  private final AtomicInteger binary = new AtomicInteger();
  private final AtomicInteger tooLarge = new AtomicInteger();
  private final AtomicInteger unreadable = new AtomicInteger();
  private final AtomicLong walked = new AtomicLong();
  private final AtomicBoolean truncated = new AtomicBoolean();
  private final AtomicBoolean noMemory = new AtomicBoolean();
  private volatile boolean stopped;
  private volatile boolean finished;
  private int documents;
  private final Runnable flush =
      new Runnable() {
        @Override
        public void run() {
          flush();
          listener.changed();
        }
      };

  private record Queued(FileResult result, long bytes) {}

  private HyperSearch(
      ResultSet set,
      Workers workers,
      UiDispatcher ui,
      MemoryBudget budget,
      Listener listener,
      int max) {
    this.set = set;
    this.workers = workers;
    this.ui = ui;
    this.budget = budget;
    this.listener = listener;
    this.room = new AtomicInteger(max);
  }

  /**
   * Starts a search. Call on the UI thread; the snapshots are taken here.
   *
   * @param set the set to fill, running
   * @param matcher the compiled query
   * @param sources the open documents to search
   * @param folder the folder to search, or null
   * @param searchedElsewhere absolute normalized paths of open documents, which the folder search
   *     leaves to their snapshots
   * @param limits the bounds
   * @param workers runs the search
   * @param ui receives the results
   * @param budget charged for the results and the reads
   * @param listener called on the UI thread
   * @return the running search
   */
  public static HyperSearch start(
      ResultSet set,
      TextMatcher matcher,
      List<Source> sources,
      Folder folder,
      Set<Path> searchedElsewhere,
      Limits limits,
      Workers workers,
      UiDispatcher ui,
      MemoryBudget budget,
      Listener listener) {
    HyperSearch search = new HyperSearch(set, workers, ui, budget, listener, limits.maxResults());
    search.parts.set(sources.size() + (folder == null ? 0 : 1) + 1);
    search.documents = sources.size();
    for (Source source : sources) {
      search.order.add(source.document());
      search.searchDocument(matcher, source, limits);
    }
    if (folder != null) {
      search.searchFolder(matcher, folder, searchedElsewhere, limits);
    }
    search.partDone();
    return search;
  }

  /**
   * Returns the set it fills.
   *
   * @return the set
   */
  public ResultSet set() {
    return set;
  }

  /** Stops the search; the set keeps what arrived and ends {@link ResultSet.State#CANCELED}. */
  public void cancel() {
    stopped = true;
    token.cancel();
  }

  /**
   * Reports whether the search has ended and the set holds its final state.
   *
   * @return true once finished
   */
  public boolean isFinished() {
    return finished;
  }

  private void searchDocument(final TextMatcher matcher, final Source source, final Limits limits) {
    final TextSnapshot text = source.document().text();
    final long revision = source.document().revision();
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              matchDocument(matcher, source, text, revision, limits);
            }
          });
    } catch (RejectedExecutionException e) {
      partDone();
    }
  }

  @WorkerThread
  private void matchDocument(
      TextMatcher matcher, Source source, TextSnapshot text, long revision, Limits limits) {
    try {
      int limit = left();
      if (limit > 0) {
        TextSearch.Outcome outcome =
            TextSearch.search(matcher, text, source.ranges(), limit, limits.maxLineChars(), token);
        List<TextSearch.Hit> hits = take(outcome);
        if (!hits.isEmpty() || outcome.skippedLines() > 0 || outcome.tooComplexLine() >= 0) {
          queue(
              FileResult.ofDocument(
                  source.document(), revision, source.path(), source.name(), hits, outcome));
        }
      }
    } catch (CancellationException e) {
      // Canceled: the part ends.
    } finally {
      searched.incrementAndGet();
      partDone();
    }
  }

  private void searchFolder(
      final TextMatcher matcher,
      final Folder folder,
      final Set<Path> searchedElsewhere,
      final Limits limits) {
    boolean started =
        workers.tryStart(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                walk(matcher, folder, searchedElsewhere, limits);
              }
            });
    if (!started) {
      partDone();
    }
  }

  @WorkerThread
  private void walk(
      TextMatcher matcher, Folder folder, Set<Path> searchedElsewhere, Limits limits) {
    try {
      FileSearch.run(
          workers,
          folder.root(),
          folder.recursive(),
          searchedElsewhere,
          new FileSearch.Options(
              matcher,
              folder.filter(),
              limits.fallback(),
              limits.maxFileBytes(),
              limits.maxLineChars(),
              budget),
          new FileSearch.Sink() {
            @Override
            public int room() {
              return left();
            }

            @Override
            public void found(FileSearch.FileHits hits) {
              List<TextSearch.Hit> kept = take(hits.outcome());
              queue(
                  FileResult.ofFile(
                      hits.path(),
                      hits.encoding(),
                      hits.stamp(),
                      hits.digest(),
                      kept,
                      hits.outcome()));
            }

            @Override
            public void skipped(Path file, FileSearch.Skip reason) {
              switch (reason) {
                case BINARY -> binary.incrementAndGet();
                case TOO_LARGE -> tooLarge.incrementAndGet();
                case UNREADABLE, NO_MEMORY -> unreadable.incrementAndGet();
              }
            }

            @Override
            public void progress(int done, int all) {
              walked.set(done);
              if (all >= 0) {
                total.set(all);
              }
              schedule();
            }
          },
          token);
    } finally {
      partDone();
    }
  }

  /**
   * Returns the matches still wanted. A source asks before it is searched, so no room left means an
   * unsearched source: the set is truncated and the search stops.
   */
  private int left() {
    int left = room.get();
    if (left <= 0 && !token.isCanceled()) {
      truncated.set(true);
      token.cancel();
    }
    return token.isCanceled() ? 0 : left;
  }

  /** Takes the room the hits need; hits past the limit are cut and stop the search. */
  private List<TextSearch.Hit> take(TextSearch.Outcome outcome) {
    List<TextSearch.Hit> hits = outcome.hits();
    while (true) {
      int left = room.get();
      int taken = Math.min(left, hits.size());
      if (room.compareAndSet(left, left - taken)) {
        if (taken < hits.size() || outcome.limited()) {
          truncated.set(true);
          token.cancel();
        }
        return taken == hits.size() ? hits : hits.subList(0, taken);
      }
    }
  }

  /** Reserves a group's heap and queues it; a refusal stops the search. */
  private void queue(FileResult result) {
    long bytes = result.bytes();
    if (budget != null && !budget.reserve(bytes)) {
      noMemory.set(true);
      truncated.set(true);
      token.cancel();
      return;
    }
    queue.add(new Queued(result, bytes));
    schedule();
  }

  /** Arms the UI flush once per batch of arrivals. */
  private void schedule() {
    if (pending.compareAndSet(false, true)) {
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              if (!finished) {
                ui.timerExec(FLUSH_MILLIS, flush);
              }
            }
          });
    }
  }

  private void partDone() {
    if (parts.decrementAndGet() == 0) {
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              finish();
            }
          });
    }
  }

  /** Moves the queue into the set; on the UI thread. */
  private void flush() {
    pending.set(false);
    Queued next = queue.poll();
    while (next != null) {
      set.add(next.result(), next.bytes(), budget);
      next = queue.poll();
    }
    int all = total.get();
    set.progress(
        searched.get() + (int) walked.get(),
        all < 0 ? -1 : all + documents,
        binary.get(),
        tooLarge.get(),
        unreadable.get(),
        noMemory.get());
  }

  private void finish() {
    if (finished) {
      return;
    }
    ui.cancelTimer(flush);
    flush();
    finished = true;
    ResultSet.State end;
    if (truncated.get()) {
      end = ResultSet.State.TRUNCATED;
    } else if (stopped) {
      end = ResultSet.State.CANCELED;
    } else {
      end = ResultSet.State.DONE;
    }
    set.finish(end, order);
    listener.finished();
  }
}
