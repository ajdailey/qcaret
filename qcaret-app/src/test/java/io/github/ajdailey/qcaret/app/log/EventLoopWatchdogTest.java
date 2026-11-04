package io.github.ajdailey.qcaret.app.log;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class EventLoopWatchdogTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** The test thread acts as a stalled UI thread until the watchdog reports it, then drains. */
  @Test
  void reportsAStallWithTheUiStackThenTheRecovery() throws Exception {
    final CountDownLatch stalled = new CountDownLatch(1);
    final AtomicReference<StackTraceElement[]> stack = new AtomicReference<StackTraceElement[]>();
    final AtomicLong recovered = new AtomicLong(-1);
    ManualUiDispatcher ui = new ManualUiDispatcher();
    try (Workers workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError("worker failed", error);
              }
            })) {
      EventLoopWatchdog watchdog =
          new EventLoopWatchdog(
              workers,
              ui,
              Thread.currentThread(),
              20,
              50,
              new EventLoopWatchdog.StallListener() {
                @Override
                public void stalled(long millis, StackTraceElement[] uiStack) {
                  stack.compareAndSet(null, uiStack);
                  stalled.countDown();
                }

                @Override
                public void recovered(long millis) {
                  recovered.compareAndSet(-1, millis);
                }
              });
      assertTrue(watchdog.start());
      assertTrue(stalled.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
      boolean inTest = false;
      for (StackTraceElement frame : stack.get()) {
        inTest |= frame.getClassName().equals(EventLoopWatchdogTest.class.getName());
      }
      assertTrue(inTest, "the stack is the UI thread's");
      assertTrue(
          ui.runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return recovered.get() >= 50;
                }
              },
              BOUND));
      watchdog.stop();
    }
  }
}
