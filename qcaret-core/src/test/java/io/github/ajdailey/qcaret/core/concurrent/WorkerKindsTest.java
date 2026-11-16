package io.github.ajdailey.qcaret.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * {@link CpuSteps} on the bulk pool. A virtual thread that computes holds its carrier, and the
 * carrier pool has {@code availableProcessors()} threads: that many CPU-bound tasks on virtual
 * kinds stall every other virtual task (measured on 8 cores: a {@code LIFECYCLE} task waited over 5
 * s). Steps on the bulk pool leave the carriers free.
 */
class WorkerKindsTest {
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Thread.UncaughtExceptionHandler handler =
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread thread, Throwable error) {
          failures.add(error);
        }
      };

  @Test
  void aStepFromAVirtualTaskRunsOnTheBulkPoolAndElsewhereInline() throws Exception {
    final CpuSteps.Compute<String> name =
        new CpuSteps.Compute<String>() {
          @Override
          public String run() {
            return Thread.currentThread().getName();
          }
        };
    final AtomicReference<String> pooled = new AtomicReference<String>();
    Workers workers = new Workers(1, handler);
    try {
      Thread task =
          workers.start(
              Workers.Kind.IO,
              new Runnable() {
                @Override
                public void run() {
                  pooled.set(CpuSteps.compute(null, name));
                }
              });
      assertTrue(task.join(Duration.ofSeconds(10)));
    } finally {
      assertTrue(workers.close(Duration.ofSeconds(10)));
    }
    assertEquals("qcaret-bulk-1", pooled.get());
    assertEquals(Thread.currentThread().getName(), CpuSteps.compute(null, name));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /**
   * One spinning step per core, each from its own {@code IO} task, leaves a task of every kind
   * runnable. With the step run inline on the virtual thread, the virtual kinds wait for the
   * spinners.
   */
  @Test
  void oneSpinningStepPerCoreLeavesEveryKindRunnable() throws Exception {
    int cores = Runtime.getRuntime().availableProcessors();
    final AtomicBoolean spin = new AtomicBoolean(true);
    final CountDownLatch spinners = new CountDownLatch(cores);
    Workers workers = new Workers(cores, handler);
    try {
      for (int i = 0; i < cores; i++) {
        workers.start(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                CpuSteps.compute(
                    null,
                    new CpuSteps.Compute<Void>() {
                      @Override
                      public Void run() {
                        spinners.countDown();
                        while (spin.get()) {
                          Thread.onSpinWait();
                        }
                        return null;
                      }
                    });
              }
            });
      }
      assertTrue(spinners.await(30, TimeUnit.SECONDS), "spinners started");
      Workers.Kind[] kinds = Workers.Kind.values();
      final CountDownLatch ran = new CountDownLatch(kinds.length);
      for (Workers.Kind kind : kinds) {
        workers.start(
            kind,
            new Runnable() {
              @Override
              public void run() {
                ran.countDown();
              }
            });
      }
      assertTrue(ran.await(30, TimeUnit.SECONDS), ran.getCount() + " kinds did not run");
    } finally {
      spin.set(false);
      assertTrue(workers.close(Duration.ofSeconds(10)));
    }
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void aCancelWhileTheStepIsQueuedEndsTheWaitAndTheStepNeverRuns() throws Exception {
    final Workers workers = new Workers(1, handler);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicBoolean ran = new AtomicBoolean();
    final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
    final CancellationToken token = new CancellationToken();
    try {
      occupyBulk(workers, release);
      Thread waiter =
          workers.start(
              Workers.Kind.IO,
              new Runnable() {
                @Override
                public void run() {
                  try {
                    CpuSteps.compute(
                        token,
                        new CpuSteps.Compute<Void>() {
                          @Override
                          public Void run() {
                            ran.set(true);
                            return null;
                          }
                        });
                  } catch (CancellationException e) {
                    thrown.set(e);
                  }
                }
              });
      awaitQueued(workers, 1);
      token.cancel();
      assertTrue(waiter.join(Duration.ofSeconds(10)), "the waiter ended while the pool was busy");
      assertTrue(thrown.get() instanceof CancellationException, String.valueOf(thrown.get()));
    } finally {
      release.countDown();
      assertTrue(workers.close(Duration.ofSeconds(10)));
    }
    assertTrue(!ran.get(), "a canceled step ran");
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void closeDropsAQueuedStepAndItsWaiterEnds() throws Exception {
    final Workers workers = new Workers(1, handler);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
    occupyBulk(workers, release);
    Thread waiter =
        workers.start(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                try {
                  CpuSteps.compute(
                      null,
                      new CpuSteps.Compute<Void>() {
                        @Override
                        public Void run() {
                          return null;
                        }
                      });
                } catch (CancellationException e) {
                  thrown.set(e);
                }
              }
            });
    awaitQueued(workers, 1);
    workers.close(Duration.ofMillis(200));
    release.countDown();
    assertTrue(waiter.join(Duration.ofSeconds(10)), "the waiter ended after close");
    assertTrue(thrown.get() instanceof CancellationException, String.valueOf(thrown.get()));
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreads() == 0;
              }
            },
            Duration.ofSeconds(10)));
  }

  @Test
  void aStepFailureReachesTheCaller() throws Exception {
    final IOException failure = new IOException("mock failure");
    final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
    Workers workers = new Workers(1, handler);
    try {
      Thread task =
          workers.start(
              Workers.Kind.IO,
              new Runnable() {
                @Override
                public void run() {
                  try {
                    CpuSteps.run(
                        null,
                        new CpuSteps.Step<Void>() {
                          @Override
                          public Void run() throws IOException {
                            throw failure;
                          }
                        });
                  } catch (IOException e) {
                    thrown.set(e);
                  }
                }
              });
      assertTrue(task.join(Duration.ofSeconds(10)));
    } finally {
      assertTrue(workers.close(Duration.ofSeconds(10)));
    }
    assertTrue(thrown.get() == failure, String.valueOf(thrown.get()));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** Holds the only bulk thread in a step until {@code release}. */
  static void occupyBulk(Workers workers, final CountDownLatch release) throws Exception {
    final CountDownLatch holding = new CountDownLatch(1);
    workers.start(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            CpuSteps.compute(
                null,
                new CpuSteps.Compute<Void>() {
                  @Override
                  public Void run() {
                    holding.countDown();
                    try {
                      release.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    return null;
                  }
                });
          }
        });
    assertTrue(holding.await(10, TimeUnit.SECONDS), "the bulk thread is held");
  }

  /** Waits until {@code count} steps wait in the bulk queue. */
  static void awaitQueued(final Workers workers, final int count) throws InterruptedException {
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.queuedSteps() == count;
              }
            },
            Duration.ofSeconds(10)),
        "steps queued");
  }
}
