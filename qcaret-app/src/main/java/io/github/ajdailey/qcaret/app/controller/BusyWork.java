package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.util.concurrent.RejectedExecutionException;

/**
 * A user-started operation on a worker that holds one {@link ActivityIndicator} operation, the busy
 * cursor, from {@link #start} until it ends. {@link #work} runs on the worker; {@link #finished}
 * runs on the UI thread after {@code work} returns or throws, and after every task {@code work}
 * posted to the {@link UiDispatcher} before it returned. The indicator goes idle after {@code
 * finished} on every path, including a throwing {@code work} or {@code finished}. Fields {@code
 * work} sets are visible to {@code finished}. A disposed dispatcher drops {@code finished} and the
 * idle call with it. Each instance starts once.
 */
abstract class BusyWork {
  private final UiDispatcher ui;
  private final ActivityIndicator activity;
  private final Runnable finish =
      new Runnable() {
        @Override
        public void run() {
          try {
            finished(completed);
          } finally {
            activity.setIdle();
          }
        }
      };
  private boolean started;
  private boolean completed;

  /**
   * Creates the operation.
   *
   * @param ui runs {@link #finished}
   * @param activity shows the operation while it runs
   */
  BusyWork(UiDispatcher ui, ActivityIndicator activity) {
    this.ui = ui;
    this.activity = activity;
  }

  /** Does the work on the worker. An exception it throws goes to the {@link Workers} handler. */
  @WorkerThread
  abstract void work();

  /**
   * Applies the result on the UI thread. The default does nothing.
   *
   * @param completed true when {@link #work} returned, false when it threw
   */
  void finished(boolean completed) {
    // Nothing to apply.
  }

  /**
   * Raises the indicator and starts {@link #work} on a new thread of a kind; call on the UI thread.
   *
   * @param workers starts the thread
   * @param kind names the thread
   * @return false, with the indicator idle again and {@link #finished} not run, when the workers
   *     are closed
   */
  final boolean start(Workers workers, Workers.Kind kind) {
    return submit(
        workers,
        kind,
        new Runnable() {
          @Override
          public void run() {
            runWork();
          }
        });
  }

  /**
   * Raises the indicator and runs {@link #work} on the CPU pool; call on the UI thread.
   *
   * @param workers runs the task
   * @return false, with the indicator idle again and {@link #finished} not run, when the pool
   *     refuses the task
   */
  final boolean startCpu(Workers workers) {
    return submit(
        workers,
        null,
        new Runnable() {
          @Override
          public void run() {
            runWork();
          }
        });
  }

  private boolean submit(Workers workers, Workers.Kind kind, Runnable task) {
    if (started) {
      throw new IllegalStateException("already started");
    }
    started = true;
    activity.setActive();
    boolean running;
    if (kind == null) {
      try {
        workers.executeCpu(task);
        running = true;
      } catch (RejectedExecutionException e) {
        running = false;
      }
    } else {
      running = workers.tryStart(kind, task);
    }
    if (!running) {
      activity.setIdle();
    }
    return running;
  }

  @WorkerThread
  private void runWork() {
    boolean returned = false;
    try {
      work();
      returned = true;
    } finally {
      completed = returned;
      ui.asyncExec(finish);
    }
  }
}
