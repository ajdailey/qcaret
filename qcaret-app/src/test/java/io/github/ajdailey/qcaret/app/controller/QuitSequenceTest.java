package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WindowSet} close and quit: listeners, closed workers and a window close that ends last.
 */
class QuitSequenceTest {
  @TempDir Path temp;

  private Windows windows;
  private WindowSet set;
  private ApplicationController first;

  @BeforeEach
  void setUp() {
    windows = new Windows(temp, false);
    set = windows.set;
    first = windows.first();
  }

  @AfterEach
  void tearDown() throws Exception {
    windows.close();
  }

  @Test
  void aFinishListenerAddedAfterTheQuitFinishedRunsAtOnce() throws Exception {
    final AtomicInteger finished = new AtomicInteger();
    set.addFinishListener(finished::incrementAndGet);
    set.quit();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return finished.get() == 1;
          }
        },
        "finish");
    set.addFinishListener(finished::incrementAndGet);
    assertEquals(2, finished.get());
  }

  @Test
  void aQuitWithTheWorkersClosedRunsTheListenersAndDisposesOnTheCallingThread() {
    AtomicInteger quit = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();
    set.addQuitListener(quit::incrementAndGet);
    set.addFinishListener(finished::incrementAndGet);
    windows.workers.close();
    set.quit();
    assertEquals(1, quit.get());
    assertEquals(1, finished.get());
    assertTrue(windows.view(first).disposed);
  }

  @Test
  void aWindowWhoseCloseEndsAfterTheOtherClosedQuits() {
    ApplicationController second = windows.second();
    second.documents().newDocument();
    second.documents().activeEditor().type("text");
    second.closeWindow();
    assertNotNull(windows.view(second).unsaved);
    first.closeWindow();
    windows.ui.runPending();
    assertEquals(List.of(second), set.windows());
    assertFalse(set.isQuitting());
    windows.view(second).unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(set.isQuitting());
  }

  @Test
  void aWindowCloseAnsweredAfterTheQuitBeganClosesNothingMore() {
    ApplicationController second = windows.second();
    second.documents().newDocument();
    second.documents().activeEditor().type("text");
    second.closeWindow();
    assertNotNull(windows.view(second).unsaved);
    set.quit();
    windows.view(second).unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(set.isQuitting());
    assertTrue(set.windows().isEmpty());
  }
}
