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
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Searches one group's source again with its set's query and puts the new group in the old one's
 * place: an open document from a snapshot on a {@code qcaret-cpu-} worker, a file from its bytes on
 * a {@code qcaret-io-} worker. The group may take the matches the set's limit leaves free. A source
 * without matches leaves the set; a file that cannot be read keeps its group.
 */
public final class FileRerun {
  private FileRerun() {}

  /** Receives the outcome on the UI thread. */
  public interface Listener {
    /**
     * The search ended.
     *
     * @param matches the group's new match count, or -1 when the file was skipped or the set had
     *     moved on; 0 means the group left the set
     * @param skip why the file was not searched, or null
     */
    void finished(int matches, FileSearch.Skip skip);
  }

  /**
   * Starts the search. Call on the UI thread; a document's snapshot is taken here.
   *
   * @param set the set holding the group, finished
   * @param group the group
   * @param matcher the set's compiled query
   * @param document the open document to search, or null to read {@code path}
   * @param path the group's file, or null for an untitled document
   * @param name the name the new group shows
   * @param limits the search bounds
   * @param workers runs the search
   * @param ui receives the result
   * @param budget charged for the new group and the read
   * @param listener called on the UI thread
   */
  public static void start(
      final ResultSet set,
      final FileResult group,
      final TextMatcher matcher,
      final Document document,
      final Path path,
      final String name,
      final HyperSearch.Limits limits,
      Workers workers,
      final UiDispatcher ui,
      final MemoryBudget budget,
      final Listener listener) {
    final int room = Math.max(0, limits.maxResults() - (set.matches() - group.matchCount()));
    final CancellationToken token = new CancellationToken();
    if (document != null) {
      searchDocument(
          set, group, matcher, document, path, name, room, limits, workers, ui, budget, listener,
          token);
    } else {
      searchFile(set, group, matcher, path, room, limits, workers, ui, budget, listener, token);
    }
  }

  private static void searchDocument(
      final ResultSet set,
      final FileResult group,
      final TextMatcher matcher,
      final Document document,
      final Path path,
      final String name,
      final int room,
      final HyperSearch.Limits limits,
      Workers workers,
      final UiDispatcher ui,
      final MemoryBudget budget,
      final Listener listener,
      final CancellationToken token) {
    final TextSnapshot text = document.text();
    final long revision = document.revision();
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              FileResult next = null;
              try {
                TextSearch.Outcome outcome =
                    TextSearch.search(matcher, text, null, room, limits.maxLineChars(), token);
                next =
                    FileResult.ofDocument(document, revision, path, name, outcome.hits(), outcome);
              } finally {
                post(ui, set, group, next, null, budget, listener);
              }
            }
          });
    } catch (RejectedExecutionException e) {
      listener.finished(-1, null);
    }
  }

  private static void searchFile(
      final ResultSet set,
      final FileResult group,
      final TextMatcher matcher,
      final Path path,
      final int room,
      final HyperSearch.Limits limits,
      Workers workers,
      final UiDispatcher ui,
      final MemoryBudget budget,
      final Listener listener,
      final CancellationToken token) {
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                read(set, group, matcher, path, limits, room, ui, budget, listener, token);
              }
            });
    if (!started) {
      listener.finished(-1, null);
    }
  }

  @WorkerThread
  private static void read(
      ResultSet set,
      FileResult group,
      TextMatcher matcher,
      Path path,
      HyperSearch.Limits limits,
      int room,
      UiDispatcher ui,
      MemoryBudget budget,
      Listener listener,
      CancellationToken token) {
    FileResult next = null;
    FileSearch.Skip skip = null;
    try {
      FileSearch.Single single =
          FileSearch.searchFile(
              path,
              new FileSearch.Options(
                  matcher,
                  FileFilter.of("", "", "", true),
                  limits.fallback(),
                  limits.maxFileBytes(),
                  limits.maxLineChars(),
                  budget),
              room,
              token);
      skip = single.skip();
      if (single.hits() != null) {
        FileSearch.FileHits hits = single.hits();
        next =
            FileResult.ofFile(
                path,
                hits.encoding(),
                hits.stamp(),
                hits.digest(),
                hits.outcome().hits(),
                hits.outcome());
      }
    } catch (CancellationException e) {
      skip = FileSearch.Skip.UNREADABLE;
    } finally {
      post(ui, set, group, next, skip, budget, listener);
    }
  }

  /** Reserves the new group's heap and swaps it in on the UI thread. */
  private static void post(
      UiDispatcher ui,
      final ResultSet set,
      final FileResult group,
      final FileResult next,
      final FileSearch.Skip skip,
      final MemoryBudget budget,
      final Listener listener) {
    final boolean empty = next == null || next.matchCount() == 0 && next.skippedLines() == 0;
    final long bytes = next == null || empty ? 0 : next.bytes();
    final boolean kept = bytes == 0 || budget == null || budget.reserve(bytes);
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (next == null || !kept) {
              listener.finished(-1, kept ? skip : FileSearch.Skip.NO_MEMORY);
              return;
            }
            boolean replaced = set.replace(group, empty ? null : next, bytes, budget);
            if (!replaced && bytes > 0 && budget != null) {
              budget.release(bytes);
            }
            listener.finished(replaced ? next.matchCount() : -1, null);
          }
        });
  }
}
