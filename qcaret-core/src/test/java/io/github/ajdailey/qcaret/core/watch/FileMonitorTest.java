package io.github.ajdailey.qcaret.core.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMonitorTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** A poll interval no test waits for. */
  private static final long NEVER = 3_600_000;

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<Path> checked = new CopyOnWriteArrayList<Path>();
  private Workers workers;
  private FileMonitor monitor;

  @BeforeEach
  void createWorkers() {
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
  }

  @AfterEach
  void closeWorkers() throws Exception {
    if (monitor != null) {
      monitor.close();
    }
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertEquals(0, workers.liveThreads());
  }

  private FileMonitor recording(long pollMillis) {
    monitor =
        new FileMonitor(
            workers,
            pollMillis,
            new FileMonitor.Listener() {
              @Override
              public void check(List<Path> files) {
                checked.addAll(files);
              }
            });
    return monitor;
  }

  private BooleanSupplier checkedAtLeast(final int count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return checked.size() >= count;
      }
    };
  }

  private BooleanSupplier stopped() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !monitor.running() && workers.liveThreads() == 0;
      }
    };
  }

  @Test
  void pollsEveryFileUntilTheLastIsUnwatched() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "a");
    Path b = Files.writeString(dir.resolve("b.txt"), "b");
    recording(20);
    monitor.watch(a);
    monitor.watch(b);
    assertTrue(Poll.until(checkedAtLeast(6), BOUND));
    assertTrue(checked.contains(a) && checked.contains(b));
    monitor.unwatch(a);
    monitor.unwatch(b);
    assertTrue(Poll.until(stopped(), BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void aShorterIntervalTakesEffectWhileRunning() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "a");
    recording(NEVER);
    monitor.watch(a);
    monitor.setPollMillis(20);
    assertTrue(Poll.until(checkedAtLeast(3), BOUND));
    monitor.unwatch(a);
    assertTrue(Poll.until(stopped(), BOUND));
  }

  @Test
  void aRequestedCheckRunsWithoutWaitingForThePoll() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "a");
    Path b = Files.writeString(dir.resolve("b.txt"), "b");
    recording(NEVER);
    monitor.watch(a);
    monitor.watch(b);
    monitor.check(b);
    assertTrue(Poll.until(checkedAtLeast(1), BOUND));
    assertEquals(b, checked.get(0));
    monitor.checkAll();
    assertTrue(Poll.until(checkedAtLeast(3), BOUND));
    assertTrue(checked.subList(1, 3).containsAll(List.of(a, b)));
  }

  @Test
  void oneDirectoryIsRegisteredOnceAndReleasedWithItsLastFile() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "a");
    Path b = Files.writeString(dir.resolve("b.txt"), "b");
    recording(NEVER);
    monitor.watch(a);
    monitor.watch(b);
    monitor.watch(b);
    BooleanSupplier registered =
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return monitor.watchedDirectories() == 1;
          }
        };
    assertTrue(Poll.until(registered, BOUND));
    monitor.unwatch(a);
    monitor.unwatch(b);
    assertTrue(monitor.running());
    monitor.unwatch(b);
    assertTrue(Poll.until(stopped(), BOUND));
    assertEquals(0, monitor.watchedDirectories());
  }

  @Test
  void aThrowingListenerIsReportedOnceAndChecksGoOn() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "a");
    final AtomicInteger calls = new AtomicInteger();
    monitor =
        new FileMonitor(
            workers,
            10,
            new FileMonitor.Listener() {
              @Override
              public void check(List<Path> files) {
                calls.incrementAndGet();
                throw new IllegalStateException("mock check failure");
              }
            });
    monitor.watch(a);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return calls.get() >= 5;
              }
            },
            BOUND));
    assertEquals(1, failures.size());
  }

  @Test
  void aDirectoryEventRequestsACheckOfItsFile() throws Exception {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    Assumptions.assumeFalse(os.contains("mac"), "the macOS WatchService polls");
    Path a = Files.writeString(dir.resolve("a.txt"), "a");
    Path b = Files.writeString(dir.resolve("b.txt"), "b");
    recording(NEVER);
    monitor.watch(a);
    monitor.watch(b);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return monitor.watchedDirectories() == 1;
              }
            },
            BOUND));
    Files.writeString(a, "changed");
    assertTrue(Poll.until(checkedAtLeast(1), BOUND));
    assertTrue(checked.contains(a));
    assertFalse(checked.contains(b));
  }

  @Test
  void closeStopsTheThreadAndLaterWatchesDoNothing() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "a");
    recording(NEVER);
    monitor.watch(a);
    monitor.close();
    assertTrue(Poll.until(stopped(), BOUND));
    monitor.watch(a);
    assertFalse(monitor.running());
  }
}
