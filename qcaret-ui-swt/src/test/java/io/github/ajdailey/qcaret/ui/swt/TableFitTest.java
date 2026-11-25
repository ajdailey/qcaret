package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** {@link TableFit#shares}: proportional widths that keep each header's minimum while they fit. */
class TableFitTest {
  private static final int[] WEIGHTS = {0, 260, 480};

  @Test
  void aWideTableSharesInProportion() {
    assertArrayEquals(
        new int[] {0, 260, 480}, TableFit.shares(740, WEIGHTS, new int[] {0, 70, 40}));
  }

  @Test
  void aColumnBelowItsHeaderTakesItsMinimumAndTheOtherTheRest() {
    assertArrayEquals(new int[] {0, 70, 80}, TableFit.shares(150, WEIGHTS, new int[] {0, 70, 40}));
  }

  @Test
  void aTableNarrowerThanEveryMinimumSharesInProportion() {
    assertArrayEquals(new int[] {0, 35, 65}, TableFit.shares(100, WEIGHTS, new int[] {0, 70, 40}));
  }
}
