package io.github.ajdailey.qcaret.core.edit;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * Grapheme cluster boundaries around an offset ({@link BreakIterator#getCharacterInstance}): a
 * surrogate pair, a base char with its combining marks and a CR LF are each one cluster. The
 * boundaries are read from at most {@value #WINDOW} chars on each side of the offset, within given
 * bounds, so a cluster longer than that is split at the window's edge.
 */
final class Clusters {
  /** Chars on each side of the offset the boundaries are read from. */
  static final int WINDOW = 64;

  private final BreakIterator breaks;
  private final int base;

  /**
   * Reads the boundaries around an offset.
   *
   * @param text the text
   * @param offset the offset
   * @param from the lowest offset read, a boundary
   * @param to the highest offset read, a boundary
   */
  Clusters(CharSequence text, int offset, int from, int to) {
    base = Math.max(from, offset - WINDOW);
    breaks = BreakIterator.getCharacterInstance(Locale.ROOT);
    breaks.setText(text.subSequence(base, Math.min(to, offset + WINDOW)).toString());
  }

  /**
   * Reports whether an offset of the window is a boundary.
   *
   * @param offset the offset
   * @return true at a boundary
   */
  boolean isBoundary(int offset) {
    return breaks.isBoundary(offset - base);
  }

  /**
   * Returns the boundary before an offset of the window.
   *
   * @param offset the offset
   * @return the boundary, or -1 at the window's start
   */
  int preceding(int offset) {
    int at = breaks.preceding(offset - base);
    return at == BreakIterator.DONE ? -1 : base + at;
  }

  /**
   * Returns the boundary after an offset of the window.
   *
   * @param offset the offset
   * @return the boundary, or -1 at the window's end
   */
  int following(int offset) {
    int at = breaks.following(offset - base);
    return at == BreakIterator.DONE ? -1 : base + at;
  }
}
