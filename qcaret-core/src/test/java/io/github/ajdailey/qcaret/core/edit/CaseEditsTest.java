package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.document.EditBatch;
import org.junit.jupiter.api.Test;

/** Title Case and Swap Case at ranges and carets, one undo step each. */
class CaseEditsTest {
  private static String convert(String marked, CaseEdits.Mode mode) {
    Marked m = new Marked(marked);
    return m.apply(CaseEdits.convert(m.document.text(), m.model.selections(), mode)).marked();
  }

  @Test
  void titleCaseCapitalizesEachWordOfARangeAndTheWordAtEachCaret() {
    assertEquals(
        "[Hello World_X 2nd Don't O'brien]",
        convert("[hELLO world_X 2ND don't o'brien]", CaseEdits.Mode.TITLE));
    assertEquals("Ab|c x Ab|c", convert("ab|c x ab|c", CaseEdits.Mode.TITLE));
  }

  @Test
  void titleCaseKeepsCombiningMarksAndSurrogatePairsInTheirWord() {
    assertEquals(
        "[E\u0301cole \uD801\uDC00\uD801\uDC28]",
        convert("[e\u0301COLE \uD801\uDC28\uD801\uDC00]", CaseEdits.Mode.TITLE));
  }

  @Test
  void swapCaseSwapsEachLetterAndLeavesTheRest() {
    assertEquals("[aBc 1-\u01C6]", convert("[AbC 1-\u01C5]", CaseEdits.Mode.SWAP));
    assertEquals("x[\uD801\uDC28]", convert("x[\uD801\uDC00]", CaseEdits.Mode.SWAP));
  }

  @Test
  void everySelectionChangesInOneCommandStep() {
    Marked m = new Marked("[ab] [cd]");
    Edit edit = CaseEdits.convert(m.document.text(), m.model.selections(), CaseEdits.Mode.SWAP);
    assertEquals(EditBatch.Kind.COMMAND, edit.batch().kind());
    assertEquals(2, edit.batch().replacements().size());
    m.apply(edit);
    m.document.undo(m.model);
    assertEquals("[ab] [cd]", m.marked());
  }

  @Test
  void nothingToChangeReturnsNull() {
    Marked m = new Marked("[Done] | 12");
    assertNull(CaseEdits.convert(m.document.text(), m.model.selections(), CaseEdits.Mode.TITLE));
  }
}
