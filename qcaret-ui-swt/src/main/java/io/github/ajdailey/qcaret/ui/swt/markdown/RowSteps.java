package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownDisplayMap;
import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.editor.TableWords;
import io.github.ajdailey.qcaret.app.editor.TableWrap;
import io.github.ajdailey.qcaret.app.editor.TextMeasurer;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;

/**
 * Shows a table's laid-out rows in the map and the widget through {@code replaceTextRange}: {@value
 * #ROWS_PER_STEP} rows in the first step, then as many as the last step's rate fits in about 4 ms,
 * from 4 to 64. Steps starting above the top line, in view and below the view each keep their own
 * rate: a replace above the top line also measures the lines down to the top line. Across the steps
 * the top line keeps its rendered line and pixel offset, and the selection its rendered text. Call
 * on the UI thread.
 */
final class RowSteps {
  /** Rows laid out by the first text replace. */
  static final int ROWS_PER_STEP = 16;

  /**
   * The time a text replace aims at; later replaces take as many rows as the last rate fits. Part
   * of a replace's cost does not grow with its rows: 2 ms steps took 8 percent more turn time for
   * 2,000 rows than 4 ms steps, and twice the collector pauses (GTK 3).
   */
  private static final long STEP_NANOS = 4_000_000L;

  /** The most rows one text replace lays out. */
  private static final int MOST_ROWS_PER_STEP = 64;

  /**
   * The fewest rows one text replace lays out: a replace above the top line takes 3-12 ms for one
   * row or several (GTK 3), so its rate says little about a smaller step.
   */
  private static final int FEWEST_ROWS_PER_STEP = 4;

  private final StyledText text;

  /** Rows per step starting above the top line, in view and below the view. */
  private final int[] rowsPerStep = {ROWS_PER_STEP, ROWS_PER_STEP, ROWS_PER_STEP};

  RowSteps(StyledText text) {
    this.text = text;
  }

  /**
   * Shows a table's rows from {@code from} in steps: one, then another while rows remain and twice
   * the last step's time fits before the deadline.
   *
   * @param map the map the widget shows
   * @param table the table
   * @param rendered the rendered text
   * @param layout the table's fitted layout
   * @param words its measured words, or null
   * @param measurer measures the parts of a word wider than its column
   * @param from the first row to show
   * @param deadline a {@link System#nanoTime} value
   * @return the row after the last one shown
   */
  int show(
      MarkdownDisplayMap map,
      MarkdownTable table,
      String rendered,
      MarkdownTableLayout layout,
      TableWords words,
      TextMeasurer measurer,
      int from,
      long deadline) {
    int top = text.getTopIndex();
    int topLine = map.renderedLine(top);
    int sub = map.subLine(top);
    int topY = text.getLinePixel(top);
    Point selection = text.getSelectionRange();
    int[] selected =
        selection.y > 0 ? map.renderedRange(selection.x, selection.x + selection.y) : null;
    int next = from;
    long last;
    do {
      long stepStart = System.nanoTime();
      int where = where(map.displayLine(table.firstLine() + next));
      int to = Math.min(table.rowCount(), next + rowsPerStep[where]);
      MarkdownDisplayMap.Change change =
          map.replace(
              table.firstLine() + next,
              TableWrap.rows(table, rendered, layout, words, measurer, next, to));
      text.replaceTextRange(change.start(), change.length(), change.text());
      int nextTop = map.displayLine(topLine) + Math.min(sub, map.displayLineCount(topLine) - 1);
      keepTop(nextTop, topY);
      last = System.nanoTime() - stepStart;
      rowsPerStep[where] =
          (int)
              Math.max(
                  FEWEST_ROWS_PER_STEP,
                  Math.min(MOST_ROWS_PER_STEP, STEP_NANOS * (to - next) / Math.max(1, last)));
      next = to;
    } while (next < table.rowCount() && TableTurns.fits(last, deadline));
    if (selected != null) {
      int[] range = map.displayRange(selected[0], selected[1]);
      text.setSelection(range[0], range[1]);
    }
    return next;
  }

  /**
   * The rate index of a step starting at a display line: 0 above the top line, 1 in view, 2 below.
   */
  private int where(int displayLine) {
    int top = text.getTopIndex();
    if (displayLine < top) {
      return 0;
    }
    return displayLine <= text.getLineIndex(Math.max(0, text.getClientArea().height - 1)) ? 1 : 2;
  }

  /**
   * Puts a display line back at the top, at the pixel it had: a replace below the top line moves
   * the view by a few pixels on GTK 4 without changing the top index.
   */
  private void keepTop(int line, int y) {
    if (text.getTopIndex() != line) {
      text.setTopIndex(line);
    }
    int drift = text.getLinePixel(line) - y;
    if (drift != 0) {
      text.setTopPixel(text.getTopPixel() + drift);
    }
  }
}
