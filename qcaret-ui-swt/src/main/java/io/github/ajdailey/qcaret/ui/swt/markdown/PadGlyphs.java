package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.BlockSpacing;
import io.github.ajdailey.qcaret.app.editor.TableWrap;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.GlyphMetrics;

/**
 * Styles the zero-width {@link TableWrap#PAD} chars that add space to a body font line: the char of
 * a padded separator line ({@link BlockSpacing}) and the char ending a laid-out table row's first
 * and last display lines. The body font's ascent and descent are measured once, with a {@link GC}
 * on the widget. Call on the UI thread.
 */
final class PadGlyphs {
  /** Pixels above a row's text. */
  static final int PAD_ABOVE = 6;

  /** Pixels below a row's text, before the pane's line spacing. */
  static final int PAD_BELOW = 4;

  private final StyledText text;
  private final MarkdownFonts fonts;
  private int ascent = -1;
  private int descent;

  PadGlyphs(StyledText text, MarkdownFonts fonts) {
    this.text = text;
    this.fonts = fonts;
  }

  /**
   * Returns the style of a pad char whose glyph metrics are the body font's plus some pixels.
   *
   * @param offset the char's display offset
   * @param above pixels above the body font's ascent
   * @param below pixels below its descent
   * @return the style
   */
  StyleRange glyph(int offset, int above, int below) {
    if (ascent < 0) {
      GC gc = new GC(text);
      try {
        gc.setFont(fonts.get(false, SWT.NORMAL, 0, false));
        FontMetrics metrics = gc.getFontMetrics();
        ascent = metrics.getAscent() + metrics.getLeading();
        descent = metrics.getDescent();
      } finally {
        gc.dispose();
      }
    }
    StyleRange glyph = new StyleRange();
    glyph.start = offset;
    glyph.length = 1;
    glyph.metrics = new GlyphMetrics(ascent + above, descent + below, 0);
    return glyph;
  }

  /**
   * Returns the style of the pad char ending a display line of a laid-out row: {@link #PAD_ABOVE}
   * on its first line, {@link #PAD_BELOW} on its last.
   *
   * @param offset the char's display offset
   * @param row the row
   * @param sub the display line's index within the row
   * @return the style, or null for a line between the first and the last
   */
  StyleRange row(int offset, TableWrap.Row row, int sub) {
    boolean first = sub == 0;
    boolean last = sub == row.lines() - 1;
    if (!first && !last) {
      return null;
    }
    StyleRange glyph = glyph(offset, PAD_ABOVE, PAD_BELOW);
    glyph.metrics =
        new GlyphMetrics(first ? glyph.metrics.ascent : 0, last ? glyph.metrics.descent : 0, 0);
    return glyph;
  }
}
