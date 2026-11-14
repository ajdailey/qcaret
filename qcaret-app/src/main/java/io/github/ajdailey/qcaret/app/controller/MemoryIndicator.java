package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.MemoryFields;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.util.concurrent.RejectedExecutionException;

/**
 * View > Memory Indicator, saved as {@code ui.memoryIndicator}, and the figures the window's status
 * bar shows. While shown, a {@link MemoryProbe} read runs on a CPU worker and the next is armed
 * {@link #REFRESH_MILLIS} ms after its result arrives; nothing reads while hidden. A click requests
 * a garbage collection on a worker under the busy cursor, then shows the figures and the heap
 * freed; a click while one runs does nothing. A read result carries the generation it started in
 * and is dropped once the indicator hides or stops. Confined to the UI thread.
 */
final class MemoryIndicator {
  /** Milliseconds from one read's result to the next read. */
  static final int REFRESH_MILLIS = 2000;

  private final Settings settings;
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MemoryProbe probe;
  private final MemoryBudget budget;
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          read();
        }
      };
  private boolean shown;
  private boolean stopped;
  private boolean reading;
  private boolean collecting;
  private long generation;

  MemoryIndicator(
      Settings settings,
      MainView view,
      Workers workers,
      UiDispatcher ui,
      MemoryProbe probe,
      MemoryBudget budget) {
    this.settings = settings;
    this.view = view;
    this.workers = workers;
    this.ui = ui;
    this.probe = probe;
    this.budget = budget;
    shown = settings.current().state().memoryIndicator();
    view.setMemoryShown(shown);
    read();
  }

  /** Shows or hides the indicator after a settings change. */
  void apply(Preferences before, Preferences after) {
    boolean value = after.state().memoryIndicator();
    if (value == before.state().memoryIndicator() || value == shown || stopped) {
      return;
    }
    shown = value;
    generation++;
    ui.cancelTimer(refresh);
    view.setMemoryShown(value);
    read();
  }

  /** Flips {@code ui.memoryIndicator}. */
  void toggle() {
    Preferences current = settings.current();
    UiState state = current.state();
    settings.set(current.withState(state.withMemoryIndicator(!state.memoryIndicator())));
  }

  /** Requests a garbage collection unless one runs, the indicator is hidden or it stopped. */
  void collect() {
    if (!shown || stopped || collecting) {
      return;
    }
    final long started = generation;
    collecting =
        new BusyWork(ui, view) {
          private MemoryStatus before;
          private MemoryStatus after;

          @Override
          @WorkerThread
          void work() {
            before = probe.read();
            probe.collect();
            after = probe.read();
          }

          @Override
          void finished(boolean completed) {
            collected(started, before, after);
          }
        }.startCpu(workers);
  }

  /**
   * Reports whether a garbage collection a click requested has not reported back.
   *
   * @return true while one runs
   */
  boolean collecting() {
    return collecting;
  }

  /**
   * Reports whether a read has not reported back.
   *
   * @return true while one runs
   */
  boolean reading() {
    return reading;
  }

  /** Stops reading; later results are dropped. */
  void stop() {
    stopped = true;
    generation++;
    ui.cancelTimer(refresh);
  }

  /** Starts a read unless one runs, the indicator is hidden or it stopped. */
  private void read() {
    if (!shown || stopped || reading) {
      return;
    }
    reading = true;
    final long started = generation;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              MemoryStatus status = null;
              try {
                status = probe.read();
              } finally {
                final MemoryStatus result = status;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        readDone(started, result);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      reading = false;
    }
  }

  /**
   * Shows a read and arms the next. A read from before a hide and show starts a fresh one; a failed
   * read, already logged by {@link Workers}, ends the reads until the next show.
   */
  private void readDone(long started, MemoryStatus status) {
    reading = false;
    if (!shown || stopped) {
      return;
    }
    if (started != generation) {
      read();
    } else if (status != null) {
      show(status);
      ui.timerExec(REFRESH_MILLIS, refresh);
    }
  }

  private void collected(long started, MemoryStatus before, MemoryStatus after) {
    collecting = false;
    if (stopped || before == null || after == null) {
      return;
    }
    view.showNotice(
        UiText.MEMORY_FREED.format(Math.max(0, before.used() - after.used()) / MemoryStatus.MB));
    if (shown && started == generation) {
      show(after);
    }
  }

  private void show(MemoryStatus status) {
    view.showMemory(new MemoryFields(status.text(), status.tip(budget), status.fill()));
  }
}
