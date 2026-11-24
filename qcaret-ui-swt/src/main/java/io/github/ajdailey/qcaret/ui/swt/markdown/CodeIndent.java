package io.github.ajdailey.qcaret.ui.swt.markdown;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;

/**
 * The width of a code line's leading spaces and tabs in a pane, which a wrapped code line's
 * continuation adds to its indent so it starts under the line's own text. A space is as wide as in
 * the code font, measured once per font with a {@link GC} on the widget; a tab advances to the next
 * multiple of the widget's tab width ({@code StyledText.getTabs} spaces of the widget font). Call
 * on the UI thread.
 */
final class CodeIndent {
  private final StyledText text;
  private final MarkdownFonts fonts;
  private Font measured;
  private int space;
  private int tab;

  CodeIndent(StyledText text, MarkdownFonts fonts) {
    this.text = text;
    this.fonts = fonts;
  }

  /**
   * Measures the leading spaces and tabs of a line.
   *
   * @param chars the text holding the line
   * @param start the line's first offset
   * @param end the offset after its last char
   * @return pixels
   */
  int of(CharSequence chars, int start, int end) {
    if (start >= end || !Character.isWhitespace(chars.charAt(start))) {
      return 0;
    }
    Font code = fonts.get(true, SWT.NORMAL, 0, false);
    if (code != measured) {
      measured = code;
      GC gc = new GC(text);
      try {
        gc.setFont(code);
        space = gc.textExtent(" ", 0).x;
        gc.setFont(text.getFont());
        tab = Math.max(1, text.getTabs() * gc.textExtent(" ", 0).x);
      } finally {
        gc.dispose();
      }
    }
    int width = 0;
    for (int i = start; i < end; i++) {
      char c = chars.charAt(i);
      if (c == ' ') {
        width += space;
      } else if (c == '\t') {
        width = (width / tab + 1) * tab;
      } else {
        break;
      }
    }
    return width;
  }
}
