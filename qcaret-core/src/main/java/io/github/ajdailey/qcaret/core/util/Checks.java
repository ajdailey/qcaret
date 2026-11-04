package io.github.ajdailey.qcaret.core.util;

/** Argument checks that throw {@link IllegalArgumentException} naming the argument. */
public final class Checks {
  private Checks() {}

  /**
   * Requires a value of at least {@code min}.
   *
   * @param value the argument
   * @param min the least value accepted
   * @param name the argument name for the message
   * @throws IllegalArgumentException when {@code value < min}
   */
  public static void atLeast(long value, long min, String name) {
    if (value < min) {
      throw new IllegalArgumentException(name + " must be at least " + min);
    }
  }

  /**
   * Requires a value of at least 0.
   *
   * @param value the argument
   * @param name the argument name for the message
   * @throws IllegalArgumentException when {@code value < 0}
   */
  public static void notNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
