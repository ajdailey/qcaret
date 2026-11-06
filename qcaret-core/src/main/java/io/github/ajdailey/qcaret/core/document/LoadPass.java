package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * One pass over a file in one encoding: each block is digested, decoded strictly into a {@link
 * TextSink} and the chars encoded back against the same bytes, so the text, the digest and the
 * round-trip result describe one read of the file. The decoder and encoder keep their state across
 * blocks; bytes of a sequence that a block splits stay in the buffer for the next one.
 *
 * <p>An auto-detecting decoder ({@code x-JISAutoDetect}) gives another result on split input, so it
 * reads the file as one block. A charset without an encoder never round-trips.
 */
final class LoadPass {
  /** Room in the read buffer for the undecoded end of a block. */
  private static final int SEQUENCE_BYTES = 64;

  /** Least room for encoded bytes, enough for any char sequence. */
  private static final int MIN_ENCODED_BYTES = 1024;

  private final FileChannel channel;
  private final long size;
  private final TextEncoding encoding;
  private final LoadReservation reservation;
  private final CancellationToken token;
  private final int blockBytes;
  private final CharBuffer chars;
  private final TextSink text;
  private final MessageDigest digest = Digests.sha256();
  private CharsetEncoder encoder;
  private ByteBuffer encoded;
  private ByteBuffer unmatched;
  private int kept;
  private boolean roundTrips;

  LoadPass(
      FileChannel channel,
      long size,
      TextEncoding encoding,
      LoadReservation reservation,
      CancellationToken token,
      int blockBytes,
      int blockChars,
      TextSink text) {
    this.channel = channel;
    this.size = size;
    this.encoding = encoding;
    this.reservation = reservation;
    this.token = token;
    this.text = text;
    this.blockBytes = (int) Math.max(1, Math.min(blockBytes, size));
    this.chars = CharBuffer.allocate((int) Math.max(8, Math.min(blockChars, size)));
  }

  /**
   * Reads the file from its start, at most the size given.
   *
   * @throws TextCodingException when the bytes do not decode
   * @throws IOException when the file cannot be read or the text does not fit
   * @throws java.util.concurrent.CancellationException when canceled
   */
  void run() throws IOException {
    CharsetDecoder decoder =
        encoding
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    int block = blockBytes;
    if (decoder.isAutoDetecting()) {
      block = (int) Math.max(1, size);
      reservation.add(size);
    }
    startEncoder(block);
    ByteBuffer bytes =
        ByteBuffer.allocate((int) Math.min(DocumentFiles.MAX_BYTES, (long) block + SEQUENCE_BYTES));
    int bom = encoding.bomBytes().length;
    long read = 0;
    long base = 0;
    boolean end = false;
    channel.position(0);
    while (!end) {
      token.check();
      int start = bytes.position();
      bytes.limit((int) Math.min(bytes.capacity(), start + Math.min(block, size - read)));
      int n = bytes.hasRemaining() ? channel.read(bytes) : -1;
      end = n < 0;
      if (n > 0) {
        read += n;
        digest.update(bytes.array(), start, n);
        end = read == size;
      }
      bytes.flip();
      int skip = Math.min(bom, bytes.remaining());
      bytes.position(skip);
      bom -= skip;
      offer(bytes.array(), Math.max(start, skip), bytes.limit());
      while (true) {
        token.check();
        CoderResult result = decoder.decode(bytes, chars, end);
        if (result.isError()) {
          throw failure(base + bytes.position());
        }
        drain();
        if (result.isUnderflow()) {
          break;
        }
      }
      base += bytes.position();
      bytes.compact();
      reservation.charge(text.heapBytes(), read);
    }
    while (true) {
      CoderResult result = decoder.flush(chars);
      if (result.isError()) {
        throw failure(base);
      }
      drain();
      if (result.isUnderflow()) {
        break;
      }
    }
    finishEncoder();
  }

  private TextCodingException failure(long offset) {
    return new TextCodingException("Not valid text", offset, encoding.charset().name());
  }

  /**
   * Returns the SHA-256 of the bytes read, BOM included; called once, after {@link #run}.
   *
   * @return 64 lower-case hex digits
   */
  String digest() {
    return Digests.hex(digest);
  }

  /**
   * Reports whether the text encodes back to the bytes after the BOM.
   *
   * @return the result
   */
  boolean roundTrips() {
    return roundTrips;
  }

  private void startEncoder(int block) {
    if (!encoding.charset().canEncode()) {
      return;
    }
    encoder =
        encoding
            .charset()
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    encoded = ByteBuffer.allocate(Math.max(block, MIN_ENCODED_BYTES));
    unmatched = ByteBuffer.allocate(2 * block + MIN_ENCODED_BYTES);
    unmatched.flip();
  }

  /** Stops the round-trip check: the text does not encode back. */
  private void mismatch() {
    encoder = null;
    encoded = null;
    unmatched = null;
  }

  /** Queues the file's bytes for the encoder's output to match. */
  private void offer(byte[] array, int from, int to) {
    if (encoder == null) {
      return;
    }
    unmatched.compact();
    if (unmatched.remaining() < to - from) {
      mismatch();
      return;
    }
    unmatched.put(array, from, to - from);
    unmatched.flip();
  }

  /** Moves the decoded chars to the text and the encoder; a char it holds back stays in front. */
  private void drain() throws IOException {
    chars.flip();
    text.append(chars.array(), kept, chars.limit());
    if (encoder == null) {
      chars.clear();
      kept = 0;
      return;
    }
    encode(false);
    chars.compact();
    kept = chars.position();
  }

  private void encode(boolean last) {
    while (encoder != null) {
      CoderResult result = encoder.encode(chars, encoded, last);
      if (result.isError()) {
        mismatch();
        return;
      }
      match(result.isOverflow());
      if (result.isUnderflow()) {
        return;
      }
    }
  }

  /** Compares the encoder's output with the file's bytes; output past them waits for a block. */
  private void match(boolean full) {
    if (encoder == null) {
      return;
    }
    encoded.flip();
    int n = Math.min(encoded.remaining(), unmatched.remaining());
    int a = encoded.position();
    int b = unmatched.position();
    if (!Arrays.equals(encoded.array(), a, a + n, unmatched.array(), b, b + n)) {
      mismatch();
      return;
    }
    encoded.position(a + n);
    unmatched.position(b + n);
    encoded.compact();
    if (full && !encoded.hasRemaining()) {
      mismatch();
    }
  }

  private void finishEncoder() {
    chars.flip();
    encode(true);
    while (encoder != null) {
      CoderResult result = encoder.flush(encoded);
      match(result.isOverflow());
      if (result.isUnderflow()) {
        break;
      }
    }
    roundTrips = encoder != null && encoded.position() == 0 && !unmatched.hasRemaining();
  }
}
