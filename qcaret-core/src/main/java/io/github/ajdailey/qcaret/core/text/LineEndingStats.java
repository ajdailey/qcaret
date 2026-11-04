package io.github.ajdailey.qcaret.core.text;

/**
 * Counts of each line break kind in a text. A CR followed by an LF is one {@link LineEnding#CRLF}.
 *
 * @param lf LF breaks
 * @param crLf CR LF breaks
 * @param cr CR breaks
 * @param first the kind of the first break, or null when there is none
 */
public record LineEndingStats(long lf, long crLf, long cr, LineEnding first) {
  /** No breaks. */
  public static final LineEndingStats NONE = new LineEndingStats(0, 0, 0, null);

  /**
   * Counts the breaks of a range taken on its own: a CR at the end of the range is a CR break.
   *
   * @param text the text
   * @param start the first index
   * @param end the index after the last char
   * @return the counts
   */
  public static LineEndingStats count(CharSequence text, int start, int end) {
    long lf = 0;
    long crLf = 0;
    long cr = 0;
    LineEnding first = null;
    for (int i = start; i < end; i++) {
      char c = text.charAt(i);
      LineEnding kind = null;
      if (c == '\n') {
        lf++;
        kind = LineEnding.LF;
      } else if (c == '\r') {
        if (i + 1 < end && text.charAt(i + 1) == '\n') {
          crLf++;
          i++;
          kind = LineEnding.CRLF;
        } else {
          cr++;
          kind = LineEnding.CR;
        }
      }
      if (first == null) {
        first = kind;
      }
    }
    return new LineEndingStats(lf, crLf, cr, first);
  }

  /**
   * Returns these counts plus {@code after} minus {@code before}, for an edit that turned a range
   * counting {@code before} into one counting {@code after}. The first kind is kept.
   *
   * @param before the counts of the replaced range
   * @param after the counts of the range that replaced it
   * @return the new counts
   */
  public LineEndingStats replace(LineEndingStats before, LineEndingStats after) {
    return new LineEndingStats(
        lf - before.lf + after.lf,
        crLf - before.crLf + after.crLf,
        cr - before.cr + after.cr,
        first != null ? first : after.first);
  }

  /**
   * Reports whether more than one kind occurs.
   *
   * @return true for mixed line endings
   */
  public boolean isMixed() {
    int kinds = (lf > 0 ? 1 : 0) + (crLf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
    return kinds > 1;
  }

  /**
   * Returns the most frequent kind; on a tie the first one seen.
   *
   * @param empty the result when the text has no break
   * @return the dominant kind
   */
  public LineEnding dominant(LineEnding empty) {
    if (first == null) {
      return empty;
    }
    long max = Math.max(lf, Math.max(crLf, cr));
    if (count(first) == max) {
      return first;
    }
    if (lf == max) {
      return LineEnding.LF;
    }
    return crLf == max ? LineEnding.CRLF : LineEnding.CR;
  }

  private long count(LineEnding kind) {
    switch (kind) {
      case LF:
        return lf;
      case CRLF:
        return crLf;
      default:
        return cr;
    }
  }
}
