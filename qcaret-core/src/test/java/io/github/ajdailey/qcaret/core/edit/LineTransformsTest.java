package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Sort, duplicate removal and trim over selected line blocks or the whole text, one undo step. */
class LineTransformsTest {
  private static final CancellationToken TOKEN = new CancellationToken();

  private static String sort(String marked, LineTransforms.Order order) {
    Marked m = new Marked(marked);
    return m.apply(LineTransforms.sort(m.document.text(), m.model.selections(), order, TOKEN))
        .marked();
  }

  private static String dedupe(String marked) {
    Marked m = new Marked(marked);
    return m.apply(LineTransforms.removeDuplicates(m.document.text(), m.model.selections(), TOKEN))
        .marked();
  }

  private static String trim(String marked) {
    Marked m = new Marked(marked);
    return m.apply(LineTransforms.trimTrailing(m.document.text(), m.model.selections(), TOKEN))
        .marked();
  }

  @Test
  void aCaretSortsEveryLineKeepingEachBreakAndTheCaretOffset() {
    assertEquals("a\nb\n|", sort("b\na\n|", LineTransforms.Order.ASCENDING));
    assertEquals("a\r\nB|\nb\nc", sort("c\r\nb|\nB\na", LineTransforms.Order.IGNORE_CASE));
    assertEquals("B\r\na|\nb\nc", sort("c\r\nb|\nB\na", LineTransforms.Order.ASCENDING));
    assertEquals("c\r\nb|\na\nB", sort("c\r\nb|\nB\na", LineTransforms.Order.DESCENDING));
  }

  @Test
  void eachRangeSortsItsOwnTouchedLines() {
    assertEquals(
        "x\n[a\nb\n]z\ny\n[c\nd]", sort("x\n[b\na\n]z\ny\n[d\nc]", LineTransforms.Order.ASCENDING));
  }

  @Test
  void sortedLinesChangeNothingAndReturnNull() {
    Marked m = new Marked("a\nb|\nc");
    assertNull(
        LineTransforms.sort(
            m.document.text(), m.model.selections(), LineTransforms.Order.ASCENDING, TOKEN));
  }

  @Test
  void removeDuplicatesKeepsTheFirstOfEachLine() {
    assertEquals("a\nb\n|c\n", dedupe("a\nb\na\n|c\nb\n"));
    assertEquals("a\nb|", dedupe("a\nb|\na"));
    assertEquals("x\n\n|", dedupe("x\n\n\n|"));
  }

  @Test
  void removeDuplicatesInsideARangeOnly() {
    assertEquals("a\n[a\nb]\na", dedupe("a\n[a\na\nb]\na"));
  }

  @Test
  void trimRemovesTrailingBlanksAndPullsACaretToTheLineEnd() {
    assertEquals("a\nb|\n  c", trim("a \t\nb  |  \n  c\t"));
    Marked m = new Marked("a|\nb");
    assertNull(LineTransforms.trimTrailing(m.document.text(), m.model.selections(), TOKEN));
  }

  @Test
  void eachTransformIsOneCommandStep() {
    Marked m = new Marked("b \na \nb |");
    Edit sorted =
        LineTransforms.sort(
            m.document.text(), m.model.selections(), LineTransforms.Order.ASCENDING, TOKEN);
    assertEquals(EditBatch.Kind.COMMAND, sorted.batch().kind());
    m.apply(sorted);
    m.apply(LineTransforms.removeDuplicates(m.document.text(), m.model.selections(), TOKEN));
    m.apply(LineTransforms.trimTrailing(m.document.text(), m.model.selections(), TOKEN));
    assertEquals("a\nb|", m.marked());
    m.document.undo(m.model);
    m.document.undo(m.model);
    m.document.undo(m.model);
    assertEquals("b \na \nb |", m.marked());
  }

  @Test
  void aCanceledTokenStopsTheWork() {
    final Marked m = new Marked("b\na|");
    final CancellationToken canceled = new CancellationToken();
    canceled.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            LineTransforms.sort(
                m.document.text(), m.model.selections(), LineTransforms.Order.ASCENDING, canceled);
          }
        });
  }
}
