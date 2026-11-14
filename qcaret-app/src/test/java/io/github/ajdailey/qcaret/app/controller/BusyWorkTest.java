package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** {@link BusyWork}: the indicator is active while the work runs and idle after every ending. */
class BusyWorkTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Counter activity = new Counter();
  private final Workers workers =
      new Workers(
          2,
          new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
              failures.add(e);
            }
          });

  /** Counts operations as the window's counter does, from any thread. */
  private static final class Counter implements ActivityIndicator {
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger started = new AtomicInteger();

    @Override
    public void setActive() {
      active.incrementAndGet();
      started.incrementAndGet();
    }

    @Override
    public void setIdle() {
      active.decrementAndGet();
    }
  }

  /** Work that waits for {@link #release}, then throws when told to. */
  private class Gated extends BusyWork {
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger finishedCalls = new AtomicInteger();
    volatile boolean workThrows;
    boolean finishedThrows;
    Boolean completed;
    String result;

    Gated() {
      super(ui, activity);
    }

    @Override
    void work() {
      try {
        assertTrue(release.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      if (workThrows) {
        throw new IllegalStateException("mock work failure");
      }
      result = "done";
    }

    @Override
    void finished(boolean completed) {
      finishedCalls.incrementAndGet();
      this.completed = completed;
      if (finishedThrows) {
        throw new IllegalStateException("mock finish failure");
      }
    }
  }

  @AfterEach
  void close() {
    assertTrue(workers.close(BOUND));
  }

  @Test
  void activeWhileTheWorkRunsAndIdleAfterItsResult() throws InterruptedException {
    Gated work = new Gated();
    assertTrue(work.start(workers, Workers.Kind.IO));
    assertEquals(1, activity.active.get());
    work.release.countDown();
    awaitFinished(work);
    assertEquals(Boolean.TRUE, work.completed);
    assertEquals("done", work.result);
    assertEquals(0, activity.active.get());
    assertTrue(failures.isEmpty());
  }

  @Test
  void aThrowingWorkStillFinishesAndClearsTheIndicator() throws InterruptedException {
    Gated work = new Gated();
    work.workThrows = true;
    assertTrue(work.startCpu(workers));
    work.release.countDown();
    awaitFinished(work);
    assertEquals(Boolean.FALSE, work.completed);
    assertEquals(0, activity.active.get());
    // The handler gets the failure after the post that ran finished: poll for it.
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return failures.size() == 1;
              }
            },
            BOUND));
  }

  @Test
  void aThrowingFinishedStillClearsTheIndicator() throws InterruptedException {
    final Gated work = new Gated();
    work.finishedThrows = true;
    assertTrue(work.start(workers, Workers.Kind.IO));
    work.release.countDown();
    assertThrows(
        IllegalStateException.class,
        new Executable() {
          @Override
          public void execute() throws InterruptedException {
            ui.runUntil(
                new BooleanSupplier() {
                  @Override
                  public boolean getAsBoolean() {
                    return work.finishedCalls.get() > 0;
                  }
                },
                BOUND);
          }
        });
    assertEquals(1, work.finishedCalls.get());
    assertEquals(0, activity.active.get());
  }

  @Test
  void aPostTheWorkMadeRunsBeforeFinished() throws InterruptedException {
    final List<String> order = new CopyOnWriteArrayList<String>();
    BusyWork work =
        new BusyWork(ui, activity) {
          @Override
          void work() {
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    order.add("posted");
                  }
                });
          }

          @Override
          void finished(boolean completed) {
            order.add("finished");
          }
        };
    assertTrue(work.start(workers, Workers.Kind.IO));
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return order.size() == 2;
              }
            },
            BOUND));
    assertEquals(List.of("posted", "finished"), order);
    assertEquals(0, activity.active.get());
  }

  @Test
  void closedWorkersRefuseWithTheIndicatorIdle() throws InterruptedException {
    assertTrue(workers.close(BOUND));
    Gated io = new Gated();
    assertFalse(io.start(workers, Workers.Kind.IO));
    Gated cpu = new Gated();
    assertFalse(cpu.startCpu(workers));
    assertEquals(2, activity.started.get());
    assertEquals(0, activity.active.get());
    ui.runPending();
    assertEquals(0, io.finishedCalls.get() + cpu.finishedCalls.get());
  }

  @Test
  void aSecondStartFails() {
    final Gated work = new Gated();
    work.release.countDown();
    assertTrue(work.start(workers, Workers.Kind.IO));
    assertThrows(
        IllegalStateException.class,
        new Executable() {
          @Override
          public void execute() {
            work.startCpu(workers);
          }
        });
  }

  private void awaitFinished(final Gated work) throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return work.finishedCalls.get() > 0;
              }
            },
            BOUND));
  }
}
