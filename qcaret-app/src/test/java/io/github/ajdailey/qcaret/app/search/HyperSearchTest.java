package io.github.ajdailey.qcaret.app.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs searches into result sets on a manual UI queue: open documents from snapshots and a folder
 * from disk in one set, the match limit and a refused memory reservation ending it truncated, a
 * cancel ending it canceled with what arrived, a regex overflow recorded on its group, and a
 * document group following edits through the position map.
 */
class HyperSearchTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private Workers workers;
  private final List<String> events = new ArrayList<String>();

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

  private static Document document(String text) {
    return Document.of(
        text,
        TextEncoding.of(StandardCharsets.UTF_8, false),
        LoadOptions.DEFAULTS.empty(),
        LoadOptions.DEFAULTS.limits());
  }

  private static SearchRequest request(String text, SearchScope scope, Path folder) {
    return new SearchRequest(text, false, true, false, scope, folder, "", "", "", false);
  }

  private HyperSearch start(
      ResultSet set,
      List<HyperSearch.Source> sources,
      HyperSearch.Folder folder,
      HyperSearch.Limits limits,
      MemoryBudget budget) {
    return HyperSearch.start(
        set,
        set.request().query().compile(),
        sources,
        folder,
        Set.of(),
        limits,
        workers,
        ui,
        budget,
        new HyperSearch.Listener() {
          @Override
          public void changed() {
            events.add("changed");
          }

          @Override
          public void finished() {
            events.add("finished");
          }
        });
  }

  private void await(final HyperSearch search) throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return search.isFinished();
              }
            },
            BOUND));
  }

  @Test
  void documentsAndAFolderFillOneSetInOrder() throws Exception {
    Files.writeString(temp.resolve("b.txt"), "key\nkey\n");
    Files.writeString(temp.resolve("a.txt"), "no\nkey\n");
    Document second = document("x key");
    Document first = document("key key\nkey");
    ResultSet set = new ResultSet(1, request("key", SearchScope.TREE, temp), 0);
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    HyperSearch search =
        start(
            set,
            List.of(
                new HyperSearch.Source(first, null, "Untitled 1", null),
                new HyperSearch.Source(second, null, "Untitled 2", null)),
            new HyperSearch.Folder(temp, true, FileFilter.of("", "", "", false)),
            HyperSearch.Limits.withMaxResults(100),
            budget);
    await(search);
    assertEquals(ResultSet.State.DONE, set.state());
    List<String> names = new ArrayList<String>();
    for (FileResult f : set.files()) {
      names.add(f.name() + "=" + f.matchCount());
    }
    assertEquals(List.of("Untitled 1=3", "Untitled 2=1", "a.txt=1", "b.txt=2"), names);
    assertEquals(7, set.matches());
    assertEquals(4, set.searched());
    assertEquals(4, set.total());
    assertEquals("finished", events.get(events.size() - 1));
    assertTrue(budget.reserved() > 0);
    assertEquals(budget.reserved(), set.reserved());
    set.release();
    assertEquals(0, budget.reserved());
    FileResult a = set.files().get(2);
    assertEquals(1, a.line(0));
    assertTrue(a.stamp() != null && a.digest().length == 32);
  }

  @Test
  void theMatchLimitTruncatesTheSet() throws Exception {
    ResultSet set = new ResultSet(1, request("a", SearchScope.CURRENT_DOCUMENT, null), 0);
    HyperSearch search =
        start(
            set,
            List.of(new HyperSearch.Source(document("aaaaaaaaaa"), null, "d", null)),
            null,
            HyperSearch.Limits.withMaxResults(4),
            null);
    await(search);
    assertEquals(ResultSet.State.TRUNCATED, set.state());
    assertEquals(4, set.matches());
  }

  @Test
  void aRefusedReservationDropsTheGroupAndTruncates() throws Exception {
    MemoryBudget budget = new MemoryBudget(100, 100);
    ResultSet set = new ResultSet(1, request("a", SearchScope.CURRENT_DOCUMENT, null), 0);
    HyperSearch search =
        start(
            set,
            List.of(new HyperSearch.Source(document("a a a"), null, "d", null)),
            null,
            HyperSearch.Limits.withMaxResults(100),
            budget);
    await(search);
    assertEquals(ResultSet.State.TRUNCATED, set.state());
    assertTrue(set.noMemory());
    assertTrue(set.files().isEmpty());
    assertEquals(0, budget.reserved());
  }

  @Test
  void aCancelKeepsWhatArrived() throws Exception {
    ResultSet set = new ResultSet(1, request("a", SearchScope.CURRENT_DOCUMENT, null), 0);
    HyperSearch search =
        start(
            set,
            List.of(new HyperSearch.Source(document("a"), null, "d", null)),
            null,
            HyperSearch.Limits.withMaxResults(100),
            null);
    search.cancel();
    await(search);
    assertEquals(ResultSet.State.CANCELED, set.state());
  }

  @Test
  void aCancelLandsInsideACatastrophicMatch() throws Exception {
    SearchRequest request =
        new SearchRequest(
            "(.*a){25}x", true, true, false, SearchScope.CURRENT_DOCUMENT, null, "", "", "", false);
    ResultSet set = new ResultSet(1, request, 0);
    HyperSearch search =
        start(
            set,
            List.of(new HyperSearch.Source(document("a".repeat(60)), null, "d", null)),
            null,
            HyperSearch.Limits.withMaxResults(100),
            null);
    ui.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return false;
          }
        },
        Duration.ofMillis(300));
    search.cancel();
    await(search);
    assertEquals(ResultSet.State.CANCELED, set.state());
  }

  @Test
  void aRegexOverflowIsRecordedOnItsGroup() throws Exception {
    SearchRequest request =
        new SearchRequest(
            "(a|b)*c", true, true, false, SearchScope.CURRENT_DOCUMENT, null, "", "", "", false);
    ResultSet set = new ResultSet(1, request, 0);
    HyperSearch search =
        start(
            set,
            List.of(
                new HyperSearch.Source(document("ac\n" + "ab".repeat(200_000)), null, "d", null)),
            null,
            HyperSearch.Limits.withMaxResults(100),
            null);
    await(search);
    assertEquals(1, set.tooComplex());
    assertEquals(1, set.files().get(0).tooComplexLine());
    assertEquals(1, set.matches());
  }

  @Test
  void aDocumentGroupFollowsEditsAndMarksTheMatchesTheyTouch() throws Exception {
    Document d = document("one key two key three key");
    ResultSet set = new ResultSet(1, request("key", SearchScope.CURRENT_DOCUMENT, null), 0);
    await(
        start(
            set,
            List.of(new HyperSearch.Source(d, null, "d", null)),
            null,
            HyperSearch.Limits.withMaxResults(100),
            null));
    FileResult f = set.files().get(0);
    d.apply(EditBatch.replace(0, 0, "\n"));
    d.apply(EditBatch.replace(13, 1, "K"));
    assertTrue(f.rebase());
    assertEquals(5, f.start(0));
    assertEquals(1, f.line(0));
    assertFalse(f.stale(0));
    assertTrue(f.stale(1));
    assertFalse(f.stale(2));
    assertEquals("key", d.text().subSequence(f.start(2), f.end(2)).toString());
  }
}
