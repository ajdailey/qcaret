package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Restore Replace Run's choice for one {@link ReplaceController}: prunes and lists the kept runs on
 * a {@code qcaret-io-} worker, then asks which one to restore and hands it to {@link
 * ReplaceController#restore}. Confined to the UI thread apart from the listing.
 */
final class ReplaceRestore {
  private static final DateTimeFormatter RUN_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

  private final ReplaceController replace;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final Clock clock;
  private boolean disposed;

  ReplaceRestore(
      ReplaceController replace, Workers workers, UiDispatcher ui, MainView view, Clock clock) {
    this.replace = replace;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.clock = clock;
  }

  /**
   * Lists the runs under {@code root} on a worker, under the busy cursor, after pruning those older
   * than {@code days}; a refused worker shows the running notice.
   */
  void choose(final Path root, final int days) {
    boolean started =
        new BusyWork(ui, view) {
          @Override
          @WorkerThread
          void work() {
            listRuns(root, days);
          }
        }.start(workers, Workers.Kind.IO);
    if (!started) {
      view.showNotice(UiText.REPLACE_RUNNING.text());
    }
  }

  /** Drops a listing still on its way. */
  void dispose() {
    disposed = true;
  }

  @WorkerThread
  private void listRuns(Path root, int days) {
    List<ReplaceBackups.Run> runs = null;
    String failure = null;
    List<String> labels = new ArrayList<String>();
    try {
      ReplaceBackups.prune(root, days, clock.instant());
      runs = ReplaceBackups.list(root);
      ZoneId zone = ZoneId.systemDefault();
      for (ReplaceBackups.Run r : runs) {
        labels.add(
            UiText.RESTORE_RUN_ROW.format(
                RUN_TIME.format(r.started().atZone(zone)),
                UiText.count(r.files(), UiText.COUNT_FILE, UiText.COUNT_FILES)));
      }
    } catch (IOException e) {
      failure = LogText.oneLine(String.valueOf(e.getMessage()));
    }
    final List<ReplaceBackups.Run> found = runs;
    final String failed = failure;
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            listed(found, labels, failed);
          }
        });
  }

  private void listed(final List<ReplaceBackups.Run> runs, List<String> labels, String failure) {
    if (disposed) {
      return;
    }
    if (failure != null) {
      view.showInfo(UiText.RESTORE_CHOOSE_TITLE.text(), UiText.RESTORE_LIST_FAILED.format(failure));
    } else if (runs.isEmpty()) {
      view.showInfo(UiText.RESTORE_CHOOSE_TITLE.text(), UiText.RESTORE_NO_RUNS.text());
    } else {
      view.chooseReplaceRun(
          labels,
          new Consumer<Integer>() {
            @Override
            public void accept(Integer index) {
              ReplaceBackups.Run run = runs.get(index);
              replace.restore(run.directory(), run.id());
            }
          });
    }
  }
}
