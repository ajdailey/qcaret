package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The numeric sort of {@link LineTransforms.Order#NUMERIC}. A line's key is the number at its start
 * after spaces and tabs: an optional {@code +} or {@code -}, ASCII digits and an optional {@code .}
 * with more digits, at least one digit in all, compared exactly. A line without one sorts after
 * every numbered line; the sort is stable.
 */
final class NumericKeys {
  /** Lines between cancellation checks. */
  private static final int CHECK_LINES = 4096;

  private static final Comparator<Keyed> ORDER =
      new Comparator<Keyed>() {
        @Override
        public int compare(Keyed a, Keyed b) {
          if (a.key() == null || b.key() == null) {
            return a.key() == null ? (b.key() == null ? 0 : 1) : -1;
          }
          return a.key().compareTo(b.key());
        }
      };

  private record Keyed(BigDecimal key, String line) {}

  private NumericKeys() {}

  /**
   * Returns lines in numeric order.
   *
   * @param lines the lines
   * @param token checked every {@value #CHECK_LINES} lines
   * @return a new sorted list
   * @throws CancellationException when canceled
   */
  static List<String> sort(List<String> lines, CancellationToken token) {
    List<Keyed> keyed = new ArrayList<Keyed>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      if (i % CHECK_LINES == 0) {
        token.check();
      }
      keyed.add(new Keyed(key(lines.get(i)), lines.get(i)));
    }
    keyed.sort(ORDER);
    token.check();
    List<String> sorted = new ArrayList<String>(lines.size());
    for (Keyed k : keyed) {
      sorted.add(k.line());
    }
    return sorted;
  }

  /**
   * Returns a line's key.
   *
   * @param line the line
   * @return the number at its start, or null
   */
  static BigDecimal key(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    int start = i;
    if (i < line.length() && (line.charAt(i) == '+' || line.charAt(i) == '-')) {
      i++;
    }
    int digits = 0;
    while (i < line.length() && isDigit(line.charAt(i))) {
      i++;
      digits++;
    }
    if (i < line.length() && line.charAt(i) == '.') {
      int fraction = i + 1;
      while (fraction < line.length() && isDigit(line.charAt(fraction))) {
        fraction++;
      }
      if (digits > 0 || fraction > i + 1) {
        digits += fraction - i - 1;
        i = fraction;
      }
    }
    return digits == 0 ? null : new BigDecimal(line.substring(start, i));
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
