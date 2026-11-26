package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.core.text.ControlChars;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.eclipse.swt.custom.PaintObjectEvent;
import org.eclipse.swt.custom.PaintObjectListener;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Draws each {@link ControlChars} boxed char of a {@code StyledText} as a box holding its label,
 * two columns wide. The widget's content gives {@link ControlChars#shown} lines, so it holds {@link
 * ControlChars#PLACEHOLDER} where the text holds a boxed char; {@link #styles} gives each run of
 * such chars a {@link StyleRange} of its own with {@link GlyphMetrics} of the cell's width and the
 * font's ascent and descent, which keeps the row height, and a paint object listener draws the
 * boxes inside the GC's clipping over their cells. A row without the placeholder keeps its styles
 * array.
 *
 * <p>Box: a 1 px outline from the cell's left edge to the pixel before its right edge, 1 px below
 * the row top and 1 px above the row's last pixel, around the label in the widget's font from the
 * cell's left edge on the text baseline. Outline and label are {@link #ink}: the text color (the
 * selection text in the selection, a style's foreground) over the ground under the cell (the
 * selection, a style's background, the row's background, else the widget's) at {@link #ALPHA}.
 */
public final class ControlBoxes {
  /** The text of the rows a widget shows, with their boxed chars, and each row's background. */
  public interface Rows {
    /**
     * Returns a row's original text.
     *
     * @param rowOffset the row's first offset in the widget
     * @return the row's text, as long as the widget's line
     */
    String original(int rowOffset);

    /**
     * Returns a row's own background.
     *
     * @param rowOffset the row's first offset in the widget
     * @return the color, or null for the widget's background
     */
    Color background(int rowOffset);
  }

  /** Alpha of the box color over its ground: 4.6:1 on the light palette, 5.6:1 on the dark. */
  public static final int ALPHA = 0xa6;

  /** Least contrast ratio of the ink against its ground. */
  static final double MIN_CONTRAST = 3.0;

  private final StyledText text;
  private final Rows rows;
  private GlyphMetrics metrics;
  private Font font;
  private RGB inkFore;
  private RGB inkBack;
  private Color inkColor;

  /**
   * Draws the boxes of a widget.
   *
   * @param text the widget, whose content gives {@link ControlChars#shown} lines
   * @param rows the widget's original rows
   */
  public ControlBoxes(StyledText text, Rows rows) {
    this.text = text;
    this.rows = rows;
    text.addPaintObjectListener(
        new PaintObjectListener() {
          @Override
          public void paintObject(PaintObjectEvent event) {
            paint(event);
          }
        });
  }

  /**
   * Measures a font: the cell is {@code cellWidth} wide and as high as the font's row.
   *
   * @param next the widget's font, owned by the caller
   * @param cellWidth the width of two columns
   */
  public void setFont(Font next, int cellWidth) {
    GC gc = new GC(text);
    try {
      gc.setFont(next);
      FontMetrics fm = gc.getFontMetrics();
      metrics = new GlyphMetrics(fm.getAscent(), fm.getDescent(), cellWidth);
      font = next;
    } finally {
      gc.dispose();
    }
  }

  /** Returns the cell's metrics, or null before {@link #setFont}. */
  GlyphMetrics metrics() {
    return metrics;
  }

  /**
   * Returns a row's styles with a box style for each control char.
   *
   * @param rowOffset the row's first offset in the widget
   * @param shown the row as the widget has it
   * @param styles the row's styles, sorted and not overlapping, or null
   * @return {@code styles} itself when the row has no placeholder, else the split styles
   */
  public StyleRange[] styles(int rowOffset, String shown, StyleRange[] styles) {
    if (metrics == null || shown.indexOf(ControlChars.PLACEHOLDER) < 0) {
      return styles;
    }
    return split(styles, rowOffset, rows.original(rowOffset), metrics);
  }

  /**
   * Splits styles around the control chars of a row and gives each run of control chars within one
   * style a style of its own, carrying {@code metrics} and the run's chars as its {@code data}.
   *
   * @param styles sorted, not overlapping, or null
   * @param rowOffset the row's first offset
   * @param original the row's text
   * @param metrics the cell
   * @return the styles, sorted
   */
  static StyleRange[] split(
      StyleRange[] styles, int rowOffset, String original, GlyphMetrics metrics) {
    StyleRange[] in = styles == null ? new StyleRange[0] : styles;
    List<StyleRange> out = new ArrayList<StyleRange>(in.length + 8);
    int at = next(original, 0);
    for (StyleRange style : in) {
      int end = style.start + style.length;
      while (at >= 0 && rowOffset + at < style.start) {
        int to = runEnd(original, at, style.start - rowOffset);
        out.add(box(null, rowOffset + at, original.substring(at, to), metrics));
        at = next(original, to);
      }
      int cursor = style.start;
      while (at >= 0 && rowOffset + at < end) {
        int offset = rowOffset + at;
        if (offset > cursor) {
          out.add(part(style, cursor, offset - cursor));
        }
        int to = runEnd(original, at, end - rowOffset);
        out.add(box(style, offset, original.substring(at, to), metrics));
        cursor = rowOffset + to;
        at = next(original, to);
      }
      if (cursor == style.start) {
        out.add(style);
      } else if (cursor < end) {
        out.add(part(style, cursor, end - cursor));
      }
    }
    while (at >= 0) {
      int to = runEnd(original, at, original.length());
      out.add(box(null, rowOffset + at, original.substring(at, to), metrics));
      at = next(original, to);
    }
    return out.toArray(new StyleRange[0]);
  }

  private static int next(String original, int from) {
    for (int i = from; i < original.length(); i++) {
      if (ControlChars.boxed(original.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  /** Returns the end of the run of control chars from {@code at}, at most {@code limit}. */
  private static int runEnd(String original, int at, int limit) {
    int to = at + 1;
    int max = Math.min(limit, original.length());
    while (to < max && ControlChars.boxed(original.charAt(to))) {
      to++;
    }
    return to;
  }

  private static StyleRange part(StyleRange style, int start, int length) {
    StyleRange copy = (StyleRange) style.clone();
    copy.start = start;
    copy.length = length;
    return copy;
  }

  private static StyleRange box(StyleRange style, int offset, String run, GlyphMetrics metrics) {
    StyleRange box = style == null ? new StyleRange() : part(style, offset, run.length());
    box.start = offset;
    box.length = run.length();
    box.metrics = metrics;
    box.data = run;
    return box;
  }

  private void paint(PaintObjectEvent event) {
    StyleRange style = event.style;
    if (!(style.data instanceof String run) || font == null) {
      return;
    }
    int width = style.metrics.width;
    Rectangle clip = event.gc.getClipping();
    int first = Math.max(0, (clip.x - event.x) / width);
    int last = Math.min(run.length(), (clip.x + clip.width - event.x) / width + 1);
    if (first >= last) {
      return;
    }
    Color fore = style.foreground != null ? style.foreground : text.getForeground();
    Color back = style.background;
    if (back == null) {
      back = rows.background(text.getOffsetAtLine(text.getLineAtOffset(style.start)));
      if (back == null) {
        back = text.getBackground();
      }
    }
    Color plain = ink(fore.getRGB(), back.getRGB());
    int[] selection = text.getSelectionRanges();
    int height = event.ascent + event.descent;
    for (int i = first; i < last; i++) {
      Color ink = plain;
      if (selected(selection, style.start + i)) {
        ink = ink(text.getSelectionForeground().getRGB(), text.getSelectionBackground().getRGB());
      }
      draw(
          event.gc,
          font,
          ink,
          event.x + i * width,
          event.y,
          width,
          height,
          ControlChars.label(run.charAt(i)));
    }
  }

  /** Returns the ink over a background, the last one computed when both colors repeat. */
  private Color ink(RGB fore, RGB back) {
    if (!fore.equals(inkFore) || !back.equals(inkBack)) {
      Rgb rgb =
          ink(new Rgb(fore.red, fore.green, fore.blue), new Rgb(back.red, back.green, back.blue));
      inkFore = fore;
      inkBack = back;
      inkColor = new Color(rgb.red(), rgb.green(), rgb.blue());
    }
    return inkColor;
  }

  private static boolean selected(int[] ranges, int offset) {
    for (int i = 0; i < ranges.length; i += 2) {
      if (offset >= ranges[i] && offset < ranges[i] + ranges[i + 1]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Draws one box; the GC's foreground and font are restored.
   *
   * @param gc the GC
   * @param font the text font
   * @param ink the outline and label color
   * @param x the cell's left edge
   * @param y the row's top edge
   * @param width the cell's width
   * @param height the row's height
   * @param label the two digits
   */
  public static void draw(
      GC gc, Font font, Color ink, int x, int y, int width, int height, String label) {
    Color oldFore = gc.getForeground();
    Font oldFont = gc.getFont();
    try {
      gc.setForeground(ink);
      gc.setLineWidth(1);
      gc.setFont(font);
      gc.drawString(label, x, y, true);
      gc.drawRectangle(x, y + 1, Math.max(1, width - 1), Math.max(1, height - 3));
    } finally {
      gc.setForeground(oldFore);
      gc.setFont(oldFont);
    }
  }

  /**
   * Draws one box for a printer: the outline is four filled bands {@code rule} units thick, from
   * the cell's left edge to its right edge and {@code rule} inside the row's top and bottom. The
   * label is centered in {@link #labelArea}: its extent across, and down the part of its extent
   * above the descent, since hex digits have no descenders. The caller sizes {@code font} with
   * {@link #labelHeight} so the whole extent fits there. The GC's colors and font are restored.
   *
   * @param gc the GC
   * @param font the label font
   * @param ink the outline and label color
   * @param x the cell's left edge
   * @param y the row's top edge
   * @param width the cell's width
   * @param height the row's height
   * @param rule the outline's thickness, at least 1
   * @param label the two digits
   */
  public static void drawRuled(
      GC gc, Font font, Color ink, int x, int y, int width, int height, int rule, String label) {
    Color oldFore = gc.getForeground();
    Color oldBack = gc.getBackground();
    Font oldFont = gc.getFont();
    try {
      gc.setForeground(ink);
      gc.setFont(font);
      Point at =
          labelOrigin(
              labelArea(x, y, width, height, rule),
              gc.stringExtent(label),
              gc.getFontMetrics().getDescent());
      gc.drawString(label, at.x, at.y, true);
      gc.setBackground(ink);
      int top = y + rule;
      int inner = Math.max(0, height - 4 * rule);
      gc.fillRectangle(x, top, width, rule);
      gc.fillRectangle(x, top + rule + inner, width, rule);
      gc.fillRectangle(x, top + rule, rule, inner);
      gc.fillRectangle(x + width - rule, top + rule, rule, inner);
    } finally {
      gc.setForeground(oldFore);
      gc.setBackground(oldBack);
      gc.setFont(oldFont);
    }
  }

  /**
   * Returns where a {@link #drawRuled} label may go: inside the outline, one {@code rule} clear of
   * it on every side.
   *
   * @param x the cell's left edge
   * @param y the row's top edge
   * @param width the cell's width
   * @param height the row's height
   * @param rule the outline's thickness
   * @return the area, empty when the box is too small
   */
  public static Rectangle labelArea(int x, int y, int width, int height, int rule) {
    return new Rectangle(
        x + 2 * rule, y + 3 * rule, Math.max(0, width - 4 * rule), Math.max(0, height - 6 * rule));
  }

  /**
   * Returns where {@link #drawRuled} draws a label: its extent centered across {@code area}, and
   * the part of its extent above the descent centered down it.
   *
   * @param area the {@link #labelArea}
   * @param extent the label's text extent
   * @param descent the label font's descent
   * @return the label's top-left corner
   */
  static Point labelOrigin(Rectangle area, Point extent, int descent) {
    return new Point(
        area.x + (area.width - extent.x) / 2, area.y + (area.height - (extent.y - descent)) / 2);
  }

  /**
   * Returns the largest font height, from {@code max} points down to 1, whose widest label extent
   * fits {@code space}.
   *
   * @param max the text font's height in points
   * @param space the size of {@link #labelArea}
   * @param widest returns the widest label's extent at a height in points
   * @return the height in points, 1 when none fits
   */
  public static int labelHeight(int max, Point space, IntFunction<Point> widest) {
    for (int points = max; points > 1; points--) {
      Point extent = widest.apply(points);
      if (extent.x <= space.x && extent.y <= space.y) {
        return points;
      }
    }
    return 1;
  }

  /**
   * Returns the box color: {@code text} over {@code ground} at {@link #ALPHA}, or {@code text}
   * itself where that blend is under 3:1 against the ground (a theme file's colors).
   *
   * @param text the color the char's text would take
   * @param ground the color under the cell
   * @return the color
   */
  public static Rgb ink(Rgb text, Rgb ground) {
    Rgb blend =
        new Rgb(
            mix(text.red(), ground.red()),
            mix(text.green(), ground.green()),
            mix(text.blue(), ground.blue()));
    return contrast(blend, ground) >= MIN_CONTRAST ? blend : text;
  }

  private static int mix(int top, int bottom) {
    return (top * ALPHA + bottom * (255 - ALPHA) + 127) / 255;
  }

  /**
   * Returns the WCAG 2 contrast ratio of two colors.
   *
   * @param a a color
   * @param b another color
   * @return 1 to 21
   */
  static double contrast(Rgb a, Rgb b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double luminance(Rgb rgb) {
    return 0.2126 * linear(rgb.red()) + 0.7152 * linear(rgb.green()) + 0.0722 * linear(rgb.blue());
  }

  private static double linear(int component) {
    double c = component / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }
}
