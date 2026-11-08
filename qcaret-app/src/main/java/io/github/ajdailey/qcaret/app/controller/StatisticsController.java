package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.TextStatistics;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.StatisticsDisplay;
import io.github.ajdailey.qcaret.app.view.StatisticsInput;
import io.github.ajdailey.qcaret.app.view.StatisticsReport;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * Edit > Document Statistics: counts the selected document and its non-empty selections ({@link
 * TextStatistics}) on a {@code qcaret-cpu-} worker from a text snapshot, while the dialog shows the
 * percent read. Refresh counts again, canceling a running count; closing the dialog cancels it. A
 * result shows only while the document is still at the snapshot's revision; otherwise the dialog
 * says the document changed. Confined to the UI thread.
 */
final class StatisticsController implements StatisticsInput {
  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private StatisticsDisplay display;
  private CancellationToken running;

  /** What a count reads, taken on the UI thread. */
  private record Request(
      String name, Document document, TextSnapshot text, long revision, int[] ranges) {}

  StatisticsController(
      DocumentController documents, Workers workers, UiDispatcher ui, MainView view) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
  }

  /** Opens the dialog, or brings it to the front, and counts. */
  void open() {
    if (display == null) {
      display = view.showStatistics(this);
    } else {
      display.toFront();
    }
    refresh();
  }

  @Override
  public void refresh() {
    if (display == null) {
      return;
    }
    cancel();
    EditorController editor = documents.activeEditor();
    if (editor == null) {
      display.showMessage(UiText.STATISTICS_NO_DOCUMENT.text());
      return;
    }
    Document document = editor.document();
    List<Selection> list = editor.selections().selections().selections();
    int[] ranges = new int[2 * list.size()];
    int n = 0;
    for (Selection s : list) {
      if (!s.isEmpty()) {
        ranges[n++] = s.start();
        ranges[n++] = s.end();
      }
    }
    final Request request =
        new Request(
            documents.nameOf(document),
            document,
            document.text(),
            document.revision(),
            Arrays.copyOf(ranges, n));
    final CancellationToken token = new CancellationToken();
    running = token;
    display.showProgress(0);
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              StatisticsReport report = null;
              try {
                report = count(request, token, ui, StatisticsController.this);
              } catch (CancellationException e) {
                report = null;
              } finally {
                final StatisticsReport result = report;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        counted(token, request, result);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  @Override
  public void closed() {
    cancel();
    display = null;
  }

  private void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  private void progress(CancellationToken token, int percent) {
    if (token == running && display != null) {
      display.showProgress(percent);
    }
  }

  private void counted(CancellationToken token, Request request, StatisticsReport report) {
    if (token != running || display == null) {
      return;
    }
    running = null;
    if (report == null) {
      return;
    }
    if (request.document().revision() != request.revision()) {
      display.showMessage(UiText.STATISTICS_CHANGED.text());
      return;
    }
    display.showReport(report);
  }

  /**
   * Counts a request's text and ranges, posting each new whole percent read.
   *
   * @param request the snapshot and ranges
   * @param token checked before each chunk
   * @param ui receives the progress
   * @param owner shows the progress
   * @return the report
   */
  @WorkerThread
  private static StatisticsReport count(
      Request request,
      final CancellationToken token,
      final UiDispatcher ui,
      final StatisticsController owner) {
    TextSnapshot text = request.text();
    int[] ranges = request.ranges();
    long total = text.length();
    for (int i = 0; i < ranges.length; i += 2) {
      total += ranges[i + 1] - ranges[i];
    }
    final long all = Math.max(1, total);
    final AtomicInteger posted = new AtomicInteger();
    final long[] base = {0};
    LongConsumer progress =
        new LongConsumer() {
          @Override
          public void accept(long read) {
            final int percent = (int) ((base[0] + read) * 100 / all);
            int last = posted.get();
            if (percent > last && posted.compareAndSet(last, percent)) {
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      owner.progress(token, percent);
                    }
                  });
            }
          }
        };
    TextEncoding encoding = request.document().encoding();
    TextStatistics whole =
        TextStatistics.count(text, 0, text.length(), encoding.charset(), token, progress)
            .withExtraBytes(encoding.bomBytes().length);
    base[0] = text.length();
    TextStatistics selected = ranges.length == 0 ? null : TextStatistics.NONE;
    for (int i = 0; i < ranges.length; i += 2) {
      selected =
          selected.plus(
              TextStatistics.count(
                  text, ranges[i], ranges[i + 1], encoding.charset(), token, progress));
      base[0] += ranges[i + 1] - ranges[i];
    }
    return new StatisticsReport(request.name(), encoding.charset().name(), whole, selected);
  }
}
