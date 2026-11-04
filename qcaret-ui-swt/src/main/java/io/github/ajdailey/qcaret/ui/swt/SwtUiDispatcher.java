package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

/** {@link UiDispatcher} over an SWT {@link Display}; drops submissions once it is disposed. */
public final class SwtUiDispatcher implements UiDispatcher {
  private final Display display;

  /**
   * Creates the dispatcher.
   *
   * @param display the application Display
   */
  public SwtUiDispatcher(Display display) {
    this.display = display;
  }

  @Override
  public void asyncExec(Runnable task) {
    if (display.isDisposed()) {
      return;
    }
    try {
      display.asyncExec(task);
    } catch (SWTException e) {
      // Disposed between the check and the call.
      if (e.code != SWT.ERROR_DEVICE_DISPOSED) {
        throw e;
      }
    }
  }

  @Override
  public void timerExec(int delayMillis, Runnable task) {
    if (!display.isDisposed()) {
      display.timerExec(delayMillis, task);
    }
  }

  @Override
  public boolean isUiThread() {
    return !display.isDisposed() && display.getThread() == Thread.currentThread();
  }
}
