package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.BlockSpacing;
import io.github.ajdailey.qcaret.app.editor.MarkdownDisplayMap;
import io.github.ajdailey.qcaret.app.editor.TableWrap;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.core.markdown.CodeBlock;
import io.github.ajdailey.qcaret.core.markdown.MarkdownAlert;
import io.github.ajdailey.qcaret.core.markdown.MarkdownHeading;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.nio.file.Path;
import org.eclipse.swt.custom.PaintObjectEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Paints what line styles cannot show over the display lines in view: a box around each code block,
 * reaching the code padding into the right margin, its padding in the separator lines beside it
 * ({@link BlockSpacing}), or in the bottom margin below the last block; a bar per quote level,
 * colored by the alert's kind inside an alert; thematic breaks; a rule under level 1 and 2
 * headings, below the gap the separator after them adds; table borders, and the header row's shade
 * cut to the table's width, once the table's rows are laid out; and each image, or a placeholder
 * box with its file name when it does not decode. Rendered lines and offsets reach the widget
 * through the {@link MarkdownDisplayMap}. Call on the UI thread.
 */
final class MarkdownPainter {
  private static final int BAR_WIDTH = 3;

  /** A code block box's corner radius. */
  static final int RADIUS = 4;

  private final StyledText text;
  private final MarkdownStyler styler;
  private final TableColumns tables;
  private final PaneImages images;
  private RenderedMarkdown rendered;
  private MarkdownDisplayMap map;

  MarkdownPainter(StyledText text, MarkdownStyler styler, TableColumns tables, PaneImages images) {
    this.text = text;
    this.styler = styler;
    this.tables = tables;
    this.images = images;
  }

  /** Takes the render shown and the map of the widget's text. */
  void setRendered(RenderedMarkdown next, MarkdownDisplayMap nextMap) {
    rendered = next;
    map = nextMap;
  }

  /** Paints the decorations of the lines in view. */
  void paint(GC gc) {
    if (rendered == null || text.getCharCount() == 0) {
      return;
    }
    Palette palette = styler.palette();
    Rectangle area = text.getClientArea();
    int left = text.getLeftMargin() - text.getHorizontalPixel();
    int right = area.width - text.getRightMargin();
    int top = text.getTopIndex();
    int bottom = Math.min(map.lineCount() - 1, text.getLineIndex(area.height - 1));
    Color bar = MarkdownStyler.color(palette.lineNumbers());
    Color rule = MarkdownStyler.color(palette.accents().rightMargin());
    for (int i = 0; i < rendered.tables().size(); i++) {
      int header = rendered.tables().get(i).firstLine();
      int[] edges = tables.edges(i);
      int first = map.displayLine(header);
      int last = first + map.displayLineCount(header) - 1;
      if (edges.length > 0 && last >= top && first <= bottom) {
        clearBesideHeader(gc, header, first, last, edges, left, area.width);
      }
    }
    for (CodeBlock code : rendered.codeBlocks()) {
      int first = map.displayLine(code.firstLine());
      int last = map.displayLine(code.firstLine() + code.lineCount() - 1);
      if (last >= top - 1 && first <= bottom + 1) {
        codeBox(gc, code, first, last, top, bottom, left, right, area);
      }
    }
    for (int line = top; line <= bottom; line++) {
      int y0 = text.getLinePixel(line);
      int y1 = y0 + lineHeight(line);
      int source = map.renderedLine(line);
      int quotes = rendered.quoteDepth(source);
      if (quotes > 0) {
        MarkdownAlert alert = styler.alertAt(source);
        gc.setBackground(alert != null ? styler.alertColor(alert) : bar);
        for (int q = 0; q < quotes; q++) {
          gc.fillRectangle(left + q * MarkdownStyler.QUOTE_INDENT + 2, y0, BAR_WIDTH, y1 - y0);
        }
      }
      gc.setForeground(rule);
      if (rendered.rule(source)) {
        int x = left + styler.baseIndent(source);
        gc.drawLine(x, (y0 + y1) / 2, right, (y0 + y1) / 2);
      }
    }
    for (MarkdownHeading heading : rendered.headings()) {
      int line = map.displayLine(heading.line());
      if (heading.level() <= 2 && line >= top && line <= bottom) {
        int gap = styler.spacing().above(heading.line() + 1);
        int y = text.getLinePixel(line) + lineHeight(line) + gap - 1;
        gc.drawLine(left + styler.baseIndent(heading.line()), y, right, y);
      }
    }
    for (int i = 0; i < rendered.tables().size(); i++) {
      MarkdownTable table = rendered.tables().get(i);
      int lastRow = table.firstLine() + table.rowCount() - 1;
      int first = map.displayLine(table.firstLine());
      int last = map.displayLine(lastRow) + map.displayLineCount(lastRow) - 1;
      int[] edges = tables.edges(i);
      if (edges.length > 0 && first <= bottom && last >= top) {
        borders(gc, table, edges, left, Math.max(first, top), Math.min(last, bottom), last);
      }
    }
  }

  /**
   * Paints a code block's box: the shade over its padding in the separator lines beside it, the
   * pane's background over the code lines' shade outside the box and at its rounded corners, and
   * its border. Only the pixel positions of lines in view are read; an edge outside the view is
   * placed beyond the client area.
   */
  private void codeBox(
      GC gc,
      CodeBlock code,
      int first,
      int last,
      int top,
      int bottom,
      int left,
      int right,
      Rectangle area) {
    BlockSpacing spacing = styler.spacing();
    int x0 = left + styler.textStart(code.firstLine());
    // Code wraps at the right margin: the box takes its padding from up to half the margin.
    int margin = text.getRightMargin() / 2;
    int x1 = Math.max(x0 + 2 * RADIUS, right + Math.min(styler.codePadding(), margin));
    int lastLine = code.firstLine() + code.lineCount() - 1;
    int textTop = first >= top ? text.getLinePixel(first) : -2 * RADIUS;
    int y0 = first >= top ? textTop - spacing.below(code.firstLine() - 1) : textTop;
    int textBottom =
        last <= bottom ? text.getLinePixel(last) + lineHeight(last) : area.height + 2 * RADIUS;
    int pad = spacing.above(lastLine + 1);
    int y1 = last <= bottom && pad > 0 ? textBottom + pad - MarkdownPane.LINE_SPACING : textBottom;
    if (last <= bottom && pad == 0 && lastLine == rendered.lineCount() - 1) {
      // No separator follows the last line, whose height has no line spacing: the padding goes
      // in the bottom margin.
      y1 = textBottom + Math.min(BlockSpacing.CODE_PADDING, text.getBottomMargin());
    }
    Color shade = MarkdownStyler.color(styler.palette().gutter());
    Color background = MarkdownStyler.color(styler.palette().background());
    gc.setBackground(background);
    gc.fillRectangle(0, textTop, Math.max(0, x0), textBottom - textTop);
    gc.fillRectangle(x1, textTop, Math.max(0, area.width - x1), textBottom - textTop);
    gc.setBackground(shade);
    gc.fillRectangle(x0, y0, x1 - x0, textTop - y0);
    gc.fillRectangle(x0, textBottom, x1 - x0, y1 - textBottom);
    // The code lines' own shade ends at the right margin.
    gc.fillRectangle(right, textTop, Math.max(0, x1 - right), textBottom - textTop);
    int[][] corners = {{x0, y0}, {x1 - RADIUS, y0}, {x0, y1 - RADIUS}, {x1 - RADIUS, y1 - RADIUS}};
    Rectangle clip = gc.getClipping();
    for (int[] corner : corners) {
      gc.setClipping(clip.intersection(new Rectangle(corner[0], corner[1], RADIUS, RADIUS)));
      gc.setBackground(background);
      gc.fillRectangle(corner[0], corner[1], RADIUS, RADIUS);
      gc.setBackground(shade);
      gc.fillRoundRectangle(x0, y0, x1 - x0, y1 - y0, 2 * RADIUS, 2 * RADIUS);
    }
    gc.setClipping(clip);
    gc.setForeground(MarkdownStyler.color(styler.palette().lineNumbers()));
    gc.drawRoundRectangle(x0, y0, x1 - x0 - 1, y1 - y0 - 1, 2 * RADIUS, 2 * RADIUS);
  }

  /**
   * Paints the pane's background over a header row's shade left and right of its table: the line
   * background {@link MarkdownStyler#background} gives the row spans the whole line.
   */
  private void clearBesideHeader(
      GC gc, int header, int first, int last, int[] edges, int left, int width) {
    int x0 = left + styler.baseIndent(header) + edges[0];
    int x1 = left + styler.baseIndent(header) + edges[edges.length - 1];
    int y = text.getLinePixel(first);
    int height = text.getLinePixel(last) + lineHeight(last) - y;
    gc.setBackground(MarkdownStyler.color(styler.palette().background()));
    gc.fillRectangle(0, y, Math.max(0, x0), height);
    if (x1 < width) {
      gc.fillRectangle(x1, y, width - x1, height);
    }
  }

  /**
   * Draws a table's borders over its display lines from {@code from} to {@code to}, the lines in
   * view; only those lines' pixel positions are read.
   */
  private void borders(
      GC gc, MarkdownTable table, int[] edges, int left, int from, int to, int lastLine) {
    int x0 = left + styler.baseIndent(table.firstLine());
    int x1 = x0 + edges[edges.length - 1];
    int y0 = text.getLinePixel(from);
    int y1 = text.getLinePixel(to) + lineHeight(to);
    for (int row = 0; row < table.rowCount(); row++) {
      int line = map.displayLine(table.firstLine() + row);
      if (line > to) {
        break;
      }
      if (line >= from) {
        int y = text.getLinePixel(line);
        gc.drawLine(x0 + edges[0], y, x1, y);
      }
    }
    if (to == lastLine) {
      gc.drawLine(x0 + edges[0], y1 - 1, x1, y1 - 1);
    }
    for (int edge : edges) {
      gc.drawLine(x0 + edge, y0, x0 + edge, y1 - 1);
    }
  }

  private int lineHeight(int line) {
    return line + 1 < text.getLineCount()
        ? text.getLinePixel(line + 1) - text.getLinePixel(line)
        : text.getLineHeight(text.getOffsetAtLine(line));
  }

  /** Draws an image, or a placeholder for one that does not decode. */
  void paintObject(PaintObjectEvent event) {
    if (map.charAt(event.style.start) == TableWrap.PAD) {
      return;
    }
    int offset = map.rendered(event.style.start);
    MarkdownImageRequest request = images.request(offset);
    if (request == null || event.style.metrics == null) {
      return;
    }
    int width = event.style.metrics.width;
    int height = event.style.metrics.ascent;
    int y = event.y + event.ascent - height;
    Image image = images.image(offset);
    if (image != null) {
      event.gc.drawImage(image, event.x, y);
      return;
    }
    if (images.failed(offset)) {
      event.gc.setForeground(MarkdownStyler.color(styler.palette().lineNumbers()));
      event.gc.drawRectangle(event.x, y, width - 1, height - 1);
      Path name = request.path().getFileName();
      String label = name == null ? request.alt() : name.toString();
      event.gc.setClipping(event.x + 1, y + 1, width - 2, height - 2);
      event.gc.drawText(label, event.x + 6, y + 6, true);
      event.gc.setClipping((Rectangle) null);
    }
  }
}
