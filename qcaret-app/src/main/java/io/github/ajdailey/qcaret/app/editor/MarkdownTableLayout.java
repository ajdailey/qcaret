package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;

/**
 * One rendered table's columns: each cell measured through a {@link TextMeasurer}, the header row
 * strong, a column as wide as its widest cell plus padding on both sides. A row is one rendered
 * line whose cells are separated by tabs; {@link #row} gives the line's indent and tab stops that
 * start each cell at its column, left, centered or right as the column's alignment says. Measuring
 * runs a bounded number of cells per call so a caller can spread a large table over several UI
 * turns. {@link #fit} narrows the columns of a table wider than a given width, for a caller that
 * wraps each cell inside its column; each fit starts from the measured widths. Positions are in
 * pixels from the table's left edge. Not thread-safe.
 */
public final class MarkdownTableLayout {
  /**
   * A row's placement.
   *
   * @param indent the first cell's start
   * @param tabStops the start of each later cell, ascending
   * @param edges the table's column edges, as {@link #edges} returns them. Shared, not copied: do
   *     not modify
   */
  public record Row(int indent, int[] tabStops, int[] edges) {}

  private final MarkdownTable table;
  private final String text;
  private final int padding;
  private final int columns;
  private final int[][] widths;
  private final int[] inner;
  private final int[] natural;
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
    this.natural = new int[columns];
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
        int width = end > start ? measurer.width(start, end, header) : 0;
        widths[measuredRows][c] = width;
        inner[c] = Math.max(inner[c], width);
        cells++;
      }
      measuredRows++;
    }
    if (measured() && edges == null) {
      System.arraycopy(inner, 0, natural, 0, columns);
      setEdges();
    }
    return cells;
  }

  private void setEdges() {
    edges = new int[columns + 1];
    for (int c = 0; c < columns; c++) {
      edges[c + 1] = edges[c] + inner[c] + 2 * padding;
    }
  }

  /**
   * Narrows the columns of a measured table wider than a width. Each column keeps at least its
   * widest word; the space above the columns' widest words goes to each column in proportion to how
   * much its widest cell exceeds its widest word. A table whose widest words do not fit keeps them
   * and stays wider. A cell wider than its column then has to wrap inside it.
   *
   * @param measurer measures the words of every cell
   * @param width the widest the table may be, padding included
   * @throws IllegalStateException before every row is measured
   */
  public void fit(TextMeasurer measurer, int width) {
    if (edges == null) {
      throw new IllegalStateException("not measured");
    }
    int[] words = new int[columns];
    if (wider(width)) {
      for (int row = 0; row < table.rowCount(); row++) {
        int[] pairs = table.cells()[row];
        for (int c = 0; c < columns && 2 * c + 1 < pairs.length; c++) {
          words[c] = Math.max(words[c], widestWord(measurer, pairs[2 * c], pairs[2 * c + 1], row));
        }
      }
    }
    fit(words, width, false);
  }

  /**
   * Fits the measured columns to a width as {@link #fit(TextMeasurer, int)} does, from each
   * column's widest word. A table that fits gets its measured widths back.
   *
   * @param words each column's widest word; read only when the table is {@link #wider} than the
   *     width
   * @param width the widest the table may be, padding included
   * @param bounded true to narrow the widest words in proportion when they do not fit, so the table
   *     is never wider than {@code width}; a word wider than its column then has to break
   * @throws IllegalStateException before every row is measured
   */
  public void fit(int[] words, int width, boolean bounded) {
    if (edges == null) {
      throw new IllegalStateException("not measured");
    }
    System.arraycopy(natural, 0, inner, 0, columns);
    if (!wider(width)) {
      setEdges();
      return;
    }
    int room = width - 2 * padding * columns;
    int[] least = new int[columns];
    long leastTotal = 0;
    long flex = 0;
    for (int c = 0; c < columns; c++) {
      least[c] = Math.min(words[c], natural[c]);
      leastTotal += least[c];
      flex += natural[c] - least[c];
    }
    if (bounded && leastTotal > room) {
      for (int c = 0; c < columns; c++) {
        inner[c] = (int) (Math.max(0, room) * least[c] / leastTotal);
      }
    } else {
      long spare = Math.max(0, room - leastTotal);
      for (int c = 0; c < columns; c++) {
        inner[c] = least[c] + (int) (flex == 0 ? 0 : spare * (natural[c] - least[c]) / flex);
      }
    }
    setEdges();
  }

  /**
   * Reports whether the table at its measured column widths is wider than a width.
   *
   * @param width the widest the table may be, padding included
   * @return true when {@link #fit(int[], int, boolean)} narrows a column
   * @throws IllegalStateException before every row is measured
   */
  public boolean wider(int width) {
    if (edges == null) {
      throw new IllegalStateException("not measured");
    }
    long total = 0;
    for (int c = 0; c < columns; c++) {
      total += natural[c];
    }
    return total > width - 2 * padding * columns;
  }

  private int widestWord(TextMeasurer measurer, int start, int end, int row) {
    int widest = 0;
    int word = start;
    for (int i = start; i <= end; i++) {
      if (i == end || text.charAt(i) == ' ') {
        if (i > word) {
          widest = Math.max(widest, measurer.width(word, i, row == 0));
        }
        word = i + 1;
      }
    }
    return widest;
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
   * Returns a cell's measured width.
   *
   * @param row a row, 0 for the header
   * @param column a column
   * @return pixels, without padding
   */
  public int width(int row, int column) {
    return widths[row][column];
  }

  /**
   * Returns a column's width after the last fit.
   *
   * @param column a column
   * @return pixels, without padding
   */
  public int columnWidth(int column) {
    return inner[column];
  }

  /**
   * Returns the pixels on each side of a cell's text.
   *
   * @return the padding
   */
  public int padding() {
    return padding;
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
      int slack = Math.max(0, inner[c] - widths[row][c]);
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
    return new Row(columns == 0 ? 0 : starts[0], stops, edges);
  }
}
