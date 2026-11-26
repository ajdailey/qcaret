package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.SplittableRandom;

/** Generated text, a whole-text encode to compare with, and in-memory sources. */
final class HexFixtures {
  /** Code points drawn for random text: ASCII, Latin-1, Cyrillic, CJK, kana, Hangul, astral. */
  private static final int[] POOL = {
    'a', 'Z', '0', ' ', '\n', '\r', 0x01, 0xe9, 0xfc, 0x20ac, 0x416, 0x44f, 0x4e2d, 0x3042, 0xd55c,
    0xfffd, 0x1f600, 0x20000
  };

  private HexFixtures() {}

  /**
   * Generates text with surrogate pairs straddling the first two block boundaries and occasional
   * lone surrogates.
   */
  static String text(int length, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    StringBuilder sb = new StringBuilder(length + 2);
    while (sb.length() < length) {
      int roll = random.nextInt(100);
      if (roll == 0) {
        sb.append((char) 0xd800);
      } else if (roll == 1) {
        sb.append((char) 0xdc00);
      } else {
        sb.appendCodePoint(POOL[random.nextInt(POOL.length)]);
      }
    }
    sb.setLength(length);
    for (int boundary = ByteOffsets.BLOCK_CHARS;
        boundary + 1 < length && boundary <= 2 * ByteOffsets.BLOCK_CHARS;
        boundary += ByteOffsets.BLOCK_CHARS) {
      sb.setCharAt(boundary - 1, (char) 0xd83d);
      sb.setCharAt(boundary, (char) 0xde00);
      if (boundary + 1 < length && Character.isLowSurrogate(sb.charAt(boundary + 1))) {
        sb.setCharAt(boundary + 1, 'x');
      }
    }
    if (Character.isHighSurrogate(sb.charAt(length - 1))) {
      sb.setCharAt(length - 1, 'y');
    }
    return sb.toString();
  }

  /**
   * Encodes a whole text with the JDK encoder in replace mode, BOM first. In a charset of one byte
   * per char each surrogate pair the charset cannot encode becomes two replacements, as {@link
   * ByteOffsets} documents, so the text is rewritten with two unencodable chars in its place.
   */
  static byte[] wholeEncode(String text, TextEncoding encoding) {
    String input = text;
    if (!ByteOffsets.indexed(encoding) && encoding.unitWidth() == 1) {
      StringBuilder sb = new StringBuilder(text);
      for (int i = 0; i + 1 < sb.length(); i++) {
        if (Character.isHighSurrogate(sb.charAt(i)) && Character.isLowSurrogate(sb.charAt(i + 1))) {
          sb.setCharAt(i, (char) 0xffff);
          sb.setCharAt(i + 1, (char) 0xffff);
        }
      }
      input = sb.toString();
    }
    try {
      ByteBuffer encoded =
          encoding
              .charset()
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE)
              .encode(CharBuffer.wrap(input));
      byte[] bom = encoding.bomBytes();
      byte[] all = new byte[bom.length + encoded.remaining()];
      System.arraycopy(bom, 0, all, 0, bom.length);
      encoded.get(all, bom.length, encoded.remaining());
      return all;
    } catch (CharacterCodingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A source over an array, counting reads. */
  static class ArraySource implements HexSource {
    final byte[] bytes;
    int reads;

    ArraySource(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public long size() {
      return bytes.length;
    }

    @Override
    public Charset charset() {
      return StandardCharsets.ISO_8859_1;
    }

    @Override
    public void read(long position, byte[] dst, int length, BitSet replaced) {
      Objects.checkFromIndexSize(position, length, size());
      reads++;
      System.arraycopy(bytes, (int) position, dst, 0, length);
      if (replaced != null) {
        replaced.clear(0, length);
      }
    }
  }

  /** A source of {@code 2^40} bytes, each a hash of its position. */
  static final class HugeSource implements HexSource {
    static final long SIZE = 1L << 40;

    static byte at(long position) {
      long z = (position + 1) * 0x9e3779b97f4a7c15L;
      z = (z ^ (z >>> 31)) * 0xbf58476d1ce4e5b9L;
      return (byte) (z ^ (z >>> 29));
    }

    @Override
    public long size() {
      return SIZE;
    }

    @Override
    public Charset charset() {
      return StandardCharsets.ISO_8859_1;
    }

    @Override
    public void read(long position, byte[] dst, int length, BitSet replaced) {
      Objects.checkFromIndexSize(position, length, SIZE);
      for (int i = 0; i < length; i++) {
        dst[i] = at(position + i);
      }
      if (replaced != null) {
        replaced.clear(0, length);
      }
    }
  }

  /** Returns {@code bytes[from, from + length)}. */
  static byte[] slice(byte[] bytes, int from, int length) {
    return Arrays.copyOfRange(bytes, from, from + length);
  }
}
