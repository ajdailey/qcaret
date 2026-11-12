package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.document.EditBatch;
import org.junit.jupiter.api.Test;

/** Transpose at carets and across selections, over CR LF, surrogate pairs and combining marks. */
class TransposeTest {
  private static String transpose(String marked) {
    Marked m = new Marked(marked);
    return m.apply(Transpose.transpose(m.document.text(), m.model.selections())).marked();
  }

  @Test
  void aCaretSwapsItsNeighborsAndMovesPastThem() {
    assertEquals("acb|d", transpose("ab|cd"));
    assertEquals("acb|", transpose("abc|"));
    assertEquals("|ab", transpose("|ab"));
    assertEquals("a|", transpose("a|"));
  }

  @Test
  void noSwapCrossesALineBreak() {
    assertEquals("ba|\r\ncd", transpose("ab|\r\ncd"));
    assertEquals("ab\r\n|cd", transpose("ab\r\n|cd"));
    assertEquals("x\n|", transpose("x\n|"));
  }

  @Test
  void surrogatePairsAndCombiningMarksMoveWhole() {
    String bold = "\uD835\uDC00";
    assertEquals("b" + bold + "|c", transpose(bold + "|bc"));
    assertEquals("axe\u0301|", transpose("ae\u0301|x"));
    assertEquals("xe\u0301\u0302|", transpose("e\u0301\u0302x|"));
  }

  @Test
  void everyCaretSwapsInOneCommandStep() {
    Marked m = new Marked("ab|c ab|c");
    Edit edit = Transpose.transpose(m.document.text(), m.model.selections());
    assertEquals(EditBatch.Kind.COMMAND, edit.batch().kind());
    m.apply(edit);
    assertEquals("acb| acb|", m.marked());
    m.document.undo(m.model);
    assertEquals("ab|c ab|c", m.marked());
  }

  @Test
  void adjacentCaretsSwapOnce() {
    assertEquals("acb|d", transpose("ab|c|d"));
  }

  @Test
  void twoOrMoreSelectionsRotateTheirTexts() {
    assertEquals("[two] [one]", transpose("[one] [two]"));
    assertEquals("[c] [a] >bb<* |", transpose("[a] [bb] >c<* |"));
  }

  @Test
  void aRotationThatWouldJoinACrAndAnLfChangesNothing() {
    Marked m = new Marked("[X\r]m[Y]\n");
    Edit edit = Transpose.transpose(m.document.text(), m.model.selections());
    assertTrue(edit.batch().replacements().isEmpty());
    assertEquals(2, edit.unchanged());
    assertEquals("[Y]m[X\r]z\n", transpose("[X\r]m[Y]z\n"));
  }

  @Test
  void oneSelectionAloneChangesNothing() {
    Marked m = new Marked("[ab]c");
    assertNull(Transpose.transpose(m.document.text(), m.model.selections()));
  }
}
