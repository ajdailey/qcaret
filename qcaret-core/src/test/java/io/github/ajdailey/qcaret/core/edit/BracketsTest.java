package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Bracket lookup beside a caret and bounded partner search. */
class BracketsTest {
  private static final CancellationToken TOKEN = new CancellationToken();

  private static TextSnapshot text(String value) {
    return new Marked(value).document.text();
  }

  private static int match(TextSnapshot text, int at) {
    return Brackets.match(text, at, Pairs.DEFAULT, 0, text.length(), TOKEN);
  }

  @Test
  void theCharBeforeTheCaretComesFirst() {
    TextSnapshot text = text("a(b)c");
    assertEquals(1, Brackets.at(text, 2, Pairs.DEFAULT));
    assertEquals(1, Brackets.at(text, 1, Pairs.DEFAULT));
    assertEquals(3, Brackets.at(text, 4, Pairs.DEFAULT));
    assertEquals(-1, Brackets.at(text, 0, Pairs.DEFAULT));
    assertEquals(-1, Brackets.at(text("\"x\""), 1, Pairs.DEFAULT));
  }

  @Test
  void sameBracketsNestAndOthersAreIgnored() {
    TextSnapshot text = text("f(a(b)}{c)");
    assertEquals(9, match(text, 1));
    assertEquals(1, match(text, 9));
    assertEquals(5, match(text, 3));
  }

  @Test
  void anUnmatchedBracketHasNoPartner() {
    assertEquals(Brackets.NONE, match(text("((x)"), 0));
    assertEquals(Brackets.NONE, match(text("x}"), 1));
  }

  @Test
  void theBoundsStopTheSearchBeforeTheText() {
    TextSnapshot text = text("{ 0123456789 }");
    assertEquals(Brackets.BEYOND, Brackets.match(text, 0, Pairs.DEFAULT, 0, 6, TOKEN));
    assertEquals(Brackets.BEYOND, Brackets.match(text, 13, Pairs.DEFAULT, 6, 14, TOKEN));
    assertEquals(13, Brackets.match(text, 0, Pairs.DEFAULT, 0, 14, TOKEN));
  }

  @Test
  void aCanceledSearchThrowsOnALongText() {
    final TextSnapshot text = text("(" + " ".repeat(1 << 17));
    final CancellationToken canceled = new CancellationToken();
    canceled.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            Brackets.match(text, 0, Pairs.DEFAULT, 0, text.length(), canceled);
          }
        });
  }

  @Test
  void aFilterLeavesBracketsOutOfMatchingInBothDirections() {
    // The ")" at 3 stands for a bracket inside a string.
    TextSnapshot text = text("f(\")\")");
    Brackets.Filter filter =
        new Brackets.Filter() {
          @Override
          public boolean counts(int offset) {
            return offset != 3;
          }
        };
    assertEquals(5, Brackets.match(text, 1, Pairs.DEFAULT, 0, text.length(), TOKEN, filter));
    assertEquals(1, Brackets.match(text, 5, Pairs.DEFAULT, 0, text.length(), TOKEN, filter));
    assertEquals(3, match(text, 1));
    assertEquals(Brackets.NONE, match(text, 5));
    assertEquals(-1, Brackets.at(text, 4, Pairs.DEFAULT, filter));
    assertEquals(3, Brackets.at(text, 4, Pairs.DEFAULT));
  }
}
