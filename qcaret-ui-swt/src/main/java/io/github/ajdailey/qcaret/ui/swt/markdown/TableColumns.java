package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.editor.TextMeasurer;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

/**
 * The column layouts of the tables a pane shows, measured with a {@link GC} on the UI thread in
 * turns of at most {@value #CELLS_PER_TURN} cells and {@value #TURN_MILLIS} ms, whole rows at a
 * time, the tables in view first. A measured table's rows are styled again so they take its tab
 * stops. Call on the UI thread.
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
  private MarkdownTableLayout[] layouts = new MarkdownTableLayout[0];
  private final List<Integer> queue = new ArrayList<Integer>();
  private long generation;
  private int turns;
  private long longestTurn;

  TableColumns(StyledText text, MarkdownFonts fonts, UiDispatcher ui) {
    this.text = text;
    this.fonts = fonts;
    this.ui = ui;
  }

  /**
   * Starts measuring the tables of new content.
   *
   * @param next the render shown
   * @param topLine the first line in view
   * @param bottomLine the last line in view
   */
  void reset(RenderedMarkdown next, int topLine, int bottomLine) {
    generation++;
    rendered = next;
    List<MarkdownTable> tables = next.tables();
    layouts = new MarkdownTableLayout[tables.size()];
    queue.clear();
    List<Integer> later = new ArrayList<Integer>();
    for (int i = 0; i < tables.size(); i++) {
      MarkdownTable table = tables.get(i);
      layouts[i] = new MarkdownTableLayout(table, next.text(), PADDING);
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

  /** Counts the UI turns that measured, for tests. */
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
    final GC gc = new GC(text);
    try {
      TextMeasurer measurer =
          new TextMeasurer() {
            @Override
            public int width(String cell, boolean strong) {
              gc.setFont(fonts.get(false, strong ? SWT.BOLD : SWT.NORMAL, 0, false));
              return gc.textExtent(cell, 0).x;
            }
          };
      int budget = CELLS_PER_TURN;
      long deadline = System.nanoTime() + TURN_MILLIS * 1_000_000L;
      while (budget > 0 && !queue.isEmpty() && System.nanoTime() < deadline) {
        int index = queue.get(0);
        budget -= Math.max(1, layouts[index].measure(measurer, Math.min(budget, CELLS_PER_STEP)));
        if (layouts[index].measured()) {
          queue.remove(0);
          redraw(index);
        }
      }
    } finally {
      gc.dispose();
      longestTurn = Math.max(longestTurn, System.nanoTime() - start);
    }
    if (!queue.isEmpty()) {
      schedule();
    }
  }

  private void redraw(int index) {
    if (rendered == null) {
      return;
    }
    MarkdownTable table = rendered.tables().get(index);
    int first = rendered.lineStart(table.firstLine());
    int lastLine = table.firstLine() + table.rowCount() - 1;
    int end =
        lastLine + 1 < rendered.lineCount()
            ? rendered.lineStart(lastLine + 1)
            : rendered.text().length();
    restyle(text, first, end);
  }

  /**
   * Styles a range's lines again and repaints from its first line down, full width: a new indent or
   * line height moves text the range's own bounds do not cover.
   *
   * @param text the widget
   * @param start the range's first offset
   * @param end the offset after it
   */
  static void restyle(StyledText text, int start, int end) {
    text.redrawRange(start, Math.max(0, end - start), true);
    Rectangle area = text.getClientArea();
    int y = Math.max(0, text.getLinePixel(text.getLineAtOffset(start)));
    if (y < area.height) {
      text.redraw(0, y, area.width, area.height - y, true);
    }
  }
}
