package io.github.ajdailey.qcaret.app.view;

/**
 * The only route onto the UI thread. Implementations drop a submission once the UI is disposed, so
 * a worker that finishes after shutdown cannot touch a disposed widget. A submitted runnable still
 * checks {@code isDisposed()} on each widget it touches. There is no synchronous variant: a worker
 * never waits for the UI thread.
 */
public interface UiDispatcher {
  /**
   * Runs a task on the UI thread after the current event; callable from any thread.
   *
   * @param task the task
   */
  void asyncExec(Runnable task);

  /**
   * Runs a task on the UI thread after a delay; call on the UI thread.
   *
   * @param delayMillis the delay
   * @param task the task
   */
  void timerExec(int delayMillis, Runnable task);

  /**
   * Reports whether the caller is on the UI thread.
   *
   * @return true on the UI thread
   */
  boolean isUiThread();
}
