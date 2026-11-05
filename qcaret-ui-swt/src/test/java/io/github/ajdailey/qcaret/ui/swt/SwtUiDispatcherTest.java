package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Timer scheduling and cancel through {@link SwtUiDispatcher}. */
@Tag("swt")
class SwtUiDispatcherTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** Sets its flag when run. */
  private static final class Flag implements Runnable {
    boolean ran;

    @Override
    public void run() {
      ran = true;
    }
  }

  private static boolean ran(Display display, final Flag flag) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return flag.ran;
          }
        },
        BOUND);
  }

  @Test
  void aCanceledTimerDoesNotRun() {
    Display display = new Display();
    try {
      SwtUiDispatcher dispatcher = new SwtUiDispatcher(display);
      Flag canceled = new Flag();
      Flag later = new Flag();
      dispatcher.timerExec(20, canceled);
      dispatcher.timerExec(200, later);
      dispatcher.cancelTimer(canceled);
      assertTrue(ran(display, later));
      assertFalse(canceled.ran);

      Flag kept = new Flag();
      dispatcher.timerExec(20, kept);
      assertTrue(ran(display, kept));
    } finally {
      display.dispose();
    }
  }

  @Test
  void cancelAfterDisposeDoesNothing() {
    Display display = new Display();
    SwtUiDispatcher dispatcher = new SwtUiDispatcher(display);
    display.dispose();
    dispatcher.cancelTimer(new Flag());
    dispatcher.timerExec(20, new Flag());
  }
}
