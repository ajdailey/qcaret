package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Column-mode edits: padding into virtual space, the block kept, and one undo step per run. */
class BlockEditsTest {
  private static final String TEXT = "abcdef\nab\n\nabcdefgh";

  private static Marked block(String text, BlockSelection selection) {
    Marked m = new Marked(text);
    m.model.setBlock(selection, 4);
    return m;
  }

  private static void apply(Marked m, BlockEdits.Result result) {
    m.document.applyInBlock(result.batch(), m.model, result.after());
  }

  @Test
  void aTallCaretInsideATabOrWideCharRemovesNothingWhenTyping() {
    for (String[] row : new String[][] {{"\tx", "2", "Z\tx"}, {"\u4e2dx", "1", "Z\u4e2dx"}}) {
      int column = Integer.parseInt(row[1]);
      Marked m = block(row[0], new BlockSelection(0, column, 0, column));
      apply(m, BlockEdits.type(m.document.text(), m.model.block(), 4, "Z", EditBatch.Kind.TYPING));
      assertEquals(row[2], m.document.text().toString());
    }
  }

  @Test
  void backspaceAndDeleteAtATallCaretInsideATab() {
    Marked back = block("a\tx", new BlockSelection(0, 2, 0, 2));
    apply(back, BlockEdits.backspace(back.document.text(), back.model.block(), 4));
    assertEquals("\tx", back.document.text().toString());
    Marked forward = block("a\tx", new BlockSelection(0, 2, 0, 2));
    apply(forward, BlockEdits.delete(forward.document.text(), forward.model.block(), 4));
    assertEquals("ax", forward.document.text().toString());
  }

  @Test
  void typingPadsShortLinesAndKeepsATallCaret() {
    Marked m = block(TEXT, new BlockSelection(0, 4, 3, 4));
    for (char c : "XY".toCharArray()) {
      apply(
          m,
          BlockEdits.type(
              m.document.text(), m.model.block(), 4, String.valueOf(c), EditBatch.Kind.TYPING));
    }
    assertEquals("abcdXYef\nab  XY\n    XY\nabcdXYefgh", m.document.text().toString());
    assertEquals(new BlockSelection(0, 6, 3, 6), m.model.block());
    m.document.undo(m.model);
    assertEquals(TEXT, m.document.text().toString());
  }

  @Test
  void typingReplacesEachRowsRange() {
    Marked m = block(TEXT, new BlockSelection(0, 1, 3, 3));
    apply(m, BlockEdits.type(m.document.text(), m.model.block(), 4, "-", EditBatch.Kind.TYPING));
    assertEquals("a-def\na-\n -\na-defgh", m.document.text().toString());
    assertEquals(new BlockSelection(0, 2, 3, 2), m.model.block());
  }

  @Test
  void backspaceAndDeleteWorkOnTheLinesThatReachTheColumn() {
    Marked m = block(TEXT, new BlockSelection(0, 2, 3, 2));
    apply(m, BlockEdits.backspace(m.document.text(), m.model.block(), 4));
    assertEquals("acdef\na\n\nacdefgh", m.document.text().toString());
    assertEquals(new BlockSelection(0, 1, 3, 1), m.model.block());
    apply(m, BlockEdits.delete(m.document.text(), m.model.block(), 4));
    assertEquals("adef\na\n\nadefgh", m.document.text().toString());
    assertNull(BlockEdits.backspace(m.document.text(), new BlockSelection(0, 0, 1, 0), 4));
  }

  @Test
  void pasteGivesEachRowItsLineAndCopyReadsShortRowsEmpty() {
    Marked m = block(TEXT, new BlockSelection(0, 3, 2, 5));
    assertEquals(List.of("de", "", ""), BlockEdits.rowsText(m.document.text(), m.model.block(), 4));
    m.apply(BlockEdits.paste(m.document.text(), m.model.block(), 4, List.of("1", "2", "3")));
    assertEquals("abc1f\nab 2\n   3\nabcdefgh", m.document.text().toString());
    assertEquals("abc1|f\nab 2|\n   3|*\nabcdefgh", m.marked());
    assertNull(m.model.block());
  }
}
