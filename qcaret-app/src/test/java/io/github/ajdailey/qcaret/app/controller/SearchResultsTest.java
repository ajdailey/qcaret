package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The results dock's labels and commands and the navigation through {@link SearchController}: a
 * running, rerunning, stopped, truncated, restored and pinned set's notes, the skipped counts, the
 * commands a running set refuses, Rerun File and opening a match after its document closed, and
 * Next and Previous Result after the navigated set is cleared.
 */
class SearchResultsTest {
  private static final String TEXT = "alpha key\nbeta\nkey gamma key\n";

  @TempDir Path temp;

  private Harness harness;
  private SearchController search;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    search = harness.application.search();
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private void settle() throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.running();
          }
        },
        "search ends");
    harness.ui.runPending();
  }

  private EditorController open(String name, String text) throws Exception {
    harness.open(Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8));
    return harness.application.documents().activeEditor();
  }

  private void run(String text, SearchScope scope, String directory) throws Exception {
    search.run(SearchControllerTest.form(text, scope, directory));
    settle();
  }

  private static String last(List<String> list) {
    return list.get(list.size() - 1);
  }

  /** The set label without notes. */
  private String plain(int set) {
    ResultSet r = search.sets().get(set);
    SearchRequest q = r.request();
    String scope = q.scope().label() + (q.folder() == null ? "" : " " + q.folder());
    return UiText.SEARCH_SET.format(
        q.text(),
        scope,
        UiText.count(r.matches(), UiText.COUNT_MATCH, UiText.COUNT_MATCHES),
        UiText.count(r.files().size(), UiText.COUNT_FILE, UiText.COUNT_FILES));
  }

  @Test
  void aRunningSetShowsItsProgressAndRefusesFileCommandsUntilStopped() throws Exception {
    open("doc.txt", TEXT);
    search.run(SearchControllerTest.form("key", SearchScope.CURRENT_DOCUMENT, ""));
    assertTrue(search.running(0));
    ResultSet r = search.sets().get(0);
    assertEquals(
        plain(0)
            + "; "
            + UiText.SEARCH_RUNNING.format(
                r.searched(), r.total() < 0 ? "?" : String.valueOf(r.total())),
        search.set(0).text());
    search.rerunFile(0, 0);
    assertEquals(UiText.SEARCH_NOT_FINISHED.text(), last(harness.view.notices));
    harness.view.notices.clear();
    search.replace(0);
    assertEquals(UiText.SEARCH_NOT_FINISHED.text(), last(harness.view.notices));
    search.stop(0);
    settle();
    assertFalse(search.running(0));
    assertEquals(ResultSet.State.CANCELED, search.sets().get(0).state());
    assertEquals(plain(0) + "; " + UiText.SEARCH_CANCELED.text(), search.set(0).text());
  }

  @Test
  void aRerunOfARunningSetStopsItAndShowsTheRerunUntilItEnds() throws Exception {
    open("doc.txt", TEXT);
    search.run(SearchControllerTest.form("key", SearchScope.CURRENT_DOCUMENT, ""));
    search.rerun(0);
    assertTrue(search.set(0).text().endsWith("; " + UiText.SEARCH_RERUNNING.text()));
    search.rerun(0);
    settle();
    assertEquals(ResultSet.State.DONE, search.sets().get(0).state());
    assertEquals(3, search.sets().get(0).matches());
    assertEquals(plain(0), search.set(0).text());
  }

  @Test
  void aTruncatedPinnedSetAndSkippedFilesShowTheirNotes() throws Exception {
    open("many.txt", "a".repeat(100_001));
    run("a", SearchScope.CURRENT_DOCUMENT, "");
    search.togglePin(0);
    assertEquals(
        plain(0)
            + "; "
            + UiText.SEARCH_TRUNCATED.format(search.sets().get(0).matches())
            + "; "
            + UiText.SEARCH_PINNED.text(),
        search.set(0).text());
    search.togglePin(0);
    assertFalse(search.pinned(0));
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Files.write(tree.resolve("bin.dat"), new byte[] {'a', 0, 1, 2, 0, 'a'});
    Files.writeString(tree.resolve("text.txt"), "a\n");
    run("a", SearchScope.TREE, tree.toString());
    assertEquals(
        plain(0) + "; " + UiText.SEARCH_SKIPPED.format(UiText.SEARCH_SKIPPED_BINARY.format(1)),
        search.set(0).text());
  }

  @Test
  void restoredSetsWaitForARerunAndTheDockHides() throws Exception {
    Path tree = temp.toRealPath();
    SearchRequest request =
        new SearchRequest(
            "key",
            false,
            true,
            false,
            SearchScope.TREE,
            tree,
            "",
            "",
            FileFilter.DEFAULT_EXCLUDED_DIRECTORIES,
            false);
    search.restore(List.of(new Session.Search(request, true)), true);
    assertTrue(harness.view.resultsShown);
    assertEquals(ResultSet.State.RESTORED, search.sets().get(0).state());
    assertEquals(
        plain(0) + "; " + UiText.SEARCH_RESTORED.text() + "; " + UiText.SEARCH_PINNED.text(),
        search.set(0).text());
    assertEquals(List.of(new Session.Search(request, true)), search.retained());
    search.replaceInResults();
    assertEquals(UiText.SEARCH_NO_RESULTS.text(), last(harness.view.notices));
    search.hide();
    assertFalse(harness.view.resultsShown);
    search.hide();
    assertFalse(search.resultsShown());
    search.toggleResults();
    assertTrue(search.resultsShown());
  }

  @Test
  void aClosedUntitledDocumentsMatchesReportTheCloseAndAClosedFileReopens() throws Exception {
    harness.application.documents().newDocument();
    EditorController untitled = harness.application.documents().activeEditor();
    untitled.type("key key");
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    harness.application.documents().closeActive();
    harness.view.unsaved.accept(UnsavedChoice.DISCARD);
    harness.awaitIdle("close");
    search.rerunFile(0, 0);
    assertEquals(UiText.SEARCH_CLOSED.text(), last(harness.view.notices));
    harness.view.notices.clear();
    search.open(0, 0, 0);
    assertEquals(UiText.SEARCH_CLOSED.text(), last(harness.view.notices));
    final EditorController editor = open("doc.txt", TEXT);
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    harness.application.documents().closeActive();
    harness.awaitIdle("close");
    final int before = harness.application.documents().count();
    search.open(0, 0, 2);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == before + 1;
          }
        },
        "doc.txt opens again");
    harness.awaitIdle("open");
    EditorController reopened = harness.application.documents().activeEditor();
    assertFalse(reopened == editor);
    assertEquals(Selection.of(25, 28), reopened.selections().selections().primary());
    assertArrayEquals(new int[] {6, 15, 25}, search.offsets(reopened.document()));
  }

  @Test
  void rerunFileOnAnOpenFileAndOnASelectionSet() throws Exception {
    EditorController editor = open("doc.txt", TEXT);
    Path tree = temp.toRealPath();
    run("key", SearchScope.TREE, tree.toString());
    assertEquals(3, search.matchCount(0, 0));
    editor.selections().set(SelectionSet.caret(0));
    editor.type("key ");
    search.rerunFile(0, 0);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return search.matchCount(0, 0) == 4;
          }
        },
        "the group searched again");
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 13)), 0));
    run("key", SearchScope.SELECTION, "");
    assertEquals(2, search.setCount());
    search.rerunFile(0, 0);
    settle();
    assertEquals(2, search.sets().get(0).matches());
  }

  @Test
  void nextAndPreviousStartOverAfterTheNavigatedSetIsCleared() throws Exception {
    EditorController editor = open("doc.txt", TEXT);
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    run("beta", SearchScope.CURRENT_DOCUMENT, "");
    search.open(1, 0, 1);
    assertEquals(15, editor.selections().selections().primary().start());
    search.replaceInResults();
    assertTrue(harness.view.replacePreview != null);
    search.clear(1);
    search.next(false);
    assertEquals(Selection.of(10, 14), editor.selections().selections().primary());
    search.clearAll();
    search.next(true);
    assertEquals(UiText.SEARCH_NO_RESULTS.text(), last(harness.view.notices));
    assertArrayEquals(new int[0], search.offsets(editor.document()));
  }
}
