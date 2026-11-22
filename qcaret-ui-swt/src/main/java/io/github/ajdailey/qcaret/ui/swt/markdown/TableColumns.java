package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.editor.TextMeasurer;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

/**
 * The column layouts of the tables a pane shows, each cell measured in its spans' fonts by {@link
 * SpanWidths} with a {@link GC} on the UI thread in turns of at most {@value #CELLS_PER_TURN} cells
 * and {@value #TURN_MILLIS} ms, whole rows at a time, the tables in view first. A turn stops when a
 * table is measured; the next turn styles that table's rows again so they take its tab stops. Call
 * on the UI thread.
 */
final class TableColumns {
  /** The most cells measured in one UI turn. */
  static final int CELLS_PER_TURN = 200;

  /** The time after which a turn stops at the next row. */
  static final int TURN_MILLIS = 8;

  /** Cells measured between two time checks. */
  private static final int CELLS_PER_STEP = 16;

  /** Pixels on each side of a cell's text. */
  static final int PADDING = 8;

  private final StyledText text;
  private final MarkdownFonts fonts;
  private final UiDispatcher ui;
  private RenderedMarkdown rendered;
  private int[] spans;
  private MarkdownTableLayout[] layouts = new MarkdownTableLayout[0];
  private final List<Integer> queue = new ArrayList<Integer>();
  private long generation;
  private int turns;
  private long longestTurn;
  private int restyled = -1;

  TableColumns(StyledText text, MarkdownFonts fonts, UiDispatcher ui) {
    this.text = text;
    this.fonts = fonts;
    this.ui = ui;
  }

  /**
   * Starts measuring the tables of new content.
   *
   * @param next the content shown
   * @param topLine the first line in view
   * @param bottomLine the last line in view
   */
  void reset(MarkdownPreviewContent next, int topLine, int bottomLine) {
    generation++;
    rendered = next.rendered();
    spans = next.spans();
    List<MarkdownTable> tables = rendered.tables();
    layouts = new MarkdownTableLayout[tables.size()];
    queue.clear();
    restyled = -1;
    List<Integer> later = new ArrayList<Integer>();
    for (int i = 0; i < tables.size(); i++) {
      MarkdownTable table = tables.get(i);
      layouts[i] = new MarkdownTableLayout(table, rendered.text(), PADDING);
      boolean inView =
          table.firstLine() <= bottomLine && table.firstLine() + table.rowCount() > topLine;
      (inView ? queue : later).add(i);
    }
    queue.addAll(later);
    if (!queue.isEmpty()) {
      schedule();
    }
  }

  /**
   * Returns a table's layout once measured.
   *
   * @param table the table's index in the render
   * @return the layout, or null while measuring runs
   */
  MarkdownTableLayout measured(int table) {
    MarkdownTableLayout layout = table < layouts.length ? layouts[table] : null;
    return layout != null && layout.measured() ? layout : null;
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
    restyled = -1;
  }

  /** Reports whether measuring or a restyle is still to run, for tests. */
  boolean busy() {
    return !queue.isEmpty() || restyled >= 0;
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
    RenderedMarkdown shown = rendered;
    if (shown == null) {
      return;
    }
    turns++;
    long start = System.nanoTime();
    try {
      if (restyled >= 0) {
        redraw(text, shown, restyled);
        restyled = -1;
      } else {
        measure(shown);
      }
    } finally {
      longestTurn = Math.max(longestTurn, System.nanoTime() - start);
    }
    if (!queue.isEmpty() || restyled >= 0) {
      schedule();
    }
  }

  /** Measures until the budget or the deadline runs out, or until a table is measured. */
  private void measure(RenderedMarkdown shown) {
    GC gc = new GC(text);
    try {
      TextMeasurer measurer = new SpanWidths(gc, fonts, shown.text(), spans);
      int budget = CELLS_PER_TURN;
      long deadline = System.nanoTime() + TURN_MILLIS * 1_000_000L;
      while (budget > 0 && !queue.isEmpty() && System.nanoTime() < deadline) {
        int index = queue.get(0);
        budget -= Math.max(1, layouts[index].measure(measurer, Math.min(budget, CELLS_PER_STEP)));
        if (layouts[index].measured()) {
          queue.remove(0);
          restyled = index;
          return;
        }
      }
    } finally {
      gc.dispose();
    }
  }

  private static void redraw(StyledText text, RenderedMarkdown shown, int index) {
    MarkdownTable table = shown.tables().get(index);
    int first = shown.lineStart(table.firstLine());
    int lastLine = table.firstLine() + table.rowCount() - 1;
    int end =
        lastLine + 1 < shown.lineCount() ? shown.lineStart(lastLine + 1) : shown.text().length();
    restyle(text, first, end);
  }

  /**
   * Styles a range's lines again and, when its first line is above the last line in view, repaints
   * from that line down, full width: a new indent or line height moves text the range's own bounds
   * do not cover. A range below the view repaints nothing: the pixel position of a line below the
   * view lays out every wrapped line above it.
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
