package io.github.ajdailey.qcaret.core.edit;

/**
 * The char pairs auto-close inserts and bracket matching pairs: brackets have distinct open and
 * close chars, quotes the same char twice. Immutable.
 */
public final class Pairs {
  /** Round, square and curly brackets, double and single quotes. */
  public static final Pairs DEFAULT = new Pairs("()[]{}\"\"''");

  private final String chars;

  /**
   * Creates the pairs.
   *
   * @param chars open and close char of each pair in turn, such as {@code "()[]\"\""}
   * @throws IllegalArgumentException when the length is odd
   */
  public Pairs(String chars) {
    if (chars.length() % 2 != 0) {
      throw new IllegalArgumentException("odd pair chars: " + chars);
    }
    this.chars = chars;
  }

  /**
   * Returns the close char of a pair that opens with a char.
   *
   * @param c a char
   * @return the close char, or 0 when no pair opens with {@code c}
   */
  public char closer(char c) {
    for (int i = 0; i < chars.length(); i += 2) {
      if (chars.charAt(i) == c) {
        return chars.charAt(i + 1);
      }
    }
    return 0;
  }

  /**
   * Reports whether a pair closes with a char.
   *
   * @param c a char
   * @return true for a close char, a quote included
   */
  public boolean isCloser(char c) {
    for (int i = 1; i < chars.length(); i += 2) {
      if (chars.charAt(i) == c) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether a char opens or closes a bracket pair, one whose two chars differ.
   *
   * @param c a char
   * @return true for a bracket
   */
  public boolean isBracket(char c) {
    return partner(c) != 0;
  }

  /**
   * Returns the other char of a bracket pair.
   *
   * @param c a bracket char
   * @return the partner, or 0 when {@code c} is no bracket
   */
  public char partner(char c) {
    for (int i = 0; i < chars.length(); i += 2) {
      char open = chars.charAt(i);
      char close = chars.charAt(i + 1);
      if (open != close) {
        if (open == c) {
          return close;
        }
        if (close == c) {
          return open;
        }
      }
    }
    return 0;
  }

  /**
   * Reports whether a bracket char opens its pair.
   *
   * @param c a char
   * @return true for an open bracket
   */
  public boolean opens(char c) {
    char close = closer(c);
    return close != 0 && close != c;
  }
}
