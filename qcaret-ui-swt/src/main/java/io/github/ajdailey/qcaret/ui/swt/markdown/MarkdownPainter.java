package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
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
 * Paints what line styles cannot show over the lines in view: a bar per quote level, colored by the
 * alert's kind inside an alert; thematic breaks; a rule under level 1 and 2 headings; table borders
 * once the table is measured; and each image, or a placeholder box with its file name when it does
 * not decode. Call on the UI thread.
 */
final class MarkdownPainter {
  private static final int BAR_WIDTH = 3;

  private final StyledText text;
  private final MarkdownStyler styler;
  private final TableColumns tables;
  private final PaneImages images;
  private RenderedMarkdown rendered;

  MarkdownPainter(StyledText text, MarkdownStyler styler, TableColumns tables, PaneImages images) {
    this.text = text;
    this.styler = styler;
    this.tables = tables;
    this.images = images;
  }

  /** Takes the render shown. */
  void setRendered(RenderedMarkdown next) {
    rendered = next;
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
    int bottom = Math.min(rendered.lineCount() - 1, text.getLineIndex(area.height - 1));
    Color bar = MarkdownStyler.color(palette.lineNumbers());
    Color rule = MarkdownStyler.color(palette.accents().rightMargin());
    for (int line = top; line <= bottom; line++) {
      int y0 = text.getLinePixel(line);
      int y1 = y0 + lineHeight(line);
      int quotes = rendered.quoteDepth(line);
      if (quotes > 0) {
        MarkdownAlert alert = styler.alertAt(line);
        gc.setBackground(alert != null ? styler.alertColor(alert) : bar);
        for (int q = 0; q < quotes; q++) {
          gc.fillRectangle(left + q * MarkdownStyler.QUOTE_INDENT + 2, y0, BAR_WIDTH, y1 - y0);
        }
      }
      gc.setForeground(rule);
      if (rendered.rule(line)) {
        int x = left + styler.baseIndent(line);
        gc.drawLine(x, (y0 + y1) / 2, right, (y0 + y1) / 2);
      }
    }
    for (MarkdownHeading heading : rendered.headings()) {
      if (heading.level() <= 2 && heading.line() >= top && heading.line() <= bottom) {
        int y = text.getLinePixel(heading.line()) + lineHeight(heading.line()) - 1;
        gc.drawLine(left + styler.baseIndent(heading.line()), y, right, y);
      }
    }
    for (int i = 0; i < rendered.tables().size(); i++) {
      MarkdownTable table = rendered.tables().get(i);
      int last = table.firstLine() + table.rowCount() - 1;
      MarkdownTableLayout layout = tables.measured(i);
      if (layout != null && table.firstLine() <= bottom && last >= top) {
        borders(gc, table, layout, left);
      }
    }
  }

  private void borders(GC gc, MarkdownTable table, MarkdownTableLayout layout, int left) {
    int[] edges = layout.edges();
    int x0 = left + styler.baseIndent(table.firstLine());
    int first = text.getLinePixel(table.firstLine());
    int last = table.firstLine() + table.rowCount() - 1;
    int end = text.getLinePixel(last) + lineHeight(last);
    for (int row = 0; row <= table.rowCount(); row++) {
      int y = row == table.rowCount() ? end - 1 : text.getLinePixel(table.firstLine() + row);
      gc.drawLine(x0 + edges[0], y, x0 + edges[edges.length - 1], y);
    }
    for (int edge : edges) {
      gc.drawLine(x0 + edge, first, x0 + edge, end - 1);
    }
  }

  private int lineHeight(int line) {
    return line + 1 < text.getLineCount()
        ? text.getLinePixel(line + 1) - text.getLinePixel(line)
        : text.getLineHeight(text.getOffsetAtLine(line));
  }

  /** Draws an image, or a placeholder for one that does not decode. */
  void paintObject(PaintObjectEvent event) {
    int offset = event.style.start;
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
