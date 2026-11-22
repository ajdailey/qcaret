package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.List;

/**
 * The text a Basic preview pane shows and its offset and line maps to the rendered text. The
 * display text is the rendered text with some table rows replaced by a {@link TableWrap.Row}: one
 * rendered line becomes the row's display lines, whose chars come from the rendered row in another
 * order, plus the tabs, line breaks and {@link TableWrap#PAD} chars the layout adds. Every other
 * line is the rendered line.
 *
 * <p>Maps:
 *
 * <ul>
 *   <li>A display char copied from the rendered text maps to that char and back.
 *   <li>A display char the layout added maps to the rendered offset after the piece before it on
 *       its row, or the row's start.
 *   <li>A rendered char a row leaves out (a space where a cell breaks) maps to the display offset
 *       after the piece that ends nearest before it.
 *   <li>A display line of a row maps to the row's rendered line; a rendered line maps to its first
 *       display line.
 * </ul>
 *
 * Not thread-safe.
 */
public final class MarkdownDisplayMap {
  /**
   * A change of the display text.
   *
   * @param start the first display offset replaced
   * @param length the display chars replaced
   * @param text the chars that replace them
   */
  public record Change(int start, int length, String text) {}

  private final RenderedMarkdown rendered;
  private String text;
  private int[] lines = new int[0];
  private TableWrap.Row[] rows = new TableWrap.Row[0];
  private int[] displayLines = new int[0];
  private int[] displayStarts = new int[0];

  /**
   * Creates the identity map of a render.
   *
   * @param rendered the render
   */
  public MarkdownDisplayMap(RenderedMarkdown rendered) {
    this.rendered = rendered;
    this.text = rendered.text();
  }

  /**
   * Returns the display text.
   *
   * @return the text
   */
  public String text() {
    return text;
  }

  /**
   * Counts the display lines.
   *
   * @return at least 1
   */
  public int lineCount() {
    int n = rows.length;
    return rendered.lineCount() + (n == 0 ? 0 : extraLinesThrough(n - 1));
  }

  /**
   * Returns a rendered line's display row, if it has one.
   *
   * @param line a rendered line
   * @return the row, or null for a line shown as rendered
   */
  public TableWrap.Row row(int line) {
    int i = rowAtLine(line);
    return i >= 0 && lines[i] == line ? rows[i] : null;
  }

  /**
   * Maps a display line to the rendered line it shows.
   *
   * @param displayLine a display line
   * @return the rendered line
   */
  public int renderedLine(int displayLine) {
    int i = rowAtDisplayLine(displayLine);
    if (i < 0) {
      return displayLine;
    }
    if (displayLine < displayLines[i] + rows[i].lines()) {
      return lines[i];
    }
    return displayLine - extraLinesThrough(i);
  }

  /**
   * Returns a display line's index within its row.
   *
   * @param displayLine a display line
   * @return 0 for a row's first line and for a line shown as rendered
   */
  public int subLine(int displayLine) {
    int i = rowAtDisplayLine(displayLine);
    return i >= 0 && displayLine < displayLines[i] + rows[i].lines()
        ? displayLine - displayLines[i]
        : 0;
  }

  /**
   * Maps a rendered line to its first display line.
   *
   * @param line a rendered line
   * @return the display line
   */
  public int displayLine(int line) {
    int i = rowAtLine(line);
    if (i < 0) {
      return line;
    }
    return lines[i] == line ? displayLines[i] : line + extraLinesThrough(i);
  }

  /**
   * Counts a rendered line's display lines.
   *
   * @param line a rendered line
   * @return 1 for a line shown as rendered
   */
  public int displayLineCount(int line) {
    TableWrap.Row row = row(line);
    return row == null ? 1 : row.lines();
  }

  /**
   * Maps a display offset to a rendered offset.
   *
   * @param offset a display offset, 0 to the display length
   * @return the rendered offset
   */
  public int rendered(int offset) {
    int i = rowAtOffset(offset);
    if (i < 0) {
      return offset;
    }
    int local = offset - displayStarts[i];
    if (local >= rows[i].text().length()) {
      return offset - shiftThrough(i);
    }
    int[] p = rows[i].pieces();
    int rowStart = rendered.lineStart(lines[i]);
    int after = 0;
    for (int k = 0; k < p.length && p[k] <= local; k += 3) {
      if (local < p[k] + p[k + 2]) {
        return rowStart + p[k + 1] + local - p[k];
      }
      after = p[k + 1] + p[k + 2];
    }
    return rowStart + after;
  }

  /**
   * Maps a rendered offset to a display offset.
   *
   * @param offset a rendered offset, 0 to the rendered length
   * @return the display offset
   */
  public int display(int offset) {
    int i = rowAtRenderedOffset(offset);
    if (i < 0) {
      return offset;
    }
    int rowStart = rendered.lineStart(lines[i]);
    int local = offset - rowStart;
    if (offset >= rowEnd(lines[i])) {
      return offset + shiftThrough(i);
    }
    int[] p = rows[i].pieces();
    int best = -1;
    int bestEnd = 0;
    for (int k = 0; k < p.length; k += 3) {
      if (p[k + 1] <= local && local < p[k + 1] + p[k + 2]) {
        return displayStarts[i] + p[k] + local - p[k + 1];
      }
      int end = p[k + 1] + p[k + 2];
      if (end <= local && (best < 0 || end >= bestEnd)) {
        best = k;
        bestEnd = end;
      }
    }
    return displayStarts[i] + (best < 0 ? 0 : p[best] + p[best + 2]);
  }

  /**
   * Returns the rendered range a display range shows: from the first to the last rendered char of
   * its pieces, which within a row may come from cells in any order.
   *
   * @param start the first display offset
   * @param end the display offset after the range
   * @return {@code {start, end}} in the rendered text
   */
  public int[] renderedRange(int start, int end) {
    int lo = Integer.MAX_VALUE;
    int hi = Integer.MIN_VALUE;
    int at = start;
    int i = rowAtOffset(start);
    if (i < 0 || start >= displayStarts[i] + rows[i].text().length()) {
      i++;
    }
    while (at < end) {
      if (i < rows.length && displayStarts[i] <= at) {
        int rowEnd = displayStarts[i] + rows[i].text().length();
        int rowStart = rendered.lineStart(lines[i]);
        int[] p = rows[i].pieces();
        for (int k = 0; k < p.length; k += 3) {
          int s = Math.max(at, displayStarts[i] + p[k]);
          int e = Math.min(end, displayStarts[i] + p[k] + p[k + 2]);
          if (s < e) {
            int r = rowStart + p[k + 1] - displayStarts[i] - p[k];
            lo = Math.min(lo, s + r);
            hi = Math.max(hi, e + r);
          }
        }
        at = rowEnd;
        i++;
      } else {
        int next = i < rows.length ? Math.min(end, displayStarts[i]) : end;
        int shift = i == 0 ? 0 : shiftThrough(i - 1);
        lo = Math.min(lo, at - shift);
        hi = Math.max(hi, next - shift);
        at = next;
      }
    }
    if (lo > hi) {
      int r = rendered(start);
      return new int[] {r, r};
    }
    return new int[] {lo, hi};
  }

  /**
   * Returns the pieces of a display line: the runs of its chars copied from the rendered text.
   *
   * @param displayLine a display line
   * @return {@code display, rendered, length} triples in absolute offsets, ascending in display
   */
  public int[] pieces(int displayLine) {
    int i = rowAtDisplayLine(displayLine);
    if (i < 0 || displayLine >= displayLines[i] + rows[i].lines()) {
      int line = renderedLine(displayLine);
      int start = rendered.lineStart(line);
      int length = rowEnd(line) - start;
      return length == 0 ? new int[0] : new int[] {display(start), start, length};
    }
    TableWrap.Row row = rows[i];
    String s = row.text();
    int sub = displayLine - displayLines[i];
    int lineStart = 0;
    for (int j = 0; j < sub; j++) {
      lineStart = s.indexOf('\n', lineStart) + 1;
    }
    int lineEnd = s.indexOf('\n', lineStart);
    if (lineEnd < 0) {
      lineEnd = s.length();
    }
    int rowStart = rendered.lineStart(lines[i]);
    int[] p = row.pieces();
    List<int[]> out = new ArrayList<int[]>();
    for (int k = 0; k < p.length; k += 3) {
      if (p[k] >= lineStart && p[k] < lineEnd) {
        out.add(new int[] {displayStarts[i] + p[k], rowStart + p[k + 1], p[k + 2]});
      }
    }
    int[] flat = new int[3 * out.size()];
    for (int k = 0; k < out.size(); k++) {
      System.arraycopy(out.get(k), 0, flat, 3 * k, 3);
    }
    return flat;
  }

  /**
   * Replaces the display rows of consecutive rendered lines.
   *
   * @param first the first rendered line
   * @param next one row per line from {@code first}; null shows a line as rendered
   * @return the change to the display text
   */
  public Change replace(int first, TableWrap.Row[] next) {
    int last = first + next.length - 1;
    int start = lineStart(first);
    int end = lineEnd(last);
    StringBuilder inserted = new StringBuilder();
    List<Integer> newLines = new ArrayList<Integer>();
    List<TableWrap.Row> newRows = new ArrayList<TableWrap.Row>();
    int k = 0;
    while (k < lines.length && lines[k] < first) {
      newLines.add(lines[k]);
      newRows.add(rows[k]);
      k++;
    }
    for (int j = 0; j < next.length; j++) {
      int line = first + j;
      if (j > 0) {
        inserted.append('\n');
      }
      if (next[j] == null) {
        inserted.append(rendered.text(), rendered.lineStart(line), rowEnd(line));
      } else {
        inserted.append(next[j].text());
        newLines.add(line);
        newRows.add(next[j]);
      }
    }
    while (k < lines.length && lines[k] <= last) {
      k++;
    }
    while (k < lines.length) {
      newLines.add(lines[k]);
      newRows.add(rows[k]);
      k++;
    }
    String replacement = inserted.toString();
    text = text.substring(0, start) + replacement + text.substring(end);
    lines = new int[newLines.size()];
    rows = newRows.toArray(new TableWrap.Row[0]);
    displayLines = new int[lines.length];
    displayStarts = new int[lines.length];
    int extraLines = 0;
    int shift = 0;
    for (int i = 0; i < lines.length; i++) {
      lines[i] = newLines.get(i);
      displayLines[i] = lines[i] + extraLines;
      int rowStart = rendered.lineStart(lines[i]);
      displayStarts[i] = rowStart + shift;
      extraLines += rows[i].lines() - 1;
      shift += rows[i].text().length() - (rowEnd(lines[i]) - rowStart);
    }
    return new Change(start, end - start, replacement);
  }

  /**
   * Returns the display offset a rendered line's display lines start at.
   *
   * @param line a rendered line
   * @return the offset
   */
  public int lineStart(int line) {
    int i = rowAtLine(line);
    return i >= 0 && lines[i] == line ? displayStarts[i] : display(rendered.lineStart(line));
  }

  /**
   * Returns the display offset after a rendered line's last display line, before its line break.
   *
   * @param line a rendered line
   * @return the offset
   */
  public int lineEnd(int line) {
    return display(rowEnd(line));
  }

  /** The offset after a rendered line's last char, before its line break. */
  private int rowEnd(int line) {
    return line + 1 < rendered.lineCount()
        ? rendered.lineStart(line + 1) - 1
        : rendered.text().length();
  }

  /** Display lines added by rows 0 to {@code i}. */
  private int extraLinesThrough(int i) {
    return displayLines[i] + rows[i].lines() - 1 - lines[i];
  }

  /** Display chars added by rows 0 to {@code i}. */
  private int shiftThrough(int i) {
    return displayStarts[i] + rows[i].text().length() - rowEnd(lines[i]);
  }

  /** The last row at or before a rendered line, or -1. */
  private int rowAtLine(int line) {
    return last(lines, line);
  }

  /** The last row starting at or before a display line, or -1. */
  private int rowAtDisplayLine(int displayLine) {
    return last(displayLines, displayLine);
  }

  /** The last row starting at or before a display offset, or -1. */
  private int rowAtOffset(int offset) {
    return last(displayStarts, offset);
  }

  /** The last row starting at or before a rendered offset, or -1. */
  private int rowAtRenderedOffset(int offset) {
    int lo = 0;
    int hi = lines.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (rendered.lineStart(lines[mid]) <= offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo - 1;
  }

  /** The last index whose value is at most {@code key}, or -1. */
  private static int last(int[] sorted, int key) {
    int lo = 0;
    int hi = sorted.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (sorted[mid] <= key) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo - 1;
  }
}
