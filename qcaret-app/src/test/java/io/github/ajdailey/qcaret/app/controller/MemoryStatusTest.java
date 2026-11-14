package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shown figure for each collector's pools, the fallback, and the texts. */
class MemoryStatusTest {
  private static final long MB = MemoryStatus.MB;

  @Test
  void theOldGenerationGivesTheLargerOfItsCollectedAndCurrentUsage() {
    List<MemoryStatus.Pool> g1 =
        List.of(
            new MemoryStatus.Pool("G1 Eden Space", 90 * MB, 0),
            new MemoryStatus.Pool("G1 Old Gen", 40 * MB, 30 * MB),
            new MemoryStatus.Pool("G1 Survivor Space", 5 * MB, 5 * MB));
    assertEquals(40 * MB, MemoryStatus.of(135 * MB, 512 * MB, g1).kept());
    List<MemoryStatus.Pool> uncollected =
        List.of(
            new MemoryStatus.Pool("PS Eden Space", 60 * MB, 0),
            new MemoryStatus.Pool("PS Old Gen", 25 * MB, 0));
    assertEquals(25 * MB, MemoryStatus.of(85 * MB, 512 * MB, uncollected).kept());
    List<MemoryStatus.Pool> serial = List.of(new MemoryStatus.Pool("Tenured Gen", 8 * MB, 9 * MB));
    assertEquals(9 * MB, MemoryStatus.of(20 * MB, 512 * MB, serial).kept());
  }

  @Test
  void withoutAnOldGenerationThePoolsCollectionUsageCounts() {
    List<MemoryStatus.Pool> shenandoah =
        List.of(new MemoryStatus.Pool("Shenandoah", 110 * MB, 61 * MB));
    assertEquals(61 * MB, MemoryStatus.of(110 * MB, 512 * MB, shenandoah).kept());
    List<MemoryStatus.Pool> uncollected = List.of(new MemoryStatus.Pool("Shenandoah", 3 * MB, 0));
    assertEquals(3 * MB, MemoryStatus.of(3 * MB, 512 * MB, uncollected).kept());
  }

  @Test
  void withoutCollectionUsageTheLiveHeapShows() {
    List<MemoryStatus.Pool> none =
        List.of(
            new MemoryStatus.Pool("Old Space", 40 * MB, -1),
            new MemoryStatus.Pool("New Space", 10 * MB, -1));
    assertEquals(77 * MB, MemoryStatus.of(77 * MB, 512 * MB, none).kept());
    assertEquals(77 * MB, MemoryStatus.of(77 * MB, 512 * MB, List.of()).kept());
  }

  @Test
  void theTextShowsTheKeptAndMaximumHeapAndTheFillIsTheirShare() {
    MemoryStatus status = new MemoryStatus(300 * MB, 4096 * MB, 1024 * MB + MB / 2);
    assertEquals("1024/4096 MB", status.text());
    assertEquals(0.25, status.fill(), 0.001);
    assertEquals(1.0, new MemoryStatus(9 * MB, 8 * MB, 9 * MB).fill());
    assertEquals(0.0, new MemoryStatus(0, 0, 0).fill());
  }

  @Test
  void theTipNamesTheLiveHeapTheMaximumTheBudgetAndXmx() {
    MemoryBudget budget = new MemoryBudget(1000 * MB, 60);
    assertTrue(budget.reserve(100 * MB));
    MemoryStatus status = new MemoryStatus(300 * MB, 1000 * MB, 200 * MB);
    assertEquals(
        "Heap in use now: 300 MB\n"
            + "Maximum heap: 1000 MB\n"
            + "Open documents: 100 of 600 MB (60% of the maximum)\n"
            + "Start Java with -Xmx to raise the maximum; see Large files in the user guide.\n"
            + "Click to run a garbage collection.",
        status.tip(budget));
  }

  @Test
  void thisJvmReportsItsMaximumAndAShownFigure() {
    MemoryStatus status = MemoryProbe.SYSTEM.read();
    assertEquals(Runtime.getRuntime().maxMemory(), status.max());
    assertTrue(status.kept() > 0, status.toString());
    assertTrue(status.used() > 0, status.toString());
  }
}
