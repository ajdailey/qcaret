package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Wrap Paragraph at a column: carets wrap their paragraph, selections the touched lines. */
class ParagraphWrapTest {
  private static final CancellationToken TOKEN = new CancellationToken();

  private static Edit edit(Marked m, int column, String comment) {
    return ParagraphWrap.wrap(
        m.document.text(), m.model.selections(), column, comment, 4, "\n", TOKEN);
  }

  private static String wrap(String marked, int column, String comment) {
    Marked m = new Marked(marked);
    return m.apply(edit(m, column, comment)).marked();
  }

  @Test
  void wordsFillEachLineUpToTheColumnAndTheCaretEndsTheParagraph() {
    assertEquals("one two\nthree\nfour five|", wrap("one tw|o three four five", 9, null));
    assertEquals("one two three four five|", wrap("one\ntwo\nthree four|\nfive", 80, null));
  }

  @Test
  void aWordLongerThanTheColumnTakesALineOfItsOwn() {
    assertEquals("a\nverylongwordhere\nb|", wrap("a verylongwordhere b|", 5, null));
    assertEquals("x\ny|", wrap("x y|", 1, null));
  }

  @Test
  void everyLineTakesTheFirstLinesIndent() {
    assertEquals("  one two\n  three\n  four|", wrap("  one two three\n    four|", 10, null));
    assertEquals("\tone\n\ttwo|", wrap("\tone two|", 8, null));
  }

  @Test
  void aCommentParagraphKeepsTheCommentPrefixOnEveryLine() {
    assertEquals(
        "    // alpha\n    // beta\n    // gamma\n    // delta|",
        wrap("    // alpha beta\n    //gamma delta|", 16, "//"));
    assertEquals("# a b c|\nnot comment", wrap("# a\n# b c|\nnot comment", 80, "#"));
  }

  @Test
  void aCommentLineAndACodeLineAreSeparateParagraphs() {
    Marked m = new Marked("code a b\n// c d|");
    assertNull(edit(m, 80, "//"));
    assertEquals("code a b\n// c\n// d|", wrap("code a b\n// c d|", 4, "//"));
  }

  @Test
  void theParagraphsBreakIsKeptAndBlankLinesEndIt() {
    assertEquals(
        "one two\r\nthree\r\nfour|\r\n\r\nnext a b",
        wrap("one two\r\nthree four|\r\n\r\nnext a b", 9, null));
  }

  @Test
  void aSelectionWrapsEachParagraphOfItsLinesAndSpansThem() {
    assertEquals("[a b c\nd\n\ne f g\nh]", wrap("[a b\nc d\n\ne f\ng h]", 5, null));
    assertEquals("x y z\n[a b c\nd]\n\ne f\ng h", wrap("x y z\n[a b\nc d]\n\ne f\ng h", 5, null));
  }

  @Test
  void blankLinesAndWrappedTextChangeNothing() {
    assertNull(edit(new Marked("a\n|\nb"), 5, null));
    assertNull(edit(new Marked("[\n  \n]"), 5, null));
    assertNull(edit(new Marked("a b\nc|"), 3, null));
  }

  @Test
  void theEditIsOneCommandAndUndoRestoresTheText() {
    Marked m = new Marked("one two three|");
    Edit edit = edit(m, 4, null);
    assertEquals(EditBatch.Kind.COMMAND, edit.batch().kind());
    m.apply(edit);
    assertEquals("one\ntwo\nthree", m.document.text().toString());
    m.document.undo(m.model);
    assertEquals("one two three", m.document.text().toString());
  }

  @Test
  void aCanceledTokenStopsALongParagraph() {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 10_000; i++) {
      b.append("word\n");
    }
    final Marked m = new Marked(b + "|");
    final CancellationToken token = new CancellationToken();
    token.cancel();
    m.model.set(SelectionSet.caret(b.length() - 2));
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            ParagraphWrap.wrap(m.document.text(), m.model.selections(), 80, null, 4, "\n", token);
          }
        });
  }
}
