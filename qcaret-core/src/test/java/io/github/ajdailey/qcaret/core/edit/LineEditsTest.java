package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.document.EditBatch;
import org.junit.jupiter.api.Test;

/** Line and case commands at one and several selections, with the selections they leave. */
class LineEditsTest {
  private static String duplicate(String marked) {
    Marked m = new Marked(marked);
    return m.apply(LineEdits.duplicate(m.document.text(), m.model.selections(), "\n")).marked();
  }

  @Test
  void duplicateCopiesEachCaretsLinesBelowAndKeepsTheCaretAbove() {
    assertEquals("a|b\nab\ncd", duplicate("a|b\ncd"));
    assertEquals("ab\ncd|\ncd", duplicate("ab\ncd|"));
    assertEquals("a|b\nab\n|cd\ncd\n", duplicate("a|b\n|cd\n"));
  }

  @Test
  void duplicateWithACaretOnAnEmptyLastLineKeepsBothCaretsOnTheirLines() {
    assertEquals("a|\na\n|\n", duplicate("a|\n|"));
  }

  @Test
  void duplicateCopiesEachRangeAfterItself() {
    assertEquals("x[ab]ab y [c]c", duplicate("x[ab]* y [c]"));
  }

  @Test
  void moveLinesSwapsEachBlockWithItsNeighborAndCarriesTheCarets() {
    Marked m = new Marked("one\nt|wo\nthree");
    m.apply(LineEdits.moveLines(m.document.text(), m.model.selections(), true));
    assertEquals("t|wo\none\nthree", m.marked());
    m.apply(LineEdits.moveLines(m.document.text(), m.model.selections(), false));
    m.apply(LineEdits.moveLines(m.document.text(), m.model.selections(), false));
    assertEquals("one\nthree\nt|wo", m.marked());
    assertNull(LineEdits.moveLines(m.document.text(), m.model.selections(), false));
  }

  @Test
  void moveLinesRefusesAMoveThatJoinsACrAndAnLf() {
    Marked up = new Marked("\r  fo|o\n  ");
    assertUnchanged(1, LineEdits.moveLines(up.document.text(), up.model.selections(), true));
    Marked down = new Marked("|\r  foo\n  ");
    assertUnchanged(1, LineEdits.moveLines(down.document.text(), down.model.selections(), false));
    Marked neighbor = new Marked("p\rx\n|\ny");
    assertUnchanged(
        1, LineEdits.moveLines(neighbor.document.text(), neighbor.model.selections(), true));
  }

  @Test
  void moveLinesRefusesAJoinBetweenTwoMovedBlocks() {
    Marked m = new Marked("a\nb|\rc\n|\nz");
    assertUnchanged(2, LineEdits.moveLines(m.document.text(), m.model.selections(), true));
  }

  @Test
  void moveLinesKeepsMixedBreaksInPlaceWhenNothingJoins() {
    Marked m = new Marked("a\rb|\nc");
    m.apply(LineEdits.moveLines(m.document.text(), m.model.selections(), true));
    assertEquals("b|\ra\nc", m.marked());
  }

  @Test
  void touchingBlocksMoveTogetherAndARangeEndingAtALineStartLeavesThatLine() {
    Marked m = new Marked("a\n[b\n]c\nd");
    m.apply(LineEdits.moveLines(m.document.text(), m.model.selections(), true));
    assertEquals("[b\n]a\nc\nd", m.marked());
    Marked two = new Marked("a\nb|\nc|*\nd");
    two.apply(LineEdits.moveLines(two.document.text(), two.model.selections(), false));
    assertEquals("a\nd\nb|\nc|*", two.marked());
  }

  @Test
  void deleteLinesRemovesEveryTouchedLineAndTheLastTakesTheBreakBefore() {
    Marked m = new Marked("a\nb|b\nc\nd|d");
    m.apply(LineEdits.deleteLines(m.document.text(), m.model.selections()));
    assertEquals("a\n|c", m.marked());
    Marked all = new Marked("o|ne");
    all.apply(LineEdits.deleteLines(all.document.text(), all.model.selections()));
    assertEquals("|", all.marked());
  }

  /** Asserts an edit that changes nothing and counts the lines it left. */
  static void assertUnchanged(int lines, Edit edit) {
    assertTrue(edit.batch().replacements().isEmpty(), "replacements");
    assertEquals(lines, edit.unchanged());
  }

  @Test
  void deleteLinesRefusesADeleteThatJoinsACrAndAnLf() {
    Marked m = new Marked("a\rx|\n\nb");
    assertUnchanged(1, LineEdits.deleteLines(m.document.text(), m.model.selections()));
    Marked kept = new Marked("a\rx|\nb");
    Edit deleted = LineEdits.deleteLines(kept.document.text(), kept.model.selections());
    assertEquals(0, deleted.unchanged());
    kept.apply(deleted);
    assertEquals("a\r|b", kept.marked());
  }

  @Test
  void joinLinesLeavesOneSpaceUnlessTheLineEndsBlankOrTheRestIsEmpty() {
    Marked m = new Marked("al|pha\n    beta\ngamma ");
    m.apply(LineEdits.joinLines(m.document.text(), m.model.selections()));
    assertEquals("alpha| beta\ngamma ", m.marked());
    Marked range = new Marked("[a\nb\n]c\n\nd");
    range.apply(LineEdits.joinLines(range.document.text(), range.model.selections()));
    assertEquals("[a b\n]c\n\nd", range.marked());
    Marked empty = new Marked("x|\n\ny");
    empty.apply(LineEdits.joinLines(empty.document.text(), empty.model.selections()));
    assertEquals("x|\ny", empty.marked());
    assertNull(
        LineEdits.joinLines(
            new Marked("last|").document.text(), new Marked("last|").model.selections()));
  }

  @Test
  void caseChangesRangesAndTheWordsAtCarets() {
    Marked m = new Marked("abc d|ef [ghi]");
    m.apply(LineEdits.changeCase(m.document.text(), m.model.selections(), true));
    assertEquals("abc D|EF [GHI]", m.marked());
    m.apply(LineEdits.changeCase(m.document.text(), m.model.selections(), false));
    assertEquals("abc d|ef [ghi]", m.marked());
    assertNull(LineEdits.changeCase(m.document.text(), m.model.selections(), false));
  }

  @Test
  void indentAndOutdentEveryTouchedLine() {
    Marked m = new Marked("[a\n\nb]\nc");
    m.apply(LineEdits.indent(m.document.text(), m.model.selections(), 4, false));
    assertEquals("\t[a\n\n\tb]\nc", m.marked());
    m.apply(LineEdits.outdent(m.document.text(), m.model.selections(), 4));
    assertEquals("[a\n\nb]\nc", m.marked());
    Marked spaces = new Marked("|x\n      y|");
    spaces.apply(LineEdits.indent(spaces.document.text(), spaces.model.selections(), 4, true));
    assertEquals("    |x\n          y|", spaces.marked());
    spaces.apply(LineEdits.outdent(spaces.document.text(), spaces.model.selections(), 4));
    spaces.apply(LineEdits.outdent(spaces.document.text(), spaces.model.selections(), 4));
    assertEquals("|x\n  y|", spaces.marked());
  }

  @Test
  void tabInsertsAtCaretsToTheNextStopAndIndentsWhenARangeSpansLines() {
    Marked m = new Marked("ab|c\n|d");
    m.apply(LineEdits.insertTab(m.document.text(), m.model.selections(), 4, true));
    assertEquals("ab  |c\n    |d", m.marked());
    assertEquals(
        EditBatch.Kind.TYPING,
        LineEdits.insertTab(m.document.text(), m.model.selections(), 4, false).batch().kind());
    Marked range = new Marked("[a\nb]");
    range.apply(LineEdits.insertTab(range.document.text(), range.model.selections(), 4, false));
    assertEquals("\t[a\n\tb]", range.marked());
  }

  @Test
  void splitIntoLinesGivesEachLineItsPartOfTheRange() {
    Marked m = new Marked("a[bc\ndef\ng]h");
    m.model.set(LineEdits.splitIntoLines(m.document.text(), m.model.selections()));
    assertEquals("a[bc]\n[def]\n[g]*h", m.marked());
  }
}
