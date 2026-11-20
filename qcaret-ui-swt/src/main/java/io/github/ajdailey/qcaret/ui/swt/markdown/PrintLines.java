package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
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
 * Builds the {@link TextLayout} of one rendered line on a printer, styled as Basic styles it with
 * {@link Palette#LIGHT}: a paragraph line wraps at the body's width less its quote and list indent;
 * a table row does not wrap and starts each cell at its tab stop. Basic's pixel constants scale
 * from 96 dpi to the printer's. Without color every style is black on white. Confined to the job's
 * thread.
 */
final class PrintLines {
  /** The resolution Basic's pixel constants are written for. */
  static final float SCREEN_DPI = 96f;

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
    styler.setContent(content);
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
   * @param line a rendered line
   * @param table the row's placement in its table, or null
   * @return pixels from the body's left edge
   */
  int start(int line, MarkdownTableLayout.Row table) {
    int base = baseIndent(line);
    if (table != null) {
      return base + table.indent();
    }
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
   * Builds a line's layout; the caller disposes it.
   *
   * @param line a rendered line
   * @param table the row's placement in its table, or null outside a table
   * @return the layout, its origin at {@link #start}
   */
  TextLayout layout(int line, MarkdownTableLayout.Row table) {
    RenderedMarkdown r = content.rendered();
    int start = lineStart(line);
    int end = lineEnd(line);
    TextLayout layout = new TextLayout(device);
    layout.setFont(fonts.get(false, SWT.NORMAL, 0, false));
    layout.setText(r.text().substring(start, end));
    if (table != null) {
      int[] stops = table.tabStops();
      if (stops.length > 0) {
        int[] tabs = new int[stops.length];
        for (int i = 0; i < tabs.length; i++) {
          tabs[i] = Math.max(1, stops[i] - table.indent());
        }
        layout.setTabs(tabs);
      }
    } else {
      layout.setWidth(Math.max(1, width - start(line, null)));
    }
    boolean code = r.code(line);
    boolean header = table != null && isHeader(r, line);
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
    return layout;
  }

  private boolean isHeader(RenderedMarkdown r, int line) {
    int table = styler.tableAt(line);
    return table >= 0 && r.tables().get(table).firstLine() == line;
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

  /** Returns the image offsets inside a line, ascending. */
  int[] imagesIn(int line) {
    int start = lineStart(line);
    int end = lineEnd(line);
    int i = Arrays.binarySearch(imageOffsets, start);
    i = i < 0 ? -i - 1 : i;
    int j = i;
    while (j < imageOffsets.length && imageOffsets[j] < end) {
      j++;
    }
    return Arrays.copyOfRange(imageOffsets, i, j);
  }
}
