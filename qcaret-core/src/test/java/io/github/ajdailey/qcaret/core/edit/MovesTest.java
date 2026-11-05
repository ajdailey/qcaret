package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Char, word and line moves, word bounds and the occurrence search. */
class MovesTest {
  private static TextSnapshot text(String value) {
    return RopeStorage.of(value).snapshot();
  }

  @Test
  void charMovesTakeCrLfAndSurrogatePairsWhole() {
    TextSnapshot t = text("a\r\nb😀c");
    assertEquals(3, Moves.right(t, 1));
    assertEquals(1, Moves.left(t, 3));
    assertEquals(6, Moves.right(t, 4));
    assertEquals(4, Moves.left(t, 6));
    assertEquals(0, Moves.left(t, 0));
    assertEquals(t.length(), Moves.right(t, t.length()));
  }

  @Test
  void homeTogglesBetweenTheFirstNonBlankAndTheLineStart() {
    TextSnapshot t = text("x\n    code\n   \n");
    assertEquals(6, Moves.home(t, 9));
    assertEquals(2, Moves.home(t, 6));
    assertEquals(11, Moves.home(t, 13));
    assertEquals(10, Moves.end(t, 3));
  }

  @Test
  void wordMovesStopAtRunsAndLineBreaks() {
    TextSnapshot t = text("foo.bar  baz\r\nnext");
    assertEquals(3, Words.right(t, 0));
    assertEquals(4, Words.right(t, 3));
    assertEquals(9, Words.right(t, 4));
    assertEquals(12, Words.right(t, 9));
    assertEquals(14, Words.right(t, 12));
    assertEquals(12, Words.left(t, 14));
    assertEquals(9, Words.left(t, 12));
    assertEquals(4, Words.left(t, 9));
    assertEquals(0, Words.left(t, 3));
  }

  @Test
  void theWordAtAnOffsetPrefersTheOneAfterIt() {
    TextSnapshot t = text("ab cd_1 !");
    assertArrayEquals(new int[] {3, 7}, Words.at(t, 3));
    assertArrayEquals(new int[] {0, 2}, Words.at(t, 2));
    assertEquals(0, Words.at(t, 8).length);
  }

  @Test
  void theNextOccurrenceWrapsToTheStart() {
    TextSnapshot t = text("ab xab ab");
    CancellationToken token = new CancellationToken();
    assertEquals(4, Occurrences.next(t, "ab", 2, token));
    assertEquals(7, Occurrences.next(t, "ab", 5, token));
    assertEquals(0, Occurrences.next(t, "ab", 8, token));
    assertEquals(-1, Occurrences.next(t, "zz", 0, token));
  }

  @Test
  void aCanceledSearchStops() {
    final TextSnapshot t = text("a".repeat(1 << 17));
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            Occurrences.next(t, "b", 0, token);
          }
        });
  }
}
