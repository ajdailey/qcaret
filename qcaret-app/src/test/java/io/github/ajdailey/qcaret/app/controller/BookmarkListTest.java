package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.BookmarkRow;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edit Bookmark Comment and the Bookmarks list through the controllers: the prompt, its bounds, the
 * list following edits, tab changes and close, go to into a collapsed region, edit and remove from
 * the list, and the row limit and memory charge.
 */
class BookmarkListTest {
  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;
  private BookmarkList list;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    documents = harness.application.documents();
    list = harness.application.bookmarkList();
    harness.open(Files.writeString(temp.resolve("a.txt"), "alpha\n  beta\n  gamma\ndelta\n"));
  }

  @AfterEach
  void shutDown() throws InterruptedException {
    documents.closeAll(null);
    while (harness.view.unsaved != null) {
      harness.view.unsaved.accept(UnsavedChoice.DISCARD);
      harness.view.unsaved = null;
      harness.ui.runPending();
    }
    harness.close();
  }

  private void caretAt(int line) {
    EditorController editor = documents.activeEditor();
    editor.selections().set(SelectionSet.caret(editor.document().text().offsetOfLine(line)));
  }

  /** Runs Edit Bookmark Comment on a line and answers the prompt. */
  private void comment(int line, String answer) {
    caretAt(line);
    harness.application.execute(CommandId.EDIT_BOOKMARK_COMMENT);
    assertEquals(line, harness.view.commentLine);
    harness.view.comment.accept(answer);
  }

  private void awaitRows(final String what, final int... lines) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            if (list.rowCount() != lines.length) {
              return false;
            }
            for (int i = 0; i < lines.length; i++) {
              if (list.row(i).line() != lines[i]) {
                return false;
              }
            }
            return true;
          }
        },
        what);
  }

  @Test
  void editBookmarkCommentBookmarksTheLineAndKeepsAnEmptyOne() {
    Bookmarks bookmarks = documents.bookmarksOf(documents.activeDocument());
    comment(1, "first\nsecond");
    assertEquals("first second", bookmarks.comment(1));
    assertEquals(Bookmarks.MAX_COMMENT_LENGTH, harness.view.commentLimit);
    caretAt(1);
    harness.application.execute(CommandId.EDIT_BOOKMARK_COMMENT);
    assertEquals("first second", harness.view.commentCurrent);
    harness.view.comment.accept("");
    assertTrue(bookmarks.contains(1));
    assertNull(bookmarks.comment(1));
    caretAt(2);
    harness.application.execute(CommandId.EDIT_BOOKMARK_COMMENT);
    harness.view.comment.accept(null);
    assertEquals(false, bookmarks.contains(2));
    comment(2, "x".repeat(Bookmarks.MAX_COMMENT_LENGTH + 1));
    assertEquals(false, bookmarks.contains(2));
    assertEquals(
        UiText.BOOKMARK_COMMENT_TOO_LONG.format(Bookmarks.MAX_COMMENT_LENGTH),
        harness.view.notices.get(harness.view.notices.size() - 1));
  }

  @Test
  void thePromptFollowsItsLineThroughAnEditWhileItShows() {
    Bookmarks bookmarks = documents.bookmarksOf(documents.activeDocument());
    caretAt(2);
    harness.application.execute(CommandId.EDIT_BOOKMARK_COMMENT);
    caretAt(0);
    documents.activeEditor().type("new\n");
    harness.view.comment.accept("moved");
    assertEquals("moved", bookmarks.comment(3));
  }

  @Test
  void theListFollowsEditsTabsAndClose() throws Exception {
    comment(1, "on beta");
    caretAt(3);
    harness.application.execute(CommandId.TOGGLE_BOOKMARK);
    harness.application.execute(CommandId.BOOKMARKS_LIST);
    assertTrue(harness.view.bookmarksShown);
    assertTrue(new CommandStates(harness.application).checked(CommandId.BOOKMARKS_LIST));
    awaitRows("rows", 1, 3);
    assertEquals(new BookmarkRow(1, "on beta", "  beta"), list.row(0));
    assertEquals(new BookmarkRow(3, "", "delta"), list.row(1));
    assertEquals("a.txt: 2 bookmarks", list.summary());
    assertTrue(list.charged() > 0);

    caretAt(0);
    documents.activeEditor().type("inserted\n");
    awaitRows("rows after an insert above", 2, 4);
    assertEquals("on beta", list.row(0).comment());
    harness.application.execute(CommandId.UNDO);
    awaitRows("rows after undo", 1, 3);

    harness.open(Files.writeString(temp.resolve("b.txt"), "one\ntwo\n"));
    awaitRows("the second tab's rows");
    assertEquals("b.txt: 0 bookmarks", list.summary());
    harness.view.tabs.get(0).select();
    awaitRows("the first tab's rows again", 1, 3);

    documents.closeActive();
    harness.ui.runPending();
    awaitRows("the remaining tab's rows");
    assertEquals("b.txt: 0 bookmarks", list.summary());
    documents.closeAll(null);
    harness.ui.runPending();
    assertEquals(UiText.BOOKMARKS_NO_DOCUMENT.text(), list.summary());
    assertEquals(0, list.charged());

    harness.application.execute(CommandId.BOOKMARKS_LIST);
    assertEquals(false, harness.view.bookmarksShown);
  }

  @Test
  void goToIntoACollapsedRegionExpandsItAndEditAndRemoveActOnTheRow() throws Exception {
    final EditorController editor = documents.activeEditor();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.projection().foldRegionCount() > 0;
          }
        },
        "fold regions");
    caretAt(2);
    harness.application.execute(CommandId.TOGGLE_BOOKMARK);
    editor.projection().foldAll();
    caretAt(0);
    assertTrue(editor.projection().collapsedCount() > 0);
    harness.application.execute(CommandId.BOOKMARKS_LIST);
    awaitRows("rows", 2);
    list.goTo(0);
    assertEquals(0, editor.projection().collapsedCount());
    assertEquals(
        editor.document().text().offsetOfLine(2),
        editor.selections().selections().primary().caret());

    list.editComment(0);
    assertEquals(2, harness.view.commentLine);
    harness.view.comment.accept("from the list");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return list.rowCount() == 1 && list.row(0).comment().equals("from the list");
          }
        },
        "edited row");
    list.remove(0);
    awaitRows("removed");
    assertEquals(0, documents.bookmarksOf(editor.document()).lines().length);
    list.hide();
    assertEquals(false, harness.view.bookmarksShown);
    assertEquals(0, list.charged());
  }

  @Test
  void rowsStopAtTheLimitAndAtABudgetRefusal() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i <= BookmarkList.MAX_ROWS; i++) {
      text.append("line ").append(i).append('\t').append("x".repeat(200)).append('\n');
    }
    Document document =
        Document.of(
            text.toString(),
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    Bookmarks bookmarks = new Bookmarks(document);
    for (int i = 0; i <= BookmarkList.MAX_ROWS; i++) {
      bookmarks.toggle(i);
    }
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    BookmarkList.Snapshot all =
        BookmarkList.rows(document.text(), 1, bookmarks.marks(), budget, new CancellationToken());
    assertEquals(BookmarkList.MAX_ROWS, all.rows().size());
    assertEquals(BookmarkList.MAX_ROWS + 1, all.total());
    BookmarkRow first = all.rows().get(0);
    assertEquals(BookmarkList.TEXT_CHARS, first.text().length());
    assertEquals("line 0 x", first.text().substring(0, 8));
    assertEquals(budget.reserved(), all.charged());
    budget.release(all.charged());

    long row = BookmarkList.ROW_BYTES + 2L * BookmarkList.TEXT_CHARS;
    MemoryBudget small = new MemoryBudget(100_000, 100);
    small.reserve(small.limit() - 3 * row);
    BookmarkList.Snapshot cut =
        BookmarkList.rows(document.text(), 1, bookmarks.marks(), small, new CancellationToken());
    assertEquals(3, cut.rows().size());
    assertEquals(small.limit(), small.reserved());
  }
}
