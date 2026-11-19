package io.github.ajdailey.qcaret.core.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The numbers Edit > Insert Number Sequence writes: {@code start}, {@code start + step}, and so on,
 * each zero-padded to {@code width} characters with any minus sign first, as {@code %0Nd} pads. Hex
 * is written with a sign and magnitude, never as two's complement.
 *
 * @param start the first number
 * @param step added for each next number; may be negative or zero
 * @param width the shortest number written, sign included, from 0 to {@link #MAX_WIDTH}
 * @param base how a number is written
 */
public record NumberSequence(long start, long step, int width, Base base) {
  /** Widest zero padding. */
  public static final int MAX_WIDTH = 64;

  /** Start 1, step 1, no padding, decimal. */
  public static final NumberSequence DEFAULTS = new NumberSequence(1, 1, 0, Base.DECIMAL);

  /** How a number is written. */
  public enum Base {
    /** Decimal digits. */
    DECIMAL("decimal"),
    /** Hex digits {@code 0-9A-F}. */
    HEX_UPPER("hexUpper"),
    /** Hex digits {@code 0-9a-f}. */
    HEX_LOWER("hexLower");

    private final String key;

    Base(String key) {
      this.key = key;
    }

    /**
     * Returns the value saved in the settings file.
     *
     * @return for example {@code hexUpper}
     */
    public String key() {
      return key;
    }

    /**
     * Returns the base a saved value names.
     *
     * @param key a {@link #key()}
     * @return the base, or null for an unknown value
     */
    public static Base parse(String key) {
      for (Base b : values()) {
        if (b.key.equals(key)) {
          return b;
        }
      }
      return null;
    }
  }

  /** Checks the components. */
  public NumberSequence {
    if (base == null) {
      throw new IllegalArgumentException("base is required");
    }
    if (width < 0 || width > MAX_WIDTH) {
      throw new IllegalArgumentException("width out of range");
    }
  }

  /**
   * Parses a start or step: an optional minus sign, then decimal digits or {@code 0x} and hex
   * digits; spaces around it are ignored.
   *
   * @param text the field's text
   * @return the value, or null when the text is not such a number or lies outside {@code long}
   */
  public static Long parse(String text) {
    String s = text.strip();
    boolean negative = s.startsWith("-");
    String digits = negative ? s.substring(1) : s;
    int radix = 10;
    if (digits.startsWith("0x") || digits.startsWith("0X")) {
      digits = digits.substring(2);
      radix = 16;
    }
    if (digits.isEmpty()) {
      return null;
    }
    for (int i = 0; i < digits.length(); i++) {
      char c = digits.charAt(i);
      if (c > 0x7f || Character.digit(c, radix) < 0) {
        return null;
      }
    }
    try {
      return Long.parseLong(negative ? "-" + digits : digits, radix);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Returns the first {@code count} numbers, written.
   *
   * @param count how many
   * @return the numbers in order
   * @throws ArithmeticException when a number lies outside {@code long}
   */
  public List<String> values(int count) {
    List<String> out = new ArrayList<String>(count);
    long value = start;
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        value = Math.addExact(value, step);
      }
      out.add(format(value));
    }
    return out;
  }

  /**
   * Writes one number in this base and width.
   *
   * @param value the number
   * @return the text
   */
  public String format(long value) {
    String digits = Long.toString(value, base == Base.DECIMAL ? 10 : 16);
    if (base == Base.HEX_UPPER) {
      digits = digits.toUpperCase(Locale.ROOT);
    }
    if (digits.length() >= width) {
      return digits;
    }
    boolean negative = value < 0;
    StringBuilder out = new StringBuilder(width);
    if (negative) {
      out.append('-');
    }
    out.append("0".repeat(width - digits.length()));
    out.append(digits, negative ? 1 : 0, digits.length());
    return out.toString();
  }
}
