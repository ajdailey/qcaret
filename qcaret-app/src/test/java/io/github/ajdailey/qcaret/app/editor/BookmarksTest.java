package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Bookmarks follow the bookmarked line's text through edits and wrap around in navigation. */
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
  void twoBookmarksOnOneLineCountOnceAndToggleTogether() {
    Document document = document("a\nb\nc");
    Bookmarks bookmarks = new Bookmarks(document);
    bookmarks.toggle(0);
    bookmarks.toggle(1);
    document.apply(EditBatch.replace(1, 2, ""));
    assertArrayEquals(new int[] {0}, bookmarks.lines());
    bookmarks.toggle(0);
    assertArrayEquals(new int[0], bookmarks.lines());
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
