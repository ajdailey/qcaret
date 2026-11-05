package io.github.ajdailey.qcaret.core.syntax;

import java.util.Arrays;

/**
 * One line's tokens as {@code start, end, type} triples, ascending and disjoint, with adjacent
 * tokens of one type merged. Plain text has no entry. Reused per line; not thread-safe.
 */
public final class TokenList {
  private int[] data = new int[48];
  private int size;

  /** Removes every token. */
  public void clear() {
    size = 0;
  }

  /**
   * Appends a token after the last one; an empty range or a null type adds nothing.
   *
   * @param start the first char
   * @param end the char after the last
   * @param type the type, or null for plain
   */
  public void add(int start, int end, TokenType type) {
    if (end <= start || type == null) {
      return;
    }
    if (size > 0 && data[size - 2] == start && data[size - 1] == type.ordinal()) {
      data[size - 2] = end;
      return;
    }
    if (size + 3 > data.length) {
      data = Arrays.copyOf(data, data.length * 2);
    }
    data[size++] = start;
    data[size++] = end;
    data[size++] = type.ordinal();
  }

  /**
   * Returns the token count.
   *
   * @return tokens
   */
  public int count() {
    return size / 3;
  }

  /**
   * Returns the tokens as triples, copied.
   *
   * @return {@code start, end, type ordinal} per token
   */
  public int[] toArray() {
    return Arrays.copyOf(data, size);
  }

  /**
   * Returns a token's type at a char.
   *
   * @param index a char index
   * @return the type, or null for plain
   */
  public TokenType typeAt(int index) {
    int lo = 0;
    int hi = size / 3;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (data[mid * 3 + 1] <= index) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo < size / 3 && data[lo * 3] <= index ? TokenType.of(data[lo * 3 + 2]) : null;
  }
}
