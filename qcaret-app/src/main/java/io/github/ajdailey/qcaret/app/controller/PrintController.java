package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.print.PrintLayout;
import io.github.ajdailey.qcaret.app.print.PrintSource;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File > Print and File > Print Selection of one window: one job at a time on a {@code
 * qcaret-print-} thread, shown in the window's print row with Cancel. Print Selection prints the
 * selection whatever scope the dialog returns; a page range counts the selection's pages, the pages
 * its header numbers. The job reads the {@link PrintSource} captured when the command ran, so
 * editing goes on while it prints. Closing the window cancels the job.
 */
final class PrintController {
  private static final Logger LOG = LoggerFactory.getLogger(PrintController.class);

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final AtomicBoolean progressPosted = new AtomicBoolean();
  private CancellationToken running;
  private boolean disposed;

  PrintController(DocumentController documents, Workers workers, UiDispatcher ui, MainView view) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
  }

  /** Asks for a printer and prints the selected document, or its selection. */
  void print() {
    print(false);
  }

  /** Asks for a printer and prints the selection, or shows a notice without one. */
  void printSelection() {
    print(true);
  }

  private void print(final boolean selection) {
    if (running != null) {
      view.showNotice(UiText.PRINT_RUNNING.text());
      return;
    }
    final PrintSource source = documents.printSource();
    if (source == null) {
      return;
    }
    if (selection && source.selections().length == 0) {
      view.showNotice(UiText.PRINT_NO_SELECTION.text());
      return;
    }
    view.choosePrinter(
        source.name(),
        new Consumer<PrintTarget>() {
          @Override
          public void accept(PrintTarget target) {
            begin(source, target, selection || target.selectionOnly());
          }
        });
  }

  private void begin(PrintSource source, PrintTarget target, boolean selection) {
    if (disposed) {
      target.close();
      return;
    }
    if (selection && source.selections().length == 0) {
      target.close();
      view.showNotice(UiText.PRINT_NO_SELECTION.text());
      return;
    }
    CancellationToken cancel = new CancellationToken();
    running = cancel;
    showProgress(UiText.PRINT_PREPARING.format(source.name()), 0, cancel);
    if (!launch(source, target, selection, cancel)) {
      target.close();
      finished(cancel, null);
    }
  }

  private boolean launch(
      final PrintSource source,
      final PrintTarget target,
      final boolean selection,
      final CancellationToken cancel) {
    return workers.tryStart(
        Workers.Kind.PRINT,
        new Runnable() {
          @Override
          public void run() {
            job(source, target, selection, cancel);
          }
        });
  }

  /** Runs a job and posts its outcome in every case; a canceled job shows no notice. */
  @WorkerThread
  private void job(
      PrintSource source, PrintTarget target, boolean selection, CancellationToken cancel) {
    String notice = null;
    try {
      notice = pages(source, target, selection, cancel);
    } catch (CancellationException e) {
      LOG.debug("Print of {} canceled", LogText.oneLine(source.name()));
    } catch (PrintTarget.PrintFailure e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      LOG.warn("Print of {} failed: {}", LogText.oneLine(source.name()), LogText.oneLine(cause));
      notice = UiText.PRINT_FAILED.format(source.name());
    } finally {
      target.close();
      report(cancel, notice);
    }
  }

  private void report(final CancellationToken cancel, final String notice) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            finished(cancel, notice);
          }
        });
  }

  /** Prints the chosen pages and returns the notice for the outcome. */
  @WorkerThread
  private String pages(
      PrintSource source, PrintTarget target, boolean selection, CancellationToken cancel) {
    PrintTarget.Frame frame = target.start(source.name());
    PrintLayout layout = PrintLayout.of(source, selection, frame, cancel);
    int total = layout.pageCount();
    int first = target.firstPage();
    int last = Math.min(target.lastPage(), total);
    if (first > total) {
      return UiText.PRINT_NO_PAGES.format(
          UiText.count(total, UiText.COUNT_PAGE, UiText.COUNT_PAGES));
    }
    for (int page = first; page <= last; page++) {
      target.page(layout.page(page, cancel));
      post(source.name(), page, total, (page - first + 1) * 100 / (last - first + 1), cancel);
    }
    cancel.check();
    target.finish();
    return UiText.PRINT_SENT.format(
        source.name(), UiText.count(last - first + 1, UiText.COUNT_PAGE, UiText.COUNT_PAGES));
  }

  /** Posts an update unless the last one has not shown yet. */
  private void post(
      String name, int page, int total, final int percent, final CancellationToken cancel) {
    if (progressPosted.compareAndSet(false, true)) {
      final String text = UiText.PRINT_PROGRESS.format(name, page, total);
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              progressPosted.set(false);
              if (running == cancel && !disposed) {
                showProgress(text, percent, cancel);
              }
            }
          });
    }
  }

  private void showProgress(String text, int percent, final CancellationToken cancel) {
    view.showPrintProgress(
        text,
        percent,
        new Runnable() {
          @Override
          public void run() {
            cancel.cancel();
          }
        });
  }

  private void finished(CancellationToken cancel, String notice) {
    if (running != cancel) {
      return;
    }
    running = null;
    if (!disposed) {
      view.hidePrintProgress();
      if (notice != null) {
        view.showNotice(notice);
      }
    }
  }

  /** Cancels a running job; the window is closing. */
  void dispose() {
    disposed = true;
    if (running != null) {
      running.cancel();
    }
  }
}
