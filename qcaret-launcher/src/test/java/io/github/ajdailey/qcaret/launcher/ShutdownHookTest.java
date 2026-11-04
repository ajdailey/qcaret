package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The JVM shutdown hook's task, run directly on the test thread. */
class ShutdownHookTest {
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger releases = new AtomicInteger();
  private final AtomicInteger survivorReads = new AtomicInteger();

  private ShutdownHook hook(
      final CountDownLatch finished, final boolean quitFinishes, Duration bound) {
    return new ShutdownHook(
        new Runnable() {
          @Override
          public void run() {
            requests.incrementAndGet();
            if (quitFinishes) {
              finished.countDown();
            }
          }
        },
        finished,
        new Runnable() {
          @Override
          public void run() {
            releases.incrementAndGet();
          }
        },
        bound,
        new Supplier<String>() {
          @Override
          public String get() {
            survivorReads.incrementAndGet();
            return "qcaret-index-1";
          }
        });
  }

  @Test
  void requestsTheQuitAndReleasesOnceItFinishes() {
    CountDownLatch finished = new CountDownLatch(1);
    hook(finished, true, ShutdownHook.BOUND).run();
    assertEquals(1, requests.get());
    assertEquals(1, releases.get());
    assertEquals(0, survivorReads.get());
  }

  @Test
  void afterAnOrderedQuitItOnlyReleases() {
    CountDownLatch finished = new CountDownLatch(1);
    finished.countDown();
    ShutdownHook hook = hook(finished, true, ShutdownHook.BOUND);
    hook.run();
    hook.run();
    assertEquals(0, requests.get());
    assertEquals(2, releases.get());
  }

  @Test
  void aQuitThatDoesNotFinishIsBoundedAndNamesTheSurvivors() {
    CountDownLatch finished = new CountDownLatch(1);
    Duration bound = Duration.ofMillis(200);
    long start = System.nanoTime();
    hook(finished, false, bound).run();
    long waited = System.nanoTime() - start;
    assertTrue(waited >= bound.toNanos(), Long.toString(waited));
    assertTrue(waited < Duration.ofSeconds(10).toNanos(), Long.toString(waited));
    assertEquals(1, requests.get());
    assertEquals(1, survivorReads.get());
    assertEquals(1, releases.get());
  }
}
