package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Bookmarks and their comments follow the bookmarked line's text through edits, undo and redo, are
 * bounded, and wrap around in navigation.
 */
class BookmarksTest {
  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  @Test
  void aBreakInsertedAtTheLineStartMovesTheBookmarkWithTheText() {
    Document document = document("a\nb\nc");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.toggle(1);
    document.apply(EditBatch.replace(2, 0, "\n\n"));
    assertArrayEquals(new int[] {3}, bookmarks.lines());
    document.apply(EditBatch.replace(0, 4, ""));
    assertArrayEquals(new int[] {0}, bookmarks.lines());
  }

  @Test
  void joiningTwoBookmarkedLinesKeepsTheUpperAndUndoRestoresTheLower() {
    Document document = document("a\nb\nc");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.toggle(0);
    bookmarks.setComment(1, "lower");
    document.apply(EditBatch.replace(1, 2, ""));
    assertArrayEquals(new int[] {0}, bookmarks.lines());
    assertEquals(0, bookmarks.commentCount());
    document.undo(null);
    assertArrayEquals(new int[] {0, 1}, bookmarks.lines());
    assertEquals("lower", bookmarks.comment(1));
    bookmarks.toggle(0);
    assertArrayEquals(new int[] {1}, bookmarks.lines());
  }

  @Test
  void aCommentFollowsItsBookmarkAndGoesWithIt() {
    Document document = document("a\nb\nc\n");
    Bookmarks bookmarks = new Bookmarks(document);
    assertEquals(Bookmarks.Result.DONE, bookmarks.setComment(1, "  first\r\nsecond\tthird "));
    assertEquals("first second third", bookmarks.comment(1));
    document.apply(EditBatch.replace(0, 0, "x\ny\n"));
    assertArrayEquals(new int[] {3}, bookmarks.lines());
    assertArrayEquals(new int[] {3}, bookmarks.commentedLines());
    assertEquals("first second third", bookmarks.comment(3));
    assertEquals(null, bookmarks.comment(1));
    bookmarks.setComment(3, " ");
    assertArrayEquals(new int[] {3}, bookmarks.lines());
    assertEquals(0, bookmarks.commentCount());
    bookmarks.setComment(3, "again");
    bookmarks.toggle(3);
    assertArrayEquals(new int[0], bookmarks.lines());
    assertEquals(0, bookmarks.commentCount());
    bookmarks.setComment(0, "cleared");
    bookmarks.clear();
    assertEquals(0, bookmarks.commentCount());
    assertEquals(null, bookmarks.comment(0));
  }

  @Test
  void deletingTheLineRemovesTheCommentAndUndoAndRedoFollow() {
    Document document = document("a\nb\nc\n");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.setComment(1, "on b");
    final AtomicInteger heard = new AtomicInteger();
    bookmarks.addListener(
        new Runnable() {
          @Override
          public void run() {
            heard.incrementAndGet();
          }
        });
    document.apply(EditBatch.replace(2, 2, ""));
    assertArrayEquals(new int[0], bookmarks.lines());
    assertEquals(0, bookmarks.commentCount());
    assertEquals(1, heard.get());
    document.undo(null);
    assertArrayEquals(new int[] {1}, bookmarks.lines());
    assertEquals("on b", bookmarks.comment(1));
    document.redo(null);
    assertArrayEquals(new int[0], bookmarks.lines());
    document.undo(null);
    assertEquals("on b", bookmarks.comment(1));
    assertEquals(4, heard.get());
  }

  @Test
  void theLastLineGoesWithTheBreakBeforeIt() {
    Document document = document("a\nb");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.setComment(1, "last");
    document.apply(EditBatch.replace(1, 2, ""));
    assertArrayEquals(new int[0], bookmarks.lines());
    document.undo(null);
    assertEquals("last", bookmarks.comment(1));
  }

  @Test
  void clearingALineKeepsItsBookmarkAndAJoinedLineCarriesIt() {
    Document document = document("abc\ndef\n");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.setComment(1, "def");
    document.apply(EditBatch.replace(4, 3, ""));
    assertEquals("def", bookmarks.comment(1));
    document.apply(EditBatch.replace(3, 1, ""));
    assertEquals("def", bookmarks.comment(0));
  }

  @Test
  void movedAndSortedLinesKeepTheirBookmarks() {
    Document document = document("c\na\nb\nend\n");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.setComment(0, "on c");
    bookmarks.toggle(2);
    document.apply(EditBatch.replace(0, 5, "a\nb\nc"));
    assertEquals("on c", bookmarks.comment(2));
    assertArrayEquals(new int[] {1, 2}, bookmarks.lines());
    document.undo(null);
    assertEquals("on c", bookmarks.comment(0));
    assertArrayEquals(new int[] {0, 2}, bookmarks.lines());
  }

  @Test
  void deletesCoalescedIntoOneStepRestoreTheLineOnUndo() {
    Document document = document("ab\nxy\ncd");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.setComment(1, "xy");
    for (int i = 0; i < 3; i++) {
      document.apply(EditBatch.of(EditBatch.Kind.DELETE, List.of(new Replacement(3, 1, ""))));
    }
    assertEquals("ab\ncd", document.text().toString());
    assertArrayEquals(new int[0], bookmarks.lines());
    document.undo(null);
    assertEquals("ab\nxy\ncd", document.text().toString());
    assertEquals("xy", bookmarks.comment(1));
  }

  @Test
  void oneEditRemovingTwoBookmarkedLinesRestoresBothOnUndo() {
    Document document = document("a\nb\nc\nd");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.setComment(1, "on b");
    bookmarks.setComment(2, "on c");
    document.apply(EditBatch.replace(2, 4, ""));
    assertArrayEquals(new int[0], bookmarks.lines());
    document.undo(null);
    assertArrayEquals(new int[] {1, 2}, bookmarks.lines());
    assertEquals("on b", bookmarks.comment(1));
    assertEquals("on c", bookmarks.comment(2));
  }

  @Test
  void anUndoPuttingBackOtherTextKeepsTheRemovedBookmarkForALaterUndo() {
    Document document = document("a\nb\nc");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.setComment(1, "on b");
    document.apply(EditBatch.replace(2, 2, ""));
    document.apply(EditBatch.replace(2, 0, "q\n"));
    document.apply(EditBatch.replace(2, 2, ""));
    document.undo(null);
    assertEquals("a\nq\nc", document.text().toString());
    assertArrayEquals(new int[0], bookmarks.lines());
    document.undo(null);
    document.undo(null);
    assertEquals("a\nb\nc", document.text().toString());
    assertEquals("on b", bookmarks.comment(1));
  }

  @Test
  void aRestoredBookmarkLeavesItsCommentOutAtTheCommentLimit() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i <= Bookmarks.MAX_COMMENTS; i++) {
      text.append(i).append('\n');
    }
    Document document = document(text.toString());
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.setComment(0, "removed");
    document.apply(EditBatch.replace(0, 2, ""));
    for (int i = 0; i < Bookmarks.MAX_COMMENTS; i++) {
      assertEquals(Bookmarks.Result.DONE, bookmarks.setComment(i, "c" + i));
    }
    document.undo(null);
    assertEquals(true, bookmarks.contains(0));
    assertEquals(null, bookmarks.comment(0));
    assertEquals(Bookmarks.MAX_COMMENTS, bookmarks.commentCount());
  }

  @Test
  void onlyTheNewestRemovingEditsCanRestoreTheirBookmarks() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i <= Bookmarks.MAX_REMOVED + 1; i++) {
      text.append(i).append('\n');
    }
    Document document = document(text.toString());
    Bookmarks bookmarks = new Bookmarks(document);
    for (int i = 0; i <= Bookmarks.MAX_REMOVED; i++) {
      bookmarks.toggle(0);
      document.apply(EditBatch.replace(0, document.text().offsetOfLine(1), ""));
    }
    assertArrayEquals(new int[0], bookmarks.lines());
    for (int i = 0; i <= Bookmarks.MAX_REMOVED; i++) {
      document.undo(null);
    }
    assertEquals(Bookmarks.MAX_REMOVED, bookmarks.lines().length);
    assertEquals(false, bookmarks.contains(0));
    assertEquals(true, bookmarks.contains(1));
  }

  @Test
  void clearDropsTheRemovedBookmarksAnUndoWouldRestore() {
    Document document = document("a\nb\nc");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.toggle(1);
    document.apply(EditBatch.replace(2, 2, ""));
    bookmarks.clear();
    document.undo(null);
    assertEquals("a\nb\nc", document.text().toString());
    assertArrayEquals(new int[0], bookmarks.lines());
  }

  @Test
  void commentsAreBoundedInLengthAndCount() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i <= Bookmarks.MAX_COMMENTS; i++) {
      text.append(i).append('\n');
    }
    Bookmarks bookmarks = new Bookmarks(document(text.toString()));
    assertEquals(
        Bookmarks.Result.TOO_LONG,
        bookmarks.setComment(0, "x".repeat(Bookmarks.MAX_COMMENT_LENGTH + 1)));
    assertArrayEquals(new int[0], bookmarks.lines());
    assertEquals(
        Bookmarks.Result.DONE, bookmarks.setComment(0, "x".repeat(Bookmarks.MAX_COMMENT_LENGTH)));
    for (int i = 1; i < Bookmarks.MAX_COMMENTS; i++) {
      assertEquals(Bookmarks.Result.DONE, bookmarks.setComment(i, "c" + i));
    }
    assertEquals(Bookmarks.Result.TOO_MANY, bookmarks.setComment(Bookmarks.MAX_COMMENTS, "more"));
    assertEquals(false, bookmarks.contains(Bookmarks.MAX_COMMENTS));
    assertEquals(Bookmarks.Result.DONE, bookmarks.setComment(5, "replaced"));
    assertEquals("replaced", bookmarks.comment(5));
    assertEquals(Bookmarks.Result.DONE, bookmarks.setComment(5, ""));
    assertEquals(Bookmarks.Result.DONE, bookmarks.setComment(Bookmarks.MAX_COMMENTS, "fits"));
    assertEquals(Bookmarks.MAX_COMMENTS, bookmarks.commentCount());
  }

  @Test
  void nextWrapsAroundAndListenersHearTogglesAndClear() {
    Bookmarks bookmarks = new Bookmarks(document("0\n1\n2\n3"));
    final AtomicInteger heard = new AtomicInteger();
    bookmarks.addListener(
        new Runnable() {
          @Override
          public void run() {
            heard.incrementAndGet();
          }
        });
    assertEquals(-1, bookmarks.next(0, true));
    bookmarks.toggle(1);
    bookmarks.toggle(3);
    assertEquals(3, bookmarks.next(1, true));
    assertEquals(1, bookmarks.next(3, true));
    assertEquals(3, bookmarks.next(1, false));
    assertEquals(1, bookmarks.next(2, false));
    bookmarks.clear();
    bookmarks.clear();
    assertEquals(3, heard.get());
    assertArrayEquals(new int[0], bookmarks.lines());
  }

  @Test
  void lineEndsNameTheBreakAfterALine() {
    Document document = document("a\r\nb\nc\rd");
    assertEquals(LineEnding.CRLF, LineEnds.of(document, 0));
    assertEquals(LineEnding.LF, LineEnds.of(document, 1));
    assertEquals(LineEnding.CR, LineEnds.of(document, 2));
    assertEquals(null, LineEnds.of(document, 3));
  }
}
