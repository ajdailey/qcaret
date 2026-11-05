package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * A finished side-by-side line comparison, immutable. Both texts have one line per row, joined with
 * {@code \n}; a row whose line exists on one side only is an empty filler line on the other.
 *
 * @param leftTitle the left side's name
 * @param rightTitle the right side's name
 * @param leftText the left rows
 * @param rightText the right rows
 * @param rows the kind of each row
 * @param changes the first row of each run of changed rows, ascending
 * @param notice a line shown above the texts, or empty
 */
public record Comparison(
    String leftTitle,
    String rightTitle,
    String leftText,
    String rightText,
    List<Row> rows,
    List<Integer> changes,
    String notice) {
  /** What a row shows. */
  public enum Row {
    /** The same line on both sides. */
    SAME,
    /** A line on each side, different. */
    CHANGED,
    /** A line on the left only. */
    REMOVED,
    /** A line on the right only. */
    ADDED
  }

  /** Keeps unmodifiable copies of the lists. */
  public Comparison {
    rows = List.copyOf(rows);
    changes = List.copyOf(changes);
  }
}
