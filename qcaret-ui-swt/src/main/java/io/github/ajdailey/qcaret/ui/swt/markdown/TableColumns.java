package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownDisplayMap;
import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.editor.TableWords;
import io.github.ajdailey.qcaret.app.editor.TableWrap;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Lays out the tables a pane shows, the tables in view first, in UI turns of at most {@value
 * #CELLS_PER_TURN} cells and {@value #TURN_MILLIS} ms: a turn takes another step while the last
 * step's time fits before its deadline. Per table:
 *
 * <ol>
 *   <li>{@link SpanWidths} measures each cell in its spans' fonts with a {@link GC} ({@link
 *       MarkdownTableLayout}).
 *   <li>A table wider than the pane's text width less its indent has its words measured ({@link
 *       TableWords}) and its columns fitted to that width.
 *   <li>{@link TableWrap} lays out its rows, which replace the table's lines in the {@link
 *       MarkdownDisplayMap} and the widget, {@value #ROWS_PER_STEP} rows per {@code
 *       replaceTextRange}, until the turn's time runs out. The top line and the selection keep
 *       their rendered text. A layout starts a turn of its own and ends it.
 * </ol>
 *
 * A replace lays out each line between the top line and the replaced rows whose height the widget
 * has not measured, and the new rows; {@code setText} would leave the widget to measure every line
 * again.
 *
 * <p>A width change lays out again each table whose columns change, from the measured widths. New
 * content reuses the layout and rows of a table whose text, spans and alignments equal one shown
 * before, so the rows go into the new map before the widget's text changes. Call on the UI thread.
 */
final class TableColumns {
  /** The most cells measured in one UI turn. */
  static final int CELLS_PER_TURN = 200;

  /** The time after which a turn stops at the next step. */
  static final int TURN_MILLIS = 8;

  /** Cells measured between two time checks. */
  private static final int CELLS_PER_STEP = 16;

  /** Rows laid out per text replace. */
  static final int ROWS_PER_STEP = 16;

  /** Pixels left and right of a cell's text. */
  static final int PADDING = 13;

  /** Pixels above a row's text. */
  static final int PAD_ABOVE = 6;

  /** Pixels below a row's text, before the pane's line spacing. */
  static final int PAD_BELOW = 4;

  private final StyledText text;
  private final MarkdownFonts fonts;
  private final UiDispatcher ui;
  private RenderedMarkdown rendered;
  private int[] spans = new int[0];
  private MarkdownDisplayMap map;
  private int width;
  private Table[] tables = new Table[0];
  private final List<Integer> queue = new ArrayList<Integer>();
  private long generation;
  private int turns;
  private long longestTurn;
  private int ascent = -1;
  private int descent;

  /** A table's layout state. */
  private static final class Table {
    final String key;
    final int firstLine;
    MarkdownTableLayout layout;
    TableWords words;
    int[] shown;
    int room = -1;
    int next = -1;

    Table(String key, int firstLine) {
      this.key = key;
      this.firstLine = firstLine;
    }
  }

  TableColumns(StyledText text, MarkdownFonts fonts, UiDispatcher ui) {
    this.text = text;
    this.fonts = fonts;
    this.ui = ui;
  }

  /**
   * Takes new content: a table equal to one shown before keeps its layout, and its rows go into the
   * map at once when laid out for the current width; the rest are queued.
   *
   * @param next the content shown
   * @param nextMap its display map, still showing the render as is; its text is not shown yet
   * @param topLine the first rendered line in view
   * @param bottomLine the last rendered line in view
   */
  void reset(MarkdownPreviewContent next, MarkdownDisplayMap nextMap, int topLine, int bottomLine) {
    generation++;
    Map<String, Table> before = new HashMap<String, Table>();
    for (Table t : tables) {
      if (t.layout.measured() && t.next < 0) {
        before.put(t.key, t);
      }
    }
    MarkdownDisplayMap previous = map;
    rendered = next.rendered();
    spans = next.spans();
    map = nextMap;
    List<MarkdownTable> all = rendered.tables();
    tables = new Table[all.size()];
    queue.clear();
    List<Integer> later = new ArrayList<Integer>();
    for (int i = 0; i < all.size(); i++) {
      MarkdownTable table = all.get(i);
      Table t = new Table(key(table), table.firstLine());
      Table old = before.remove(t.key);
      if (old != null) {
        t.layout = old.layout;
        t.words = old.words;
        if (old.shown != null && old.room == room(table)) {
          TableWrap.Row[] rows = new TableWrap.Row[table.rowCount()];
          for (int r = 0; r < rows.length; r++) {
            rows[r] = previous.row(old.firstLine + r);
          }
          map.replace(table.firstLine(), rows);
          t.shown = old.shown;
          t.room = old.room;
        }
      } else {
        t.layout = new MarkdownTableLayout(table, rendered.text(), PADDING);
      }
      tables[i] = t;
      if (t.shown == null) {
        boolean inView =
            table.firstLine() <= bottomLine && table.firstLine() + table.rowCount() > topLine;
        (inView ? queue : later).add(i);
      }
    }
    queue.addAll(later);
    if (!queue.isEmpty()) {
      schedule();
    }
  }

  /** The text, spans and alignments a reused layout has to match. */
  private String key(MarkdownTable table) {
    int start = rendered.lineStart(table.firstLine());
    int last = table.firstLine() + table.rowCount() - 1;
    int end =
        last + 1 < rendered.lineCount() ? rendered.lineStart(last + 1) : rendered.text().length();
    StringBuilder key = new StringBuilder(end - start + 64);
    key.append(table.alignments()).append('\u0000').append(rendered.text(), start, end);
    for (int i = MarkdownSpans.first(spans, start);
        3 * i < spans.length && spans[3 * i] < end;
        i++) {
      key.append('\u0000')
          .append(spans[3 * i] - start)
          .append(',')
          .append(spans[3 * i + 1] - start)
          .append(',')
          .append(spans[3 * i + 2]);
    }
    return key.toString();
  }

  /**
   * Takes the pane's text width and queues each measured table whose fit changes, the tables in
   * view first.
   *
   * @param next the text width in pixels
   * @param topLine the first rendered line in view
   * @param bottomLine the last rendered line in view
   */
  void setWidth(int next, int topLine, int bottomLine) {
    if (next == width) {
      return;
    }
    width = next;
    if (rendered == null) {
      return;
    }
    generation++;
    queue.clear();
    List<Integer> later = new ArrayList<Integer>();
    for (int i = 0; i < tables.length; i++) {
      MarkdownTable table = rendered.tables().get(i);
      Table t = tables[i];
      if (t.next >= 0) {
        // Rows laid out for the earlier width are in the map: lay out every row again.
        t.next = -1;
        t.shown = null;
      } else if (t.layout.measured() && t.room == room(table)) {
        continue;
      }
      boolean inView =
          table.firstLine() <= bottomLine && table.firstLine() + table.rowCount() > topLine;
      (inView ? queue : later).add(i);
    }
    queue.addAll(later);
    if (!queue.isEmpty()) {
      schedule();
    }
  }

  /** The width a table may take: the text width less the table's indent. */
  private int room(MarkdownTable table) {
    return width - MarkdownStyler.baseIndent(rendered, table.firstLine());
  }

  /**
   * Returns a table's measured layout.
   *
   * @param table the table's index in the render
   * @return the layout, or null while measuring runs
   */
  MarkdownTableLayout measured(int table) {
    MarkdownTableLayout layout = table < tables.length ? tables[table].layout : null;
    return layout != null && layout.measured() ? layout : null;
  }

  /**
   * Reports whether the map shows a table's rows as laid out.
   *
   * @param table the table's index in the render, or -1
   * @return true once every row is laid out
   */
  boolean laidOut(int table) {
    return table >= 0 && table < tables.length && tables[table].shown != null;
  }

  /**
   * Returns the column edges of a table whose rows the map shows.
   *
   * @param table the table's index in the render
   * @return the edges its rows were laid out with, or none before then. Shared: do not modify
   */
  int[] edges(int table) {
    return laidOut(table) ? tables[table].shown : new int[0];
  }

  /**
   * Returns the ascent and descent of the zero-width char that pads a row.
   *
   * @return {@code {ascent, descent}} in pixels: the body font's plus the padding
   */
  int[] padMetrics() {
    if (ascent < 0) {
      GC gc = new GC(text);
      try {
        gc.setFont(fonts.get(false, SWT.NORMAL, 0, false));
        FontMetrics metrics = gc.getFontMetrics();
        ascent = metrics.getAscent() + metrics.getLeading();
        descent = metrics.getDescent();
      } finally {
        gc.dispose();
      }
    }
    return new int[] {ascent + PAD_ABOVE, descent + PAD_BELOW};
  }

  /** Counts the UI turns, for tests. */
  int turns() {
    return turns;
  }

  /** Returns the longest turn so far in nanoseconds, for measurements. */
  long longestTurn() {
    return longestTurn;
  }

  /** Stops measuring. */
  void cancel() {
    generation++;
    queue.clear();
  }

  /** Reports whether measuring or a layout is still to run, for tests. */
  boolean busy() {
    return !queue.isEmpty();
  }

  private void schedule() {
    final long scheduled = generation;
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (scheduled == generation && rendered != null && !text.isDisposed()) {
              turn();
            }
          }
        });
  }

  private void turn() {
    turns++;
    long start = System.nanoTime();
    try {
      step(start + TURN_MILLIS * 1_000_000L);
    } finally {
      longestTurn = Math.max(longestTurn, System.nanoTime() - start);
    }
    if (!queue.isEmpty()) {
      schedule();
    }
  }

  /**
   * Runs the queue's steps until the budget or the deadline runs out. A layout runs first in its
   * turn and ends it.
   */
  private void step(long deadline) {
    MarkdownDisplayMap current = map;
    if (current == null) {
      return;
    }
    GC gc = null;
    try {
      int budget = CELLS_PER_TURN;
      long last = 0;
      while (budget > 0 && !queue.isEmpty() && System.nanoTime() + last < deadline) {
        long stepStart = System.nanoTime();
        int index = queue.get(0);
        Table t = tables[index];
        MarkdownTable table = rendered.tables().get(index);
        int room = room(table);
        if (!t.layout.measured() || (t.layout.wider(room) && !words(t, table).measured())) {
          if (gc == null) {
            gc = new GC(text);
          }
          SpanWidths measurer = new SpanWidths(gc, fonts, rendered.text(), spans);
          int step = Math.min(budget, CELLS_PER_STEP);
          budget -=
              Math.max(
                  1,
                  t.layout.measured()
                      ? t.words.measure(measurer, step)
                      : t.layout.measure(measurer, step));
          last = System.nanoTime() - stepStart;
          continue;
        }
        if (width <= 1) {
          queue.remove(0);
          continue;
        }
        if (budget < CELLS_PER_TURN) {
          return;
        }
        if (layOut(t, table, current, room, deadline)) {
          queue.remove(0);
        }
        return;
      }
    } finally {
      if (gc != null) {
        gc.dispose();
      }
    }
  }

  private TableWords words(Table t, MarkdownTable table) {
    if (t.words == null) {
      t.words = new TableWords(table, rendered.text(), t.layout, spans);
    }
    return t.words;
  }

  /**
   * Lays out a table's next rows and shows them.
   *
   * @return true once every row is shown
   */
  private boolean layOut(
      Table t, MarkdownTable table, MarkdownDisplayMap map, int room, long deadline) {
    if (t.next < 0) {
      int[] widest = t.words != null && t.words.measured() ? t.words.widest() : null;
      t.layout.fit(widest != null ? widest : new int[table.alignments().size()], room, true);
      int[] edges = t.layout.edges();
      if (t.shown != null && Arrays.equals(edges, t.shown)) {
        t.room = room;
        return true;
      }
      t.next = 0;
    }
    int top = text.getTopIndex();
    int topLine = map.renderedLine(top);
    int sub = map.subLine(top);
    Point selection = text.getSelectionRange();
    int[] selected =
        selection.y > 0 ? map.renderedRange(selection.x, selection.x + selection.y) : null;
    long last;
    do {
      long stepStart = System.nanoTime();
      int from = t.next;
      int to = Math.min(table.rowCount(), from + ROWS_PER_STEP);
      MarkdownDisplayMap.Change change =
          map.replace(
              table.firstLine() + from,
              TableWrap.rows(table, rendered.text(), t.layout, t.words, from, to));
      text.replaceTextRange(change.start(), change.length(), change.text());
      int nextTop = map.displayLine(topLine) + Math.min(sub, map.displayLineCount(topLine) - 1);
      if (text.getTopIndex() != nextTop) {
        text.setTopIndex(nextTop);
      }
      t.next = to;
      last = System.nanoTime() - stepStart;
    } while (t.next < table.rowCount() && System.nanoTime() + last < deadline);
    if (selected != null) {
      int from = map.display(selected[0]);
      int to = map.display(selected[1]);
      text.setSelection(Math.min(from, to), Math.max(from, to));
    }
    if (t.next < table.rowCount()) {
      return false;
    }
    t.next = -1;
    t.shown = t.layout.edges();
    t.room = room;
    return true;
  }

  /**
   * Styles a range's lines again and, when its first line is above the last line in view, repaints
   * from that line down, full width: a new image size moves text the range's own bounds do not
   * cover. A range below the view repaints nothing: the pixel position of a line below the view
   * lays out every wrapped line above it.
   *
   * @param text the widget
   * @param start the range's first offset
   * @param end the offset after it
   */
  static void restyle(StyledText text, int start, int end) {
    text.redrawRange(start, Math.max(0, end - start), true);
    Rectangle area = text.getClientArea();
    int line = text.getLineAtOffset(start);
    if (line > text.getLineIndex(Math.max(0, area.height - 1))) {
      return;
    }
    int y = line <= text.getTopIndex() ? 0 : Math.max(0, text.getLinePixel(line));
    text.redraw(0, y, area.width, area.height - y, true);
  }
}
