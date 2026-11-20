package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.editor.TextMeasurer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.TextLayout;

/**
 * Measures rendered Markdown on a printer: table cells with the printer's {@link GC}, a line's rows
 * with its {@link TextLayout}. Runs on the print job's thread, as drawing does; the measuring of a
 * {@code TextLayout} on a {@code Printer} off the UI thread is verified on GTK only.
 */
final class PrintMeasurer implements TextMeasurer {
  private final PrintLines lines;
  private final MarkdownFonts fonts;
  private final GC gc;

  /**
   * Creates the measurer.
   *
   * @param lines builds the line layouts
   * @param fonts the printer's fonts
   * @param gc the printer's GC
   */
  PrintMeasurer(PrintLines lines, MarkdownFonts fonts, GC gc) {
    this.lines = lines;
    this.fonts = fonts;
    this.gc = gc;
  }

  @Override
  public int width(String text, boolean strong) {
    gc.setFont(fonts.get(false, strong ? SWT.BOLD : SWT.NORMAL, 0, false));
    return gc.textExtent(text, 0).x;
  }

  /**
   * Returns the height of each row a rendered line takes.
   *
   * @param line the rendered line
   * @param table the row's placement in its table, or null
   * @return at least one height
   */
  int[] rows(int line, MarkdownTableLayout.Row table) {
    TextLayout layout = lines.layout(line, table);
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
