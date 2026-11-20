package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPrintSheet;
import io.github.ajdailey.qcaret.core.markdown.MarkdownAlert;
import io.github.ajdailey.qcaret.core.markdown.MarkdownHeading;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.TextLayout;
import org.eclipse.swt.printing.Printer;

/**
 * Rendered Markdown on a printer page body: measures lines through {@link PrintMeasurer} and draws
 * placed lines as Basic paints them, with {@link Palette#LIGHT} on white. Images decode from file
 * bytes for the printer's resolution, never above their own pixel size, and draw scaled to their
 * printed size. Confined to the print job's thread; {@link #dispose} releases the fonts.
 */
public final class MarkdownPrinting {
  /** Size of the body text; headings scale from it. */
  public static final int POINTS = 10;

  private static final int BAR_WIDTH = 3;
  private static final Color BLACK = new Color(0, 0, 0);

  private final Printer printer;
  private final GC gc;
  private final Rectangle body;
  private final RenderedMarkdown rendered;
  private final boolean color;
  private final MarkdownFonts fonts;
  private final PrintLines lines;
  private final PrintMeasurer measurer;
  private final Map<Integer, Point> sizes = new HashMap<Integer, Point>();
  private final Map<Integer, ImageData> images = new HashMap<Integer, ImageData>();
  private final Map<Integer, MarkdownImageRequest> requests =
      new HashMap<Integer, MarkdownImageRequest>();
  private final int[] headingLevels;

  /**
   * Creates the pages' drawing on a started job.
   *
   * @param printer the printer, its job started
   * @param gc the printer's GC
   * @param body the page body in printer pixels
   * @param content the render
   * @param bodyFamily the text's font family
   * @param monoFamily the code's font family
   * @param color false to print black
   */
  public MarkdownPrinting(
      Printer printer,
      GC gc,
      Rectangle body,
      MarkdownPreviewContent content,
      String bodyFamily,
      String monoFamily,
      boolean color) {
    this.printer = printer;
    this.gc = gc;
    this.body = body;
    this.rendered = content.rendered();
    this.color = color;
    this.fonts =
        new MarkdownFonts(printer, new FontData(bodyFamily, POINTS, SWT.NORMAL), monoFamily);
    this.lines =
        new PrintLines(printer, content, fonts, sizes, body.width, printer.getDPI(), color);
    this.measurer = new PrintMeasurer(lines, fonts, gc);
    this.headingLevels = new int[rendered.lineCount()];
    for (MarkdownHeading heading : rendered.headings()) {
      headingLevels[heading.line()] = heading.level();
    }
  }

  /**
   * Returns the padding on each side of a table cell's text.
   *
   * @return printer pixels
   */
  public int padding() {
    return lines.scaled(TableColumns.PADDING);
  }

  /**
   * Measures text in the body font.
   *
   * @param text the text
   * @param strong true for bold
   * @return the width in printer pixels
   */
  public int width(String text, boolean strong) {
    return measurer.width(text, strong);
  }

  /**
   * Returns the height of each row a rendered line takes.
   *
   * @param line the rendered line
   * @param table the row's placement in its table, or null
   * @return at least one height
   */
  public int[] rows(int line, MarkdownTableLayout.Row table) {
    return measurer.rows(line, table);
  }

  /**
   * Decodes an image for printing; call for every image before measuring.
   *
   * @param request the image
   * @param bytes its file's content, empty when unread
   */
  public void image(MarkdownImageRequest request, byte[] bytes) {
    requests.put(request.offset(), request);
    ImageData data = bytes.length == 0 ? null : decode(bytes);
    if (data == null) {
      sizes.put(
          request.offset(),
          new Point(lines.scaled(PaneImages.FAILED.x), lines.scaled(PaneImages.FAILED.y)));
      return;
    }
    int maxWidth = Math.max(1, lines.unscaled(body.width));
    int[] size = SwtImageDecoder.size(data.width, data.height, request, maxWidth);
    long w = lines.scaled(size[0]);
    long h = lines.scaled(size[1]);
    if (w > body.width) {
      h = h * body.width / w;
      w = body.width;
    }
    if (h > body.height) {
      w = w * body.height / h;
      h = body.height;
    }
    int printW = (int) Math.max(1, w);
    int printH = (int) Math.max(1, h);
    sizes.put(request.offset(), new Point(printW, printH));
    if (printW < data.width && printH < data.height) {
      data = data.scaledTo(printW, printH);
    }
    images.put(request.offset(), data);
  }

  private static ImageData decode(byte[] bytes) {
    try {
      ImageData[] frames = new ImageLoader().load(new ByteArrayInputStream(bytes));
      return frames.length == 0 ? null : frames[0];
    } catch (SWTException e) {
      return null;
    }
  }

  /**
   * Draws a page's lines into the body.
   *
   * @param placed the lines, top to bottom
   */
  public void draw(List<MarkdownPrintSheet.Placed> placed) {
    gc.setClipping(body);
    try {
      for (MarkdownPrintSheet.Placed p : placed) {
        line(p);
      }
    } finally {
      gc.setClipping((Rectangle) null);
    }
  }

  private void line(MarkdownPrintSheet.Placed p) {
    TextLayout layout = lines.layout(p.line(), p.table());
    try {
      int last = Math.min(p.firstRow() + p.rowCount(), layout.getLineCount()) - 1;
      int rowTop = layout.getLineBounds(p.firstRow()).y;
      Rectangle lastRow = layout.getLineBounds(last);
      int top = body.y + p.y();
      int height = lastRow.y + lastRow.height - rowTop;
      int base = body.x + lines.baseIndent(p.line());
      int right = body.x + body.width;
      Color rule = color(Palette.LIGHT.accents().rightMargin());
      if (rendered.code(p.line()) && color) {
        gc.setBackground(MarkdownStyler.color(Palette.LIGHT.gutter()));
        gc.fillRectangle(base, top, right - base, height);
      }
      bars(p.line(), top, height);
      int x = body.x + lines.start(p.line(), p.table());
      int y = top - rowTop;
      gc.setForeground(BLACK);
      layout.draw(gc, x, y);
      images(p, layout, x, y, last);
      if (p.firstRow() == 0) {
        marker(p.line(), base, top, layout);
      }
      gc.setForeground(rule);
      if (rendered.rule(p.line())) {
        gc.drawLine(base, top + height / 2, right, top + height / 2);
      }
      int level = headingLevels[p.line()];
      if (level > 0 && level <= 2 && last == layout.getLineCount() - 1) {
        gc.drawLine(base, top + height - 1, right, top + height - 1);
      }
      if (p.edges() != null) {
        borders(p.edges(), base, top, height);
      }
    } finally {
      layout.dispose();
    }
  }

  private void bars(int line, int top, int height) {
    int quotes = rendered.quoteDepth(line);
    if (quotes == 0) {
      return;
    }
    MarkdownAlert alert = lines.styler().alertAt(line);
    gc.setBackground(
        !color
            ? BLACK
            : alert != null
                ? lines.styler().alertColor(alert)
                : MarkdownStyler.color(Palette.LIGHT.lineNumbers()));
    for (int q = 0; q < quotes; q++) {
      gc.fillRectangle(
          body.x + lines.scaled(q * MarkdownStyler.QUOTE_INDENT + 2),
          top,
          lines.scaled(BAR_WIDTH),
          height);
    }
  }

  /** Draws a list item's bullet, number or checkbox before its first row. */
  private void marker(int line, int x, int top, TextLayout layout) {
    String text =
        switch (rendered.task(line)) {
          case DONE -> "☑";
          case OPEN -> "☐";
          case NONE ->
              switch (rendered.marker(line)) {
                case BULLET -> "•";
                case NUMBER -> rendered.number(line) + ".";
                case NONE -> null;
              };
        };
    if (text == null) {
      return;
    }
    Font font = fonts.get(false, SWT.NORMAL, 0, false);
    gc.setFont(font);
    gc.setForeground(color(Palette.LIGHT.text()));
    int ascent = layout.getLineMetrics(0).getAscent() - gc.getFontMetrics().getAscent();
    gc.drawString(text, x, top + ascent, true);
  }

  private void images(MarkdownPrintSheet.Placed p, TextLayout layout, int x, int y, int lastRow) {
    int start = lines.lineStart(p.line());
    for (int offset : lines.imagesIn(p.line())) {
      int index = offset - start;
      int row = layout.getLineIndex(index);
      if (row < p.firstRow() || row > lastRow) {
        continue;
      }
      Point size = sizes.get(offset);
      if (size == null) {
        continue;
      }
      Rectangle bounds = layout.getBounds(index, index);
      int ascent = layout.getLineMetrics(row).getAscent();
      int left = x + bounds.x;
      int top = y + layout.getLineBounds(row).y + ascent - size.y;
      ImageData data = images.get(offset);
      if (data == null) {
        placeholder(requests.get(offset), left, top, size);
        continue;
      }
      Image image = new Image(printer, data);
      try {
        gc.drawImage(image, 0, 0, data.width, data.height, left, top, size.x, size.y);
      } finally {
        image.dispose();
      }
    }
  }

  private void placeholder(MarkdownImageRequest request, int x, int y, Point size) {
    gc.setForeground(color(Palette.LIGHT.lineNumbers()));
    gc.drawRectangle(x, y, size.x - 1, size.y - 1);
    Path name = request == null ? null : request.path().getFileName();
    String label = name == null ? (request == null ? "" : request.alt()) : name.toString();
    gc.setFont(fonts.get(false, SWT.NORMAL, 0, false));
    int pad = lines.scaled(6);
    gc.drawText(label, x + pad, y + pad, true);
  }

  private void borders(int[] edges, int x0, int top, int height) {
    int bottom = top + height - 1;
    gc.drawLine(x0 + edges[0], top, x0 + edges[edges.length - 1], top);
    gc.drawLine(x0 + edges[0], bottom, x0 + edges[edges.length - 1], bottom);
    for (int edge : edges) {
      gc.drawLine(x0 + edge, top, x0 + edge, bottom);
    }
  }

  private Color color(Rgb rgb) {
    return color ? MarkdownStyler.color(rgb) : BLACK;
  }

  /** Releases the fonts. */
  public void dispose() {
    fonts.dispose();
  }
}
