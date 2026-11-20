package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.print.MarkdownPrintSource;
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
 * File > Print, Print Selection and Print Rendered Markdown of one window: one job at a time on a
 * {@code qcaret-print-} thread, shown in the window's print row with Cancel. Print Selection prints
 * the selection whatever scope the dialog returns; a page range counts the selection's pages, the
 * pages its header numbers. Print Rendered Markdown prints the whole rendering whatever the scope.
 * The job reads the {@link PrintSource} or {@link MarkdownPrintSource} captured when the command
 * ran, so editing goes on while it prints. Closing the window cancels the job.
 */
final class PrintController {
  private static final Logger LOG = LoggerFactory.getLogger(PrintController.class);

  /** A job's laid-out pages. */
  interface Pages {
    /**
     * Returns the page count.
     *
     * @return at least 1
     */
    int count();

    /**
     * Prints a page; pages print in ascending order.
     *
     * @param number from 1 to {@link #count}
     * @param cancel checked while the page builds
     */
    @WorkerThread
    void print(int number, CancellationToken cancel);
  }

  /** Starts a job on its target and lays out its pages. */
  private interface Layout {
    @WorkerThread
    Pages start(PrintTarget target, CancellationToken cancel);
  }

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
            boolean selected = selection || target.selectionOnly();
            if (!disposed && selected && source.selections().length == 0) {
              target.close();
              view.showNotice(UiText.PRINT_NO_SELECTION.text());
              return;
            }
            begin(source.name(), target, sourceLayout(source, selected));
          }
        });
  }

  /** Asks for a printer and prints the selected Markdown tab's rendering, the whole document. */
  void printMarkdown() {
    if (running != null) {
      view.showNotice(UiText.PRINT_RUNNING.text());
      return;
    }
    final MarkdownPrintSource source = documents.markdownPrintSource();
    if (source == null) {
      return;
    }
    view.choosePrinter(
        source.name(),
        new Consumer<PrintTarget>() {
          @Override
          public void accept(PrintTarget target) {
            begin(
                source.name(),
                target,
                new Layout() {
                  @Override
                  @WorkerThread
                  public Pages start(PrintTarget on, CancellationToken cancel) {
                    return MarkdownPrintJob.start(source, on, cancel);
                  }
                });
          }
        });
  }

  private static Layout sourceLayout(final PrintSource source, final boolean selection) {
    return new Layout() {
      @Override
      @WorkerThread
      public Pages start(final PrintTarget target, CancellationToken cancel) {
        final PrintLayout layout =
            PrintLayout.of(source, selection, target.start(source.name()), cancel);
        return new Pages() {
          @Override
          public int count() {
            return layout.pageCount();
          }

          @Override
          @WorkerThread
          public void print(int number, CancellationToken token) {
            target.page(layout.page(number, token));
          }
        };
      }
    };
  }

  private void begin(String name, PrintTarget target, Layout layout) {
    if (disposed) {
      target.close();
      return;
    }
    CancellationToken cancel = new CancellationToken();
    running = cancel;
    showProgress(UiText.PRINT_PREPARING.format(name), 0, cancel);
    if (!launch(name, target, layout, cancel)) {
      target.close();
      finished(cancel, null);
    }
  }

  private boolean launch(
      final String name,
      final PrintTarget target,
      final Layout layout,
      final CancellationToken cancel) {
    return workers.tryStart(
        Workers.Kind.PRINT,
        new Runnable() {
          @Override
          public void run() {
            job(name, target, layout, cancel);
          }
        });
  }

  /** Runs a job and posts its outcome in every case; a canceled job shows no notice. */
  @WorkerThread
  private void job(String name, PrintTarget target, Layout layout, CancellationToken cancel) {
    String notice = null;
    try {
      notice = pages(name, target, layout, cancel);
    } catch (CancellationException e) {
      LOG.debug("Print of {} canceled", LogText.oneLine(name));
    } catch (PrintTarget.PrintFailure e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      LOG.warn("Print of {} failed: {}", LogText.oneLine(name), LogText.oneLine(cause));
      notice = UiText.PRINT_FAILED.format(name);
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
  private String pages(String name, PrintTarget target, Layout layout, CancellationToken cancel) {
    Pages pages = layout.start(target, cancel);
    int total = pages.count();
    int first = target.firstPage();
    int last = Math.min(target.lastPage(), total);
    if (first > total) {
      return UiText.PRINT_NO_PAGES.format(
          UiText.count(total, UiText.COUNT_PAGE, UiText.COUNT_PAGES));
    }
    for (int page = first; page <= last; page++) {
      pages.print(page, cancel);
      post(name, page, total, (page - first + 1) * 100 / (last - first + 1), cancel);
    }
    cancel.check();
    target.finish();
    return UiText.PRINT_SENT.format(
        name, UiText.count(last - first + 1, UiText.COUNT_PAGE, UiText.COUNT_PAGES));
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
