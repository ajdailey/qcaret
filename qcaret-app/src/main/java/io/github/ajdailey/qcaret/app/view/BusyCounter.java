package io.github.ajdailey.qcaret.app.view;

/**
 * An {@link ActivityIndicator} that counts overlapping operations on the UI thread. Calls from any
 * thread are posted through the {@link UiDispatcher}; the first active call turns the indicator on
 * and the last idle call turns it off, so one operation ending does not clear another's indicator.
 * An idle call with nothing active is ignored.
 */
public final class BusyCounter implements ActivityIndicator {
  /** Receives the indicator state on the UI thread. */
  public interface Indicator {
    /**
     * Called on the UI thread when the indicator turns on or off.
     *
     * @param busy true while any operation is active
     */
    void busyChanged(boolean busy);
  }

  private final UiDispatcher ui;
  private final Indicator indicator;
  private final Runnable increment =
      new Runnable() {
        @Override
        public void run() {
          change(1);
        }
      };
  private final Runnable decrement =
      new Runnable() {
        @Override
        public void run() {
          change(-1);
        }
      };
  private int active;

  /**
   * Creates the counter.
   *
   * @param ui the UI-thread dispatcher
   * @param indicator shows the state
   */
  public BusyCounter(UiDispatcher ui, Indicator indicator) {
    this.ui = ui;
    this.indicator = indicator;
  }

  @Override
  public void setActive() {
    ui.asyncExec(increment);
  }

  @Override
  public void setIdle() {
    ui.asyncExec(decrement);
  }

  /**
   * Returns the operations active now; call on the UI thread.
   *
   * @return the count
   */
  public int active() {
    return active;
  }

  private void change(int delta) {
    if (delta < 0 && active == 0) {
      return;
    }
    active += delta;
    if (active == 1 && delta > 0) {
      indicator.busyChanged(true);
    } else if (active == 0) {
      indicator.busyChanged(false);
    }
  }
}
