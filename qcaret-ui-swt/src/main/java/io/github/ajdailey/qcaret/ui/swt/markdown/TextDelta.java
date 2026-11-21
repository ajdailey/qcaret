package io.github.ajdailey.qcaret.ui.swt.markdown;

import org.eclipse.swt.custom.StyledText;

/**
 * Changes a wrapping {@link StyledText}'s text to another by whichever of two calls measures fewer
 * lines. {@code setText} drops every measured line height, and the caller's {@code setTopIndex}
 * then measures each line above the top line: 1.9 s at 1 MiB with the top at the end. One {@code
 * replaceTextRange} of the span between the common prefix and suffix keeps the heights outside the
 * span, and measures the lines between the top line and the far end of the span.
 */
final class TextDelta {
  private TextDelta() {}

  /**
   * Shows the new text; an equal text is left alone.
   *
   * @param text the widget, holding {@code shown}
   * @param shown the widget's text
   * @param next the text it gets
   */
  static void apply(StyledText text, String shown, String next) {
    int prefix = prefix(shown, next);
    if (prefix == shown.length() && prefix == next.length()) {
      return;
    }
    int suffix = suffix(shown, next, prefix);
    String inserted = next.substring(prefix, next.length() - suffix);
    int top = text.getTopIndex();
    int first = text.getLineAtOffset(prefix);
    int last = Math.max(text.getLineAtOffset(shown.length() - suffix), first + newlines(inserted));
    int replaceCost = Math.max(Math.abs(first - top), Math.abs(last - top));
    if (top <= replaceCost) {
      text.setText(next);
    } else {
      text.replaceTextRange(prefix, shown.length() - prefix - suffix, inserted);
    }
  }

  private static int newlines(String s) {
    int count = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
  }

  /** The length of the common prefix. */
  private static int prefix(String a, String b) {
    int limit = Math.min(a.length(), b.length());
    int i = 0;
    while (i < limit && a.charAt(i) == b.charAt(i)) {
      i++;
    }
    return i;
  }

  /** The length of the common suffix that leaves the first {@code prefix} chars of each alone. */
  private static int suffix(String a, String b, int prefix) {
    int limit = Math.min(a.length(), b.length()) - prefix;
    int i = 0;
    while (i < limit && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
      i++;
    }
    return i;
  }
}
