package io.github.ajdailey.qcaret.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class WorkersTest {
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Thread.UncaughtExceptionHandler handler =
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread thread, Throwable error) {
          failures.add(error);
        }
      };

  @Test
  void namesThreadsByKind() throws Exception {
    final CountDownLatch done = new CountDownLatch(2);
    final List<String> names = new CopyOnWriteArrayList<String>();
    Runnable record =
        new Runnable() {
          @Override
          public void run() {
            names.add(Thread.currentThread().getName());
            done.countDown();
          }
        };
    try (Workers workers = new Workers(1, handler)) {
      workers.start(Workers.Kind.IO, record);
      workers.executeCpu(record);
      assertTrue(done.await(10, TimeUnit.SECONDS));
    }
    assertTrue(names.contains("qcaret-io-1"), names.toString());
    assertTrue(names.contains("qcaret-cpu-1"), names.toString());
  }

  @Test
  void aPrintTaskRunsOnANamedPlatformThreadThatCloseWaitsFor() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    Workers workers = new Workers(1, handler);
    Thread thread =
        workers.start(
            Workers.Kind.PRINT,
            new Runnable() {
              @Override
              public void run() {
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    assertFalse(thread.isVirtual());
    assertTrue(thread.isDaemon());
    assertTrue(thread.getName().startsWith("qcaret-print-"));
    assertEquals(1, workers.liveThreads());
    release.countDown();
    assertTrue(workers.close(Duration.ofSeconds(10)));
    assertEquals(0, workers.liveThreads());
  }

  @Test
  void taskFailureReachesHandler() throws Exception {
    final IllegalStateException boom = new IllegalStateException("mock failure");
    Workers workers = new Workers(1, handler);
    Thread thread =
        workers.start(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                throw boom;
              }
            });
    assertTrue(thread.join(Duration.ofSeconds(10)));
    assertTrue(workers.close(Duration.ofSeconds(10)));
    assertEquals(1, failures.size());
    assertSame(boom, failures.get(0));
  }

  @Test
  void closeEndsEveryThreadAndRejectsNewWork() throws Exception {
    final Workers workers = new Workers(2, handler);
    final CountDownLatch started = new CountDownLatch(1);
    workers.start(
        Workers.Kind.WATCH,
        new Runnable() {
          @Override
          public void run() {
            started.countDown();
            try {
              Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    workers.executeCpu(
        new Runnable() {
          @Override
          public void run() {}
        });
    assertTrue(started.await(10, TimeUnit.SECONDS));
    workers.close(Duration.ofMillis(200));
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreads() == 0;
              }
            },
            Duration.ofSeconds(10)));
    assertFalse(
        workers.tryStart(
            Workers.Kind.IPC,
            new Runnable() {
              @Override
              public void run() {}
            }));
    assertThrows(
        RejectedExecutionException.class,
        new Executable() {
          @Override
          public void execute() {
            workers.start(
                Workers.Kind.IPC,
                new Runnable() {
                  @Override
                  public void run() {}
                });
          }
        });
  }

  @Test
  void tryStartRunsTheTaskWhileOpen() throws Exception {
    final CountDownLatch ran = new CountDownLatch(1);
    try (Workers workers = new Workers(1, handler)) {
      assertTrue(
          workers.tryStart(
              Workers.Kind.LIFECYCLE,
              new Runnable() {
                @Override
                public void run() {
                  ran.countDown();
                }
              }));
      assertTrue(ran.await(10, TimeUnit.SECONDS));
    }
  }

  /**
   * A start racing close is either rejected or waited for, so no task runs after close returns.
   * Without the second {@code closed} check after joining the live set, a thread starts after close
   * took its snapshot of the set. The live count is polled because {@code ConcurrentHashMap.size}
   * lags a removal the set already shows.
   */
  @Test
  void noTaskRunsAfterCloseReturns() throws Exception {
    for (int round = 0; round < 50; round++) {
      final Workers workers = new Workers(1, handler);
      final AtomicBoolean closeReturned = new AtomicBoolean();
      final AtomicInteger late = new AtomicInteger();
      final Runnable task =
          new Runnable() {
            @Override
            public void run() {
              if (closeReturned.get()) {
                late.incrementAndGet();
              }
            }
          };
      final CountDownLatch spawning = new CountDownLatch(1);
      workers.start(
          Workers.Kind.LIFECYCLE,
          new Runnable() {
            @Override
            public void run() {
              spawning.countDown();
              while (workers.tryStart(Workers.Kind.IO, task)) {
                Thread.onSpinWait();
              }
            }
          });
      assertTrue(spawning.await(10, TimeUnit.SECONDS));
      assertTrue(workers.close(Duration.ofSeconds(10)));
      closeReturned.set(true);
      assertEquals(0, late.get());
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
    assertTrue(failures.isEmpty(), failures.toString());
  }
}
