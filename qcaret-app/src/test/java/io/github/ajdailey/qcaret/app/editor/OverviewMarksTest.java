package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Line-to-row mapping both ways, for strips shorter and taller than the document. */
class OverviewMarksTest {
  @Test
  void linesMapToRowsAndBack() {
    assertEquals(0, OverviewMarks.rowOf(0, 1000, 100));
    assertEquals(49, OverviewMarks.rowOf(499, 1000, 100));
    assertEquals(99, OverviewMarks.rowOf(999, 1000, 100));
    assertEquals(500, OverviewMarks.lineAt(50, 1000, 100));
    assertEquals(990, OverviewMarks.lineAt(500, 1000, 100));
    assertEquals(40, OverviewMarks.rowOf(4, 10, 100));
    assertEquals(4, OverviewMarks.lineAt(40, 10, 100));
    assertEquals(0, OverviewMarks.rowOf(5, 0, 100));
  }

  @Test
  void eachKindMarksItsRows() {
    OverviewMarks marks =
        OverviewMarks.of(10, 3, 10, new int[] {1, 1}, new int[] {1, 9}, new int[] {20, 4});
    assertEquals(10, marks.height());
    assertEquals(OverviewMarks.FIND | OverviewMarks.SEARCH, marks.rows()[1]);
    assertEquals(OverviewMarks.SEARCH, marks.rows()[9]);
    assertEquals(OverviewMarks.BOOKMARK, marks.rows()[4]);
    assertEquals(0, marks.rows()[0]);
    assertEquals(3, marks.revision());
  }
}
