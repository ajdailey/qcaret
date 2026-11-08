package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.document.EditBatch;
import org.junit.jupiter.api.Test;

/**
 * Delete Word Back and Forward at every caret, as one undo step, over CR LF, surrogate pairs and
 * combining marks.
 */
class WordDeletesTest {
  private static String back(String marked) {
    Marked m = new Marked(marked);
    return m.apply(WordDeletes.delete(m.document.text(), m.model.selections(), true)).marked();
  }

  private static String forward(String marked) {
    Marked m = new Marked(marked);
    return m.apply(WordDeletes.delete(m.document.text(), m.model.selections(), false)).marked();
  }

  @Test
  void backDeletesTheBlanksAndTheRunBeforeEachCaret() {
    assertEquals("foo |baz", back("foo bar  |baz"));
    assertEquals("a.|", back("a.bc|"));
    assertEquals("ab|", back("ab..|"));
    assertEquals("x\n|", back("x\n  |"));
    assertEquals("|", back("|"));
  }

  @Test
  void forwardDeletesTheRunAndTheBlanksAfterEachCaret() {
    assertEquals("foo |baz", forward("foo |bar  baz"));
    assertEquals("|.b", forward("|ab.b"));
    assertEquals("a|", forward("a|"));
  }

  @Test
  void aLineBreakGoesOnItsOwnAndCrLfAsOne() {
    assertEquals("ab|cd", back("ab\r\n|cd"));
    assertEquals("ab|cd", forward("ab|\r\ncd"));
    assertEquals("ab|  cd", forward("ab|\n  cd"));
  }

  @Test
  void aSelectionGoesWhole() {
    assertEquals("a| d", back("a[b c] d"));
    assertEquals("a| d", forward("a>b c< d"));
  }

  @Test
  void everyCaretDeletesInOneCommandStep() {
    Marked m = new Marked("one two| one two|");
    Edit edit = WordDeletes.delete(m.document.text(), m.model.selections(), true);
    assertEquals(EditBatch.Kind.COMMAND, edit.batch().kind());
    m.apply(edit);
    assertEquals("one | one |", m.marked());
    m.document.undo(m.model);
    assertEquals("one two| one two|", m.marked());
  }

  @Test
  void overlappingSpansDeleteOnce() {
    assertEquals("|", back("ab|cd|"));
    assertEquals("x |", forward("x |ab|c"));
  }

  @Test
  void surrogatePairsAndCombiningMarksStayWhole() {
    String bold = "\uD835\uDC00\uD835\uDC01";
    assertEquals("a |", back("a " + bold + "|"));
    assertEquals("|", forward("|" + bold));
    assertEquals("x |", back("x cafe\u0301|"));
    assertEquals("x |", back("x e\u0301\u0302|"));
    assertEquals("|. y", forward("|e\u0301\u0302. y"));
    assertEquals("a |", back("a \u0301|"));
  }

  @Test
  void nothingToDeleteReturnsNull() {
    Marked m = new Marked("|ab");
    assertNull(WordDeletes.delete(m.document.text(), m.model.selections(), true));
  }
}
