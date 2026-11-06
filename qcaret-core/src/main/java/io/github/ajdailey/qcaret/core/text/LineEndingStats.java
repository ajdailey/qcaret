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

  /** Counts the breaks of a text read in pieces; a CR ending a piece waits for the next char. */
  public static final class Counter {
    private long lf;
    private long crLf;
    private long cr;
    private LineEnding first;
    private boolean afterCr;

    /**
     * Counts {@code chars[from, to)} as the continuation of the text so far.
     *
     * @param chars the chars
     * @param from the first index
     * @param to the index after the last char
     */
    public void add(char[] chars, int from, int to) {
      for (int i = from; i < to; i++) {
        char c = chars[i];
        if (afterCr) {
          afterCr = false;
          if (c == '\n') {
            crLf++;
            note(LineEnding.CRLF);
            continue;
          }
          cr++;
          note(LineEnding.CR);
        }
        if (c == '\n') {
          lf++;
          note(LineEnding.LF);
        } else if (c == '\r') {
          afterCr = true;
        }
      }
    }

    private void note(LineEnding kind) {
      if (first == null) {
        first = kind;
      }
    }

    /**
     * Returns the counts of the text so far; a CR at its end is a CR break.
     *
     * @return the counts
     */
    public LineEndingStats stats() {
      if (afterCr) {
        return new LineEndingStats(lf, crLf, cr + 1, first != null ? first : LineEnding.CR);
      }
      return new LineEndingStats(lf, crLf, cr, first);
    }
  }

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
