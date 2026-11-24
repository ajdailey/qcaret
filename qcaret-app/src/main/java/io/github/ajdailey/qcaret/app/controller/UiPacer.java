package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Runs tasks on the UI thread in arrival order, at most {@code perTurn} in one UI task; the rest
 * run in a task posted behind whatever was queued meanwhile. A burst of expensive tasks, such as a
 * tab per handed file, then delays other UI work by one turn's share instead of the whole burst. A
 * task that throws ends its turn and the rest still run. Confined to the UI thread.
 */
final class UiPacer {
  private final UiDispatcher ui;
  private final int perTurn;
  private final Queue<Runnable> waiting = new ArrayDeque<Runnable>();
  private boolean posted;
  private final Runnable turn =
      new Runnable() {
        @Override
        public void run() {
          posted = false;
          try {
            for (int i = 0; i < perTurn && !waiting.isEmpty(); i++) {
              waiting.poll().run();
            }
          } finally {
            if (!waiting.isEmpty() && !posted) {
              post();
            }
          }
        }
      };

  /**
   * Creates the pacer.
   *
   * @param ui posts the turns
   * @param perTurn the most tasks one turn runs, at least 1
   */
  UiPacer(UiDispatcher ui, int perTurn) {
    this.ui = ui;
    this.perTurn = Math.max(1, perTurn);
  }

  /**
   * Queues a task behind the waiting ones.
   *
   * @param task the task
   */
  void run(Runnable task) {
    waiting.add(task);
    if (!posted) {
      post();
    }
  }

  /**
   * Counts the tasks not yet run.
   *
   * @return the count
   */
  int waiting() {
    return waiting.size();
  }

  private void post() {
    posted = true;
    ui.asyncExec(turn);
  }
}
