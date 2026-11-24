package io.github.ajdailey.qcaret.ui.swt.markdown;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;

/**
 * The width of a code line's leading spaces and tabs in a pane, which a wrapped code line's
 * continuation adds to its indent so it starts under the line's own text. A run of spaces is
 * measured whole in the code font with a {@link GC} on the widget, once per length and font: at a
 * fractional monitor zoom one space's width in points is rounded, and a sum of rounded spaces
 * drifts from the drawn indent by a point every few spaces. A tab advances to the next multiple of
 * the widget's tab width ({@code StyledText.getTabs} spaces of the widget font). Call on the UI
 * thread.
 */
final class CodeIndent {
  private final StyledText text;
  private final MarkdownFonts fonts;
  private Font measured;
  private int[] spaces = new int[0];
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
      spaces = new int[0];
      tab = 0;
    }
    int width = 0;
    int run = 0;
    for (int i = start; i < end; i++) {
      char c = chars.charAt(i);
      if (c == ' ') {
        run++;
      } else if (c == '\t') {
        width += spaces(run);
        run = 0;
        int step = tab();
        width = (width / step + 1) * step;
      } else {
        break;
      }
    }
    return width + spaces(run);
  }

  /** The width of a run of spaces in the code font. */
  private int spaces(int run) {
    if (run == 0) {
      return 0;
    }
    if (run >= spaces.length) {
      int[] grown = new int[Math.max(run + 1, 2 * spaces.length)];
      System.arraycopy(spaces, 0, grown, 0, spaces.length);
      spaces = grown;
    }
    if (spaces[run] == 0) {
      GC gc = new GC(text);
      try {
        gc.setFont(measured);
        spaces[run] = Math.max(1, gc.textExtent(" ".repeat(run), 0).x);
      } finally {
        gc.dispose();
      }
    }
    return spaces[run];
  }

  /** The widget's tab width. */
  private int tab() {
    if (tab == 0) {
      GC gc = new GC(text);
      try {
        gc.setFont(text.getFont());
        tab = Math.max(1, text.getTabs() * gc.textExtent(" ", 0).x);
      } finally {
        gc.dispose();
      }
    }
    return tab;
  }
}
