package io.github.ajdailey.qcaret.app.editor;

/**
 * Column geometry of a monospace font: the advance is the measured width of {@value #SAMPLE_CHARS}
 * digits divided by {@value #SAMPLE_CHARS}, kept fractional, and column {@code c} starts at {@code
 * round(c * advance)}. A per-char integer width drifts by the fraction per column (about 3 cells at
 * column 100 for a 7.22 px advance). Use it for printable ASCII grids only: the gutter and the wrap
 * width. Text outside printable ASCII is placed at the widget's measured glyph positions.
 *
 * @param advance pixels per column, above 0
 */
public record ColumnMetrics(double advance) {
  /** Digits in the measured sample. */
  public static final int SAMPLE_CHARS = 1000;

  /** Smallest gutter, in digits. */
  static final int MIN_DIGITS = 3;

  /** Checks the advance. */
  public ColumnMetrics {
    if (!(advance > 0)) {
      throw new IllegalArgumentException("advance must be above 0");
    }
  }

  /**
   * Returns the sample the toolkit measures: {@value #SAMPLE_CHARS} zeros.
   *
   * @return the sample text
   */
  public static String sample() {
    return "0".repeat(SAMPLE_CHARS);
  }

  /**
   * Creates the metrics from the sample's measured width.
   *
   * @param samplePixels the width of {@link #sample()}
   * @return the metrics
   */
  public static ColumnMetrics fromSample(int samplePixels) {
    return new ColumnMetrics(Math.max(1, samplePixels) / (double) SAMPLE_CHARS);
  }

  /**
   * Returns the x of a column's left edge.
   *
   * @param column a column, at least 0
   * @return {@code round(column * advance)}
   */
  public int x(int column) {
    return (int) Math.round(column * advance);
  }

  /**
   * Returns the whole columns that fit a width.
   *
   * @param pixels the width
   * @return at least 1
   */
  public int columnsIn(int pixels) {
    return Math.max(1, (int) Math.floor(pixels / advance));
  }

  /**
   * Returns the digits a line-number gutter shows.
   *
   * @param lineCount the document's lines
   * @return the digits of the largest line number, at least {@value #MIN_DIGITS}
   */
  public static int gutterDigits(int lineCount) {
    return Math.max(MIN_DIGITS, Integer.toString(Math.max(1, lineCount)).length());
  }
}
