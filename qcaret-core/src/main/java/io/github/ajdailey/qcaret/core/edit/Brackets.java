package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.buffer.TextCursor;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.concurrent.CancellationException;

/**
 * Bracket matching: brackets of the same pair nest, and other pairs are not considered. A {@link
 * Filter} leaves out brackets inside strings and comments; without one every char counts.
 */
public final class Brackets {
  /** Which brackets take part in matching. */
  public interface Filter {
    /** Every char counts. */
    Filter ALL =
        new Filter() {
          @Override
          public boolean counts(int offset) {
            return true;
          }
        };

    /**
     * Reports whether the bracket at an offset takes part in matching.
     *
     * @param offset the bracket's offset
     * @return false for a bracket inside a string or comment
     */
    boolean counts(int offset);
  }

  /** {@link #match} result: the text holds no partner. */
  public static final int NONE = -1;

  /** {@link #match} result: the bounds ended the search before the text did. */
  public static final int BEYOND = -2;

  /** Chars scanned between cancellation checks. */
  private static final int CHECK_CHARS = 1 << 16;

  private Brackets() {}

  /**
   * Returns the bracket next to a caret: the char before it, else the char after it.
   *
   * @param text the text
   * @param caret an offset from 0 to the text length
   * @param pairs the bracket pairs
   * @return the bracket's offset, or -1 when neither char is a bracket
   */
  public static int at(TextContent text, int caret, Pairs pairs) {
    return at(text, caret, pairs, Filter.ALL);
  }

  /**
   * Returns the bracket next to a caret that the filter counts: the char before it, else the char
   * after it.
   *
   * @param text the text
   * @param caret an offset from 0 to the text length
   * @param pairs the bracket pairs
   * @param filter the brackets that count
   * @return the bracket's offset, or -1 when neither char is a bracket that counts
   */
  public static int at(TextContent text, int caret, Pairs pairs, Filter filter) {
    if (caret > 0 && pairs.isBracket(text.charAt(caret - 1)) && filter.counts(caret - 1)) {
      return caret - 1;
    }
    if (caret < text.length() && pairs.isBracket(text.charAt(caret)) && filter.counts(caret)) {
      return caret;
    }
    return -1;
  }

  /**
   * Finds the partner of a bracket within {@code [from, to)}: forward for an open bracket, backward
   * for a close one.
   *
   * @param text the text
   * @param at the bracket's offset
   * @param pairs the bracket pairs
   * @param from the first offset scanned
   * @param to the offset after the last one scanned
   * @param token checked every {@value #CHECK_CHARS} chars
   * @return the partner's offset, {@link #NONE} or {@link #BEYOND}
   * @throws CancellationException when canceled
   */
  public static int match(
      TextContent text, int at, Pairs pairs, int from, int to, CancellationToken token) {
    return match(text, at, pairs, from, to, token, Filter.ALL);
  }

  /**
   * Finds the partner of a bracket within {@code [from, to)}, counting only the brackets the filter
   * counts: forward for an open bracket, backward for a close one.
   *
   * @param text the text
   * @param at the bracket's offset
   * @param pairs the bracket pairs
   * @param from the first offset scanned
   * @param to the offset after the last one scanned
   * @param token checked every {@value #CHECK_CHARS} chars
   * @param filter the brackets that count; called only for the bracket's own pair
   * @return the partner's offset, {@link #NONE} or {@link #BEYOND}
   * @throws CancellationException when canceled
   */
  public static int match(
      TextContent text,
      int at,
      Pairs pairs,
      int from,
      int to,
      CancellationToken token,
      Filter filter) {
    char bracket = text.charAt(at);
    char partner = pairs.partner(bracket);
    int low = Math.max(0, from);
    int high = Math.min(text.length(), to);
    int depth = 1;
    int scanned = 0;
    if (pairs.opens(bracket)) {
      TextCursor cursor = text.cursor(at + 1);
      for (int i = at + 1; i < high; i++) {
        if (++scanned % CHECK_CHARS == 0) {
          token.check();
        }
        char c = cursor.next();
        if ((c == bracket || c == partner) && !filter.counts(i)) {
          continue;
        }
        if (c == bracket) {
          depth++;
        } else if (c == partner && --depth == 0) {
          return i;
        }
      }
      return high < text.length() ? BEYOND : NONE;
    }
    TextCursor cursor = text.cursor(at);
    for (int i = at - 1; i >= low; i--) {
      if (++scanned % CHECK_CHARS == 0) {
        token.check();
      }
      char c = cursor.previous();
      if ((c == bracket || c == partner) && !filter.counts(i)) {
        continue;
      }
      if (c == bracket) {
        depth++;
      } else if (c == partner && --depth == 0) {
        return i;
      }
    }
    return low > 0 ? BEYOND : NONE;
  }
}
