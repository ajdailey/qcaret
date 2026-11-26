package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.io.Encoders;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.Arrays;

/**
 * A byte pattern for {@link ByteSearch}: 1 to {@value #MAX_LENGTH} bytes, any of them a wildcard
 * that matches every byte. Immutable.
 */
public final class HexPattern {
  /** The longest pattern, in bytes. */
  public static final int MAX_LENGTH = 256;

  private final byte[] bytes;
  private final boolean[] wildcards;
  private final int anchor;

  private HexPattern(byte[] bytes, boolean[] wildcards) {
    this.bytes = bytes;
    this.wildcards = wildcards;
    int first = 0;
    while (wildcards[first]) {
      first++;
    }
    this.anchor = first;
  }

  /**
   * Parses hex byte pairs, such as {@code 4D 5A ?? 00} or {@code 4d5a??00}. Whitespace may separate
   * pairs but not split one; {@code ??} is a wildcard byte.
   *
   * @param text the pattern
   * @return the pattern
   * @throws HexPatternException when the text is empty, has an unpaired digit, a char other than a
   *     hex digit, {@code ?} or whitespace, only wildcards, or more than {@value #MAX_LENGTH} bytes
   */
  public static HexPattern hex(String text) throws HexPatternException {
    byte[] bytes = new byte[MAX_LENGTH];
    boolean[] wildcards = new boolean[MAX_LENGTH];
    int count = 0;
    int i = 0;
    int length = text.length();
    while (i < length) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (i + 1 >= length || Character.isWhitespace(text.charAt(i + 1))) {
        if (c == '?' || Character.digit(c, 16) >= 0) {
          throw new HexPatternException(HexPatternException.Reason.ODD_DIGITS, i);
        }
        throw new HexPatternException(HexPatternException.Reason.NOT_HEX, i);
      }
      char d = text.charAt(i + 1);
      if (count == MAX_LENGTH) {
        throw new HexPatternException(HexPatternException.Reason.TOO_LONG, i);
      }
      if (c == '?' && d == '?') {
        wildcards[count++] = true;
      } else {
        int high = Character.digit(c, 16);
        int low = Character.digit(d, 16);
        if (high < 0 || c == '?') {
          throw new HexPatternException(HexPatternException.Reason.NOT_HEX, i);
        }
        if (low < 0) {
          throw new HexPatternException(HexPatternException.Reason.NOT_HEX, i + 1);
        }
        bytes[count++] = (byte) (high << 4 | low);
      }
      i += 2;
    }
    if (count == 0) {
      throw new HexPatternException(HexPatternException.Reason.EMPTY, -1);
    }
    boolean literal = false;
    for (int k = 0; k < count; k++) {
      literal |= !wildcards[k];
    }
    if (!literal) {
      throw new HexPatternException(HexPatternException.Reason.WILDCARDS_ONLY, -1);
    }
    return new HexPattern(Arrays.copyOf(bytes, count), Arrays.copyOf(wildcards, count));
  }

  /**
   * Encodes text in a source's charset, without a BOM, as a pattern with no wildcards.
   *
   * @param text the text
   * @param charset the source's {@link HexSource#charset}
   * @return the pattern
   * @throws HexPatternException when the text is empty, has a char the charset cannot encode, or
   *     encodes to more than {@value #MAX_LENGTH} bytes
   */
  public static HexPattern text(String text, Charset charset) throws HexPatternException {
    if (text.isEmpty()) {
      throw new HexPatternException(HexPatternException.Reason.EMPTY, -1);
    }
    CharsetEncoder encoder = Encoders.reporting(charset);
    CharBuffer in = CharBuffer.wrap(text);
    ByteBuffer out = ByteBuffer.allocate(MAX_LENGTH);
    CoderResult result = encoder.encode(in, out, true);
    if (result.isUnderflow()) {
      result = encoder.flush(out);
    }
    if (result.isError()) {
      throw new HexPatternException(HexPatternException.Reason.UNMAPPABLE, in.position());
    }
    if (result.isOverflow()) {
      throw new HexPatternException(HexPatternException.Reason.TOO_LONG, in.position());
    }
    byte[] bytes = Arrays.copyOf(out.array(), out.position());
    return new HexPattern(bytes, new boolean[bytes.length]);
  }

  /**
   * Returns the byte count.
   *
   * @return 1 to {@value #MAX_LENGTH}
   */
  public int length() {
    return bytes.length;
  }

  /**
   * Returns a pattern byte.
   *
   * @param index the index
   * @return the byte; 0 for a wildcard
   */
  public byte byteAt(int index) {
    return bytes[index];
  }

  /**
   * Reports whether a pattern byte is a wildcard.
   *
   * @param index the index
   * @return true for {@code ??}
   */
  public boolean wildcard(int index) {
    return wildcards[index];
  }

  /**
   * Reports whether the pattern matches at a position.
   *
   * @param data the bytes
   * @param at the position of the pattern's first byte; {@code at + length()} is at most {@code
   *     data.length}
   * @return true when every byte that is not a wildcard is equal
   */
  public boolean matches(byte[] data, int at) {
    if (data[at + anchor] != bytes[anchor]) {
      return false;
    }
    for (int i = 0; i < bytes.length; i++) {
      if (!wildcards[i] && data[at + i] != bytes[i]) {
        return false;
      }
    }
    return true;
  }
}
