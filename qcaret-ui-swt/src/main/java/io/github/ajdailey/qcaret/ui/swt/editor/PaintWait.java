package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Posts a task for the UI turn after a control's next paint, or after a timeout when the control
 * does not paint by then, as for a hidden window; the task runs once. UI thread.
 */
final class PaintWait implements Listener, Runnable {
  private final Control control;
  private final UiDispatcher ui;
  private final Runnable task;
  private boolean done;

  private PaintWait(Control control, UiDispatcher ui, Runnable task) {
    this.control = control;
    this.ui = ui;
    this.task = task;
  }

  /**
   * Starts a wait.
   *
   * @param control the control whose paint ends the wait
   * @param ui posts the task and the timeout
   * @param timeoutMillis the longest wait
   * @param task the task
   */
  static void start(Control control, UiDispatcher ui, int timeoutMillis, Runnable task) {
    if (control.isDisposed()) {
      ui.asyncExec(task);
      return;
    }
    PaintWait wait = new PaintWait(control, ui, task);
    control.addListener(SWT.Paint, wait);
    ui.timerExec(timeoutMillis, wait);
  }

  @Override
  public void handleEvent(Event event) {
    finish();
  }

  @Override
  public void run() {
    finish();
  }

  private void finish() {
    if (done) {
      return;
    }
    done = true;
    if (!control.isDisposed()) {
      control.removeListener(SWT.Paint, this);
    }
    ui.cancelTimer(this);
    ui.asyncExec(task);
  }
}
