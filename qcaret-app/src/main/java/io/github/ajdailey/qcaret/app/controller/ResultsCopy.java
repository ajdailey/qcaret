package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.FileResult;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.search.TextSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The results dock's Copy and Copy All Results: one line per match, {@code path:line: snippet},
 * joined with {@link System#lineSeparator()}. The path is the file's, or an untitled document's tab
 * name; the line counts from 1 in the document's current revision; the snippet is the row's.
 *
 * <p>Up to {@value #INLINE_MATCHES} matches build on the UI thread. More build on a {@code
 * qcaret-cpu-} worker under the busy cursor from groups captured on the UI thread: each group's
 * path, its immutable hits and a copy of its lines. The text reserves 2 bytes per char from the
 * {@link MemoryBudget} while it builds; a refusal copies nothing and says so. A newer copy cancels
 * an older one. Confined to the UI thread.
 */
final class ResultsCopy {
  /** Most matches copied on the UI thread. */
  static final int INLINE_MATCHES = 2000;

  /** Chars per line besides path and snippet: separators, line number, line separator. */
  private static final int LINE_CHARS = 16;

  private final List<SearchController.Entry> entries;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private CancellationToken running;

  /** One group's matches as captured on the UI thread. */
  record Group(String path, List<TextSearch.Hit> hits, int[] lines) {}

  ResultsCopy(
      List<SearchController.Entry> entries,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget) {
    this.entries = entries;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
  }

  /**
   * Copies a set's, a group's or one match's lines.
   *
   * @param set the set's index
   * @param file the group's index, or -1 for every group of the set
   * @param match the match's index, or -1 for every match of the group
   */
  void copy(int set, int file, int match) {
    if (set < 0 || set >= entries.size()) {
      return;
    }
    List<FileResult> files = entries.get(set).set.files();
    List<Group> groups = new ArrayList<Group>();
    if (file < 0) {
      for (FileResult f : files) {
        groups.add(capture(f, 0, f.matchCount()));
      }
    } else if (file < files.size()) {
      FileResult f = files.get(file);
      if (match < 0) {
        groups.add(capture(f, 0, f.matchCount()));
      } else if (match < f.matchCount()) {
        groups.add(capture(f, match, match + 1));
      }
    }
    start(groups);
  }

  /** Copies the lines of every set, newest first. */
  void copyAll() {
    List<Group> groups = new ArrayList<Group>();
    for (SearchController.Entry e : entries) {
      for (FileResult f : e.set.files()) {
        groups.add(capture(f, 0, f.matchCount()));
      }
    }
    start(groups);
  }

  /** Maps a group to its document's current revision and captures its matches in a range. */
  private static Group capture(FileResult f, int from, int to) {
    f.rebase();
    int[] lines = new int[to - from];
    for (int i = from; i < to; i++) {
      lines[i - from] = f.line(i);
    }
    String path = f.path() == null ? f.name() : f.path().toString();
    List<TextSearch.Hit> hits = new ArrayList<TextSearch.Hit>(to - from);
    for (int i = from; i < to; i++) {
      hits.add(f.hit(i));
    }
    return new Group(path, hits, lines);
  }

  private void start(final List<Group> groups) {
    if (running != null) {
      running.cancel();
      running = null;
    }
    int matches = 0;
    for (Group g : groups) {
      matches += g.lines().length;
    }
    if (matches == 0) {
      return;
    }
    if (matches <= INLINE_MATCHES) {
      view.setText(text(groups, new CancellationToken()));
      return;
    }
    final long bytes = 2L * chars(groups);
    if (!budget.reserve(bytes)) {
      view.showNotice(UiText.RESULTS_COPY_NO_MEMORY.format(matches));
      return;
    }
    final CancellationToken token = new CancellationToken();
    running = token;
    boolean started =
        new BusyWork(ui, view) {
          private String built;

          @Override
          @WorkerThread
          void work() {
            try {
              built = text(groups, token);
            } catch (CancellationException e) {
              built = null;
            }
          }

          @Override
          void finished(boolean completed) {
            budget.release(bytes);
            if (token == running) {
              running = null;
              if (built != null) {
                view.setText(built);
              }
            }
          }
        }.startCpu(workers);
    if (!started) {
      budget.release(bytes);
      running = null;
    }
  }

  /** Counts the chars of the text, the line separators included. */
  private static long chars(List<Group> groups) {
    long chars = 0;
    for (Group g : groups) {
      for (TextSearch.Hit hit : g.hits()) {
        chars += g.path().length() + hit.snippet().length() + LINE_CHARS;
      }
    }
    return chars;
  }

  /**
   * Builds the text of captured groups; reads nothing but the groups, so it runs on the UI thread
   * for up to {@value #INLINE_MATCHES} matches and on the worker for more.
   *
   * @param groups the groups
   * @param token checked between groups
   * @return one line per match
   */
  static String text(List<Group> groups, CancellationToken token) {
    String separator = System.lineSeparator();
    StringBuilder out = new StringBuilder();
    boolean first = true;
    for (Group g : groups) {
      token.check();
      for (int i = 0; i < g.lines().length; i++) {
        if (!first) {
          out.append(separator);
        }
        first = false;
        out.append(g.path())
            .append(':')
            .append(g.lines()[i] + 1)
            .append(": ")
            .append(g.hits().get(i).snippet());
      }
    }
    return out.toString();
  }
}
