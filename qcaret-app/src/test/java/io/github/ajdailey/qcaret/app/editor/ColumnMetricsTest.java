package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** QLogg UI-1: columns from the fractional advance, not a truncated per-char width. */
class ColumnMetricsTest {
  @Test
  void columnsUseTheFractionalAdvance() {
    // Menlo 12 measures about 7.22 px per digit; 10 digits truncate to 7 px each.
    ColumnMetrics metrics = ColumnMetrics.fromSample(7220);
    assertEquals(1083, metrics.x(150));
    assertEquals(13, metrics.columnsIn(100));
    assertEquals(1000, ColumnMetrics.sample().length());
  }

  @Test
  void theGutterHoldsAtLeastThreeDigits() {
    assertEquals(3, ColumnMetrics.gutterDigits(1));
    assertEquals(3, ColumnMetrics.gutterDigits(999));
    assertEquals(7, ColumnMetrics.gutterDigits(1_000_000));
  }
}
