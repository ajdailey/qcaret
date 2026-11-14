package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * View > Memory Indicator: the toggle and its key, reads only while shown, and the click's garbage
 * collection on a worker. {@link ManualUiDispatcher} queues the refresh timer at once, so a shown
 * indicator reads again on every drain.
 */
class MemoryIndicatorTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final long MB = MemoryStatus.MB;

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final FakeView view = new FakeView(ui);
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final MockProbe probe = new MockProbe();
  private Workers workers;
  private Settings settings;
  private MemoryIndicator indicator;

  /**
   * Reports 300 MB used before a collection and 100 MB after; a collection waits for release, then
   * throws while {@code failCollect} is set.
   */
  private static final class MockProbe implements MemoryProbe {
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger collections = new AtomicInteger();
    final CountDownLatch release = new CountDownLatch(1);
    final List<String> collectThreads = new CopyOnWriteArrayList<String>();
    volatile boolean failCollect;

    @Override
    public MemoryStatus read() {
      reads.incrementAndGet();
      long used = collections.get() == 0 ? 300 * MB : 100 * MB;
      return new MemoryStatus(used, 1000 * MB, used / 2);
    }

    @Override
    public void collect() {
      collectThreads.add(Thread.currentThread().getName());
      try {
        assertTrue(release.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      if (failCollect) {
        throw new IllegalStateException("mock collection failure");
      }
      collections.incrementAndGet();
    }
  }

  @BeforeEach
  void create() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    ConfigStore config =
        new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf")));
    settings = new Settings(config, workers, ui, Preferences.DEFAULTS);
    indicator =
        new MemoryIndicator(settings, view, workers, ui, probe, new MemoryBudget(1000 * MB, 60));
    settings.addListener(
        new Settings.Listener() {
          @Override
          public void changed(Preferences before, Preferences after) {
            indicator.apply(before, after);
          }
        });
  }

  @AfterEach
  void close() {
    probe.release.countDown();
    indicator.stop();
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private void await(BooleanSupplier condition, String what) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND), what);
  }

  private void awaitReads(final int count) throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.memory.size() >= count;
          }
        },
        count + " reads shown");
  }

  /** Waits for the read in flight, if any, to report back. */
  private void awaitNoRead() throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !indicator.reading();
          }
        },
        "the last read reported back");
  }

  @Test
  void aHiddenIndicatorReadsNothingAndAShownOneReadsAgain() throws Exception {
    assertEquals(Boolean.FALSE, view.memoryShown);
    assertEquals(0, ui.runPending());
    assertEquals(0, probe.reads.get());
    indicator.toggle();
    assertEquals(Boolean.TRUE, view.memoryShown);
    awaitReads(3);
    assertEquals("150/1000 MB", view.memory.get(0).text());
    assertEquals(0.15, view.memory.get(0).fill(), 0.001);
    indicator.toggle();
    assertEquals(Boolean.FALSE, view.memoryShown);
    awaitNoRead();
    ui.runPending();
    int reads = probe.reads.get();
    int shown = view.memory.size();
    assertEquals(0, ui.runPending());
    assertTrue(ui.longTimerDelays().isEmpty());
    assertEquals(reads, probe.reads.get());
    assertEquals(shown, view.memory.size());
  }

  @Test
  void aClickCollectsOnAWorkerAndASecondClickWhileItRunsDoesNothing() throws Exception {
    indicator.toggle();
    awaitReads(1);
    indicator.collect();
    assertTrue(indicator.collecting());
    assertEquals(1, view.active.get(), "a collection shows the busy cursor");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !probe.collectThreads.isEmpty();
          }
        },
        "collection started");
    indicator.collect();
    probe.release.countDown();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !indicator.collecting();
          }
        },
        "collection reported");
    assertEquals(1, probe.collections.get());
    assertEquals(1, probe.collectThreads.size());
    assertTrue(probe.collectThreads.get(0).startsWith("qcaret-cpu-"), probe.collectThreads.get(0));
    assertEquals(List.of("Garbage collection freed 200 MB."), view.notices);
    assertEquals("50/1000 MB", view.memory.get(view.memory.size() - 1).text());
    assertEquals(0, view.active.get());
  }

  @Test
  void aFailedCollectionClearsTheBusyCursor() throws Exception {
    indicator.toggle();
    awaitReads(1);
    probe.failCollect = true;
    probe.release.countDown();
    indicator.collect();
    assertEquals(1, view.active.get());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !indicator.collecting() && failures.size() == 1;
          }
        },
        "failed collection reported");
    assertEquals(0, view.active.get());
    assertTrue(view.notices.isEmpty());
    failures.clear();
  }

  @Test
  void aHiddenIndicatorIgnoresAClick() throws Exception {
    indicator.collect();
    assertFalse(indicator.collecting());
    assertEquals(0, ui.runPending());
    assertTrue(probe.collectThreads.isEmpty());
  }

  @Test
  void theViewMenuToggleWritesTheKeyAndAppliesToTheWindow() throws Exception {
    Files.createDirectories(temp.resolve("settings"));
    Harness harness = new Harness(temp);
    try {
      CommandStates states = new CommandStates(harness.application);
      assertFalse(states.checked(CommandId.MEMORY_INDICATOR));
      assertTrue(states.enabled(CommandId.MEMORY_INDICATOR));
      assertEquals(Boolean.FALSE, harness.view.memoryShown);
      harness.application.execute(CommandId.MEMORY_INDICATOR);
      assertTrue(states.checked(CommandId.MEMORY_INDICATOR));
      assertEquals(Boolean.TRUE, harness.view.memoryShown);
      final ConfigStore conf =
          new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
      harness.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return saved(conf, true);
            }
          },
          "key written");
      harness.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return !harness.view.memory.isEmpty();
            }
          },
          "figures shown");
      assertTrue(harness.view.memory.get(0).text().endsWith(" MB"));
      harness.application.execute(CommandId.MEMORY_INDICATOR);
      assertEquals(Boolean.FALSE, harness.view.memoryShown);
      harness.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return saved(conf, false);
            }
          },
          "key cleared");
    } finally {
      harness.close();
    }
  }

  private static boolean saved(ConfigStore conf, boolean value) {
    return conf.load().state().memoryIndicator() == value;
  }
}
