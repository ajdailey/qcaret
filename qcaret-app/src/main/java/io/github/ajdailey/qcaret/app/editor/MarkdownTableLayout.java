package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;

/**
 * One rendered table's columns: each cell measured through a {@link TextMeasurer}, the header row
 * strong, a column as wide as its widest cell plus padding on both sides. A row is one rendered
 * line whose cells are separated by tabs; {@link #row} gives the line's indent and tab stops that
 * start each cell at its column, left, centered or right as the column's alignment says. Measuring
 * runs a bounded number of cells per call so a caller can spread a large table over several UI
 * turns. Positions are in pixels from the table's left edge. Not thread-safe.
 */
public final class MarkdownTableLayout {
  /**
   * A row's placement.
   *
   * @param indent the first cell's start
   * @param tabStops the start of each later cell, ascending
   */
  public record Row(int indent, int[] tabStops) {}

  private final MarkdownTable table;
  private final String text;
  private final int padding;
  private final int columns;
  private final int[][] widths;
  private final int[] inner;
  private int measuredRows;
  private int[] edges;

  /**
   * Creates the layout of a table, nothing measured yet.
   *
   * @param table the table
   * @param text the rendered text holding its rows
   * @param padding pixels on each side of a cell's text
   */
  public MarkdownTableLayout(MarkdownTable table, String text, int padding) {
    this.table = table;
    this.text = text;
    this.padding = padding;
    this.columns = table.alignments().size();
    this.widths = new int[table.rowCount()][columns];
    this.inner = new int[columns];
  }

  /**
   * Measures the next rows.
   *
   * @param measurer measures cell text
   * @param maxCells measures whole rows until at least this many cells are measured
   * @return the cells measured; {@link #measured} reports the end
   */
  public int measure(TextMeasurer measurer, int maxCells) {
    int cells = 0;
    while (measuredRows < table.rowCount() && cells < maxCells) {
      int[] pairs = table.cells()[measuredRows];
      boolean header = measuredRows == 0;
      for (int c = 0; c < columns && 2 * c + 1 < pairs.length; c++) {
        int start = pairs[2 * c];
        int end = pairs[2 * c + 1];
        int width = end > start ? measurer.width(text.substring(start, end), header) : 0;
        widths[measuredRows][c] = width;
        inner[c] = Math.max(inner[c], width);
        cells++;
      }
      measuredRows++;
    }
    if (measured() && edges == null) {
      edges = new int[columns + 1];
      for (int c = 0; c < columns; c++) {
        edges[c + 1] = edges[c] + inner[c] + 2 * padding;
      }
    }
    return cells;
  }

  /**
   * Reports whether every row is measured.
   *
   * @return true after the last row
   */
  public boolean measured() {
    return measuredRows == table.rowCount();
  }

  /**
   * Returns the column edges: the table's left edge, each boundary between columns, and its right
   * edge.
   *
   * @return column count plus 1 positions
   * @throws IllegalStateException before every row is measured
   */
  public int[] edges() {
    if (edges == null) {
      throw new IllegalStateException("not measured");
    }
    return edges.clone();
  }

  /**
   * Returns a row's placement.
   *
   * @param row a row, 0 for the header
   * @return the first cell's indent and the later cells' tab stops
   * @throws IllegalStateException before every row is measured
   */
  public Row row(int row) {
    if (edges == null) {
      throw new IllegalStateException("not measured");
    }
    int[] starts = new int[columns];
    for (int c = 0; c < columns; c++) {
      int slack = inner[c] - widths[row][c];
      int offset =
          switch (table.alignments().get(c)) {
            case RIGHT -> slack;
            case CENTER -> slack / 2;
            default -> 0;
          };
      starts[c] = edges[c] + padding + offset;
    }
    int[] stops = new int[Math.max(0, columns - 1)];
    System.arraycopy(starts, 1, stops, 0, stops.length);
    return new Row(columns == 0 ? 0 : starts[0], stops);
  }
}
