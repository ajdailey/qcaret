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
import java.util.Arrays;
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
 * Listener#changed} for the text after it. A document change fires one display change, from its
 * first to its last edited line: {@code StyledText} shifts its per-line caches once per change, so
 * one change per edited line costs 10,000 spread carets about 5 times as much (measured). A
 * document change recomputes the rows of the changed lines on the UI thread; when the change spans
 * more than {@value #SYNC_CHARS} chars with wrap on, those lines get only their long-line splits
 * and the projection asks for a rewrap. Turning wrap on or off and changing the wrap width or, with
 * wrap on, the tab width also need a rewrap, which recomputes every line from a snapshot: the owner
 * takes a {@link Rewrap} from {@link #rewrapRequest}, runs {@link #compute} on a worker and passes
 * the result to {@link #install} on the UI thread, which applies it only while the document is
 * still at the snapshot's revision. Confined to the UI thread.
 *
 * <p>Folds: the view's collapsed regions ({@link FoldLayer}) hide their lines from the display
 * text, keyed by model line, without changing the rows of the other lines. A collapsed region's
 * first line shows, followed by its last line's delimiter; a hidden line has no rows and no chars,
 * and a model offset in hidden text maps to the end of the region's first line. An edit that
 * touches a collapsed region's hidden text, its first line's line break included, expands the
 * region; typing on the first line keeps it. The available regions come from {@link
 * #setFoldRegions}; until the next call they follow edits approximately. Each view holds its own
 * folds; a projection made from {@link #currentLayout} starts with none.
 */
public final class DisplayProjection implements DocumentListener {
  /** Chars per row at most, wrap on or off: longer rows make the widget's layout stall. */
  public static final int MAX_ROW_CHARS = 16_384;

  /** Columns per tab stop of a new document. */
  public static final int TAB_WIDTH = Document.DEFAULT_TAB_WIDTH;

  /** Chars a change may span and still be rewrapped on the UI thread. */
  static final int SYNC_CHARS = 1 << 20;

  /** Lines between cancellation checks on a worker. */
  static final int CHECK_LINES = 1024;

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
    private final int tabWidth;
    private final RowIndex index;

    private Layout(TextSnapshot text, int wrapColumns, int tabWidth, RowIndex index) {
      this.text = text;
      this.wrapColumns = wrapColumns;
      this.tabWidth = tabWidth;
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
      return new Layout(text, 0, TAB_WIDTH, new RowIndex());
    }
  }

  private final Document document;
  private final int maxRowChars;
  private final Map<Integer, String> rowCache = new HashMap<Integer, String>();
  private Listener listener;
  private TextSnapshot text;
  private RowIndex index;
  private int appliedColumns;
  private int tabWidth;
  private int appliedTabWidth;
  private boolean wrap;
  private int viewColumns;
  private long generation;
  private int pendingColumns = -1;
  private int pendingTabWidth;
  private Runnable rewrapHook;
  private boolean disposed;
  private final ProjectionFolds folding;
  private long linesVisited;
  private long linesMeasured;

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
    this.tabWidth = document.tabWidth();
    this.appliedTabWidth = layout.tabWidth;
    this.wrap = layout.wrapColumns > 0;
    this.viewColumns = layout.wrapColumns;
    this.folding =
        new ProjectionFolds(
            new ProjectionFolds.Host() {
              @Override
              public TextSnapshot text() {
                return text;
              }

              @Override
              public RowIndex rows() {
                return index;
              }

              @Override
              public void changing(DisplayChange change) {
                fireChanging(change);
              }

              @Override
              public void changed(DisplayChange change) {
                rowCache.clear();
                fireChanged(change);
              }
            });
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
    return layout(text, wrapColumns, TAB_WIDTH, MAX_ROW_CHARS, token);
  }

  static Layout layout(
      TextSnapshot text, int wrapColumns, int tabWidth, int maxRowChars, CancellationToken token) {
    RowIndex.Builder builder = new RowIndex.Builder();
    rows(text, 0, text.lineCount() - 1, 0, wrapColumns, tabWidth, maxRowChars, token, builder);
    return new Layout(text, wrapColumns, tabWidth, builder.build());
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
      int tabWidth,
      int maxRowChars,
      CancellationToken token,
      RowIndex.Builder builder) {
    builder.visit(to - from + 1);
    LineView line = new LineView();
    if (wrapColumns > 0) {
      for (int i = from; i <= to; i++) {
        if ((i - from) % CHECK_LINES == 0 && token != null) {
          token.check();
        }
        text.lineView(i, line);
        builder.add(i - shift, RowBreaks.compute(line, wrapColumns, maxRowChars, tabWidth));
      }
      return;
    }
    List<Integer> longLines = longLines(text, from, to, maxRowChars);
    for (int i : longLines) {
      if (token != null) {
        token.check();
      }
      text.lineView(i, line);
      builder.add(i - shift, RowBreaks.compute(line, 0, maxRowChars, tabWidth));
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
   * Returns the columns per tab stop the rows use.
   *
   * @return the tab width
   */
  public int tabWidth() {
    return tabWidth;
  }

  /**
   * Sets the columns per tab stop; with wrap on the rows need a rewrap.
   *
   * @param columns from 1 to {@link Document#MAX_TAB_WIDTH}
   */
  public void setTabWidth(int columns) {
    tabWidth = columns;
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
    private final int tabWidth;
    private final int maxRowChars;

    private Rewrap(
        long job,
        long revision,
        TextSnapshot snapshot,
        int columns,
        int tabWidth,
        int maxRowChars) {
      this.job = job;
      this.revision = revision;
      this.snapshot = snapshot;
      this.columns = columns;
      this.tabWidth = tabWidth;
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
    if (target == pendingColumns && (target == 0 || tabWidth == pendingTabWidth)) {
      return null;
    }
    generation++;
    if (target == appliedColumns && (target == 0 || tabWidth == appliedTabWidth)) {
      pendingColumns = -1;
      return null;
    }
    pendingColumns = target;
    pendingTabWidth = tabWidth;
    return new Rewrap(
        generation, document.revision(), document.text(), target, tabWidth, maxRowChars);
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
    return layout(request.snapshot, request.columns, request.tabWidth, request.maxRowChars, token);
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
    FoldLayer measured = folding.layer().remeasured(text, result.index);
    int newRows = text.lineCount() + result.index.totalBreaks() - measured.totalRows();
    int newLength = text.length() + result.index.totalBreaks() - measured.totalChars();
    DisplayChange change = new DisplayChange(0, oldLength, newLength, oldRows - 1, newRows - 1);
    fireChanging(change);
    index = result.index;
    folding.setLayer(measured);
    appliedColumns = result.wrapColumns;
    appliedTabWidth = result.tabWidth;
    rowCache.clear();
    fireChanged(change);
  }

  // Document changes.

  @Override
  public void textChanged(DocumentChange change) {
    TextSnapshot before = text;
    TextSnapshot after = document.text();
    FoldLayer folds = folding.layer();
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
      rows(after, g[2], g[3], g[2] - g[0], rowColumns, tabWidth, maxRowChars, null, builder);
      builders.add(builder);
      linesVisited += builder.visited();
      linesMeasured += builder.measured();
      oldGroupBreaks += index.breaksBefore(g[1] + 1) - index.breaksBefore(g[0]);
      newGroupBreaks += builder.build().totalBreaks();
    }
    int[] head = groups.get(0);
    int[] tail = groups.get(groups.size() - 1);
    int lineDelta = after.lineCount() - before.lineCount();
    int breakDelta = newGroupBreaks - oldGroupBreaks;
    FoldLayer.Edited edited =
        folds.afterEdit(
            before,
            after,
            change.edits(),
            groups,
            deferred,
            index,
            after.length() - before.length() + breakDelta,
            lineDelta + breakDelta);
    FoldLayer next = edited.layer();
    int from = edited.from();
    int to = edited.to();
    boolean toEnd = to == before.lineCount() - 1;
    int start = before.offsetOfLine(from) + index.breaksBefore(from);
    int oldEnd =
        toEnd
            ? before.length() + index.totalBreaks()
            : before.offsetOfLine(to + 1) + index.breaksBefore(to + 1);
    int oldEndRow =
        toEnd ? before.lineCount() + index.totalBreaks() - 1 : to + 1 + index.breaksBefore(to + 1);
    int oldRows = oldEndRow - from - index.breaksBefore(from);
    int k0 = folds.firstSpanFrom(from);
    int k1 = folds.firstSpanFrom(to + 1);
    int n0 = next.firstSpanFrom(from);
    int n1 = next.firstSpanFrom(to + lineDelta + 1);
    int oldHiddenChars = folds.charsBefore(k1) - folds.charsBefore(k0);
    int oldHiddenRows = folds.rowsBefore(k1) - folds.rowsBefore(k0);
    int newHiddenChars = next.charsBefore(n1) - next.charsBefore(n0);
    int newHiddenRows = next.rowsBefore(n1) - next.rowsBefore(n0);
    int oldChars = oldEnd - start;
    int newChars = oldChars + after.length() - before.length() + breakDelta;
    DisplayChange display =
        new DisplayChange(
            start - folds.charsBefore(k0),
            oldChars - oldHiddenChars,
            newChars - newHiddenChars,
            oldRows - oldHiddenRows,
            oldRows + lineDelta + breakDelta - newHiddenRows);
    fireChanging(display);
    replaceRows(groups, builders);
    next.place(after, index, n0, n1);
    folding.shiftRegions(before, after, change.edits(), head[0], tail[1]);
    text = after;
    folding.setLayer(next);
    rowCache.clear();
    fireChanged(display);
    if (deferred) {
      appliedColumns = 0;
      if (rewrapHook != null) {
        rewrapHook.run();
      }
    }
  }

  /** Replaces each group's lines in the row index, one pass for several groups. */
  private void replaceRows(List<int[]> groups, List<RowIndex.Builder> builders) {
    int n = groups.size();
    if (n == 1) {
      int[] g = groups.get(0);
      index.replace(g[0], g[1] + 1, g[0] + (g[3] - g[2]) + 1, builders.get(0));
      return;
    }
    int[] from = new int[n];
    int[] oldEnd = new int[n];
    int[] newEnd = new int[n];
    for (int i = 0; i < n; i++) {
      int[] g = groups.get(i);
      from[i] = g[0];
      oldEnd[i] = g[1] + 1;
      newEnd[i] = g[0] + (g[3] - g[2]) + 1;
    }
    index.replaceAll(from, oldEnd, newEnd, builders.toArray(new RowIndex.Builder[0]));
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

  // Folding.

  /**
   * Replaces the fold regions the view offers. A collapsed region expands when no offered region
   * starts on its first line, and takes the end of the one that does.
   *
   * @param regions {@code {startLine, endLine}} pairs; a pair whose end is not below its start or
   *     is past the text is skipped, and of pairs with one start line the longest is kept
   */
  public void setFoldRegions(List<int[]> regions) {
    folding.setFoldRegions(regions);
  }

  /**
   * Returns the first lines of the collapsed regions.
   *
   * @return the zero-based lines, ascending
   */
  public int[] collapsedLines() {
    FoldLayer folds = folding.layer();
    return Arrays.copyOf(folds.starts(), folds.regionCount());
  }

  /**
   * Returns the number of fold regions the view offers.
   *
   * @return regions from the last {@link #setFoldRegions}, moved by the edits since
   */
  public int foldRegionCount() {
    return folding.regionCount();
  }

  /**
   * Returns the last line of the region a line starts, collapsed or not.
   *
   * @param line a model line
   * @return the region's end line, or -1 when no region starts there
   */
  public int foldEnd(int line) {
    return folding.foldEnd(line);
  }

  /**
   * Reports whether a collapsed region starts at a line.
   *
   * @param line a model line
   * @return true for a collapsed region's first line
   */
  public boolean collapsed(int line) {
    return folding.layer().regionAt(line) >= 0;
  }

  /**
   * Returns the number of collapsed regions.
   *
   * @return collapsed regions, nested ones included
   */
  public int collapsedCount() {
    return folding.layer().regionCount();
  }

  /**
   * Reports whether a collapsed region hides a line.
   *
   * @param line a model line
   * @return true when the line has no rows
   */
  public boolean hidden(int line) {
    return folding.layer().spanHiding(line) >= 0;
  }

  /**
   * Returns the shown offset nearest a model offset: the offset itself when its line shows, else,
   * forward, the start of the line after the hidden lines, or the end of the line before them.
   *
   * @param offset a model offset
   * @param forward true to move past the hidden lines
   * @return a model offset on a shown line
   */
  public int visibleOffset(int offset, boolean forward) {
    return folding.visibleOffset(offset, forward);
  }

  /**
   * Collapses the region starting at a line, or else the innermost expanded region holding it.
   *
   * @param line a model line
   * @return false when no expanded region holds the line
   */
  public boolean fold(int line) {
    return folding.fold(line);
  }

  /**
   * Collapses the region starting at a line.
   *
   * @param line a model line
   * @return false when no expanded region starts there
   */
  public boolean foldAt(int line) {
    return folding.foldAt(line);
  }

  /**
   * Expands the collapsed region starting at a line; regions nested in it stay collapsed.
   *
   * @param line a model line
   * @return false when no collapsed region starts there
   */
  public boolean unfold(int line) {
    return folding.unfold(line);
  }

  /**
   * Collapses every region, nested ones included.
   *
   * @return false when every region was collapsed
   */
  public boolean foldAll() {
    return folding.foldAll();
  }

  /** What became of a {@link FoldAll} at {@link #installFoldAll}. */
  public enum FoldAllOutcome {
    /** The regions collapsed. */
    APPLIED,
    /** A fold or unfold since the request; nothing changed. */
    DROPPED,
    /** The text, rows or offered regions changed since the request; request again. */
    STALE
  }

  /**
   * A Fold All whose spans are measured on a worker: requested by {@link #foldAllRequest}, measured
   * by {@link #computeFoldAll}, applied by {@link #installFoldAll}.
   */
  public static final class FoldAll {
    private final long revision;
    private final long ops;
    private final TextSnapshot snapshot;
    private final RowIndex live;
    private final RowIndex rows;
    private final FoldLayer reuse;
    private final int[] offered;
    private final int[] starts;
    private final int[] ends;
    private FoldLayer result;

    private FoldAll(
        long revision,
        long ops,
        TextSnapshot snapshot,
        RowIndex live,
        FoldLayer reuse,
        int[] offered,
        int[] starts,
        int[] ends) {
      this.revision = revision;
      this.ops = ops;
      this.snapshot = snapshot;
      this.live = live;
      this.rows = live.copy();
      this.reuse = reuse;
      this.offered = offered;
      this.starts = starts;
      this.ends = ends;
    }
  }

  /**
   * Returns a Fold All of every region, nested ones included, for {@link #computeFoldAll} on a
   * worker. Takes a snapshot and a copy of the rows.
   *
   * @return the request, or null when every region is collapsed
   */
  public FoldAll foldAllRequest() {
    int[][] merged = folding.foldAllRegions();
    if (merged.length == 0) {
      return null;
    }
    return new FoldAll(
        document.revision(),
        folding.ops(),
        text,
        index,
        folding.layer(),
        folding.offered(),
        merged[0],
        merged[1]);
  }

  /**
   * Measures a Fold All's spans in its snapshot and rows.
   *
   * @param request the request
   */
  @WorkerThread
  public static void computeFoldAll(FoldAll request) {
    request.result =
        FoldLayer.of(request.starts, request.ends, request.snapshot, request.rows, request.reuse);
  }

  /**
   * Collapses a Fold All's regions when the text, the rows, the offered regions and the collapsed
   * ones are those of its request.
   *
   * @param request a request {@link #computeFoldAll} measured, or one whose measuring failed
   * @return the outcome
   */
  public FoldAllOutcome installFoldAll(FoldAll request) {
    if (disposed || request.result == null || request.ops != folding.ops()) {
      return FoldAllOutcome.DROPPED;
    }
    if (request.revision != document.revision()
        || request.live != index
        || request.reuse != folding.layer()
        || request.offered != folding.offered()) {
      return FoldAllOutcome.STALE;
    }
    folding.apply(request.result, 0, text.lineCount() - 1);
    return FoldAllOutcome.APPLIED;
  }

  /**
   * Expands every collapsed region.
   *
   * @return false when none was collapsed
   */
  public boolean unfoldAll() {
    return folding.unfoldAll();
  }

  /**
   * Expands every collapsed region that hides a line.
   *
   * @param line a model line
   * @return false when no region hides it
   */
  public boolean reveal(int line) {
    return folding.reveal(line);
  }

  // Queries, for the text last reported.

  /**
   * Returns the display text's length.
   *
   * @return model chars plus virtual breaks, less the hidden ones
   */
  public int length() {
    return text.length() + index.totalBreaks() - folding.layer().totalChars();
  }

  /**
   * Returns the number of display rows.
   *
   * @return model lines plus virtual breaks, less the hidden ones
   */
  public int rowCount() {
    return text.lineCount() + index.totalBreaks() - folding.layer().totalRows();
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
    return unfoldedLineOfRow(folding.rowToUnfolded(row));
  }

  private int unfoldedLineOfRow(int row) {
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
    return unfoldedSubRow(folding.rowToUnfolded(row));
  }

  private int unfoldedSubRow(int row) {
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
   * Returns a model line's first row; a hidden line gives the last row of the line shown in its
   * place.
   *
   * @param line a model line
   * @return the display row
   */
  public int rowOfLine(int line) {
    return folding.rowToFolded(line + index.breaksBefore(line));
  }

  /**
   * Returns the display offset of a row's first char.
   *
   * @param row a display row
   * @return the display offset
   */
  public int rowStart(int row) {
    return folding.toFolded(unfoldedRowStart(folding.rowToUnfolded(row)));
  }

  private int unfoldedRowStart(int row) {
    int line = unfoldedLineOfRow(row);
    int sub = unfoldedSubRow(row);
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
    int unfolded = folding.rowToUnfolded(row);
    int line = unfoldedLineOfRow(unfolded);
    int sub = unfoldedSubRow(unfolded);
    String value = text.subSequence(rowModelStart(line, sub), rowModelEnd(line, sub)).toString();
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
    return folding.rowToFolded(unfoldedRowAt(folding.toUnfolded(offset)));
  }

  private int unfoldedRowAt(int offset) {
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
   * offset; an offset inside a CR LF delimiter gives the offset before it; the end of a collapsed
   * region's first line gives that line's end.
   *
   * @param offset a display offset from 0 to {@link #length()}
   * @return the model offset
   */
  public int toModel(int offset) {
    int unfolded = folding.toUnfolded(offset);
    int row = unfoldedRowAt(unfolded);
    int line = unfoldedLineOfRow(row);
    int sub = unfoldedSubRow(row);
    int start = rowModelStart(line, sub);
    int length = rowModelEnd(line, sub) - start;
    return start + Math.min(unfolded - unfoldedRowStart(row), length);
  }

  /**
   * Maps a model offset to the display; an offset at a virtual break maps to the next row's start,
   * and an offset in hidden text to the end of the collapsed region's first line.
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
    return folding.toFolded(offset + breaks);
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

  /**
   * Returns the delimiter after a row: {@code \n} for a virtual break, else the model's; after a
   * collapsed region's first line, its last hidden line's.
   */
  private String delimiter(int row, int rowEnd, int nextRowStart) {
    int line = lineOfRow(row);
    if (row + 1 < rowCount() && lineOfRow(row + 1) == line) {
      return "\n";
    }
    FoldLayer folds = folding.layer();
    int k = folds.spanWithHead(line);
    int modelEnd = k >= 0 ? FoldLayer.lineEnd(text, folds.last(k)) : rowModelEnd(line, subRow(row));
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
   * Returns a copy of the rows the projection shows, for another view of the document to start
   * from. Call between changes, when the projection answers for the document's current text.
   *
   * @return the layout, independent of this projection
   */
  public Layout currentLayout() {
    return new Layout(text, appliedColumns, tabWidth, index.copy());
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

  /** Returns the collapsed regions, for tests. */
  FoldLayer folds() {
    return folding.layer();
  }

  /** Returns the lines document changes have read so far, measured or only scanned, for tests. */
  long linesVisited() {
    return linesVisited;
  }

  /** Returns the lines document changes have passed to {@link RowBreaks} so far, for tests. */
  long linesMeasured() {
    return linesMeasured;
  }
}
