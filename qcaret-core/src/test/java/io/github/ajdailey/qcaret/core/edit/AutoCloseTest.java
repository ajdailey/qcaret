package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.document.EditBatch;
import org.junit.jupiter.api.Test;

/** Auto-closed pairs, typed-over closers and pair delete at every caret. */
class AutoCloseTest {
  /** Types a char, applying the plain insert when auto-close returns null. */
  private static String type(String marked, char c) {
    Marked m = new Marked(marked);
    Edit edit = AutoClose.type(m.document.text(), m.model.selections(), c, Pairs.DEFAULT);
    if (edit == null) {
      return null;
    }
    if (edit.batch().replacements().isEmpty()) {
      m.model.set(edit.after());
    } else {
      m.apply(edit);
    }
    return m.marked();
  }

  private static String backspace(String marked) {
    Marked m = new Marked(marked);
    Edit edit = AutoClose.backspace(m.document.text(), m.model.selections(), Pairs.DEFAULT);
    return edit == null ? null : m.apply(edit).marked();
  }

  @Test
  void anOpenBracketInsertsItsPairBeforeABlankALineEndOrACloser() {
    assertEquals("f(|)", type("f|", '('));
    assertEquals("{|} x", type("| x", '{'));
    assertEquals("((|))", type("(|)", '('));
    assertEquals("a(|)\nb(|*)", type("a|\nb|*", '('));
  }

  @Test
  void anOpenBracketBeforeAWordTypesAlone() {
    assertNull(type("|word", '('));
  }

  @Test
  void aQuoteNeedsNoWordCharBeforeIt() {
    assertEquals("x = \"|\"", type("x = |", '"'));
    assertNull(type("don|", '\''));
    assertNull(type("''|", '\''));
  }

  @Test
  void aCloserBeforeTheSameCharMovesOver() {
    assertEquals("f()|", type("f(|)", ')'));
    assertEquals("\"a\"|", type("\"a|\"", '"'));
  }

  @Test
  void eachCaretDecidesAlone() {
    assertEquals("(|)\n(|*word", type("|\n|word*", '('));
  }

  @Test
  void aSelectionIsReplacedByTheChar() {
    assertEquals("(| (|*)", type("[ab] |*", '('));
    assertNull(type("([ab])", '('));
  }

  @Test
  void backspaceInsideAPairDeletesBoth() {
    assertEquals("f|", backspace("f(|)"));
    assertEquals("|\nab|*", backspace("{|}\nabc|*"));
    assertNull(backspace("(a|)"));
    assertNull(backspace("[(]|)"));
  }

  @Test
  void typingIsATypingBatchAndPairDeleteABackspaceBatch() {
    Marked m = new Marked("|");
    Edit typed = AutoClose.type(m.document.text(), m.model.selections(), '(', Pairs.DEFAULT);
    assertEquals(EditBatch.Kind.TYPING, typed.batch().kind());
    m.apply(typed);
    Edit deleted = AutoClose.backspace(m.document.text(), m.model.selections(), Pairs.DEFAULT);
    assertEquals(EditBatch.Kind.BACKSPACE, deleted.batch().kind());
    assertTrue(m.apply(deleted).document.text().length() == 0);
  }
}
