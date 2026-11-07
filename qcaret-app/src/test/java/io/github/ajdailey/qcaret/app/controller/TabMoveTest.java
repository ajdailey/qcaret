package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link WindowSet#moveDocument} and close and quit around a move. */
class TabMoveTest {
  private static final WindowSet.Placement END = new WindowSet.Placement(Integer.MAX_VALUE, 4, 5);

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

  private Path file(String name, String text) throws Exception {
    return Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
  }

  private Document open(ApplicationController window, String name, String text) throws Exception {
    windows.open(window, file(name, text));
    return window.documents().activeDocument();
  }

  @Test
  void theLiveDocumentMovesToANewWindowWithItsState() throws Exception {
    Document stays = open(first, "stays.txt", "stays\n");
    Document document = open(first, "moves.txt", "alpha\nbeta\ngamma\n");
    EditorController editor = first.documents().activeEditor();
    editor.selections().set(SelectionSet.caret(6));
    first.execute(CommandId.TOGGLE_BOOKMARK);
    editor.type("X");
    first.execute(CommandId.TAB_WIDTH_2);
    first.execute(CommandId.INDENT_WITH_SPACES);
    int[] bookmarks = first.documents().activeBookmarks();
    assertTrue(document.isDirty());
    Files.delete(temp.resolve("moves.txt"));
    int topRowBefore = 3;
    windows.view(first).selected.topRow = topRowBefore;

    ApplicationController target = set.moveDocument(first, document, null, END);

    assertNotNull(target);
    windows.ui.runPending();
    assertEquals(List.of(stays), first.documents().openDocuments());
    assertSame(stays, first.documents().activeDocument());
    assertEquals(List.of(document), target.documents().openDocuments());
    assertSame(document, target.documents().activeDocument());
    assertSame(editor, target.documents().activeEditor());
    assertTrue(document.isDirty());
    assertEquals("alpha\nXbeta\ngamma\n", document.text().toString());
    assertEquals(7, editor.selections().selections().primary().caret());
    assertEquals(bookmarks[0], target.documents().activeBookmarks()[0]);
    assertEquals(2, document.tabWidth());
    assertEquals("*moves.txt", windows.view(target).selected.title);
    assertEquals(topRowBefore, windows.view(target).selected.topRow);
    assertEquals(Integer.valueOf(4), windows.geometries.get(0).x());
    target.execute(CommandId.UNDO);
    assertEquals("alpha\nbeta\ngamma\n", document.text().toString());
    assertFalse(document.isDirty());
  }

  @Test
  void aMovedTabGoesToTheDropIndexAndItsWindowComesToTheFront() throws Exception {
    Document moved = open(first, "moved.txt", "moved\n");
    ApplicationController second = windows.second();
    Document a = open(second, "a.txt", "a\n");
    Document b = open(second, "b.txt", "b\n");
    int raised = windows.view(second).raised;
    assertSame(
        second, set.moveDocument(first, moved, second, new WindowSet.Placement(1, null, null)));
    assertEquals(List.of(a, moved, b), second.documents().openDocuments());
    assertEquals(1, second.documents().activeIndex());
    assertEquals(raised + 1, windows.view(second).raised);
    assertSame(second, set.target());
  }

  @Test
  void theLastTabMovesAndTheSourceStaysOpenAndEmpty() throws Exception {
    Document only = open(first, "only.txt", "only\n");
    ApplicationController target = set.moveDocument(first, only, null, END);
    assertNotNull(target);
    assertEquals(0, first.documents().count());
    assertEquals(2, set.windows().size());
    assertFalse(windows.view(first).disposed);
    assertNull(first.documents().activeEditor());
  }

  @Test
  void aSplitTabMovesWithBothViewsAndTheFocusedOne() throws Exception {
    Document document = open(first, "split.txt", "one\ntwo\n");
    first.documents().split(true);
    EditorController focused = first.documents().activeEditor();
    ApplicationController target = set.moveDocument(first, document, null, END);
    assertEquals(DocumentController.Split.VERTICAL, target.documents().activeSplit());
    assertEquals(2, target.documents().activeEditors().size());
    assertSame(focused, target.documents().activeEditor());
    assertEquals(1, windows.view(target).selected.splits.size());
    focused.type("Z");
    assertTrue(document.isDirty());
  }

  @Test
  void anEditAfterTheMoveRepaintsAndMarksTheNewTabDirtyOnly() throws Exception {
    Document document = open(first, "edit.txt", "edit\n");
    FakeView.FakeTab old = windows.view(first).selected;
    ApplicationController target = set.moveDocument(first, document, null, END);
    target.documents().activeEditor().type("Q");
    assertTrue(old.closed);
    assertEquals("*edit.txt", windows.view(target).selected.title);
    assertEquals("edit.txt", old.title);
  }

  @Test
  void aCopyTakesTheTargetClipboard() throws Exception {
    Document document = open(first, "copy.txt", "copy\n");
    ApplicationController target = set.moveDocument(first, document, null, END);
    EditorController editor = target.documents().activeEditor();
    editor
        .selections()
        .set(
            SelectionSet.of(
                List.of(io.github.ajdailey.qcaret.core.document.Selection.of(0, 4)), 0));
    target.execute(CommandId.COPY);
    assertEquals("copy", windows.view(target).clipboard);
    assertNull(windows.view(first).clipboard);
  }

  @Test
  void aSearchResultInTheSourceLeadsToTheMovedTab() throws Exception {
    Document document = open(first, "found.txt", "needle one\nneedle two\n");
    first.search().run(SearchControllerTest.form("needle", SearchScope.CURRENT_DOCUMENT, ""));
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !first.search().running();
          }
        },
        "search ends");
    windows.ui.runPending();
    ApplicationController target = set.moveDocument(first, document, null, END);
    int raised = windows.view(target).raised;
    first.search().open(0, 0, 1);
    assertEquals(raised + 1, windows.view(target).raised);
    assertEquals(
        "needle one\n".length(),
        target.documents().activeEditor().selections().selections().primary().start());
    target.documents().closeActive();
    first.search().open(0, 0, 1);
    windows.settle("reopen");
    assertEquals(1, first.documents().count());
  }

  @Test
  void aChangedFileReloadsInTheTargetAfterTheMove() throws Exception {
    final Document document = open(first, "watched.txt", "before\n");
    ApplicationController target = set.moveDocument(first, document, null, END);
    assertFalse(first.documents().watching() && first.documents().count() > 0);
    Files.writeString(temp.resolve("watched.txt"), "after, longer\n", StandardCharsets.UTF_8);
    target.documents().checkFiles();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().startsWith("after");
          }
        },
        "reloaded");
    windows.settle("reload");
    assertFalse(document.isDirty());
  }

  @Test
  void aSaveAfterTheMoveWritesAndCleans() throws Exception {
    Document document = open(first, "saved.txt", "text\n");
    ApplicationController target = set.moveDocument(first, document, null, END);
    target.documents().activeEditor().type("new ");
    target.execute(CommandId.SAVE);
    windows.settle("save");
    assertFalse(document.isDirty());
    assertEquals("new text\n", Files.readString(temp.resolve("saved.txt")));
    assertEquals(0, windows.view(first).active.get());
  }

  @Test
  void aSavingTabStaysWithANotice() throws Exception {
    Document document = open(first, "busy.txt", "text\n");
    first.documents().activeEditor().type("x");
    first.execute(CommandId.SAVE);
    assertNull(set.moveDocument(first, document, null, END));
    assertEquals(1, set.windows().size());
    assertEquals(1, first.documents().count());
    assertEquals(1, windows.view(first).notices.size());
    windows.settle("save");
    assertNotNull(set.moveDocument(first, document, null, END));
  }

  @Test
  void aMoveIntoAWindowHoldingTheFileIsRefused() throws Exception {
    final Path same = temp.resolve("same.txt");
    first.documents().newDocument();
    final Document left = first.documents().activeDocument();
    GatedCharset gate = new GatedCharset();
    left.setEncoding(TextEncoding.of(gate, false));
    gate.armEncoder();
    first.execute(CommandId.SAVE);
    windows.view(first).saveChooser.accept(same);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !first.documents().canMove(left);
          }
        },
        "the first save runs");
    assertTrue(gate.awaitHeld());
    final ApplicationController second = windows.second();
    second.documents().newDocument();
    second.execute(CommandId.SAVE);
    windows.view(second).saveChooser.accept(same);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.documents().activePath() != null;
          }
        },
        "the second save");
    gate.release();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.documents().activePath() != null;
          }
        },
        "the first save");
    windows.settle("save as");
    assertEquals(first.documents().pathOf(left), second.documents().activePath());
    assertNull(set.moveDocument(first, left, second, new WindowSet.Placement(0, null, null)));
    assertEquals(1, first.documents().count());
    assertEquals(1, windows.view(first).notices.size());
  }

  @Test
  void aMoveToItsOwnWindowOrAfterQuitDoesNothing() throws Exception {
    Document document = open(first, "own.txt", "own\n");
    assertNull(set.moveDocument(first, document, first, END));
    assertEquals(1, first.documents().count());
    set.quit();
    assertNull(set.moveDocument(first, document, null, END));
    assertTrue(set.windows().isEmpty());
  }

  @Test
  void aPromptOfTheSourceAnsweredAfterTheMoveDropsOut() throws Exception {
    Document document = open(first, "prompt.txt", "one\ntwo\n");
    first.execute(CommandId.GO_TO_LINE);
    ApplicationController target = set.moveDocument(first, document, null, END);
    windows.view(first).goTo.accept("2");
    assertEquals(0, target.documents().activeEditor().selections().selections().primary().caret());
  }

  @Test
  void closingTheSourceAfterAMoveKeepsTheDocumentAndClosingTheTabReleasesItOnce() throws Exception {
    long free = windows.budget.available();
    Document document = open(first, "kept.txt", "kept\n");
    assertTrue(windows.budget.available() < free);
    ApplicationController target = set.moveDocument(first, document, null, END);
    first.closeWindow();
    windows.ui.runPending();
    assertEquals(List.of(target), set.windows());
    assertEquals(List.of(document), target.documents().openDocuments());
    assertEquals("kept\n", document.text().toString());
    target.documents().closeActive();
    assertEquals(free, windows.budget.available());
  }

  @Test
  void aTabMovedDuringAQuitPromptIsAskedAboutInItsNewWindow() throws Exception {
    first.documents().newDocument();
    first.documents().activeEditor().type("a");
    set.newWindow();
    ApplicationController other = windows.controllers.get(1);
    other.documents().activeEditor().type("b");
    Document dirty = other.documents().activeDocument();
    first.windowActivated();
    first.execute(CommandId.QUIT);
    assertNotNull(windows.view(first).unsaved);
    assertNotNull(set.moveDocument(other, dirty, first, new WindowSet.Placement(0, null, null)));
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertFalse(set.isQuitting());
    assertEquals(2, first.documents().count());
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(set.isQuitting());
  }

  @Test
  void quitAfterMovesClosesEachDocumentOnce() throws Exception {
    long free = windows.budget.available();
    Document one = open(first, "one.txt", "one\n");
    Document two = open(first, "two.txt", "two\n");
    ApplicationController second = set.moveDocument(first, one, null, END);
    set.moveDocument(first, two, second, END);
    set.moveDocument(second, one, first, END);
    assertEquals(1, first.documents().count());
    assertEquals(1, second.documents().count());
    set.quit();
    assertEquals(free, windows.budget.available());
  }

  @Test
  void aMoveRunsTheMoveListenersOnce() throws Exception {
    final AtomicInteger moves = new AtomicInteger();
    set.addMoveListener(moves::incrementAndGet);
    Document document = open(first, "m.txt", "m\n");
    set.moveDocument(first, document, null, END);
    assertEquals(1, moves.get());
    set.moveDocument(first, document, null, END);
    assertEquals(1, moves.get());
  }

  @Test
  void aLineTransformRunningDuringAMoveDeliversToTheMovedDocument() throws Exception {
    Document document = open(first, "sort.txt", "c\na\nb\n");
    EditorController editor = first.documents().activeEditor();
    first.execute(CommandId.SORT_LINES_ASCENDING);

    ApplicationController target = set.moveDocument(first, document, null, END);

    assertNotNull(target);
    windows.await(textIs(document, "a\nb\nc\n"), "the sort reached the moved document");
    assertSame(editor, target.documents().activeEditor());
    assertTrue(document.isDirty());
    assertEquals("*sort.txt", windows.view(target).selected.title);
    target.execute(CommandId.UNDO);
    assertEquals("c\na\nb\n", document.text().toString());
  }

  @Test
  void aTrimRunningDuringAMoveToAnExistingWindowDeliversThere() throws Exception {
    Document document = open(first, "trim.txt", "one  \ntwo\t\n");
    ApplicationController second = windows.second();
    first.execute(CommandId.TRIM_TRAILING_WHITESPACE);

    assertSame(second, set.moveDocument(first, document, second, END));

    windows.await(textIs(document, "one\ntwo\n"), "the trim reached the moved document");
    assertEquals(List.of(document), second.documents().openDocuments());
  }

  @Test
  void aLineTransformStaleAfterAMoveEditIsDroppedWithANoticeInTheTargetWindow() throws Exception {
    Document document = open(first, "stale.txt", "b\na\n");
    first.execute(CommandId.SORT_LINES_ASCENDING);
    ApplicationController target = set.moveDocument(first, document, null, END);
    target.documents().activeEditor().type("x");
    String typed = document.text().toString();

    FakeView view = windows.view(target);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.notices.contains(UiText.LINES_STALE.text());
          }
        },
        "the stale notice");
    assertEquals(typed, document.text().toString());
  }

  @Test
  void aReplaceRunDuringAMoveDeliversToTheMovedDocumentAsOneUndoStep() throws Exception {
    Document document = open(first, "replace.txt", "key one\nkey two\n");
    ReportInput report = applyReplace("key", "lock");

    ApplicationController target = set.moveDocument(first, document, null, END);

    assertNotNull(target);
    awaitReport(report);
    assertEquals("lock one\nlock two\n", document.text().toString());
    assertEquals(UiText.REPLACE_ROW_CHANGED.format(2), report.row(0).result());
    target.execute(CommandId.UNDO);
    assertEquals("key one\nkey two\n", document.text().toString());
  }

  @Test
  void aReplaceRunSkipsADocumentEditedAfterItsMove() throws Exception {
    Document document = open(first, "edited.txt", "key one\n");
    ReportInput report = applyReplace("key", "lock");
    ApplicationController target = set.moveDocument(first, document, null, END);
    target.documents().activeEditor().type("x");
    String typed = document.text().toString();

    awaitReport(report);

    assertEquals(typed, document.text().toString());
    assertEquals(
        UiText.REPLACE_ROW_SKIPPED.format(UiText.REASON_CHANGED.text()), report.row(0).result());
  }

  /** Runs a search over the open documents and applies a replacement; nothing delivers yet. */
  private ReportInput applyReplace(String text, String with) throws Exception {
    final SearchController search = first.search();
    search.run(
        new SearchForm(
            text,
            false,
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
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.running();
          }
        },
        "search ends");
    windows.ui.runPending();
    search.replace(0);
    final FakeView view = windows.view(first);
    final ReplaceInput input = view.replacePreview;
    input.setReplacement(with);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return input.preview() != null;
          }
        },
        "preview computed");
    assertTrue(input.apply());
    return view.reports.get(view.reports.size() - 1);
  }

  private void awaitReport(final ReportInput report) throws Exception {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !report.running();
          }
        },
        "report ends");
    windows.ui.runPending();
  }

  private static BooleanSupplier textIs(final Document document, final String text) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return text.equals(document.text().toString());
      }
    };
  }

  @Test
  void encodingLineEndingsAndFoldsMoveWithTheTab() throws Exception {
    Path file = temp.resolve("state.txt");
    Files.write(
        file, "caf\u00e9\r\n  two\r\n  three\r\nfour\r\n".getBytes(StandardCharsets.ISO_8859_1));
    final int before = first.documents().count();
    first.documents().open(new FileRequest(file, 0, 0, false, "ISO-8859-1"));
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.documents().count() == before + 1;
          }
        },
        "opened");
    windows.settle("open");
    Document document = first.documents().activeDocument();
    final EditorController editor = first.documents().activeEditor();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.projection().foldRegionCount() > 0;
          }
        },
        "the fold regions");
    editor.execute(CommandId.FOLD);
    assertArrayEquals(new int[] {0}, editor.projection().collapsedLines());

    ApplicationController target = set.moveDocument(first, document, null, END);

    assertNotNull(target);
    windows.ui.runPending();
    assertEquals(StandardCharsets.ISO_8859_1, document.encoding().charset());
    assertEquals(LineEnding.CRLF, document.lineEnding());
    assertArrayEquals(new int[] {0}, editor.projection().collapsedLines());
    assertEquals(
        "ISO-8859-1",
        target.documents().sessionTabs(false).get(0).encoding(),
        "the forced charset");
    target.documents().activeEditor().type("x");
    target.documents().save();
    windows.settle("save");
    assertEquals(
        "xcaf\u00e9\r\n  two\r\n  three\r\nfour\r\n",
        new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
  }

  @Test
  void theMenuCommandMovesTheSelectedTab() throws Exception {
    Document document = open(first, "menu.txt", "menu\n");
    first.execute(CommandId.MOVE_TO_NEW_WINDOW);
    assertEquals(2, set.windows().size());
    assertEquals(List.of(document), windows.controllers.get(1).documents().openDocuments());
  }

  @Test
  void theTabMenuMovesItsOwnTabNotTheSelectedOne() throws Exception {
    Document left = open(first, "left.txt", "left\n");
    open(first, "right.txt", "right\n");
    first.moveTabToNewWindow(windows.view(first).tabs.get(0).input, 7, 8);
    assertEquals(List.of(left), windows.controllers.get(1).documents().openDocuments());
    assertEquals(Integer.valueOf(8), windows.geometries.get(0).y());
  }

  @Test
  void aMovedDocumentClosedInItsNewWindowIsCollected() throws Exception {
    open(first, "other.txt", "mockword\n");
    final WeakReference<Document> reference =
        new WeakReference<Document>(open(first, "moved.txt", "mockword\n"));
    first.documents().activeEditor().type("mockw");
    first.documents().activeEditor().execute(CommandId.COMPLETE_WORD);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !windows.view(first).selected.shown.completions.isEmpty();
          }
        },
        "the completion");
    first.documents().activeEditor().execute(CommandId.UNDO);
    first.execute(CommandId.MOVE_TO_NEW_WINDOW);
    windows.controllers.get(1).documents().closeActive();
    windows.settle("the close");
    // A CPU task still running queues a UI task holding the closed document's editor.
    assertTrue(
        windows.ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                System.gc();
                return reference.get() == null;
              }
            },
            Duration.ofSeconds(5)),
        "the closed document is still reachable");
  }
}
