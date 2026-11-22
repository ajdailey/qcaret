package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.editor.TextMeasurer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;

/**
 * Measures ranges of a render's text outside code blocks with a {@link GC}: each span in the font
 * {@link MarkdownStyler#font} gives it, so a table cell holding code, bold or italic text measures
 * as wide as it draws. Confined to the GC's thread.
 */
final class SpanWidths implements TextMeasurer {
  private final GC gc;
  private final MarkdownFonts fonts;
  private final String text;
  private final int[] spans;

  /**
   * Creates the measurer.
   *
   * @param gc measures; its font changes
   * @param fonts the fonts the text draws with
   * @param text the rendered text
   * @param spans its spans, as {@link MarkdownSpans#of} gives them
   */
  SpanWidths(GC gc, MarkdownFonts fonts, String text, int[] spans) {
    this.gc = gc;
    this.fonts = fonts;
    this.text = text;
    this.spans = spans;
  }

  @Override
  public int width(int start, int end, boolean strong) {
    int total = 0;
    int at = start;
    for (int i = MarkdownSpans.first(spans, start); 3 * i < spans.length; i++) {
      int s = Math.max(spans[3 * i], start);
      if (s >= end) {
        break;
      }
      int t = Math.min(spans[3 * i + 1], end);
      total += segment(at, s, 0, strong);
      total += segment(s, t, spans[3 * i + 2], strong);
      at = t;
    }
    return total + segment(at, end, 0, strong);
  }

  private int segment(int from, int to, int bits, boolean strong) {
    if (from >= to) {
      return 0;
    }
    Font font = MarkdownStyler.font(bits, strong, fonts);
    gc.setFont(font != null ? font : fonts.get(false, SWT.NORMAL, 0, false));
    return gc.textExtent(text.substring(from, to), 0).x;
  }
}
