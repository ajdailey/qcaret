package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import org.junit.jupiter.api.Test;

/** Modified-line marks from a line diff of the saved and current texts. */
class LineChangesTest {
  private static final int A = LineChanges.ADDED;
  private static final int C = LineChanges.CHANGED;
  private static final int R = LineChanges.REMOVED_ABOVE;
  private static final int B = LineChanges.REMOVED_BELOW;

  private static TextSnapshot text(String value) {
    return RopeStorage.of(value).snapshot();
  }

  private static int[] kinds(String saved, String current) {
    TextSnapshot now = text(current);
    LineChanges changes = LineChanges.compute(text(saved), now, 7, new CancellationToken());
    assertEquals(7, changes.revision());
    assertFalse(changes.overLimit());
    int[] out = new int[now.lineCount()];
    for (int i = 0; i < out.length; i++) {
      out[i] = changes.kind(i);
    }
    return out;
  }

  @Test
  void anInsertedLineIsAdded() {
    assertArrayEquals(new int[] {0, A, 0}, kinds("a\nc", "a\nb\nc"));
  }

  @Test
  void anEditedLineIsChangedAndExtraLinesAreAdded() {
    assertArrayEquals(new int[] {0, C, A, 0}, kinds("a\nb\nc", "a\nB\nB2\nc"));
  }

  @Test
  void removedLinesMarkTheLineAfterThemOrTheLastLine() {
    assertArrayEquals(new int[] {0, R}, kinds("a\nb\nc", "a\nc"));
    assertArrayEquals(new int[] {0, B}, kinds("a\nb\nc", "a\nb"));
    assertArrayEquals(new int[] {R}, kinds("a\nb", "b"));
  }

  @Test
  void equalTextsMarkNothing() {
    LineChanges same = LineChanges.compute(text("a\nb"), text("a\nb"), 1, new CancellationToken());
    assertFalse(same.any());
    assertEquals(0, same.kinds().length);
    assertEquals(0, same.kind(5));
  }

  @Test
  void aCrLfTextComparesByLineContent() {
    assertArrayEquals(new int[] {0, C, 0}, kinds("a\r\nb\r\nc", "a\r\nx\r\nc"));
  }

  @Test
  void aSideOverTheLineLimitIsNotCompared() {
    String big = "x\n".repeat(LineChanges.MAX_LINES);
    LineChanges changes = LineChanges.compute(text("x"), text(big), 3, new CancellationToken());
    assertTrue(changes.overLimit());
    assertFalse(changes.any());
    assertTrue(LineChanges.compute(text(big), text("x"), 3, new CancellationToken()).overLimit());
  }
}
