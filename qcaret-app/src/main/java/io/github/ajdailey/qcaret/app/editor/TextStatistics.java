package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.function.LongConsumer;

/**
 * Document Statistics' counts of a text. Immutable.
 *
 * <ul>
 *   <li>Lines: line breaks (LF, CR LF, CR) plus one.
 *   <li>Characters: code points, line breaks included; a surrogate pair is one, CR LF is two.
 *   <li>Characters without spaces: code points for which {@link Character#isWhitespace(int)} is
 *       false; a no-break space is not whitespace.
 *   <li>Words: maximal runs of such code points.
 *   <li>Bytes: the text encoded in a charset, a char it cannot encode and a lone surrogate counted
 *       as the charset's replacement bytes.
 * </ul>
 *
 * @param lines line breaks plus one
 * @param words runs of non-whitespace code points
 * @param chars code points
 * @param nonSpaceChars code points that are not whitespace
 * @param bytes encoded bytes
 */
public record TextStatistics(long lines, long words, long chars, long nonSpaceChars, long bytes) {
  /** Nothing counted. */
  public static final TextStatistics NONE = new TextStatistics(0, 0, 0, 0, 0);

  /**
   * Returns the sums of two counts.
   *
   * @param other the other counts
   * @return the sums
   */
  public TextStatistics plus(TextStatistics other) {
    return new TextStatistics(
        lines + other.lines,
        words + other.words,
        chars + other.chars,
        nonSpaceChars + other.nonSpaceChars,
        bytes + other.bytes);
  }

  /**
   * Returns a copy with more bytes, for a byte order mark.
   *
   * @param extra the bytes added
   * @return the copy
   */
  public TextStatistics withExtraBytes(long extra) {
    return new TextStatistics(lines, words, chars, nonSpaceChars, bytes + extra);
  }

  /**
   * Counts a range of a text, reading it chunk by chunk.
   *
   * @param text the text
   * @param start the first offset
   * @param end the offset after the last char
   * @param charset counts the bytes
   * @param token checked before each chunk
   * @param progress receives the chars read so far after each chunk
   * @return the counts
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  @WorkerThread
  public static TextStatistics count(
      TextContent text,
      int start,
      int end,
      Charset charset,
      final CancellationToken token,
      final LongConsumer progress) {
    final Counter counter = new Counter(charset);
    final long[] read = {0};
    token.check();
    text.readChunks(
        start,
        end,
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int from, int to) {
            token.check();
            counter.add(chunk, from, to);
            read[0] += to - from;
            progress.accept(read[0]);
          }
        });
    return counter.finish();
  }

  /**
   * Counts consecutive pieces of one text. Use once; not thread-safe.
   *
   * <p>Chars are encoded through a {@value #BUFFER_CHARS}-char buffer, so a surrogate pair split
   * between two pieces or two buffer fills encodes as one code point.
   */
  public static final class Counter {
    /** Chars encoded at a time. */
    static final int BUFFER_CHARS = 8192;

    private final CharsetEncoder encoder;
    private final CharBuffer pending = CharBuffer.allocate(BUFFER_CHARS);
    private final ByteBuffer encoded = ByteBuffer.allocate(4 * BUFFER_CHARS);
    private long breaks;
    private long words;
    private long chars;
    private long nonSpace;
    private long bytes;
    private char high;
    private boolean hasHigh;
    private boolean afterCr;
    private boolean inWord;

    /**
     * Creates a counter.
     *
     * @param charset counts the bytes
     */
    public Counter(Charset charset) {
      encoder =
          charset
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    /**
     * Counts the next piece.
     *
     * @param text holds the piece
     * @param start the piece's first index
     * @param end the index after its last char
     */
    public void add(CharSequence text, int start, int end) {
      for (int i = start; i < end; i++) {
        char c = text.charAt(i);
        scan(c);
        pending.put(c);
        if (!pending.hasRemaining()) {
          encode(false);
        }
      }
    }

    /**
     * Returns the counts of every piece added.
     *
     * @return the counts
     */
    public TextStatistics finish() {
      if (hasHigh) {
        codePoint(high);
        hasHigh = false;
      }
      encode(true);
      while (encoder.flush(encoded).isOverflow()) {
        drain();
      }
      drain();
      return new TextStatistics(breaks + 1, words, chars, nonSpace, bytes);
    }

    private void scan(char c) {
      if (hasHigh) {
        hasHigh = false;
        if (Character.isLowSurrogate(c)) {
          codePoint(Character.toCodePoint(high, c));
          return;
        }
        codePoint(high);
      }
      if (c == '\n' && !afterCr || c == '\r') {
        breaks++;
      }
      afterCr = c == '\r';
      if (Character.isHighSurrogate(c)) {
        high = c;
        hasHigh = true;
        return;
      }
      codePoint(c);
    }

    private void codePoint(int cp) {
      chars++;
      if (Character.isWhitespace(cp)) {
        inWord = false;
        return;
      }
      nonSpace++;
      if (!inWord) {
        words++;
        inWord = true;
      }
    }

    private void encode(boolean end) {
      pending.flip();
      while (encoder.encode(pending, encoded, end).isOverflow()) {
        drain();
      }
      pending.compact();
    }

    private void drain() {
      bytes += encoded.position();
      encoded.clear();
    }
  }
}
