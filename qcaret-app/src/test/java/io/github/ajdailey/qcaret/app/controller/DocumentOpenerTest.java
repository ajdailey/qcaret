package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * QLogg APP-1: a load that finishes after its window closed never reaches the window and returns
 * its heap reservation; a load the budget cannot hold is refused unread.
 */
class DocumentOpenerTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private ManualUiDispatcher ui;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final List<Object> results = new ArrayList<Object>();
  private final List<Path> resolved = new ArrayList<Path>();
  private final List<Path> canceled = new ArrayList<Path>();
  private final List<Integer> progress = new ArrayList<Integer>();
  private int progressEnds;
  private final List<String> panes = new ArrayList<String>();
  private boolean alreadyOpen;
  private boolean resolveFails;
  private DocumentOpener opener;
  private final AtomicInteger active = new AtomicInteger();
  private final ActivityIndicator activity =
      new ActivityIndicator() {
        @Override
        public void setActive() {
          active.incrementAndGet();
        }

        @Override
        public void setIdle() {
          active.decrementAndGet();
        }
      };
  private MemoryBudget budget = new MemoryBudget(1L << 30, 50);
  private final DocumentOpener.Listener listener =
      new DocumentOpener.Listener() {
        @Override
        public boolean resolved(Path requested, Path path) {
          ui.checkUiThread();
          resolved.add(path);
          if (resolveFails) {
            throw new IllegalStateException("mock unchecked failure");
          }
          return alreadyOpen;
        }

        @Override
        public LoadOptions options(Path path, LoadOptions requested) {
          return requested;
        }

        @Override
        public void opened(
            Path path, LoadedFile file, DisplayProjection.Layout layout, long reserved) {
          ui.checkUiThread();
          results.add(file);
        }

        @Override
        public void tooLarge(Path path, LoadCost cost, long available, long maxHeap) {
          ui.checkUiThread();
          results.add(cost);
        }

        @Override
        public void failed(Path path, IOException error) {
          ui.checkUiThread();
          results.add(error);
        }

        @Override
        public void canceled(Path path) {
          ui.checkUiThread();
          canceled.add(path);
        }
      };

  @BeforeEach
  void setUp() {
    create();
  }

  private void create() {
    ui = new ManualUiDispatcher();
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    opener =
        new DocumentOpener(
            workers,
            ui,
            budget,
            activity,
            new DocumentOpener.Progress() {
              @Override
              public void progress(Path path, int files, int percent) {
                ui.checkUiThread();
                progress.add(percent);
              }

              @Override
              public void progressEnded() {
                ui.checkUiThread();
                progressEnds++;
              }

              @Override
              public void paneStarting(Path path, int lines) {
                ui.checkUiThread();
                panes.add(
                    "starting "
                        + path.getFileName()
                        + " lines "
                        + lines
                        + " results "
                        + results.size()
                        + " active "
                        + (active.get() > 0));
              }

              @Override
              public void paneBuilt() {
                ui.checkUiThread();
                panes.add("built results " + results.size());
              }
            });
  }

  @AfterEach
  void tearDown() {
    workers.close();
    assertNull(failure.get());
    assertEquals(0, active.get(), "a load left its activity running");
  }

  private BooleanSupplier idle() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return opener.running() == 0;
      }
    };
  }

  private BooleanSupplier resolved() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !resolved.isEmpty();
      }
    };
  }

  private BooleanSupplier results(final int count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return results.size() >= count;
      }
    };
  }

  private Path file(String text) throws IOException {
    Path file = temp.resolve("a.txt");
    Files.writeString(file, text);
    return file;
  }

  @Test
  void aLoadReachesTheListenerOnTheUiThread() throws Exception {
    opener.open(file("text\r\n"), LoadOptions.DEFAULTS, listener);
    opener.open(temp.resolve("missing").resolve("b.txt"), LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(results(2), BOUND));
    int loaded = results.get(0) instanceof LoadedFile ? 0 : 1;
    assertEquals("text\r\n", ((LoadedFile) results.get(loaded)).document().text().toString());
    assertTrue(results.get(1 - loaded) instanceof IOException);
    // The loaded document holds its retained cost: 6 ASCII chars plus as much undo headroom.
    assertEquals(12, budget.reserved());
  }

  private void assertStopped() {
    assertEquals(1, results.size());
    assertEquals(UiText.OPEN_STOPPED.text(), ((IOException) results.get(0)).getMessage());
    assertEquals(0, active.get());
  }

  @Test
  void anUncheckedFailureResolvingAPathReportsAFailedOpen() throws Exception {
    Path broken =
        (Path)
            Proxy.newProxyInstance(
                Path.class.getClassLoader(),
                new Class<?>[] {Path.class},
                new InvocationHandler() {
                  @Override
                  public Object invoke(Object proxy, Method method, Object[] args) {
                    throw new IllegalStateException("mock unchecked failure");
                  }
                });
    opener.open(broken, LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(results(1), BOUND));
    assertTrue(ui.runUntil(idle(), BOUND));
    // The worker's finally posts the result and ends the open, which sets the activity idle after
    // running() reads 0; the failure handler runs after that finally.
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return failure.get() != null;
              }
            },
            BOUND));
    assertTrue(failure.getAndSet(null) instanceof IllegalStateException);
    assertStopped();
  }

  @Test
  void anUncheckedFailureOnTheUiThreadReportsAFailedOpen() throws Exception {
    resolveFails = true;
    opener.open(file("mock\n"), LoadOptions.DEFAULTS, listener);
    assertThrows(
        IllegalStateException.class,
        new Executable() {
          @Override
          public void execute() throws Exception {
            ui.runUntil(results(1), BOUND);
          }
        });
    assertTrue(ui.runUntil(idle(), BOUND));
    assertStopped();
  }

  @Test
  void aCanceledOpenEndsWithTheCancelBeforeItsRead() throws Exception {
    Path file = file("mock\n");
    CancellationToken token = opener.open(file, LoadOptions.DEFAULTS, listener);
    token.cancel();
    assertTrue(ui.runUntil(idle(), BOUND));
    ui.runPending();
    assertEquals(1, canceled.size());
    assertTrue(resolved.isEmpty());
    assertTrue(results.isEmpty());
  }

  @Test
  void aCanceledOpenThatFailsToResolveReportsTheCancel() throws Exception {
    opener.open(temp, LoadOptions.DEFAULTS, listener).cancel();
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !canceled.isEmpty();
              }
            },
            BOUND));
    assertTrue(results.isEmpty());
  }

  @Test
  void aLoadOverTheBudgetIsRefusedUnread() throws Exception {
    workers.close();
    budget = new MemoryBudget(1000, 100);
    create();
    opener.open(file("x".repeat(600)), LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(results(1), BOUND));
    LoadCost cost = (LoadCost) results.get(0);
    assertEquals(600, cost.fileBytes());
    assertEquals(1200, cost.peakBytes());
    assertEquals(0, budget.reserved());
  }

  /** The estimate samples an ASCII head; the text after it costs twice that. */
  @Test
  void aLoadOutgrowingTheBudgetIsRefusedWhileReading() throws Exception {
    Path file = file("x".repeat(70_000) + ("\u4e2d" + "y".repeat(1000)).repeat(3000));
    long estimate = DocumentFiles.estimate(file, UndoLimits.DEFAULTS).reserveBytes();
    workers.close();
    budget = new MemoryBudget(estimate + 100, 100);
    create();
    opener.open(file, LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(results(1), BOUND));
    LoadCost cost = (LoadCost) results.get(0);
    assertTrue(cost.reserveBytes() > estimate);
    assertTrue(ui.runUntil(idle(), BOUND));
    assertEquals(0, budget.reserved());
  }

  @Test
  void aLoadHeldPastTheCloseDropsItsDocument() throws Exception {
    GatedCharset gate = new GatedCharset();
    gate.arm();
    opener.open(file("held"), LoadOptions.DEFAULTS.withForced(gate), listener);
    assertTrue(ui.runUntil(resolved(), BOUND));
    assertTrue(gate.awaitHeld());
    opener.close();
    gate.release();
    assertTrue(Poll.until(idle(), BOUND));
    ui.runPending();
    assertEquals(List.of(), results);
    assertEquals(0, budget.reserved());
  }

  private BooleanSupplier paneStarted() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !panes.isEmpty();
      }
    };
  }

  /** G1 L7: building the editor of a 3,000,001-line file held the UI thread for 963 ms. */
  @Test
  void aLargeDocumentShowsTheNoticeAndBusyStateATurnBeforeItOpens() throws Exception {
    opener.open(file("x\n".repeat(DocumentOpener.SLOW_PANE_LINES)), LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(paneStarted(), BOUND));
    assertEquals(
        List.of(
            "starting a.txt lines "
                + (DocumentOpener.SLOW_PANE_LINES + 1)
                + " results 0 active true"),
        panes);
    assertTrue(ui.runUntil(results(1), BOUND));
    assertTrue(Poll.until(idle(), BOUND));
    ui.runPending();
    assertEquals("built results 1", panes.get(1));
    assertEquals(2, panes.size());

    panes.clear();
    results.clear();
    opener.open(
        file("x\n".repeat(DocumentOpener.SLOW_PANE_LINES - 2)), LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(results(1), BOUND));
    assertEquals(List.of(), panes);
  }

  @Test
  void aCloseBetweenTheNoticeAndTheOpenDropsTheDocument() throws Exception {
    opener.open(file("x\n".repeat(DocumentOpener.SLOW_PANE_LINES)), LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(paneStarted(), BOUND));
    opener.close();
    assertTrue(Poll.until(idle(), BOUND));
    ui.runPending();
    assertEquals(List.of(), results);
    assertEquals("built results 0", panes.get(1));
    assertEquals(0, budget.reserved());
  }

  @Test
  void aResultPostedBeforeTheCloseIsDropped() throws Exception {
    opener.open(file("posted"), LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(resolved(), BOUND));
    assertTrue(Poll.until(idle(), BOUND));
    opener.close();
    ui.runPending();
    assertEquals(List.of(), results);
    assertEquals(0, budget.reserved());
    opener.open(file("after"), LoadOptions.DEFAULTS, listener);
    assertEquals(0, opener.running());
  }

  @Test
  void anOpenDocumentEndsTheOpenUnread() throws Exception {
    alreadyOpen = true;
    Path file = file("open");
    opener.open(file, LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(idle(), BOUND));
    assertEquals(List.of(file.toRealPath()), resolved);
    assertEquals(List.of(), results);
    assertEquals(0, budget.reserved());
  }

  @Test
  void aCloseEndsAnOpenWaitingForTheUiThread() throws Exception {
    opener.open(file("waiting"), LoadOptions.DEFAULTS, listener);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreadNames().isEmpty();
              }
            },
            BOUND));
    assertEquals(1, opener.running());
    opener.close();
    assertEquals(0, opener.running());
    ui.runPending();
    assertEquals(List.of(), resolved);
  }

  private BooleanSupplier shown() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !progress.isEmpty();
      }
    };
  }

  private BooleanSupplier canceled() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !canceled.isEmpty();
      }
    };
  }

  @Test
  void aCanceledLoadReportsOnlyTheCancelAndReleasesItsReservation() throws Exception {
    Path file = file("mock text\n");
    GatedCharset gate = new GatedCharset();
    gate.arm();
    opener.open(file, LoadOptions.DEFAULTS.withForced(gate), listener);
    assertTrue(ui.runUntil(shown(), BOUND));
    assertTrue(gate.awaitHeld());
    assertTrue(budget.reserved() > 0);
    opener.cancelLoads();
    assertEquals(1, progressEnds);
    gate.release();
    assertTrue(ui.runUntil(canceled(), BOUND));
    assertTrue(ui.runUntil(idle(), BOUND));
    ui.runPending();
    assertEquals(List.of(file.toRealPath()), canceled);
    assertTrue(results.isEmpty());
    assertEquals(0, budget.reserved());
    assertEquals(1, progressEnds);
  }

  @Test
  void aCancelAfterTheWorkerFinishedDropsTheDocument() throws Exception {
    Path file = file("mock text\n");
    opener.open(file, LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(resolved(), BOUND));
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return opener.running() == 0;
              }
            },
            BOUND));
    opener.cancelLoads();
    ui.runPending();
    assertTrue(results.isEmpty());
    assertEquals(List.of(file.toRealPath()), canceled);
    assertEquals(0, budget.reserved());
  }

  @Test
  void aLoadOverSeveralBlocksReportsRisingProgressUntilItEnds() throws Exception {
    Path file = temp.resolve("large.txt");
    Files.writeString(file, "mock text line\n".repeat(600_000));
    opener.open(file, LoadOptions.DEFAULTS, listener);
    assertTrue(ui.runUntil(results(1), BOUND));
    assertTrue(ui.runUntil(idle(), BOUND));
    ui.runPending();
    assertEquals(1, progressEnds);
    int last = -1;
    for (int percent : progress) {
      assertTrue(percent >= last, "progress " + progress);
      assertTrue(percent <= 100);
      last = percent;
    }
    LoadedFile loaded = (LoadedFile) results.get(0);
    budget.release(budget.reserved());
    assertEquals(9_000_000, loaded.document().text().length());
  }

  /**
   * FINDING-W7: 100 handed files queued 100 tab builds back to back, and a UI task posted behind
   * them waited 1.5 s on macOS. A task posted after every load finished runs before more than one
   * turn's share of documents opens.
   */
  @Test
  void aBurstOfLoadsLeavesOtherUiTasksOneTurnBehind() throws Exception {
    final int count = 3 * DocumentOpener.OPENED_PER_TURN + 1;
    for (int i = 0; i < count; i++) {
      Path file = Files.writeString(temp.resolve("f" + i + ".txt"), "mock " + i);
      opener.open(file, LoadOptions.DEFAULTS, listener);
    }
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return ui.queued() == count;
              }
            },
            BOUND));
    assertEquals(count, ui.runQueued());
    assertEquals(count, resolved.size());
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return active.get() == 0;
              }
            },
            BOUND));
    final List<Integer> before = new ArrayList<Integer>();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            before.add(results.size());
          }
        });
    assertTrue(ui.runUntil(results(count), BOUND));
    assertTrue(ui.runUntil(idle(), BOUND));
    ui.runPending();
    assertEquals(1, before.size());
    assertTrue(
        before.get(0) <= DocumentOpener.OPENED_PER_TURN,
        "documents opened before the later task: " + before.get(0));
    budget.release(budget.reserved());
  }
}
