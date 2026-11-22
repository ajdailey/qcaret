package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import java.util.HashMap;
import java.util.Map;

/**
 * The words of a measured table's cells: for each cell holding a space, each word's range and width
 * and the width of the spaces before it, measured through a {@link TextMeasurer} a bounded number
 * of cells per call; a cell without a space is one word as wide as the cell. The spaces between
 * words are measured once per run of spaces, span style and row kind. {@link #widest} gives each
 * column's widest word for {@link MarkdownTableLayout#fit(int[], int, boolean)}, and {@link
 * TableWrap} breaks a cell at its words. Offsets are from each cell's start, so the words serve an
 * equal table at another place in a later render. Not thread-safe.
 */
public final class TableWords {
  private final MarkdownTable table;
  private final String text;
  private final MarkdownTableLayout layout;
  private final int columns;
  private final int[][][] words;
  private final int[] widest;
  private final int[] spans;
  private final Map<String, Integer> gaps = new HashMap<String, Integer>();
  private int measuredRows;

  /**
   * Creates the words of a table, nothing measured yet.
   *
   * @param table the table
   * @param text the rendered text holding its rows
   * @param layout the table's measured layout, for the width of a cell without a space
   * @param spans the text's spans, as {@link MarkdownSpans#of} gives them
   */
  public TableWords(MarkdownTable table, String text, MarkdownTableLayout layout, int[] spans) {
    this.table = table;
    this.text = text;
    this.layout = layout;
    this.spans = spans;
    this.columns = table.alignments().size();
    this.words = new int[table.rowCount()][columns][];
    this.widest = new int[columns];
  }

  /**
   * Measures the words of the next rows.
   *
   * @param measurer measures word and space widths
   * @param maxCells measures whole rows until at least this many cells are done
   * @return the cells done; {@link #measured} reports the end
   */
  public int measure(TextMeasurer measurer, int maxCells) {
    int cells = 0;
    while (measuredRows < table.rowCount() && cells < maxCells) {
      int row = measuredRows;
      int[] pairs = table.cells()[row];
      for (int c = 0; c < columns && 2 * c + 1 < pairs.length; c++) {
        int[] cell = split(measurer, pairs[2 * c], pairs[2 * c + 1], row == 0);
        words[row][c] = cell;
        int width = 0;
        if (cell.length == 0) {
          width = layout.width(row, c);
        } else {
          for (int w = 0; w < cell.length; w += 4) {
            width = Math.max(width, cell[w + 2]);
          }
        }
        widest[c] = Math.max(widest[c], width);
        cells++;
      }
      measuredRows++;
    }
    return cells;
  }

  /** Returns {@code start, end, width, gap} per word, or none for a cell without a space. */
  private int[] split(TextMeasurer measurer, int start, int end, boolean strong) {
    if (text.indexOf(' ', start) < 0 || text.indexOf(' ', start) >= end) {
      return new int[0];
    }
    int count = 0;
    for (int i = start; i < end; i++) {
      if (text.charAt(i) != ' ' && (i == start || text.charAt(i - 1) == ' ')) {
        count++;
      }
    }
    int[] out = new int[4 * count];
    int w = 0;
    int previous = -1;
    int i = start;
    while (i < end) {
      if (text.charAt(i) == ' ') {
        i++;
        continue;
      }
      int wordEnd = i;
      while (wordEnd < end && text.charAt(wordEnd) != ' ') {
        wordEnd++;
      }
      out[w] = i - start;
      out[w + 1] = wordEnd - start;
      out[w + 2] = measurer.width(i, wordEnd, strong);
      out[w + 3] = previous < 0 ? 0 : gap(measurer, previous, i, strong);
      w += 4;
      previous = wordEnd;
      i = wordEnd;
    }
    return out;
  }

  /** Measures the spaces between two words, once per run of spaces, span bits and row kind. */
  private int gap(TextMeasurer measurer, int start, int end, boolean strong) {
    int i = MarkdownSpans.first(spans, start);
    int bits = 3 * i < spans.length && spans[3 * i] <= start ? spans[3 * i + 2] : 0;
    String key = (end - start) + ":" + bits + (strong ? "!" : "");
    Integer width = gaps.get(key);
    if (width == null) {
      width = measurer.width(start, end, strong);
      gaps.put(key, width);
    }
    return width;
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
   * Returns each column's widest word.
   *
   * @return one width per column
   * @throws IllegalStateException before every row is measured
   */
  public int[] widest() {
    if (!measured()) {
      throw new IllegalStateException("not measured");
    }
    return widest.clone();
  }

  /**
   * Returns a cell's words.
   *
   * @param row a row, 0 for the header
   * @param column a column
   * @return {@code start, end, width, gap} per word, offsets from the cell's start, gap being the
   *     width of the spaces before it (0 for the first); none for a cell without a space. Shared:
   *     do not modify
   */
  int[] words(int row, int column) {
    return words[row][column];
  }
}
