package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.recovery.RecoveryFile;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.RecoveryRow;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The recovery dialog's rows for one {@link RecoveryController}: dead instances' buffers shown by
 * the window activated last, and Restore, Discard and Later. A buffer is read and its old file
 * deleted in the controller's queue. Confined to the UI thread apart from the read.
 */
final class RecoveryOffer implements RecoveryInput {
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  /** One buffer in the dialog. */
  static final class Row {
    RecoveryStore.Entry entry;
    boolean reading;

    Row(RecoveryStore.Entry entry) {
      this.entry = entry;
    }
  }

  private final RecoveryController recovery;
  private final WindowSet set;
  private final UiDispatcher ui;
  private final MemoryBudget budget;
  private final List<Row> rows = new ArrayList<Row>();
  private final List<Runnable> afterOffer = new ArrayList<Runnable>();
  private boolean offerEnded;

  /** The window showing the dialog; set by {@link #offer}. */
  private MainView dialog;

  RecoveryOffer(RecoveryController recovery, WindowSet set, UiDispatcher ui, MemoryBudget budget) {
    this.recovery = recovery;
    this.set = set;
    this.ui = ui;
    this.budget = budget;
  }

  /** Shows the rows in the window activated last, or ends the offer when there are none. */
  void offer(List<Row> found) {
    ApplicationController window = set.target();
    if (!recovery.closing() && window != null && !found.isEmpty()) {
      rows.addAll(found);
      dialog = window.view();
      dialog.showRecovery(this);
    } else {
      offerEnded();
    }
  }

  /**
   * Runs a task on the UI thread once {@link RecoveryController#start}'s offer has ended: no buffer
   * was found, or the dialog's rows are all handled, or Later was pressed. Runs it now when the
   * offer has ended.
   *
   * @param task the task
   */
  void afterOffer(Runnable task) {
    if (offerEnded) {
      task.run();
    } else {
      afterOffer.add(task);
    }
  }

  private void offerEnded() {
    if (!offerEnded) {
      offerEnded = true;
      List<Runnable> tasks = new ArrayList<Runnable>(afterOffer);
      afterOffer.clear();
      for (Runnable task : tasks) {
        task.run();
      }
    }
  }

  @Override
  public List<RecoveryRow> rows() {
    List<RecoveryRow> list = new ArrayList<RecoveryRow>();
    for (Row row : rows) {
      list.add(row(row.entry));
    }
    return list;
  }

  private static RecoveryRow row(RecoveryStore.Entry entry) {
    RecoveryFile.Header h = entry.header();
    String name;
    if (h == null) {
      name = String.valueOf(entry.file().getFileName());
    } else {
      name = h.path().isEmpty() ? h.title() : h.path();
    }
    String state =
        entry.unreadable()
            ? UiText.RECOVERY_FILE_UNREADABLE.text()
            : entry.damaged()
                ? UiText.RECOVERY_DAMAGED.text()
                : switch (entry.original()) {
                  case NONE -> UiText.RECOVERY_NO_FILE.text();
                  case UNCHANGED -> UiText.RECOVERY_UNCHANGED.text();
                  case CHANGED -> UiText.RECOVERY_CHANGED.text();
                  case MISSING -> UiText.RECOVERY_MISSING.text();
                  case UNREADABLE -> UiText.RECOVERY_UNREADABLE.text();
                };
    return new RecoveryRow(name, time(h), state, entry.damaged());
  }

  private static String time(RecoveryFile.Header header) {
    if (header == null) {
      return "";
    }
    try {
      return TIME.format(Instant.parse(header.written()).truncatedTo(ChronoUnit.SECONDS));
    } catch (DateTimeParseException e) {
      return header.written();
    }
  }

  @Override
  public void restore(int[] indexes) {
    for (int index : indexes) {
      if (index >= 0 && index < rows.size()) {
        final Row row = rows.get(index);
        if (row.entry.damaged()) {
          recovery.notice(UiText.RECOVERY_DAMAGED_KEPT.text());
        } else if (!row.reading) {
          row.reading = true;
          recovery.submitRead(row);
        }
      }
    }
  }

  /**
   * Reads a row's buffer, then adds it on the UI thread; see {@link RecoveryController#submitRead}.
   */
  @WorkerThread
  void read(final Row row) {
    RecoveryController.Result result = recovery.load(row.entry.file());
    final DocumentController.Recovered recovered = result.recovered();
    final String problem = result.problem();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            read(row, recovered, problem);
          }
        });
  }

  private void read(Row row, DocumentController.Recovered recovered, String problem) {
    row.reading = false;
    if (recovered == null) {
      recovery.notice(problem);
      dialogChanged();
      return;
    }
    ApplicationController window = set.target();
    DocumentController.Buffer buffer =
        recovery.closing() || window == null
            ? null
            : recovery.receiver(recovered, window.documents()).addRecovered(recovered, null);
    if (buffer == null) {
      budget.release(recovered.reserved());
      recovery.notice(UiText.RECOVERY_TAB_DIRTY.format(row(row.entry).name()));
      return;
    }
    recovery.adopt(buffer, row.entry.file());
    rows.remove(row);
    rowsChanged();
  }

  @Override
  public void discard(int[] indexes) {
    List<Row> chosen = new ArrayList<Row>();
    for (int index : indexes) {
      if (index >= 0 && index < rows.size() && !rows.get(index).reading) {
        chosen.add(rows.get(index));
      }
    }
    for (Row row : chosen) {
      rows.remove(row);
      recovery.submitDelete(row.entry.file());
    }
    rowsChanged();
  }

  @Override
  public void later() {
    rows.clear();
    recovery.releaseClaims();
    offerEnded();
  }

  private void rowsChanged() {
    dialogChanged();
    if (rows.isEmpty()) {
      recovery.releaseClaims();
      offerEnded();
    }
  }

  /** Repaints the dialog; rows exist only once {@link #offer} showed it. */
  private void dialogChanged() {
    if (dialog != null) {
      dialog.recoveryChanged();
    }
  }
}
