package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Session restore against the real stores: documents with their state, skipped files, remembered
 * encodings, the owner rule (IPC-8) and the order of concurrent saves (THR-5).
 */
class SessionControllerTest {
  @TempDir Path temp;

  private Path settings;
  private final List<Harness> open = new ArrayList<Harness>();

  @BeforeEach
  void create() throws IOException {
    settings = Files.createDirectories(temp.resolve("settings"));
  }

  @AfterEach
  void close() throws InterruptedException {
    for (Harness harness : open) {
      if (!harness.view.disposed) {
        harness.close();
      }
    }
  }

  private SessionStore store() {
    return new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
  }

  private Path file(String name, String text) throws IOException {
    return Files.writeString(temp.resolve(name), text).toRealPath();
  }

  /** Starts an application over the saved session. */
  private Harness start(boolean restore) throws InterruptedException {
    final Harness harness = new Harness(temp);
    open.add(harness);
    SessionStore store = store();
    harness.application.startSession(store, store.load(), restore);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return (harness.application.documents().count() > 0 || !restore)
                && !harness.application.sessionController().restoring();
          }
        },
        "the restore ended");
    harness.awaitIdle("restore");
    return harness;
  }

  /** Quits and fails unless the worker wrote what it should. */
  private void quit(Harness harness) throws InterruptedException {
    harness.close();
  }

  private static EditorController editor(Harness harness) {
    return harness.application.documents().activeEditor();
  }

  @Test
  void documentsRestoreInOrderWithTheirState() throws Exception {
    Path a = file("a.txt", "alpha\nbeta\ngamma\ndelta\n");
    Path b = file("b.txt", "one\ntwo\nthree\n");
    Harness first = start(true);
    first.application.documents().closeAll(null);
    first.open(a);
    first.open(b);
    DocumentController documents = first.application.documents();
    documents.setTabWidth(2);
    documents.toggleInsertSpaces();
    editor(first).selections().set(SelectionSet.of(List.of(Selection.of(4, 7)), 0));
    Bookmarks marks = documents.bookmarksOf(documents.activeDocument());
    marks.toggle(1);
    marks.toggle(2);
    marks.setComment(2, "mock comment");
    documents.split(true);
    first.view.tabs.get(1).weights = new int[] {30, 70};
    documents.select(0);
    first.ui.runPending();
    quit(first);

    Harness second = start(true);
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.application.documents().count() == 2;
          }
        },
        "both tabs");
    second.awaitIdle("restore");
    DocumentController restored = second.application.documents();
    assertEquals(List.of(a.toString(), b.toString()), restored.labels());
    assertEquals(0, restored.activeIndex());
    restored.select(1);
    second.ui.runPending();
    Document document = restored.activeDocument();
    assertEquals(2, document.tabWidth());
    assertTrue(document.insertSpaces());
    Bookmarks read = restored.bookmarksOf(document);
    assertEquals(2, read.lines().length);
    assertEquals("mock comment", read.comment(2));
    assertEquals(2, restored.activeEditors().size());
    assertEquals(DocumentController.Split.VERTICAL, restored.activeSplit());
    assertEquals(
        Selection.of(4, 7), restored.activeEditors().get(1).selections().selections().primary());
    assertEquals(30, second.view.tabs.get(1).weights[0]);
  }

  @Test
  void aMissingFileIsReportedAndSkipped() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Path b = file("b.txt", "beta\n");
    Harness first = start(true);
    first.application.documents().closeAll(null);
    first.open(a);
    first.open(b);
    quit(first);
    Files.delete(a);

    Harness second = start(true);
    assertEquals(List.of(b.toString()), second.application.documents().labels());
    assertTrue(String.join(" ", second.view.infos).contains("a.txt"), second.view.infos.toString());
  }

  @Test
  void anOpenCallbackMovesTheCaretAfterTheRememberedPosition() throws Exception {
    Path a = file("a.txt", "alpha\nbeta\ngamma\n");
    Harness harness = start(true);
    harness.open(a);
    editor(harness).moveCaret(8);
    harness.application.documents().closeActive();
    harness.awaitIdle("close");
    final boolean[] called = new boolean[1];
    harness
        .application
        .documents()
        .open(
            a,
            new java.util.function.Consumer<EditorController>() {
              @Override
              public void accept(EditorController editor) {
                editor.moveCaret(2);
                called[0] = true;
              }
            });
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return called[0];
          }
        },
        "opened");
    assertEquals(2, editor(harness).selections().selections().primary().caret());
  }

  @Test
  void aQuitDuringARestoreKeepsTheFile() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Path b = file("b.txt", "beta\n");
    Harness first = start(true);
    first.open(a);
    first.open(b);
    quit(first);
    byte[] saved = Files.readAllBytes(settings.resolve(SessionStore.FILE));

    Harness second = new Harness(temp);
    open.add(second);
    SessionStore store = store();
    second.application.startSession(store, store.load(), true);
    quit(second);
    assertEquals(
        new String(saved, StandardCharsets.ISO_8859_1),
        new String(
            Files.readAllBytes(settings.resolve(SessionStore.FILE)), StandardCharsets.ISO_8859_1));
  }

  private static void awaitRegions(Harness harness) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor(harness).projection().foldRegionCount() > 0;
          }
        },
        "the fold regions");
  }

  @Test
  void aCollapsedFoldReturnsThroughTheFile() throws Exception {
    Path a = file("a.txt", "one\n  two\n  three\nfour\n  five\n  six\nseven\n");
    Harness first = start(true);
    first.application.documents().closeAll(null);
    first.open(a);
    awaitRegions(first);
    editor(first).moveCaret(0);
    editor(first).execute(CommandId.FOLD);
    assertArrayEquals(new int[] {0}, editor(first).projection().collapsedLines());
    quit(first);
    assertEquals("0", store().load().windows().get(0).tabs().get(0).views().get(0).folds());

    Harness second = start(true);
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor(second).projection().collapsed(0);
          }
        },
        "the fold");
    assertArrayEquals(new int[] {0}, editor(second).projection().collapsedLines());
    assertEquals(6, editor(second).projection().rowCount());
  }

  @Test
  void anEditEndsAPendingFold() throws Exception {
    Path a = file("a.txt", "one\n  two\nfour\nfive\n");
    Harness first = start(true);
    first.application.documents().closeAll(null);
    first.open(a);
    awaitRegions(first);
    final EditorController editor = editor(first);
    editor.restoreView(null, 0, new int[] {2});
    editor.moveCaret(15);
    editor.type("  ");
    first.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.projection().foldRegionCount() == 2;
          }
        },
        "the new region");
    first.ui.runPending();
    assertArrayEquals(new int[0], editor.projection().collapsedLines());
  }

  @Test
  void anEmptySessionOpensAnUntitledDocument() throws Exception {
    Harness harness = start(true);
    assertEquals(1, harness.application.documents().count());
  }

  @Test
  void onlyTheInstanceThatRestoredSaves() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness owner = start(true);
    owner.application.documents().closeAll(null);
    owner.open(a);
    quit(owner);
    byte[] saved = Files.readAllBytes(settings.resolve(SessionStore.FILE));

    Harness multi = start(false);
    multi.open(file("b.txt", "beta\n"));
    multi.ui.runPending();
    quit(multi);
    assertEquals(
        new String(saved, StandardCharsets.ISO_8859_1),
        new String(
            Files.readAllBytes(settings.resolve(SessionStore.FILE)), StandardCharsets.ISO_8859_1));
  }

  @Test
  void recentFilesAndClosedTabsPersistAndAClosedFileKeepsItsPosition() throws Exception {
    Path a = file("a.txt", "alpha\nbeta\ngamma\n");
    Path b = file("b.txt", "one\n");
    Harness first = start(true);
    first.application.documents().closeAll(null);
    first.open(a);
    first.open(b);
    first.application.documents().select(0);
    first.ui.runPending();
    editor(first).moveCaret(8);
    first.application.documents().closeActive();
    first.awaitIdle("close");
    quit(first);

    Harness second = start(true);
    assertEquals(List.of(b, a), second.application.recentFiles().paths());
    second.application.execute(CommandId.REOPEN_CLOSED);
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.application.documents().count() == 2;
          }
        },
        "reopened");
    second.awaitIdle("reopen");
    assertEquals(8, editor(second).selections().selections().primary().caret());
  }

  private void writeTabWithEncoding(Path file, String encoding) throws IOException {
    Session.Tab tab =
        new Session.Tab(
            file.toString(),
            "",
            "",
            encoding,
            "",
            false,
            4,
            false,
            List.of(new Session.View("0,0", 0, "")),
            0,
            false,
            "",
            "",
            List.of(),
            false);
    Session.Window window =
        new Session.Window(
            io.github.ajdailey.qcaret.app.config.WindowState.UNKNOWN,
            List.of(tab),
            0,
            "",
            false,
            "",
            List.of(),
            false,
            "",
            List.of(),
            List.of(),
            Session.Display.DEFAULTS,
            "");
    assertTrue(store().save(new Session(List.of(window), List.of(), List.of())));
  }

  @Test
  void aRememberedEncodingTheRuntimeLacksDetects() throws Exception {
    Path a = file("a.txt", "mock line\n");
    writeTabWithEncoding(a, "x-mock-unknown");
    Harness harness = start(true);
    assertEquals(List.of(a.toString()), harness.application.documents().labels());
    assertEquals(
        StandardCharsets.UTF_8,
        harness.application.documents().activeDocument().encoding().charset());
  }

  @Test
  void aForcedEncodingApplies() throws Exception {
    Path a = temp.resolve("a.txt");
    Files.write(a, new byte[] {'a', (byte) 0xe9, '\n'});
    writeTabWithEncoding(a.toRealPath(), "ISO-8859-1");
    Harness harness = start(true);
    Document document = harness.application.documents().activeDocument();
    assertEquals(StandardCharsets.ISO_8859_1, document.encoding().charset());
    assertEquals("aé\n", document.text().toString());
  }

  @Test
  void aRememberedEncodingThatDoesNotDecodeDetects() throws Exception {
    Path a = temp.resolve("a.txt");
    Files.write(a, new byte[] {'a', (byte) 0xe9, '\n'});
    writeTabWithEncoding(a.toRealPath(), "UTF-8");
    Harness harness = start(true);
    assertEquals(1, harness.application.documents().count());
    assertFalse(
        StandardCharsets.UTF_8.equals(
            harness.application.documents().activeDocument().encoding().charset()));
  }

  @Test
  void retainedSearchesReturnWithoutResults() throws Exception {
    Path a = file("a.txt", "needle\n");
    Harness first = start(true);
    first.open(a);
    SearchRequest request =
        new SearchRequest(
            "needle", false, false, false, SearchScope.OPEN_DOCUMENTS, null, "", "", "", false);
    first.application.search().restore(List.of(new Session.Search(request, true)), true);
    first.ui.runPending();
    quit(first);

    Harness second = start(true);
    List<ResultSet> sets = second.application.search().sets();
    assertEquals(1, sets.size());
    assertEquals(ResultSet.State.RESTORED, sets.get(0).state());
    assertTrue(sets.get(0).pinned());
    assertTrue(second.application.search().resultsShown());
    second.application.search().rerun(0);
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.application.search().sets().get(0).state() == ResultSet.State.DONE;
          }
        },
        "the rerun ended");
    assertEquals(1, second.application.search().sets().get(0).matches());
  }

  @Test
  void theWorkspaceAndBrowserReturn() throws Exception {
    Path root = Files.createDirectories(temp.resolve("ws").resolve("sub")).getParent().toRealPath();
    Harness first = start(true);
    first.application.workspace().set(root);
    first.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(first.application.workspace().root());
          }
        },
        "the folder");
    first.application.execute(CommandId.WORKSPACE_BROWSER);
    first.view.sideWeights = new int[] {2, 5};
    first.view.resultsWeights = new int[] {1, 4};
    first.view.expanded.add(
        new io.github.ajdailey.qcaret.app.view.WorkspaceNode(
            root.resolve("sub"),
            "sub",
            io.github.ajdailey.qcaret.app.view.WorkspaceNode.Kind.DIRECTORY));
    first.awaitIdle("list");
    quit(first);

    Harness second = start(true);
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !second.view.expanded.isEmpty();
          }
        },
        "the row expanded");
    assertEquals(root, second.application.workspace().root());
    assertTrue(second.application.workspace().shown());
    assertEquals(2, second.view.sideWeights[0]);
    assertEquals(4, second.view.resultsWeights[1]);
    assertEquals(root.resolve("sub"), second.view.expanded.get(0).path());
  }

  private static Session sessionNamed(String name) {
    return new Session(List.of(), List.of(name), List.of());
  }

  @Test
  void aLaterCaptureWinsWhateverOrderTheWorkersRun() throws Exception {
    Harness harness = start(true);
    final SessionController session = harness.application.sessionController();
    for (int round = 0; round < 50; round++) {
      final SessionController.Capture debounced =
          new SessionController.Capture(2L * round + 1, sessionNamed("debounced" + round));
      final SessionController.Capture shutdown =
          new SessionController.Capture(2L * round + 2, sessionNamed("shutdown" + round));
      final CountDownLatch gate = new CountDownLatch(1);
      final CountDownLatch done = new CountDownLatch(2);
      for (final SessionController.Capture capture : List.of(shutdown, debounced)) {
        harness.workers.start(
            io.github.ajdailey.qcaret.core.concurrent.Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                try {
                  gate.await();
                  session.write(capture);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              }
            });
      }
      gate.countDown();
      done.await();
      assertEquals(List.of("shutdown" + round), store().load().recent());
    }
  }

  @Test
  void aStaleCaptureDoesNotOverwriteALaterOne() throws Exception {
    Harness harness = start(true);
    SessionController session = harness.application.sessionController();
    session.write(new SessionController.Capture(100, sessionNamed("later")));
    session.write(new SessionController.Capture(99, sessionNamed("stale")));
    assertEquals(List.of("later"), store().load().recent());
  }

  /** Opens three files of 800 commented lines each and quits, which leaves 1,400 out. */
  private void quitWithTooManyComments() throws Exception {
    Harness harness = start(true);
    harness.application.documents().closeAll(null);
    for (String name : List.of("a.txt", "b.txt", "c.txt")) {
      harness.open(file(name, "line\n".repeat(800)));
      DocumentController documents = harness.application.documents();
      Bookmarks bookmarks = documents.bookmarksOf(documents.activeDocument());
      for (int line = 0; line < 800; line++) {
        bookmarks.setComment(line, "mock");
      }
    }
    quit(harness);
  }

  private static int count(List<String> list, String value) {
    int count = 0;
    for (String item : list) {
      if (item.equals(value)) {
        count++;
      }
    }
    return count;
  }

  @Test
  void commentsPastTheCapShowOneNoticeAndTheSaveAfterItClearsTheCount() throws Exception {
    quitWithTooManyComments();
    assertEquals(1400, store().load().droppedComments());

    String notice = UiText.SESSION_COMMENTS_DROPPED.format(1400, SessionStore.MAX_COMMENTS);
    Harness second = start(true);
    assertEquals(1, count(second.view.notices, notice), second.view.notices.toString());
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return store().load().droppedComments() == 0;
          }
        },
        "the save after the notice");
    assertEquals(1, count(second.view.notices, notice), second.view.notices.toString());
    assertFalse(Files.readString(settings.resolve(SessionStore.FILE)).contains("droppedComments"));
    quit(second);

    Harness third = start(true);
    assertEquals(0, count(third.view.notices, notice), third.view.notices.toString());
  }

  @Test
  void aQuitBeforeTheSaveClearsTheCountToo() throws Exception {
    quitWithTooManyComments();
    quit(start(true));
    assertEquals(0, store().load().droppedComments());
  }

  @Test
  void anInstanceThatDoesNotRestoreLeavesTheCountForTheNextRestore() throws Exception {
    quitWithTooManyComments();
    Harness other = start(false);
    String notice = UiText.SESSION_COMMENTS_DROPPED.format(1400, SessionStore.MAX_COMMENTS);
    assertEquals(0, count(other.view.notices, notice), other.view.notices.toString());
    quit(other);
    assertEquals(1400, store().load().droppedComments());
  }

  @Test
  void aChangeSavesOnTheTimer() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness harness = start(true);
    harness.open(a);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !store().load().windows().isEmpty()
                && !store().load().windows().get(0).tabs().isEmpty();
          }
        },
        "the debounced save");
    assertEquals(a.toString(), store().load().windows().get(0).tabs().get(0).path());
  }
}
