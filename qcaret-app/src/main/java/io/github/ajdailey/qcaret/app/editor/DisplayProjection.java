package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Maps a {@link Document}'s text to the display text a view shows: model lines split into rows at
 * virtual breaks, one display char each, where word wrap ends a row or a line passes {@link
 * #maxRowChars}. A line without breaks maps one to one, so an unwrapped document of short lines
 * costs nothing. A display offset on a virtual break maps to the model offset of the break; a model
 * offset at a break maps to the start of the next row.
 *
 * <p>The projection answers for the text it last reported ({@link Listener}): during {@link
 * Listener#changing} it still answers for the text before the change, during {@link
 * Listener#changed} for the text after it. A document change recomputes the rows of the changed
 * lines on the UI thread; when the change spans more than {@value #SYNC_CHARS} chars with wrap on,
 * those lines get only their long-line splits and the projection asks for a rewrap. Turning wrap on
 * or off and changing the wrap width also need a rewrap, which recomputes every line from a
 * snapshot: the owner takes a {@link Rewrap} from {@link #rewrapRequest}, runs {@link #compute} on
 * a worker and passes the result to {@link #install} on the UI thread, which applies it only while
 * the document is still at the snapshot's revision. Confined to the UI thread.
 *
 * <p>Folds attach as a second sparse layer keyed by model line, a hidden line contributing no rows
 * and no chars; none is applied yet.
 */
public final class DisplayProjection implements DocumentListener {
  /** Chars per row at most, wrap on or off: longer rows make the widget's layout stall. */
  public static final int MAX_ROW_CHARS = 16_384;

  /** Columns per tab stop. */
  public static final int TAB_WIDTH = 4;

  /** Chars a change may span and still be rewrapped on the UI thread. */
  static final int SYNC_CHARS = 1 << 20;

  /** Lines between cancellation checks on a worker. */
  private static final int CHECK_LINES = 1024;

  /** Rows whose text is cached between changes. */
  private static final int CACHED_ROWS = 512;

  /** Receives the projection's display changes on the UI thread. */
  public interface Listener {
    /**
     * Called before a display change; the projection still answers for the old text.
     *
     * @param change the change
     */
    void changing(DisplayChange change);

    /**
     * Called after a display change; the projection answers for the new text.
     *
     * @param change the change
     */
    void changed(DisplayChange change);
  }

  /**
   * A replaced display range. Row counts are the row delimiters in the replaced and the new text.
   *
   * @param start the display offset where the change starts
   * @param replaceChars display chars replaced
   * @param newChars display chars inserted
   * @param replaceRows row delimiters in the replaced text
   * @param newRows row delimiters in the new text
   */
  public record DisplayChange(
      int start, int replaceChars, int newChars, int replaceRows, int newRows) {}

  /** Rows computed for a text on a worker, for a projection to start from. */
  public static final class Layout {
    private final TextSnapshot text;
    private final int wrapColumns;
    private final RowIndex index;

    private Layout(TextSnapshot text, int wrapColumns, RowIndex index) {
      this.text = text;
      this.wrapColumns = wrapColumns;
      this.index = index;
    }

    /**
     * Returns the rows of a text of at most {@link #MAX_ROW_CHARS} chars, such as a new document's:
     * one row per line, with nothing to compute.
     *
     * @param text the text
     * @return the layout without wrap
     * @throws IllegalArgumentException when the text is longer
     */
    public static Layout empty(TextSnapshot text) {
      if (text.length() > MAX_ROW_CHARS) {
        throw new IllegalArgumentException("text longer than a row");
      }
      return new Layout(text, 0, new RowIndex());
    }
  }

  private final Document document;
  private final int maxRowChars;
  private final Map<Integer, String> rowCache = new HashMap<Integer, String>();
  private Listener listener;
  private TextSnapshot text;
  private RowIndex index;
  private int appliedColumns;
  private boolean wrap;
  private int viewColumns;
  private long generation;
  private int pendingColumns = -1;
  private Runnable rewrapHook;
  private boolean disposed;

  /**
   * Creates a projection and starts following the document. Call on the UI thread.
   *
   * @param document the document, at the layout's text
   * @param layout the rows of the document's current text ({@link #layout})
   */
  public DisplayProjection(Document document, Layout layout) {
    this(document, layout, MAX_ROW_CHARS);
  }

  DisplayProjection(Document document, Layout layout, int maxRowChars) {
    this.document = document;
    this.maxRowChars = maxRowChars;
    this.text = layout.text;
    this.index = layout.index;
    this.appliedColumns = layout.wrapColumns;
    this.wrap = layout.wrapColumns > 0;
    this.viewColumns = layout.wrapColumns;
    document.addListener(this);
  }

  /**
   * Computes the rows of a text: the long-line splits, and word wrap when {@code wrapColumns} is
   * above 0. Runs on a worker for a large text.
   *
   * @param text the text
   * @param wrapColumns columns per row, or 0 for no wrap
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the layout
   * @throws CancellationException when canceled
   */
  @WorkerThread
  public static Layout layout(TextSnapshot text, int wrapColumns, CancellationToken token) {
    return layout(text, wrapColumns, MAX_ROW_CHARS, token);
  }

  static Layout layout(
      TextSnapshot text, int wrapColumns, int maxRowChars, CancellationToken token) {
    RowIndex.Builder builder = new RowIndex.Builder();
    rows(text, 0, text.lineCount() - 1, 0, wrapColumns, maxRowChars, token, builder);
    return new Layout(text, wrapColumns, builder.build());
  }

  /**
   * Adds the breaks of lines {@code [from, to]} to a builder, each keyed {@code shift} lines lower.
   * Without wrap only lines over the row limit are read char by char; they are found by one scan.
   */
  private static void rows(
      TextSnapshot text,
      int from,
      int to,
      int shift,
      int wrapColumns,
      int maxRowChars,
      CancellationToken token,
      RowIndex.Builder builder) {
    LineView line = new LineView();
    if (wrapColumns > 0) {
      for (int i = from; i <= to; i++) {
        if ((i - from) % CHECK_LINES == 0 && token != null) {
          token.check();
        }
        text.lineView(i, line);
        builder.add(i - shift, RowBreaks.compute(line, wrapColumns, maxRowChars, TAB_WIDTH));
      }
      return;
    }
    List<Integer> longLines = longLines(text, from, to, maxRowChars);
    for (int i : longLines) {
      if (token != null) {
        token.check();
      }
      text.lineView(i, line);
      builder.add(i - shift, RowBreaks.compute(line, 0, maxRowChars, TAB_WIDTH));
    }
  }

  /** Returns the lines in {@code [from, to]} longer than {@code max} chars, by one scan. */
  static List<Integer> longLines(TextSnapshot text, final int from, int to, final int max) {
    final List<Integer> out = new ArrayList<Integer>();
    int start = text.offsetOfLine(from);
    int end = to + 1 < text.lineCount() ? text.offsetOfLine(to + 1) : text.length();
    final int[] state = {from, 0, 0};
    text.readChunks(
        start,
        end,
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int s, int e) {
            int line = state[0];
            int run = state[1];
            boolean cr = state[2] != 0;
            for (int i = s; i < e; i++) {
              char c = chunk.charAt(i);
              if (c == '\n') {
                if (!cr) {
                  if (run > max) {
                    out.add(line);
                  }
                  line++;
                  run = 0;
                }
                cr = false;
              } else if (c == '\r') {
                if (run > max) {
                  out.add(line);
                }
                line++;
                run = 0;
                cr = true;
              } else {
                run++;
                cr = false;
              }
            }
            state[0] = line;
            state[1] = run;
            state[2] = cr ? 1 : 0;
          }
        });
    if (state[1] > max && state[0] <= to) {
      out.add(state[0]);
    }
    return out;
  }

  /**
   * Sets the listener, the view's content adapter.
   *
   * @param value the listener, or null
   */
  public void setListener(Listener value) {
    listener = value;
  }

  /**
   * Returns the document.
   *
   * @return the document projected
   */
  public Document document() {
    return document;
  }

  /** Stops following the document; a rewrap installed afterwards is ignored. */
  public void dispose() {
    if (!disposed) {
      disposed = true;
      document.removeListener(this);
    }
  }

  // Word wrap.

  /**
   * Reports whether word wrap is on.
   *
   * @return true when requested, also before the rewrap applied it
   */
  public boolean wrap() {
    return wrap;
  }

  /**
   * Turns word wrap on or off; the rows change when a rewrap installs.
   *
   * @param on true for wrap at the view's width
   */
  public void setWrap(boolean on) {
    wrap = on;
  }

  /**
   * Sets the view's width in columns, the wrap width; a changed width with wrap on needs a rewrap.
   *
   * @param columns columns that fit the view, at least 1
   */
  public void setViewColumns(int columns) {
    viewColumns = Math.max(1, columns);
  }

  /**
   * Reports whether a rewrap is out.
   *
   * @return true from {@link #rewrapRequest} until its {@link #install}
   */
  public boolean rewrapPending() {
    return pendingColumns >= 0;
  }

  private int targetColumns() {
    return wrap ? Math.max(1, viewColumns) : 0;
  }

  /** A rewrap of the whole text: computed by {@link #compute}, applied by {@link #install}. */
  public static final class Rewrap {
    private final long job;
    private final long revision;
    private final TextSnapshot snapshot;
    private final int columns;
    private final int maxRowChars;

    private Rewrap(long job, long revision, TextSnapshot snapshot, int columns, int maxRowChars) {
      this.job = job;
      this.revision = revision;
      this.snapshot = snapshot;
      this.columns = columns;
      this.maxRowChars = maxRowChars;
    }
  }

  /**
   * Sets the hook that runs when the projection needs a rewrap the owner did not ask for: after a
   * change too large to rewrap on the UI thread.
   *
   * @param hook calls {@link #rewrapRequest}, or null
   */
  public void setRewrapHook(Runnable hook) {
    rewrapHook = hook;
  }

  /**
   * Returns the rewrap the rows need, or null when they match the wrap setting or a rewrap for it
   * is already out. A new request supersedes an earlier one, whose {@link #install} is then
   * ignored.
   *
   * @return the request, or null
   */
  public Rewrap rewrapRequest() {
    if (disposed) {
      return null;
    }
    int target = targetColumns();
    if (target == pendingColumns) {
      return null;
    }
    generation++;
    if (target == appliedColumns) {
      pendingColumns = -1;
      return null;
    }
    pendingColumns = target;
    return new Rewrap(generation, document.revision(), document.text(), target, maxRowChars);
  }

  /**
   * Computes a rewrap's rows.
   *
   * @param request the request
   * @param token checked every {@value #CHECK_LINES} lines
   * @return the rows
   * @throws CancellationException when canceled
   */
  @WorkerThread
  public static Layout compute(Rewrap request, CancellationToken token) {
    return layout(request.snapshot, request.columns, request.maxRowChars, token);
  }

  /**
   * Applies a rewrap's rows when the request is the latest and the document is still at its
   * revision; a stale result is dropped, and {@link #rewrapRequest} then asks again.
   *
   * @param request the request
   * @param result its rows
   */
  public void install(Rewrap request, Layout result) {
    if (disposed || request.job != generation) {
      return;
    }
    pendingColumns = -1;
    if (request.revision != document.revision()) {
      return;
    }
    int oldLength = length();
    int oldRows = rowCount();
    int newRows = text.lineCount() + result.index.totalBreaks();
    int newLength = text.length() + result.index.totalBreaks();
    DisplayChange change = new DisplayChange(0, oldLength, newLength, oldRows - 1, newRows - 1);
    fireChanging(change);
    index = result.index;
    appliedColumns = result.wrapColumns;
    rowCache.clear();
    fireChanged(change);
  }

  // Document changes.

  @Override
  public void textChanged(DocumentChange change) {
    TextSnapshot before = text;
    TextSnapshot after = document.text();
    List<int[]> groups = groups(before, after, change.edits());
    int columns = appliedColumns;
    boolean deferred = false;
    if (columns > 0) {
      long chars = 0;
      for (int[] g : groups) {
        chars += lineRangeChars(after, g[2], g[3]);
      }
      deferred = chars > SYNC_CHARS;
    }
    int rowColumns = deferred ? 0 : columns;
    List<RowIndex.Builder> builders = new ArrayList<RowIndex.Builder>(groups.size());
    int oldGroupBreaks = 0;
    int newGroupBreaks = 0;
    for (int[] g : groups) {
      RowIndex.Builder builder = new RowIndex.Builder();
      rows(after, g[2], g[3], g[2] - g[0], rowColumns, maxRowChars, null, builder);
      builders.add(builder);
      oldGroupBreaks += index.breaksBefore(g[1] + 1) - index.breaksBefore(g[0]);
      newGroupBreaks += builder.build().totalBreaks();
    }
    int[] head = groups.get(0);
    int[] tail = groups.get(groups.size() - 1);
    int firstLine = head[0];
    boolean toEnd = tail[1] == before.lineCount() - 1;
    int oldBreaks = index.breaksBefore(tail[1] + 1) - index.breaksBefore(firstLine);
    int newBreaks = oldBreaks - oldGroupBreaks + newGroupBreaks;
    int start = before.offsetOfLine(firstLine) + index.breaksBefore(firstLine);
    int oldEnd =
        toEnd
            ? before.length() + index.totalBreaks()
            : before.offsetOfLine(tail[1] + 1) + index.breaksBefore(tail[1] + 1);
    int newModelEnd = toEnd ? after.length() : after.offsetOfLine(tail[3] + 1);
    int newEnd = newModelEnd + index.breaksBefore(firstLine) + newBreaks;
    int oldRows = tail[1] - firstLine + 1 + oldBreaks - (toEnd ? 1 : 0);
    int newRows = tail[3] - head[2] + 1 + newBreaks - (toEnd ? 1 : 0);
    DisplayChange display =
        new DisplayChange(start, oldEnd - start, newEnd - start, oldRows, newRows);
    fireChanging(display);
    for (int i = groups.size() - 1; i >= 0; i--) {
      int[] g = groups.get(i);
      int newEndLine = g[0] + (g[3] - g[2]) + 1;
      index.replace(g[0], g[1] + 1, newEndLine, builders.get(i));
    }
    text = after;
    rowCache.clear();
    fireChanged(display);
    if (deferred) {
      appliedColumns = 0;
      if (rewrapHook != null) {
        rewrapHook.run();
      }
    }
  }

  @Override
  public void dirtyChanged(boolean dirty) {
    // Rows do not depend on the dirty state.
  }

  /**
   * Groups a change's edits into line ranges of the text before ({@code [0]} to {@code [1]}) and
   * after ({@code [2]} to {@code [3]}), inclusive. Each range takes one more line on each side, so
   * a CR and an LF that join or part at an edit's edge stay inside it; ranges that would touch
   * merge.
   */
  private static List<int[]> groups(TextSnapshot before, TextSnapshot after, List<TextEdit> edits) {
    List<int[]> out = new ArrayList<int[]>();
    int lastBefore = before.lineCount() - 1;
    int shift = 0;
    int[] current = null;
    int groupShift = 0;
    for (TextEdit edit : edits) {
      int b0 = Math.max(0, before.lineAt(edit.offset()) - 1);
      int b1 = Math.min(lastBefore, before.lineAt(edit.offset() + edit.removed().length()) + 1);
      if (current != null && b0 <= current[1] + 1) {
        current[1] = Math.max(current[1], b1);
      } else {
        if (current != null) {
          out.add(close(current, before, after, groupShift, shift));
        }
        current = new int[] {b0, b1, 0, 0};
        groupShift = shift;
      }
      shift += edit.inserted().length() - edit.removed().length();
    }
    out.add(close(current, before, after, groupShift, shift));
    return out;
  }

  /**
   * Fills the after range of a group. The group's first line starts at an offset no edit touched,
   * shifted by the edits before it; the line after its last starts likewise, shifted by the edits
   * up to its end.
   */
  private static int[] close(
      int[] group, TextSnapshot before, TextSnapshot after, int shiftBefore, int shiftAfter) {
    group[2] = after.lineAt(before.offsetOfLine(group[0]) + shiftBefore);
    if (group[1] == before.lineCount() - 1) {
      group[3] = after.lineCount() - 1;
    } else {
      group[3] = after.lineAt(before.offsetOfLine(group[1] + 1) + shiftAfter) - 1;
    }
    return group;
  }

  private static long lineRangeChars(TextSnapshot text, int from, int to) {
    int end = to + 1 < text.lineCount() ? text.offsetOfLine(to + 1) : text.length();
    return end - text.offsetOfLine(from);
  }

  private void fireChanging(DisplayChange change) {
    if (listener != null) {
      listener.changing(change);
    }
  }

  private void fireChanged(DisplayChange change) {
    if (listener != null) {
      listener.changed(change);
    }
  }

  // Queries, for the text last reported.

  /**
   * Returns the display text's length.
   *
   * @return model chars plus virtual breaks
   */
  public int length() {
    return text.length() + index.totalBreaks();
  }

  /**
   * Returns the number of display rows.
   *
   * @return model lines plus virtual breaks
   */
  public int rowCount() {
    return text.lineCount() + index.totalBreaks();
  }

  /**
   * Returns the number of model lines.
   *
   * @return the line count of the text last reported
   */
  public int lineCount() {
    return text.lineCount();
  }

  /**
   * Returns the model line a row shows.
   *
   * @param row a display row
   * @return the model line
   */
  public int lineOfRow(int row) {
    int j = index.entryAtOrBeforeRow(row);
    if (j >= 0) {
      int rowStart = index.line(j) + index.firstBreak(j);
      if (row <= rowStart + index.breakCount(j)) {
        return index.line(j);
      }
      return row - index.firstBreak(j + 1);
    }
    return row;
  }

  /**
   * Returns which row of its model line a row is.
   *
   * @param row a display row
   * @return 0 for a line's first row
   */
  public int subRow(int row) {
    int j = index.entryAtOrBeforeRow(row);
    if (j >= 0) {
      int rowStart = index.line(j) + index.firstBreak(j);
      if (row <= rowStart + index.breakCount(j)) {
        return row - rowStart;
      }
    }
    return 0;
  }

  /**
   * Returns a model line's first row.
   *
   * @param line a model line
   * @return the display row
   */
  public int rowOfLine(int line) {
    return line + index.breaksBefore(line);
  }

  /**
   * Returns the display offset of a row's first char.
   *
   * @param row a display row
   * @return the display offset
   */
  public int rowStart(int row) {
    int line = lineOfRow(row);
    int sub = subRow(row);
    int lineStart = text.offsetOfLine(line);
    if (sub == 0) {
      return lineStart + index.breaksBefore(line);
    }
    int j = index.entryOf(line);
    return lineStart + index.breakAt(j, sub - 1) + index.firstBreak(j) + sub;
  }

  /**
   * Returns a row's text without its delimiter.
   *
   * @param row a display row
   * @return the text
   */
  public String rowText(int row) {
    String cached = rowCache.get(row);
    if (cached != null) {
      return cached;
    }
    int line = lineOfRow(row);
    int sub = subRow(row);
    int lineStart = text.offsetOfLine(line);
    int from = rowModelStart(line, sub) - lineStart;
    int to = rowModelEnd(line, sub) - lineStart;
    String value = text.subSequence(lineStart + from, lineStart + to).toString();
    if (rowCache.size() >= CACHED_ROWS) {
      rowCache.clear();
    }
    rowCache.put(row, value);
    return value;
  }

  /** Returns the model offset where row {@code sub} of a line starts. */
  private int rowModelStart(int line, int sub) {
    int lineStart = text.offsetOfLine(line);
    return sub == 0 ? lineStart : lineStart + index.breakAt(index.entryOf(line), sub - 1);
  }

  /** Returns the model offset where row {@code sub} of a line ends, before any delimiter. */
  private int rowModelEnd(int line, int sub) {
    int j = index.entryOf(line);
    int lineStart = text.offsetOfLine(line);
    if (j >= 0 && sub < index.breakCount(j)) {
      return lineStart + index.breakAt(j, sub);
    }
    return lineStart + text.lineLength(line);
  }

  /**
   * Returns the row holding a display offset; an offset in a row's delimiter belongs to that row.
   *
   * @param offset a display offset from 0 to {@link #length()}
   * @return the row
   */
  public int rowAt(int offset) {
    int lo = 0;
    int hi = index.entries() - 1;
    int j = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (text.offsetOfLine(index.line(mid)) + index.firstBreak(mid) <= offset) {
        j = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (j >= 0) {
      int line = index.line(j);
      int lineStart = text.offsetOfLine(line);
      int base = lineStart + index.firstBreak(j);
      if (offset <= base + text.lineLength(line) + index.breakCount(j)) {
        int n = index.breakCount(j);
        int sub = 0;
        int a = 0;
        int b = n - 1;
        while (a <= b) {
          int mid = (a + b) >>> 1;
          if (base + index.breakAt(j, mid) + mid + 1 <= offset) {
            sub = mid + 1;
            a = mid + 1;
          } else {
            b = mid - 1;
          }
        }
        return line + index.firstBreak(j) + sub;
      }
    }
    int before = index.firstBreak(j + 1);
    return text.lineAt(offset - before) + before;
  }

  /**
   * Maps a display offset to the model. An offset on a virtual break gives the break's model
   * offset; an offset inside a CR LF delimiter gives the offset before it.
   *
   * @param offset a display offset from 0 to {@link #length()}
   * @return the model offset
   */
  public int toModel(int offset) {
    int row = rowAt(offset);
    int line = lineOfRow(row);
    int sub = subRow(row);
    int start = rowModelStart(line, sub);
    int length = rowModelEnd(line, sub) - start;
    return start + Math.min(offset - rowStart(row), length);
  }

  /**
   * Maps a model offset to the display; an offset at a virtual break maps to the next row's start.
   *
   * @param offset a model offset from 0 to the model length
   * @return the display offset
   */
  public int toDisplay(int offset) {
    int line = text.lineAt(offset);
    int column = offset - text.offsetOfLine(line);
    int j = index.lowerBound(line);
    int breaks = index.firstBreak(j);
    if (j < index.entries() && index.line(j) == line) {
      int n = index.breakCount(j);
      for (int k = 0; k < n && index.breakAt(j, k) <= column; k++) {
        breaks++;
      }
    }
    return offset + breaks;
  }

  /**
   * Returns display text: rows joined by their delimiters, a virtual break as {@code \n}.
   *
   * @param start the first display offset
   * @param length display chars
   * @return the text
   */
  public String textRange(int start, int length) {
    StringBuilder out = new StringBuilder(length);
    int end = start + length;
    int row = rowAt(start);
    int at = start;
    while (at < end) {
      int rowStart = rowStart(row);
      String rowText = rowText(row);
      int from = at - rowStart;
      if (from < rowText.length()) {
        int to = Math.min(rowText.length(), end - rowStart);
        out.append(rowText, from, to);
        at = rowStart + to;
        continue;
      }
      int next = row + 1 < rowCount() ? rowStart(row + 1) : length();
      String delimiter = delimiter(row, rowStart + rowText.length(), next);
      int d = at - rowStart - rowText.length();
      int to = Math.min(delimiter.length(), end - rowStart - rowText.length());
      out.append(delimiter, d, to);
      at = rowStart + rowText.length() + to;
      row++;
    }
    return out.toString();
  }

  /** Returns the delimiter after a row: {@code \n} for a virtual break, else the model's. */
  private String delimiter(int row, int rowEnd, int nextRowStart) {
    int line = lineOfRow(row);
    if (row + 1 < rowCount() && lineOfRow(row + 1) == line) {
      return "\n";
    }
    int modelEnd = rowModelEnd(line, subRow(row));
    return text.subSequence(modelEnd, modelEnd + (nextRowStart - rowEnd)).toString();
  }

  /**
   * Returns the model text of a model range.
   *
   * @param start the first model offset
   * @param end the offset after the last
   * @return the text
   */
  public String modelText(int start, int end) {
    return text.subSequence(start, end).toString();
  }

  /**
   * Returns the model text's length.
   *
   * @return chars
   */
  public int modelLength() {
    return text.length();
  }

  /**
   * Returns a model line's start offset.
   *
   * @param line a model line
   * @return the offset
   */
  public int lineStart(int line) {
    return text.offsetOfLine(line);
  }

  /**
   * Returns the model line holding an offset.
   *
   * @param offset a model offset
   * @return the line
   */
  public int lineAt(int offset) {
    return text.lineAt(offset);
  }

  /**
   * Returns a model line's breaks, for tests.
   *
   * @param line a model line
   * @return in-line offsets where its later rows start
   */
  int[] breaksOf(int line) {
    return index.breaksOf(line);
  }
}
