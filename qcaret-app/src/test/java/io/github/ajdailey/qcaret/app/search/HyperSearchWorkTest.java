package io.github.ajdailey.qcaret.app.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import io.github.ajdailey.qcaret.core.search.TextSearch;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The result-memory charge and the bounds on a set, which hold on any runner: the charge against
 * the groups' own sizes, a long line charged by its snippet, the memory and match limits, and the
 * posts to the UI thread per search.
 */
class HyperSearchWorkTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** A group of one match of "needle": the snippet is the match alone. */
  private static final long NEEDLE_GROUP =
      FileResult.GROUP_BYTES + FileResult.MATCH_BYTES + 2L * "needle".length();

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final ManualUiDispatcher manual = new ManualUiDispatcher();
  private final AtomicInteger posts = new AtomicInteger();
  private final UiDispatcher ui =
      new UiDispatcher() {
        @Override
        public void asyncExec(Runnable task) {
          posts.incrementAndGet();
          manual.asyncExec(task);
        }

        @Override
        public void timerExec(int delayMillis, Runnable task) {
          manual.timerExec(delayMillis, task);
        }

        @Override
        public void cancelTimer(Runnable task) {
          manual.cancelTimer(task);
        }

        @Override
        public boolean isUiThread() {
          return manual.isUiThread();
        }
      };
  private Workers workers;

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
  }

  @AfterEach
  void close() {
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private void write(int files, String text) throws IOException {
    for (int i = 0; i < files; i++) {
      Files.writeString(temp.resolve("file" + i + ".txt"), text, StandardCharsets.UTF_8);
    }
  }

  private HyperSearch start(ResultSet set, int maxResults, MemoryBudget budget) {
    return HyperSearch.start(
        set,
        set.request().query().compile(),
        List.of(),
        new HyperSearch.Folder(temp, true, FileFilter.of("", "", "", false)),
        Set.of(),
        HyperSearch.Limits.withMaxResults(maxResults),
        workers,
        ui,
        budget,
        new HyperSearch.Listener() {
          @Override
          public void changed() {
            // The tests read the finished set.
          }

          @Override
          public void finished() {
            // The tests poll isFinished.
          }
        });
  }

  private ResultSet set() {
    return new ResultSet(
        1,
        new SearchRequest(
            "needle", false, true, false, SearchScope.DIRECTORY, temp, "", "", "", false),
        0);
  }

  private void await(final HyperSearch search) throws InterruptedException {
    assertTrue(
        manual.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return search.isFinished();
              }
            },
            BOUND));
  }

  /** Returns the charge of a set's groups, added up from what they hold. */
  private static long charge(ResultSet set) {
    long bytes = 0;
    for (FileResult group : set.files()) {
      bytes += FileResult.GROUP_BYTES;
      for (int i = 0; i < group.matchCount(); i++) {
        TextSearch.Hit hit = group.hit(i);
        bytes += FileResult.MATCH_BYTES + 2L * hit.snippet().length();
      }
    }
    return bytes;
  }

  @Test
  void theChargeCoversEachGroupAndReturnsOnRelease() throws Exception {
    write(5, "a needle here\nand a needle there\nnothing\n");
    MemoryBudget budget = new MemoryBudget(1_000_000, 100);
    ResultSet set = set();
    await(start(set, 100, budget));
    assertEquals(10, set.matches());
    long expected = 5L * FileResult.GROUP_BYTES;
    expected += 5L * FileResult.MATCH_BYTES * 2;
    expected += 5L * 2 * ("a needle here".length() + "and a needle there".length());
    assertEquals(expected, set.reserved());
    assertEquals(expected, budget.reserved());
    set.release();
    assertEquals(0, budget.reserved());
  }

  @Test
  void aLongLineIsChargedByItsSnippets() throws Exception {
    int matches = 50;
    write(1, ("needle" + "x".repeat(2000) + " ").repeat(matches));
    MemoryBudget budget = new MemoryBudget(1L << 30, 100);
    ResultSet set = set();
    await(start(set, 100, budget));
    assertEquals(matches, set.matches());
    long most =
        FileResult.GROUP_BYTES
            + (long) matches * (FileResult.MATCH_BYTES + 2L * TextSearch.SNIPPET_CHARS);
    assertTrue(set.reserved() <= most, "charged " + set.reserved());
  }

  @Test
  void theBudgetCapsTheGroupsAndStopsTheSearch() throws Exception {
    int kept = 10;
    int files = 200;
    write(files, "needle\n");
    // Room for the groups and four reads of 35 bytes, not for one more group.
    MemoryBudget budget = new MemoryBudget(3800, 100);
    assertEquals(3640, kept * NEEDLE_GROUP);
    ResultSet set = set();
    await(start(set, 1000, budget));
    assertEquals(ResultSet.State.TRUNCATED, set.state());
    assertTrue(set.noMemory());
    assertEquals(kept, set.files().size());
    assertEquals(kept * NEEDLE_GROUP, budget.reserved());
    assertTrue(set.searched() <= kept + FileSearch.READERS + 1, "searched " + set.searched());
    set.release();
    assertEquals(0, budget.reserved());
  }

  @Test
  void theMatchLimitChargesOnlyWhatIsKept() throws Exception {
    write(20, "needle needle needle\n");
    MemoryBudget budget = new MemoryBudget(1_000_000, 100);
    ResultSet set = set();
    await(start(set, 10, budget));
    assertEquals(ResultSet.State.TRUNCATED, set.state());
    assertEquals(10, set.matches());
    assertEquals(charge(set), set.reserved());
    assertEquals(set.reserved(), budget.reserved());
  }

  @Test
  void arrivalsPostToTheUiThreadOncePerFlush() throws Exception {
    int files = 300;
    write(files, "needle\n");
    final long all = files * NEEDLE_GROUP;
    final MemoryBudget budget = new MemoryBudget(1_000_000, 100);
    ResultSet set = set();
    HyperSearch search = start(set, 1000, budget);
    // Every read is released and every group charged, and nothing has run on the UI thread.
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return budget.reserved() == all;
              }
            },
            BOUND));
    assertTrue(posts.get() <= 2, "posts: " + posts.get());
    await(search);
    assertEquals(files, set.files().size());
  }
}
