package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;

/**
 * Lays out a table's rows for a pane that shows each row as display lines: a cell wider than its
 * fitted column breaks at the spaces between its words, a word wider than its column at a char
 * count in proportion to its width, and a row takes as many display lines as its tallest cell.
 * Display line {@code j} of a row holds every cell's {@code j}th segment, tab-separated, with the
 * indent and tab stops that start each segment at its column, aligned as the column says. A row's
 * first and last display lines end with {@link #PAD}, which the pane draws as the cell padding
 * above and below the row; at a line's start, before a tab, it moves the tab off its stop (GTK 3).
 * Widths come from {@link MarkdownTableLayout} and {@link TableWords}; no text is measured here.
 */
public final class TableWrap {
  /** A zero-width space that ends a row's first and last display lines. */
  public static final char PAD = (char) 0x200B;

  /**
   * A row's display lines.
   *
   * @param text the lines, separated by {@code \n}, without a final one
   * @param lines the line count, at least 1
   * @param pieces {@code display, rendered, length} triples, offsets from the row's display and
   *     rendered starts, ascending in display order: each run of display chars copied from the
   *     rendered row. Shared: do not modify
   * @param indents each line's first segment's start, in pixels from the table's left edge
   * @param tabStops each line's later segments' starts. Shared: do not modify
   */
  public record Row(String text, int lines, int[] pieces, int[] indents, int[][] tabStops) {}

  private TableWrap() {}

  /**
   * Lays out rows of a table fitted with {@link MarkdownTableLayout#fit(int[], int, boolean)}.
   *
   * @param table the table
   * @param text the rendered text holding it
   * @param layout its measured, fitted layout
   * @param words its words, measured when any cell is wider than its column; may be null otherwise
   * @param from the first row
   * @param to the row after the last
   * @return one row per row in {@code [from, to)}
   */
  public static Row[] rows(
      MarkdownTable table,
      String text,
      MarkdownTableLayout layout,
      TableWords words,
      int from,
      int to) {
    Row[] out = new Row[to - from];
    for (int row = from; row < to; row++) {
      out[row - from] = row(table, text, layout, words, row);
    }
    return out;
  }

  private static Row row(
      MarkdownTable table, String text, MarkdownTableLayout layout, TableWords words, int row) {
    int columns = table.alignments().size();
    int[] pairs = table.cells()[row];
    int rowStart = pairs.length > 0 ? pairs[0] : 0;
    int[][] segments = new int[columns][];
    int lines = 1;
    for (int c = 0; c < columns; c++) {
      int start = pairs[2 * c];
      int end = pairs[2 * c + 1];
      int width = layout.width(row, c);
      int room = layout.columnWidth(c);
      if (width <= room || end <= start) {
        segments[c] = new int[] {start, end, width};
      } else {
        int[] relative = words == null ? null : words.words(row, c);
        int[] cell;
        if (relative == null || relative.length == 0) {
          cell = new int[] {start, end, width, 0};
        } else {
          cell = relative.clone();
          for (int w = 0; w < cell.length; w += 4) {
            cell[w] += start;
            cell[w + 1] += start;
          }
        }
        segments[c] = breakCell(text, cell, room);
      }
      lines = Math.max(lines, segments[c].length / 3);
    }
    int[] edges = layout.edges();
    int padding = layout.padding();
    StringBuilder display = new StringBuilder();
    Pieces pieces = new Pieces();
    int[] indents = new int[lines];
    int[][] stops = new int[lines][];
    for (int j = 0; j < lines; j++) {
      if (j > 0) {
        display.append('\n');
      }
      int[] starts = new int[columns];
      for (int c = 0; c < columns; c++) {
        if (c > 0) {
          if (j == 0) {
            pieces.add(display.length(), pairs[2 * c] - 1 - rowStart, 1);
          }
          display.append('\t');
        }
        int[] segment = segments[c];
        int s = 0;
        int e = 0;
        int width = 0;
        if (3 * j < segment.length) {
          s = segment[3 * j];
          e = segment[3 * j + 1];
          width = segment[3 * j + 2];
        }
        if (e > s) {
          pieces.add(display.length(), s - rowStart, e - s);
          display.append(text, s, e);
        }
        int slack = Math.max(0, layout.columnWidth(c) - width);
        int offset =
            switch (table.alignments().get(c)) {
              case RIGHT -> slack;
              case CENTER -> slack / 2;
              default -> 0;
            };
        starts[c] = edges[c] + padding + offset;
      }
      if (j == 0 || j == lines - 1) {
        display.append(PAD);
      }
      indents[j] = columns == 0 ? 0 : starts[0];
      int[] lineStops = new int[Math.max(0, columns - 1)];
      System.arraycopy(starts, 1, lineStops, 0, lineStops.length);
      stops[j] = lineStops;
    }
    return new Row(display.toString(), lines, pieces.toArray(), indents, stops);
  }

  /**
   * Breaks a cell into segments no wider than a column.
   *
   * @param text the rendered text
   * @param words {@code start, end, width, gap} per word
   * @param room the column's width
   * @return {@code start, end, width} per segment
   */
  static int[] breakCell(String text, int[] words, int room) {
    IntList out = new IntList();
    int start = -1;
    int end = 0;
    int width = 0;
    for (int w = 0; w < words.length; w += 4) {
      int wordStart = words[w];
      int wordEnd = words[w + 1];
      int wordWidth = words[w + 2];
      if (start >= 0 && width + words[w + 3] + wordWidth <= room) {
        end = wordEnd;
        width += words[w + 3] + wordWidth;
        continue;
      }
      if (start >= 0) {
        out.add(start, end, width);
      }
      int chars = wordEnd - wordStart;
      while (wordWidth > room && chars > 1) {
        int take = Math.max(1, (int) ((long) chars * room / wordWidth));
        int cut = wordStart + take;
        if (Character.isLowSurrogate(text.charAt(cut))) {
          cut += take > 1 ? -1 : 1;
        }
        int taken = (int) ((long) wordWidth * (cut - wordStart) / chars);
        out.add(wordStart, cut, taken);
        wordWidth -= taken;
        chars -= cut - wordStart;
        wordStart = cut;
      }
      start = wordStart < wordEnd ? wordStart : -1;
      end = wordEnd;
      width = wordWidth;
    }
    if (start >= 0) {
      out.add(start, end, width);
    }
    return out.toArray();
  }

  /** Display-to-rendered pieces, merging a piece that continues the last in both texts. */
  private static final class Pieces {
    private final IntList list = new IntList();

    void add(int display, int rendered, int length) {
      int n = list.size();
      if (n >= 3
          && list.get(n - 3) + list.get(n - 1) == display
          && list.get(n - 2) + list.get(n - 1) == rendered) {
        list.set(n - 1, list.get(n - 1) + length);
        return;
      }
      list.add(display, rendered, length);
    }

    int[] toArray() {
      return list.toArray();
    }
  }

  /** A growable int array. */
  private static final class IntList {
    private int[] values = new int[12];
    private int size;

    void add(int a, int b, int c) {
      if (size + 3 > values.length) {
        int[] grown = new int[values.length * 2];
        System.arraycopy(values, 0, grown, 0, size);
        values = grown;
      }
      values[size++] = a;
      values[size++] = b;
      values[size++] = c;
    }

    int size() {
      return size;
    }

    int get(int i) {
      return values[i];
    }

    void set(int i, int value) {
      values[i] = value;
    }

    int[] toArray() {
      int[] out = new int[size];
      System.arraycopy(values, 0, out, 0, size);
      return out;
    }
  }
}
