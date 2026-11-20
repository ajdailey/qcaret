package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;

/**
 * A dispatcher that schedules every timer but a {@link GuideWindow.LoadBound}, so a test calls a
 * bound's expiry directly instead of racing it against a real load.
 */
final class UnboundDispatcher implements UiDispatcher {
  private final UiDispatcher ui;

  UnboundDispatcher(UiDispatcher ui) {
    this.ui = ui;
  }

  @Override
  public void asyncExec(Runnable task) {
    ui.asyncExec(task);
  }

  @Override
  public void timerExec(int delayMillis, Runnable task) {
    if (!(task instanceof GuideWindow.LoadBound)) {
      ui.timerExec(delayMillis, task);
    }
  }

  @Override
  public void cancelTimer(Runnable task) {
    ui.cancelTimer(task);
  }

  @Override
  public boolean isUiThread() {
    return ui.isUiThread();
  }
}
