package io.github.ajdailey.qcaret.app.editor;

/**
 * The overview strip's marks: for each pixel row, which kinds of marked lines it covers. Pixel row
 * {@code y} covers the lines from {@link #lineAt}{@code (y)} up to {@code lineAt(y + 1)}; a line
 * marks at least one row, so a short document's marks stay visible. Immutable.
 *
 * @param lineCount the document's lines when computed
 * @param revision the document revision the marks were computed from
 * @param rows a bit set of {@link #FIND}, {@link #SEARCH} and {@link #BOOKMARK} per pixel row
 */
public record OverviewMarks(int lineCount, long revision, byte[] rows) {
  /** A find-bar match. */
  public static final int FIND = 1;

  /** A HyperSearch match. */
  public static final int SEARCH = 2;

  /** A bookmark. */
  public static final int BOOKMARK = 4;

  /** No marks. */
  public static final OverviewMarks EMPTY = new OverviewMarks(0, -1, new byte[0]);

  /**
   * Returns the strip's height.
   *
   * @return pixel rows
   */
  public int height() {
    return rows.length;
  }

  /**
   * Returns the first pixel row of a line.
   *
   * @param line a line
   * @param lineCount the document's lines
   * @param height the strip's pixel rows
   * @return the row, from 0 to {@code height - 1}; 0 for an empty strip
   */
  public static int rowOf(long line, long lineCount, int height) {
    if (lineCount <= 0 || height <= 0) {
      return 0;
    }
    return (int) Math.min(height - 1, Math.max(0, line) * height / lineCount);
  }

  /**
   * Returns the line at a pixel row, for a click.
   *
   * @param y the row
   * @param lineCount the document's lines
   * @param height the strip's pixel rows
   * @return the line, from 0 to {@code lineCount - 1}
   */
  public static int lineAt(int y, int lineCount, int height) {
    if (lineCount <= 0 || height <= 0) {
      return 0;
    }
    long row = Math.max(0, Math.min(height - 1, y));
    return (int) Math.min(lineCount - 1, (row * lineCount + height - 1) / height);
  }

  /**
   * Marks lines.
   *
   * @param lineCount the document's lines
   * @param revision the revision the lines belong to
   * @param height the strip's pixel rows
   * @param find find-bar match lines, ascending
   * @param search HyperSearch match lines, ascending
   * @param bookmarks bookmarked lines, ascending
   * @return the marks
   */
  public static OverviewMarks of(
      int lineCount, long revision, int height, int[] find, int[] search, int[] bookmarks) {
    byte[] rows = new byte[Math.max(0, height)];
    mark(rows, lineCount, find, FIND);
    mark(rows, lineCount, search, SEARCH);
    mark(rows, lineCount, bookmarks, BOOKMARK);
    return new OverviewMarks(lineCount, revision, rows);
  }

  private static void mark(byte[] rows, int lineCount, int[] lines, int kind) {
    if (rows.length == 0 || lineCount <= 0) {
      return;
    }
    int last = -1;
    for (int line : lines) {
      if (line == last || line < 0 || line >= lineCount) {
        continue;
      }
      last = line;
      int row = rowOf(line, lineCount, rows.length);
      rows[row] |= (byte) kind;
    }
  }
}
