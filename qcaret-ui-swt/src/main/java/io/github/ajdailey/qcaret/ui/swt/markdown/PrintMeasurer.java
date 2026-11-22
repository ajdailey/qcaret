package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.editor.TextMeasurer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.TextLayout;

/**
 * Measures rendered Markdown on a printer: table cells through {@link SpanWidths} with the
 * printer's {@link GC}, a line's rows with its {@link TextLayout}s. Runs on the print job's thread,
 * as drawing does; the measuring of a {@code TextLayout} on a {@code Printer} off the UI thread is
 * verified on GTK only.
 */
final class PrintMeasurer implements TextMeasurer {
  private final PrintLines lines;
  private final SpanWidths widths;

  /**
   * Creates the measurer.
   *
   * @param lines builds the line layouts
   * @param widths measures cell text with the printer's GC
   */
  PrintMeasurer(PrintLines lines, SpanWidths widths) {
    this.lines = lines;
    this.widths = widths;
  }

  @Override
  public int width(int start, int end, boolean strong) {
    return widths.width(start, end, strong);
  }

  /**
   * Returns the height of each row a rendered line takes.
   *
   * @param line the rendered line
   * @param table the row's placement in its table, or null
   * @return at least one height; for a table row one, its tallest cell's
   */
  int[] rows(int line, MarkdownTableLayout.Row table) {
    if (table != null) {
      TextLayout[] cells = lines.cells(line, table);
      try {
        int height = 0;
        for (TextLayout cell : cells) {
          height = Math.max(height, cell.getBounds().height);
        }
        return new int[] {height};
      } finally {
        PrintLines.dispose(cells);
      }
    }
    TextLayout layout = lines.layout(line);
    try {
      int[] heights = new int[Math.max(1, layout.getLineCount())];
      for (int i = 0; i < heights.length; i++) {
        heights[i] = layout.getLineBounds(i).height;
      }
      return heights;
    } finally {
      layout.dispose();
    }
  }
}
