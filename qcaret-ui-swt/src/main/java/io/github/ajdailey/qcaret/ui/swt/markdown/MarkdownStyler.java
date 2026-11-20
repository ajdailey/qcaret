package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.theme.TokenStyle;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.core.markdown.MarkdownAlert;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.Bullet;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.TextStyle;

/**
 * Maps one rendered line of the content a pane shows onto {@code StyledText} line styles, as the
 * widget paints it: fonts, colors and decorations from the {@link MarkdownSpans}, the monospace
 * font on code lines with the token colors of the palette, bold table headers, glyph metrics that
 * reserve each image's space, the indent of quotes and lists with a bullet, number or checkbox on
 * an item's first line, and a measured table's tab stops. Nothing is applied ahead of painting, so
 * the work per UI turn follows the lines in view, not the document. Colors follow {@link
 * #setPalette}. Call on the UI thread.
 */
final class MarkdownStyler {
  /** Pixels a quote level indents. */
  static final int QUOTE_INDENT = 16;

  /** Pixels a list level indents. */
  static final int LIST_INDENT = 24;

  /** The space a list marker takes before an item's text. */
  static final int BULLET_WIDTH = 24;

  private static final String OPEN_BOX = "☐";
  private static final String DONE_BOX = "☑";

  /** A line's styles as {@code StyledText} takes them from a line style event. */
  static final class LineStyle {
    StyleRange[] styles;
    int indent;
    int wrapIndent;
    Bullet bullet;
    int[] tabStops;

    /** Copies the style into the widget's event. */
    void applyTo(LineStyleEvent event) {
      event.styles = styles;
      event.indent = indent;
      event.wrapIndent = wrapIndent;
      event.bullet = bullet;
      event.bulletIndex = 0;
      event.tabStops = tabStops;
    }
  }

  private final MarkdownFonts fonts;
  private final PaneImages images;
  private final TableColumns tables;
  private MarkdownPreviewContent content;
  private Palette palette;
  private int paneWidth;

  MarkdownStyler(MarkdownFonts fonts, PaneImages images, TableColumns tables, Palette palette) {
    this.fonts = fonts;
    this.images = images;
    this.tables = tables;
    this.palette = palette;
  }

  /** Takes new content. */
  void setContent(MarkdownPreviewContent next) {
    content = next;
  }

  /** Takes a new palette. */
  void setPalette(Palette next) {
    palette = next;
  }

  /** Takes the pane's text width, for images sized as a share of it. */
  void setPaneWidth(int width) {
    paneWidth = width;
  }

  /** Returns the palette. */
  Palette palette() {
    return palette;
  }

  /**
   * Computes a line's styles, indent, bullet and tab stops.
   *
   * @param line the line's index
   * @param start the line's first offset
   * @param end the offset after its last char
   * @return the style, empty for a line past the content
   */
  LineStyle style(int line, int start, int end) {
    LineStyle style = new LineStyle();
    if (content == null || line >= content.rendered().lineCount()) {
      return style;
    }
    RenderedMarkdown r = content.rendered();
    int table = tableAt(line);
    boolean header = table >= 0 && r.tables().get(table).firstLine() == line;
    boolean code = r.code(line);
    List<StyleRange> out = new ArrayList<StyleRange>();
    int[] spans = content.spans();
    int at = start;
    for (int i = MarkdownSpans.first(spans, start); 3 * i < spans.length; i++) {
      int s = Math.max(spans[3 * i], start);
      if (s >= end) {
        break;
      }
      int t = Math.min(spans[3 * i + 1], end);
      add(out, at, s, 0, code, header);
      add(out, s, t, spans[3 * i + 2], code, header);
      at = t;
    }
    add(out, at, end, 0, code, header);
    style.styles = out.toArray(new StyleRange[0]);
    indent(style, r, line, table);
    return style;
  }

  /**
   * Returns a line's background.
   *
   * @param line a line
   * @return the code shade for a code line, else null
   */
  Color background(int line) {
    if (content == null || line >= content.rendered().lineCount()) {
      return null;
    }
    return content.rendered().code(line) ? color(palette.gutter()) : null;
  }

  /**
   * Returns the indent a line's quotes and lists take.
   *
   * @param line a line
   * @return pixels from the left margin
   */
  int baseIndent(int line) {
    if (content == null) {
      return 0;
    }
    RenderedMarkdown r = content.rendered();
    int list = r.listDepth(line);
    return r.quoteDepth(line) * QUOTE_INDENT + (list > 0 ? (list - 1) * LIST_INDENT : 0);
  }

  /**
   * Finds the table holding a line.
   *
   * @param line a line
   * @return the table's index, or -1
   */
  int tableAt(int line) {
    if (content == null) {
      return -1;
    }
    List<MarkdownTable> all = content.rendered().tables();
    int lo = 0;
    int hi = all.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (all.get(mid).firstLine() <= line) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    if (lo == 0) {
      return -1;
    }
    MarkdownTable t = all.get(lo - 1);
    return line < t.firstLine() + t.rowCount() ? lo - 1 : -1;
  }

  /**
   * Finds the alert holding a line.
   *
   * @param line a line
   * @return the alert, or null
   */
  MarkdownAlert alertAt(int line) {
    if (content == null) {
      return null;
    }
    List<MarkdownAlert> all = content.rendered().alerts();
    int lo = 0;
    int hi = all.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (all.get(mid).firstLine() <= line) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    MarkdownAlert alert = lo == 0 ? null : all.get(lo - 1);
    return alert != null && line < alert.firstLine() + alert.lineCount() ? alert : null;
  }

  /**
   * Returns an alert's color.
   *
   * @param alert an alert
   * @return the color of its kind
   */
  Color alertColor(MarkdownAlert alert) {
    TokenRole role =
        switch (alert.kind()) {
          case "tip" -> TokenRole.TYPE;
          case "important" -> TokenRole.NUMBER;
          case "warning" -> TokenRole.STRING;
          case "caution" -> TokenRole.INVALID;
          default -> TokenRole.LINK;
        };
    return color(palette.style(role).foreground());
  }

  private void indent(LineStyle event, RenderedMarkdown r, int line, int table) {
    int base = baseIndent(line);
    int list = r.listDepth(line);
    int textStart = list > 0 ? base + BULLET_WIDTH : base;
    event.indent = textStart;
    event.wrapIndent = textStart;
    if (r.marker(line) != RenderedMarkdown.ListMarker.NONE) {
      // StyledText draws a bullet right-aligned in its metrics from the margin, ignoring the
      // indent, and starts the text after both: the metrics span the whole indent.
      event.bullet = bullet(r, line, textStart);
      event.indent = 0;
    }
    MarkdownTableLayout layout = table >= 0 ? tables.measured(table) : null;
    if (layout != null) {
      MarkdownTableLayout.Row row = layout.row(line - r.tables().get(table).firstLine());
      event.indent = base + row.indent();
      if (row.tabStops().length > 0) {
        int[] stops = new int[row.tabStops().length];
        for (int i = 0; i < stops.length; i++) {
          stops[i] = base + row.tabStops()[i];
        }
        event.tabStops = stops;
      }
    }
  }

  private Bullet bullet(RenderedMarkdown r, int line, int width) {
    StyleRange style = new StyleRange();
    style.metrics = new GlyphMetrics(0, 0, width);
    style.foreground = color(palette.text());
    RenderedMarkdown.TaskState task = r.task(line);
    if (task != RenderedMarkdown.TaskState.NONE) {
      Bullet b = new Bullet(ST.BULLET_TEXT, style);
      b.text = task == RenderedMarkdown.TaskState.DONE ? DONE_BOX : OPEN_BOX;
      return b;
    }
    if (r.marker(line) == RenderedMarkdown.ListMarker.NUMBER) {
      Bullet b = new Bullet(ST.BULLET_TEXT, style);
      b.text = r.number(line) + ".";
      return b;
    }
    return new Bullet(ST.BULLET_DOT, style);
  }

  /** Adds ranges over [from, to), split at each image so the image char takes its metrics. */
  private void add(List<StyleRange> out, int from, int to, int bits, boolean code, boolean header) {
    if (from >= to) {
      return;
    }
    int[] offsets = images.offsets();
    int i = Arrays.binarySearch(offsets, from);
    if (i < 0) {
      i = -i - 1;
    }
    int at = from;
    for (; i < offsets.length && offsets[i] < to; i++) {
      range(out, at, offsets[i], bits, code, header);
      StyleRange image = new StyleRange();
      image.start = offsets[i];
      image.length = 1;
      Point size = images.size(offsets[i], paneWidth);
      image.metrics = new GlyphMetrics(size.y, 0, size.x);
      out.add(image);
      at = offsets[i] + 1;
    }
    range(out, at, to, bits, code, header);
  }

  private void range(
      List<StyleRange> out, int from, int to, int bits, boolean code, boolean header) {
    if (from >= to || (bits == 0 && !code && !header)) {
      return;
    }
    StyleRange r = new StyleRange();
    r.start = from;
    r.length = to - from;
    apply(r, bits, code, header, palette, fonts, 1f);
    out.add(r);
  }

  /**
   * Sets a text style from a span's bits, as Basic shows the span.
   *
   * @param r the style to set
   * @param bits the span's {@link MarkdownSpans} bits, 0 for plain text
   * @param code true on a code line
   * @param header true on a table's header row
   * @param palette the colors
   * @param fonts the fonts
   * @param pixelsPerPoint device pixels per point, for the rise of subscript and superscript
   */
  static void apply(
      TextStyle r,
      int bits,
      boolean code,
      boolean header,
      Palette palette,
      MarkdownFonts fonts,
      float pixelsPerPoint) {
    int level = MarkdownSpans.level(bits);
    int role = MarkdownSpans.role(bits);
    boolean mono = code || (bits & (MarkdownSpans.CODE | MarkdownSpans.KBD)) != 0;
    boolean small = (bits & (MarkdownSpans.SUB | MarkdownSpans.SUP)) != 0;
    int style = SWT.NORMAL;
    if (header || level > 0 || (bits & MarkdownSpans.STRONG) != 0) {
      style |= SWT.BOLD;
    }
    if ((bits & MarkdownSpans.EMPHASIS) != 0) {
      style |= SWT.ITALIC;
    }
    if (role >= 0) {
      TokenStyle token = palette.style(TokenRole.values()[role]);
      r.foreground = color(token.foreground());
      style |= (token.bold() ? SWT.BOLD : 0) | (token.italic() ? SWT.ITALIC : 0);
      r.underline = token.underline();
    }
    if (level > 0) {
      r.foreground = color(palette.style(TokenRole.HEADING).foreground());
    }
    if ((bits & MarkdownSpans.LINK) != 0) {
      r.foreground = color(palette.style(TokenRole.LINK).foreground());
      r.underline = true;
    }
    if (!code && (bits & (MarkdownSpans.CODE | MarkdownSpans.KBD)) != 0) {
      r.background = color(palette.gutter());
    }
    if ((bits & MarkdownSpans.KBD) != 0) {
      r.borderStyle = SWT.BORDER_SOLID;
      r.borderColor = color(palette.lineNumbers());
    }
    if ((bits & MarkdownSpans.STRIKETHROUGH) != 0) {
      r.strikeout = true;
    }
    if (mono || style != SWT.NORMAL || level > 0 || small) {
      r.font = fonts.get(mono, style, level, small);
    }
    int height = (int) (r.font == null ? 0 : r.font.getFontData()[0].getHeight() * pixelsPerPoint);
    if ((bits & MarkdownSpans.SUP) != 0) {
      r.rise = height / 2;
    } else if ((bits & MarkdownSpans.SUB) != 0) {
      r.rise = -height / 3;
    }
  }

  /** Returns a palette color; SWT colors without a device hold no resource. */
  static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
