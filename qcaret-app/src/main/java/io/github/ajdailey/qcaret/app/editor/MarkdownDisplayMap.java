package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.CodeBlock;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The text a Basic preview pane shows and its offset and line maps to the rendered text. The
 * display text is the rendered text with some table rows replaced by a {@link TableWrap.Row}: one
 * rendered line becomes the row's display lines, whose chars come from the rendered row in another
 * order, plus the tabs, line breaks and {@link TableWrap#PAD} chars the layout adds. A padded
 * separator line ({@link BlockSpacing}) holds one {@link TableWrap#PAD}. Every other line is the
 * rendered line, except that a code line's leading spaces show as {@link #INDENT}: a wrapping
 * widget breaks after a space, so a first word too long to follow the indent would leave the indent
 * alone on a visual line.
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

  /** The no-break space a code line's leading space shows as. */
  public static final char INDENT = (char) 0x00A0;

  /** The row of a padded separator line. */
  private static final TableWrap.Row SPACER =
      new TableWrap.Row(
          String.valueOf(TableWrap.PAD), 1, new int[0], new int[] {0}, new int[][] {{}});

  private final RenderedMarkdown rendered;

  /** The display text, or null while {@link #edits} holds a newer one. */
  private String text;

  /** The display text after a {@link #replace}, edited in place; null before the first. */
  private StringBuilder edits;

  private DisplayRows index;

  /**
   * Creates the identity map of a render.
   *
   * @param rendered the render
   */
  public MarkdownDisplayMap(RenderedMarkdown rendered) {
    this(rendered, new int[0]);
  }

  /**
   * Creates the map of a render whose empty separator lines in {@code padded} hold a {@link
   * TableWrap#PAD}.
   *
   * @param rendered the render
   * @param padded ascending empty rendered lines, as {@link BlockSpacing#lines}
   */
  public MarkdownDisplayMap(RenderedMarkdown rendered, int[] padded) {
    this.rendered = rendered;
    String source = codeIndents(rendered);
    TableWrap.Row[] spacers = new TableWrap.Row[padded.length];
    Arrays.fill(spacers, SPACER);
    index = new DisplayRows(rendered, padded.clone(), spacers);
    if (padded.length == 0) {
      this.text = source;
      return;
    }
    StringBuilder out = new StringBuilder(source.length() + padded.length);
    int at = 0;
    for (int i = 0; i < padded.length; i++) {
      int start = rendered.lineStart(padded[i]);
      out.append(source, at, start).append(TableWrap.PAD);
      at = start;
    }
    this.text = out.append(source, at, source.length()).toString();
  }

  /** Returns the rendered text with each code line's leading spaces as {@link #INDENT}. */
  private static String codeIndents(RenderedMarkdown rendered) {
    String source = rendered.text();
    char[] chars = null;
    for (CodeBlock block : rendered.codeBlocks()) {
      for (int line = block.firstLine(); line < block.firstLine() + block.lineCount(); line++) {
        for (int i = rendered.lineStart(line);
            i < source.length() && source.charAt(i) == ' ';
            i++) {
          if (chars == null) {
            chars = source.toCharArray();
          }
          chars[i] = INDENT;
        }
      }
    }
    return chars == null ? source : new String(chars);
  }

  /**
   * Reports whether a rendered line is a padded separator line.
   *
   * @param line a rendered line
   * @return true when the line shows as one {@link TableWrap#PAD}
   */
  public boolean padded(int line) {
    return row(line) == SPACER;
  }

  /**
   * Returns the display text. After a {@link #replace} the first call copies it out of the edit
   * buffer.
   *
   * @return the text
   */
  public String text() {
    if (text == null) {
      text = edits.toString();
    }
    return text;
  }

  /**
   * Returns a display char without copying the text.
   *
   * @param offset a display offset
   * @return the char
   */
  public char charAt(int offset) {
    return text != null ? text.charAt(offset) : edits.charAt(offset);
  }

  /**
   * Counts the display lines.
   *
   * @return at least 1
   */
  public int lineCount() {
    int n = index.rows.length;
    return rendered.lineCount() + (n == 0 ? 0 : index.extraLinesThrough(n - 1));
  }

  /**
   * Returns a rendered line's display row, if it has one.
   *
   * @param line a rendered line
   * @return the row, or null for a line shown as rendered
   */
  public TableWrap.Row row(int line) {
    int i = index.atLine(line);
    return i >= 0 && index.lines[i] == line ? index.rows[i] : null;
  }

  /**
   * Maps a display line to the rendered line it shows.
   *
   * @param displayLine a display line
   * @return the rendered line
   */
  public int renderedLine(int displayLine) {
    int i = index.atDisplayLine(displayLine);
    if (i < 0) {
      return displayLine;
    }
    if (displayLine < index.displayLines[i] + index.rows[i].lines()) {
      return index.lines[i];
    }
    return displayLine - index.extraLinesThrough(i);
  }

  /**
   * Returns a display line's index within its row.
   *
   * @param displayLine a display line
   * @return 0 for a row's first line and for a line shown as rendered
   */
  public int subLine(int displayLine) {
    int i = index.atDisplayLine(displayLine);
    return i >= 0 && displayLine < index.displayLines[i] + index.rows[i].lines()
        ? displayLine - index.displayLines[i]
        : 0;
  }

  /**
   * Maps a rendered line to its first display line.
   *
   * @param line a rendered line
   * @return the display line
   */
  public int displayLine(int line) {
    int i = index.atLine(line);
    if (i < 0) {
      return line;
    }
    return index.lines[i] == line ? index.displayLines[i] : line + index.extraLinesThrough(i);
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
    int i = index.atOffset(offset);
    if (i < 0) {
      return offset;
    }
    int local = offset - index.displayStarts[i];
    if (local >= index.rows[i].text().length()) {
      return offset - index.shiftThrough(i);
    }
    int[] p = index.rows[i].pieces();
    int rowStart = rendered.lineStart(index.lines[i]);
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
    int i = index.atRenderedOffset(offset);
    if (i < 0) {
      return offset;
    }
    int rowStart = rendered.lineStart(index.lines[i]);
    int local = offset - rowStart;
    if (offset >= rowEnd(index.lines[i])) {
      return offset + index.shiftThrough(i);
    }
    int[] p = index.rows[i].pieces();
    int best = -1;
    int bestEnd = 0;
    for (int k = 0; k < p.length; k += 3) {
      if (p[k + 1] <= local && local < p[k + 1] + p[k + 2]) {
        return index.displayStarts[i] + p[k] + local - p[k + 1];
      }
      int end = p[k + 1] + p[k + 2];
      if (end <= local && (best < 0 || end >= bestEnd)) {
        best = k;
        bestEnd = end;
      }
    }
    return index.displayStarts[i] + (best < 0 ? 0 : p[best] + p[best + 2]);
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
    int i = index.atOffset(start);
    if (i < 0 || start >= index.displayStarts[i] + index.rows[i].text().length()) {
      i++;
    }
    while (at < end) {
      if (i < index.rows.length && index.displayStarts[i] <= at) {
        int rowEnd = index.displayStarts[i] + index.rows[i].text().length();
        int rowStart = rendered.lineStart(index.lines[i]);
        int[] p = index.rows[i].pieces();
        for (int k = 0; k < p.length; k += 3) {
          int s = Math.max(at, index.displayStarts[i] + p[k]);
          int e = Math.min(end, index.displayStarts[i] + p[k] + p[k + 2]);
          if (s < e) {
            int r = rowStart + p[k + 1] - index.displayStarts[i] - p[k];
            lo = Math.min(lo, s + r);
            hi = Math.max(hi, e + r);
          }
        }
        at = rowEnd;
        i++;
      } else {
        int next = i < index.rows.length ? Math.min(end, index.displayStarts[i]) : end;
        int shift = i == 0 ? 0 : index.shiftThrough(i - 1);
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
   * Returns the display range a rendered range selects. A range inside one table cell whose display
   * ends fall on different lines of a laid-out row selects its part on the first line, not the
   * other cells' segments between its ends.
   *
   * @param start the first rendered offset
   * @param end the rendered offset after the range
   * @return {@code {start, end}} in the display text, ascending
   */
  public int[] displayRange(int start, int end) {
    int from = display(start);
    int to = display(end);
    int i = index.atRenderedOffset(start);
    if (end > start
        && i >= 0
        && index.rows[i] != SPACER
        && end <= rowEnd(index.lines[i])
        && !tab(start, end)) {
      int local = start - rendered.lineStart(index.lines[i]);
      int[] p = index.rows[i].pieces();
      for (int k = 0; k < p.length; k += 3) {
        if (p[k + 1] <= local && local < p[k + 1] + p[k + 2]) {
          to = Math.min(to, index.displayStarts[i] + p[k] + p[k + 2]);
        }
      }
    }
    return new int[] {Math.min(from, to), Math.max(from, to)};
  }

  /** Reports whether the rendered range holds a tab, which separates cells. */
  private boolean tab(int start, int end) {
    String s = rendered.text();
    for (int k = start; k < end; k++) {
      if (s.charAt(k) == '\t') {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the pieces of a display line: the runs of its chars copied from the rendered text.
   *
   * @param displayLine a display line
   * @return {@code display, rendered, length} triples in absolute offsets, ascending in display
   */
  public int[] pieces(int displayLine) {
    int i = index.atDisplayLine(displayLine);
    if (i < 0 || displayLine >= index.displayLines[i] + index.rows[i].lines()) {
      int line = renderedLine(displayLine);
      int start = rendered.lineStart(line);
      int length = rowEnd(line) - start;
      return length == 0 ? new int[0] : new int[] {display(start), start, length};
    }
    TableWrap.Row row = index.rows[i];
    String s = row.text();
    int sub = displayLine - index.displayLines[i];
    int lineStart = 0;
    for (int j = 0; j < sub; j++) {
      lineStart = s.indexOf('\n', lineStart) + 1;
    }
    int lineEnd = s.indexOf('\n', lineStart);
    if (lineEnd < 0) {
      lineEnd = s.length();
    }
    int rowStart = rendered.lineStart(index.lines[i]);
    int[] p = row.pieces();
    List<int[]> out = new ArrayList<int[]>();
    for (int k = 0; k < p.length; k += 3) {
      if (p[k] >= lineStart && p[k] < lineEnd) {
        out.add(new int[] {index.displayStarts[i] + p[k], rowStart + p[k + 1], p[k + 2]});
      }
    }
    int[] flat = new int[3 * out.size()];
    for (int k = 0; k < out.size(); k++) {
      System.arraycopy(out.get(k), 0, flat, 3 * k, 3);
    }
    return flat;
  }

  /**
   * Replaces the display rows of consecutive rendered lines. The text is edited in place, so a
   * layout's steps do not copy the whole display text each.
   *
   * @param first the first rendered line
   * @param next one row per line from {@code first}; null shows a line as rendered
   * @return the change to the display text
   */
  public Change replace(int first, TableWrap.Row[] next) {
    int start = lineStart(first);
    int end = lineEnd(first + next.length - 1);
    StringBuilder inserted = new StringBuilder();
    for (int j = 0; j < next.length; j++) {
      int line = first + j;
      if (j > 0) {
        inserted.append('\n');
      }
      if (next[j] == null) {
        inserted.append(rendered.text(), rendered.lineStart(line), rowEnd(line));
      } else {
        inserted.append(next[j].text());
      }
    }
    String replacement = inserted.toString();
    if (edits == null) {
      edits = new StringBuilder(text);
    }
    edits.replace(start, end, replacement);
    text = null;
    index = index.replace(first, next);
    return new Change(start, end - start, replacement);
  }

  /**
   * Returns the display offset a rendered line's display lines start at.
   *
   * @param line a rendered line
   * @return the offset
   */
  public int lineStart(int line) {
    int i = index.atLine(line);
    return i >= 0 && index.lines[i] == line
        ? index.displayStarts[i]
        : display(rendered.lineStart(line));
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
    return DisplayRows.rowEnd(rendered, line);
  }
}
