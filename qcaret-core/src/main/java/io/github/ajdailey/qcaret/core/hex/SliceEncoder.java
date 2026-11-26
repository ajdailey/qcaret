package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.io.Encoders;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Encodes a char range of a text on its own, for one thread. A char the charset cannot encode, or a
 * lone surrogate, becomes the encoder's replacement bytes: once per sequence, or once per char when
 * {@code perChar} is set. Exact for a range that splits no surrogate pair, because every {@code
 * TextEncoding.MENU} charset is stateless.
 */
final class SliceEncoder {
  /** Chars encoded per call into the encoder. */
  private static final int CHUNK = 8192;

  private final CharsetEncoder encoder;
  private final byte[] replacement;
  private final boolean perChar;
  private final char[] chars = new char[CHUNK + 1];
  private final ByteBuffer out;
  private int filled;

  /** Receives encoded bytes in order. */
  interface Sink {
    /**
     * Receives bytes.
     *
     * @param bytes holds the bytes; valid only during the call
     * @param offset the first byte
     * @param length the byte count
     * @param replaced true when the bytes are a replacement
     */
    void bytes(byte[] bytes, int offset, int length, boolean replaced);
  }

  SliceEncoder(Charset charset, boolean perChar) {
    this.encoder = Encoders.reporting(charset);
    this.replacement = encoder.replacement();
    this.perChar = perChar;
    this.out = ByteBuffer.allocate(4 * CHUNK + 16);
  }

  /**
   * Encodes {@code text[start, end)}.
   *
   * @param sink receives the bytes, or null to count only
   * @return the byte count
   */
  long encode(TextContent text, int start, int end, Sink sink) {
    long total = 0;
    int at = start;
    while (at < end) {
      int stop = Math.min(end, at + CHUNK);
      if (stop < end && splitsPair(text, stop)) {
        stop++;
      }
      copy(text, at, stop);
      total += encodeChars(stop - at, sink);
      at = stop;
    }
    return total;
  }

  /** Reports whether a boundary at {@code offset} falls between the halves of a surrogate pair. */
  static boolean splitsPair(CharSequence text, int offset) {
    return offset > 0
        && offset < text.length()
        && Character.isHighSurrogate(text.charAt(offset - 1))
        && Character.isLowSurrogate(text.charAt(offset));
  }

  private void copy(TextContent text, int start, int end) {
    filled = 0;
    text.readChunks(
        start,
        end,
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int from, int to) {
            if (chunk instanceof String s) {
              s.getChars(from, to, chars, filled);
              filled += to - from;
            } else {
              for (int i = from; i < to; i++) {
                chars[filled++] = chunk.charAt(i);
              }
            }
          }
        });
  }

  private long encodeChars(int count, Sink sink) {
    CharBuffer in = CharBuffer.wrap(chars, 0, count);
    long total = 0;
    while (true) {
      CoderResult result = encoder.encode(in, out, true);
      if (result.isUnderflow()) {
        break;
      }
      total += drain(sink);
      if (result.isError()) {
        int times = perChar ? result.length() : 1;
        for (int i = 0; i < times; i++) {
          if (sink != null) {
            sink.bytes(replacement, 0, replacement.length, true);
          }
          total += replacement.length;
        }
        in.position(in.position() + result.length());
      }
    }
    while (encoder.flush(out).isOverflow()) {
      total += drain(sink);
    }
    total += drain(sink);
    encoder.reset();
    return total;
  }

  private int drain(Sink sink) {
    int length = out.position();
    if (sink != null && length > 0) {
      sink.bytes(out.array(), 0, length, false);
    }
    out.clear();
    return length;
  }

  /** Collects one range's bytes and the positions of replacement bytes, reused across ranges. */
  static final class Collector implements Sink {
    private byte[] bytes = new byte[1 << 16];
    private final BitSet replaced = new BitSet();
    private int length;

    void reset() {
      length = 0;
      replaced.clear();
    }

    byte[] bytes() {
      return bytes;
    }

    BitSet replaced() {
      return replaced;
    }

    int length() {
      return length;
    }

    @Override
    public void bytes(byte[] source, int offset, int count, boolean replacement) {
      if (length + count > bytes.length) {
        bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + count));
      }
      System.arraycopy(source, offset, bytes, length, count);
      if (replacement) {
        replaced.set(length, length + count);
      }
      length += count;
    }
  }
}
