package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.view.ResultLabel;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HyperSearch through the controller against a fake view, with searches on real workers: every
 * scope, the dock's rows, navigation into open and closed files with staleness, Next and Previous
 * Result, rerun keeping a finished set when stopped (QLogg APP-2), the retained limit with pins,
 * the dialog's seeding and the refused requests.
 */
class SearchControllerTest {
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

  @Test
  void theLineLimitSkipsALongerLineAtTheNextSearch() throws Exception {
    open("long.txt", "needle " + "x".repeat(20_000) + "\nneedle\n");
    Preferences current = harness.application.preferences();
    Options o = current.options();
    harness
        .application
        .settings()
        .set(
            current.withOptions(
                new Options(
                    o.completionChars(),
                    o.autoClose(),
                    o.maxHighlightLineChars(),
                    o.fontSize(),
                    o.pollSeconds(),
                    o.autoReloadClean(),
                    o.backupDays(),
                    Options.MIN_SEARCH_LINE_CHARS,
                    o.excludedDirectories(),
                    o.recoveryIntervalSeconds(),
                    o.recoveryKeepDays(),
                    o.sessionSnapshot(),
                    o.tabWidth(),
                    o.insertSpaces(),
                    Options.DEFAULTS.rightMargin(),
                    Options.DEFAULTS.dateFormat(),
                    Options.DEFAULTS.editorConfig(),
                    Options.DEFAULTS.modelines())));
    search.run(form("needle", SearchScope.CURRENT_DOCUMENT, ""));
    settle();
    assertEquals(1, search.sets().get(0).matches());
  }

  /** A dialog answer: literal, case-sensitive, no masks, hidden files skipped. */
  static SearchForm form(String text, SearchScope scope, String directory) {
    return new SearchForm(
        text, false, true, false, scope, directory, "", "", false, "", List.of(), List.of(),
        List.of());
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
    search.run(form(text, scope, directory));
    settle();
  }

  private List<String> files(int set) {
    List<String> names = new ArrayList<String>();
    for (int i = 0; i < search.fileCount(set); i++) {
      names.add(search.file(set, i).text());
    }
    return names;
  }

  private static Selection selected(EditorController editor) {
    return editor.selections().selections().primary();
  }

  @Test
  void aDocumentSearchFillsTheDockAndOpensAMatch() throws Exception {
    EditorController editor = open("doc.txt", TEXT);
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    assertTrue(harness.view.resultsShown);
    assertEquals(1, search.setCount());
    assertEquals(
        UiText.SEARCH_SET.format(
            "key", SearchScope.CURRENT_DOCUMENT.label(), "3 matches", "1 file"),
        search.set(0).text());
    assertEquals(3, search.matchCount(0, 0));
    ResultLabel row = search.match(0, 0, 1);
    assertEquals("3: key gamma key", row.text());
    assertEquals("key", row.text().substring(row.highlightStart(), row.highlightEnd()));
    search.open(0, 0, 2);
    assertEquals(Selection.of(25, 28), selected(editor));
    assertArrayEquals(new int[] {0, 0, 2}, harness.view.revealed);
  }

  @Test
  void anEditMovesAMatchAndAChangedMatchIsStale() throws Exception {
    EditorController editor = open("doc.txt", TEXT);
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    editor.selections().set(SelectionSet.caret(0));
    editor.type("new line\n");
    search.open(0, 0, 0);
    assertEquals(Selection.of(15, 18), selected(editor));
    assertEquals("2: alpha key", search.match(0, 0, 0).text());
    editor.selections().set(SelectionSet.of(List.of(Selection.of(25, 26)), 0));
    editor.type("K");
    assertTrue(search.match(0, 0, 1).stale());
    search.open(0, 0, 1);
    assertEquals(SelectionSet.caret(24).primary(), selected(editor));
    assertEquals(UiText.SEARCH_STALE.format(4), last(harness.view.notices));
  }

  /** The last message, without its title. */
  private String info() {
    String shown = last(harness.view.infos);
    return shown.substring(shown.indexOf(": ") + 2);
  }

  private static String last(List<String> list) {
    return list.get(list.size() - 1);
  }

  @Test
  void aTreeSearchReadsOpenBuffersAsEditedAndOpensClosedFiles() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Files.writeString(tree.resolve("disk.txt"), "x\nneedle\n");
    Files.createDirectories(tree.resolve("sub"));
    Files.writeString(tree.resolve("sub").resolve("other.txt"), "needle needle\n");
    harness.open(Files.writeString(tree.resolve("open.txt"), "nothing\n"));
    EditorController editor = harness.application.documents().activeEditor();
    editor.selections().set(SelectionSet.caret(0));
    editor.type("needle ");
    run("needle", SearchScope.TREE, tree.toString());
    List<String> names = files(0);
    assertEquals(3, names.size());
    assertTrue(names.get(0).startsWith("open.txt"), names.toString());
    assertTrue(names.get(1).startsWith("disk.txt"), names.toString());
    assertTrue(names.get(2).startsWith("other.txt"), names.toString());
    search.open(0, 1, 0);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == 2;
          }
        },
        "disk.txt opens");
    harness.awaitIdle("open");
    EditorController opened = harness.application.documents().activeEditor();
    assertEquals(Selection.of(2, 8), selected(opened));
    Files.writeString(tree.resolve("sub").resolve("other.txt"), "changed\nneedle needle\n");
    search.open(0, 2, 1);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == 3;
          }
        },
        "other.txt opens");
    harness.awaitIdle("open");
    assertTrue(search.file(0, 2).stale());
    assertEquals(UiText.SEARCH_STALE.format(1), last(harness.view.notices));
  }

  /**
   * A folder reached through a link (macOS {@code /var}, a Windows short name) holds the open
   * document under another path; the buffer is searched as edited and its file is not read again.
   */
  @Test
  void aTreeSearchThroughALinkReadsTheOpenBufferOnce() throws Exception {
    Path real = Files.createDirectories(temp.resolve("real")).toRealPath();
    Path link = temp.resolve("link");
    try {
      Files.createSymbolicLink(link, real);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links unavailable: " + e);
    }
    Files.writeString(real.resolve("disk.txt"), "needle\n");
    harness.open(Files.writeString(real.resolve("open.txt"), "needle\n"));
    EditorController editor = harness.application.documents().activeEditor();
    editor.selections().set(SelectionSet.caret(0));
    editor.type("needle ");
    run("needle", SearchScope.TREE, link.toString());
    List<String> names = files(0);
    assertEquals(2, names.size(), names.toString());
    assertTrue(names.get(0).startsWith("open.txt"), names.toString());
    assertEquals(2, search.matchCount(0, 0));
    assertEquals(real, search.sets().get(0).request().folder());
    harness.application.execute(CommandId.HYPERSEARCH);
    assertEquals(real.toString(), harness.view.searchForm.directoryHistory().get(0));
    harness.application.execute(CommandId.SET_WORKSPACE);
    harness.view.folderChooser.accept(link);
    awaitWorkspace(real);
  }

  private void awaitWorkspace(final Path root) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(harness.application.workspace().root());
          }
        },
        "workspace set to " + root);
  }

  @Test
  void theSelectionAndOpenDocumentScopes() throws Exception {
    open("one.txt", "key key key");
    EditorController two = open("two.txt", "key\nno\nkey");
    two.selections().set(SelectionSet.of(List.of(Selection.of(0, 6)), 0));
    run("key", SearchScope.SELECTION, "");
    assertEquals(1, search.matchCount(0, 0));
    run("key", SearchScope.OPEN_DOCUMENTS, "");
    assertEquals(2, search.setCount());
    assertEquals(List.of(3, 2), List.of(search.matchCount(0, 0), search.matchCount(0, 1)));
  }

  @Test
  void nextAndPreviousResultWrap() throws Exception {
    EditorController editor = open("doc.txt", TEXT);
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    int[] starts = {6, 15, 25};
    for (int i = 0; i < 4; i++) {
      harness.application.execute(CommandId.NEXT_RESULT);
      assertEquals(starts[i % 3], selected(editor).start());
    }
    harness.application.execute(CommandId.PREVIOUS_RESULT);
    assertEquals(25, selected(editor).start());
  }

  @Test
  void aStoppedRerunKeepsTheFinishedSetAndADoneOneReplacesIt() throws Exception {
    EditorController editor = open("doc.txt", TEXT);
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    long id = search.setId(0);
    editor.selections().set(SelectionSet.caret(0));
    editor.type("key ");
    search.rerun(0);
    search.stop(0);
    settle();
    assertEquals(3, search.sets().get(0).matches());
    assertEquals(UiText.SEARCH_RERUN_KEPT.text(), last(harness.view.notices));
    search.rerun(0);
    settle();
    assertEquals(4, search.sets().get(0).matches());
    assertEquals(id, search.setId(0));
    assertEquals(ResultSet.State.DONE, search.sets().get(0).state());
  }

  @Test
  void unpinnedSetsPastTheLimitAreDropped() throws Exception {
    open("doc.txt", TEXT);
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    search.togglePin(0);
    assertTrue(search.pinned(0));
    for (int i = 0; i < SearchController.RETAINED + 2; i++) {
      run("a", SearchScope.CURRENT_DOCUMENT, "");
    }
    assertEquals(SearchController.RETAINED + 1, search.setCount());
    assertTrue(search.pinned(search.setCount() - 1));
    search.clear(0);
    assertEquals(SearchController.RETAINED, search.setCount());
    search.clearAll();
    assertEquals(0, search.setCount());
  }

  @Test
  void theDialogTakesTheSelectionAndRemembersTheRun() throws Exception {
    EditorController editor = open("doc.txt", TEXT);
    editor.selections().set(SelectionSet.of(List.of(Selection.of(6, 9)), 0));
    harness.application.execute(CommandId.HYPERSEARCH);
    assertEquals("key", harness.view.searchForm.text());
    assertEquals(temp.toRealPath().toString(), harness.view.searchForm.directory());
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 15)), 0));
    harness.application.execute(CommandId.HYPERSEARCH);
    assertEquals(SearchScope.SELECTION, harness.view.searchForm.scope());
    harness.view.search.accept(form("beta", SearchScope.SELECTION, ""));
    settle();
    harness.application.execute(CommandId.HYPERSEARCH);
    assertEquals(List.of("beta"), harness.view.searchForm.textHistory());
  }

  @Test
  void refusedRequestsSayWhy() throws Exception {
    run("key", SearchScope.CURRENT_DOCUMENT, "");
    assertEquals(UiText.SEARCH_NO_DOCUMENT.text(), info());
    run("key", SearchScope.WORKSPACE, "");
    assertEquals(UiText.SEARCH_NO_WORKSPACE.text(), info());
    run("", SearchScope.OPEN_DOCUMENTS, "");
    assertEquals(UiText.SEARCH_EMPTY.text(), info());
    search.run(
        new SearchForm(
            "(",
            true,
            true,
            false,
            SearchScope.OPEN_DOCUMENTS,
            "",
            "",
            "",
            false,
            "",
            List.of(),
            List.of(),
            List.of()));
    assertTrue(info().startsWith("Invalid pattern"));
    search.run(
        new SearchForm(
            "a",
            false,
            true,
            false,
            SearchScope.TREE,
            temp.toString(),
            "[",
            "",
            false,
            "",
            List.of(),
            List.of(),
            List.of()));
    settle();
    assertTrue(info().startsWith("Invalid file mask"));
    run("key", SearchScope.TREE, "");
    assertEquals(UiText.SEARCH_NO_DIRECTORY.format(""), info());
    EditorController editor = open("doc.txt", TEXT);
    editor.selections().set(SelectionSet.caret(0));
    run("key", SearchScope.SELECTION, "");
    assertEquals(UiText.SEARCH_NO_SELECTION.text(), info());
    assertEquals(0, search.setCount());
    assertFalse(search.hasResults());
  }

  @Test
  void theWorkspaceFolderIsChosenAndSearched() throws Exception {
    Path folder = Files.createDirectories(temp.resolve("ws")).toRealPath();
    Files.writeString(folder.resolve("w.txt"), "key\n");
    harness.application.execute(CommandId.SET_WORKSPACE);
    harness.view.folderChooser.accept(folder);
    awaitWorkspace(folder);
    assertEquals(UiText.WORKSPACE_SET.format(folder), last(harness.view.notices));
    run("key", SearchScope.WORKSPACE, "");
    assertEquals(1, search.fileCount(0));
    assertTrue(search.set(0).text().contains(folder.toString()));
  }
}
