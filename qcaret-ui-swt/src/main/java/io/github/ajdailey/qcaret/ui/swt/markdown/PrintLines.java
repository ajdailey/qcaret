package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownDisplayMap;
import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.Arrays;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.TextLayout;
import org.eclipse.swt.graphics.TextStyle;

/**
 * Builds the {@link TextLayout}s of rendered lines on a printer, styled as Basic styles them with
 * {@link Palette#LIGHT}: a paragraph line wraps at the body's width less its quote and list indent;
 * a table row is one layout per cell, wrapped at its column's width. Basic's pixel constants scale
 * from 96 dpi to the printer's. Without color every style is black on white. Confined to the job's
 * thread.
 */
final class PrintLines {
  /** The resolution Basic's pixel constants are written for. */
  static final float SCREEN_DPI = 96f;

  /** Pixels on each side of a table cell's text at {@link #SCREEN_DPI}. */
  static final int CELL_PADDING = 8;

  private final Device device;
  private final MarkdownPreviewContent content;
  private final MarkdownFonts fonts;
  private final MarkdownStyler styler;
  private final Map<Integer, Point> imageSizes;
  private final int width;
  private final float scale;
  private final float pixelsPerPoint;
  private final boolean color;
  private final int[] imageOffsets;

  /**
   * Creates the builder.
   *
   * @param device the printer
   * @param content the render
   * @param fonts the printer's fonts
   * @param imageSizes each image's printed size by its offset
   * @param width the body's width in pixels
   * @param dpi the printer's horizontal and vertical resolution
   * @param color false to print black
   */
  PrintLines(
      Device device,
      MarkdownPreviewContent content,
      MarkdownFonts fonts,
      Map<Integer, Point> imageSizes,
      int width,
      Point dpi,
      boolean color) {
    this.device = device;
    this.content = content;
    this.fonts = fonts;
    this.imageSizes = imageSizes;
    this.width = width;
    this.scale = dpi.x / SCREEN_DPI;
    this.pixelsPerPoint = dpi.y / 72f;
    this.color = color;
    this.styler = new MarkdownStyler(fonts, null, null, Palette.LIGHT);
    styler.setContent(content, new MarkdownDisplayMap(content.rendered()));
    this.imageOffsets = new int[content.images().size()];
    for (int i = 0; i < imageOffsets.length; i++) {
      imageOffsets[i] = content.images().get(i).offset();
    }
    Arrays.sort(imageOffsets);
  }

  /** Returns the styler, for the quote and alert lookups that do not touch images or tables. */
  MarkdownStyler styler() {
    return styler;
  }

  /** Converts Basic's pixels to the printer's. */
  int scaled(int pixels) {
    return Math.round(pixels * scale);
  }

  /** Converts the printer's pixels to Basic's. */
  int unscaled(int pixels) {
    return Math.round(pixels / scale);
  }

  /** Returns the indent a line's quotes and lists take, in printer pixels. */
  int baseIndent(int line) {
    return scaled(styler.baseIndent(line));
  }

  /**
   * Returns where a line's layout starts.
   *
   * @param line a rendered line outside a table
   * @return pixels from the body's left edge
   */
  int start(int line) {
    int base = baseIndent(line);
    return content.rendered().listDepth(line) > 0
        ? base + scaled(MarkdownStyler.BULLET_WIDTH)
        : base;
  }

  /** Returns a rendered line's first offset. */
  int lineStart(int line) {
    return content.rendered().lineStart(line);
  }

  /** Returns the offset after a rendered line's last char. */
  int lineEnd(int line) {
    RenderedMarkdown r = content.rendered();
    return line + 1 < r.lineCount() ? r.lineStart(line + 1) - 1 : r.text().length();
  }

  /**
   * Builds the layout of a line outside a table, wrapped at the body's width less its indent; the
   * caller disposes it.
   *
   * @param line a rendered line
   * @return the layout, its origin at {@link #start}
   */
  TextLayout layout(int line) {
    int start = lineStart(line);
    int end = lineEnd(line);
    TextLayout layout = text(start, end);
    layout.setWidth(Math.max(1, width - start(line)));
    styleRange(layout, start, end, content.rendered().code(line), false);
    return layout;
  }

  /**
   * Builds the layouts of a table row's cells, each wrapped at its column's width less padding and
   * aligned in it; the caller disposes them.
   *
   * @param line a table row's rendered line
   * @param table the row's placement in its table
   * @return one layout per column, the first column first; column {@code c} starts at {@link
   *     #baseIndent} plus {@code table.edges()[c]} plus {@link #padding}
   */
  TextLayout[] cells(int line, MarkdownTableLayout.Row table) {
    MarkdownTable t = content.rendered().tables().get(styler.tableAt(line));
    int[] pairs = cellRanges(line);
    boolean header = line == t.firstLine();
    int[] edges = table.edges();
    int padding = padding();
    TextLayout[] cells = new TextLayout[t.alignments().size()];
    boolean built = false;
    try {
      for (int c = 0; c < cells.length; c++) {
        int start = pairs[2 * c];
        int end = pairs[2 * c + 1];
        cells[c] = text(start, end);
        cells[c].setWidth(Math.max(1, edges[c + 1] - edges[c] - 2 * padding));
        cells[c].setAlignment(
            switch (t.alignments().get(c)) {
              case RIGHT -> SWT.RIGHT;
              case CENTER -> SWT.CENTER;
              default -> SWT.LEFT;
            });
        styleRange(cells[c], start, end, false, header);
      }
      built = true;
    } finally {
      if (!built) {
        dispose(cells);
      }
    }
    return cells;
  }

  /**
   * Returns a table row's cells.
   *
   * @param line a table row's rendered line
   * @return {@code start, end} offset pairs, one per column; shared, do not modify
   */
  int[] cellRanges(int line) {
    MarkdownTable t = content.rendered().tables().get(styler.tableAt(line));
    return t.cells()[line - t.firstLine()];
  }

  /** Disposes layouts from {@link #cells}; null entries are skipped. */
  static void dispose(TextLayout[] layouts) {
    for (TextLayout layout : layouts) {
      if (layout != null) {
        layout.dispose();
      }
    }
  }

  /** Returns the padding on each side of a table cell's text, in printer pixels. */
  int padding() {
    return scaled(CELL_PADDING);
  }

  private TextLayout text(int start, int end) {
    TextLayout layout = new TextLayout(device);
    layout.setFont(fonts.get(false, SWT.NORMAL, 0, false));
    layout.setText(content.rendered().text().substring(start, end));
    return layout;
  }

  /** Styles a layout holding the rendered text from {@code start} to {@code end}. */
  private void styleRange(TextLayout layout, int start, int end, boolean code, boolean header) {
    int[] spans = content.spans();
    int at = start;
    for (int i = MarkdownSpans.first(spans, start); 3 * i < spans.length; i++) {
      int s = Math.max(spans[3 * i], start);
      if (s >= end) {
        break;
      }
      int t = Math.min(spans[3 * i + 1], end);
      style(layout, start, at, s, 0, code, header);
      style(layout, start, s, t, spans[3 * i + 2], code, header);
      at = t;
    }
    style(layout, start, at, end, 0, code, header);
    images(layout, start, end);
  }

  private void style(
      TextLayout layout, int lineStart, int from, int to, int bits, boolean code, boolean header) {
    if (from >= to || (bits == 0 && !code && !header)) {
      return;
    }
    TextStyle style = new TextStyle();
    MarkdownStyler.apply(style, bits, code, header, Palette.LIGHT, fonts, pixelsPerPoint);
    if (!color) {
      style.foreground = null;
      style.background = null;
      style.borderColor = null;
    }
    layout.setStyle(style, from - lineStart, to - lineStart - 1);
  }

  private void images(TextLayout layout, int start, int end) {
    int i = Arrays.binarySearch(imageOffsets, start);
    for (i = i < 0 ? -i - 1 : i; i < imageOffsets.length && imageOffsets[i] < end; i++) {
      Point size = imageSizes.get(imageOffsets[i]);
      if (size != null) {
        TextStyle style = new TextStyle();
        style.metrics = new GlyphMetrics(size.y, 0, size.x);
        layout.setStyle(style, imageOffsets[i] - start, imageOffsets[i] - start);
      }
    }
  }

  /** Returns the image offsets from {@code start} to {@code end}, ascending. */
  int[] imagesIn(int start, int end) {
    int i = Arrays.binarySearch(imageOffsets, start);
    i = i < 0 ? -i - 1 : i;
    int j = i;
    while (j < imageOffsets.length && imageOffsets[j] < end) {
      j++;
    }
    return Arrays.copyOfRange(imageOffsets, i, j);
  }
}
