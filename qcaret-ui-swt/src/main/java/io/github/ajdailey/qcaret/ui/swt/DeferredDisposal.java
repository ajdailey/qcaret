package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.ArrayDeque;
import org.eclipse.swt.widgets.Control;

/**
 * Disposes hidden controls in later UI tasks of at most {@value #SLICE_MILLIS} ms each, at least
 * one control per task, so a bulk close's widget teardown runs outside the command's own task. A
 * control disposed before its turn, for example with its shell, is skipped. Confined to the UI
 * thread.
 */
final class DeferredDisposal {
  /** Milliseconds of disposing per UI task. */
  static final int SLICE_MILLIS = 20;

  private final UiDispatcher ui;
  private final ArrayDeque<Control> queue = new ArrayDeque<Control>();
  private boolean scheduled;
  private final Runnable drain =
      new Runnable() {
        @Override
        public void run() {
          drain();
        }
      };

  /**
   * Creates the queue.
   *
   * @param ui runs each slice
   */
  DeferredDisposal(UiDispatcher ui) {
    this.ui = ui;
  }

  /**
   * Hides a control now and disposes it in a later UI task.
   *
   * @param control the control
   */
  void dispose(Control control) {
    if (control.isDisposed()) {
      return;
    }
    control.setVisible(false);
    queue.add(control);
    if (!scheduled) {
      scheduled = true;
      ui.asyncExec(drain);
    }
  }

  /**
   * Returns the controls waiting.
   *
   * @return the count
   */
  int pending() {
    return queue.size();
  }

  private void drain() {
    scheduled = false;
    long end = System.nanoTime() + SLICE_MILLIS * 1_000_000L;
    do {
      Control next = queue.poll();
      if (next != null && !next.isDisposed()) {
        next.dispose();
      }
    } while (!queue.isEmpty() && System.nanoTime() - end < 0);
    if (!queue.isEmpty()) {
      scheduled = true;
      ui.asyncExec(drain);
    }
  }
}
