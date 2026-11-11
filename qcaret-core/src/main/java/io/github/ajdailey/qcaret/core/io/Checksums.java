package io.github.ajdailey.qcaret.core.io;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/** Streams a file or an encoded text through a {@link MessageDigest}, as lower-case hex. */
public final class Checksums {
  /** Bytes read from a file at a time. */
  static final int BUFFER_BYTES = 64 * 1024;

  private Checksums() {}

  /**
   * Digests a file, one buffer of {@value #BUFFER_BYTES} bytes at a time.
   *
   * @param file the file; a symbolic link is followed
   * @param digest a fresh digest, reset by this call
   * @param token checked before each buffer
   * @param progress receives the bytes read so far after each buffer
   * @return the digest in lower-case hex
   * @throws IOException when the file cannot be opened or read
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  @WorkerThread
  public static String ofFile(
      Path file, MessageDigest digest, CancellationToken token, LongConsumer progress)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
    long read = 0;
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      while (true) {
        token.check();
        buffer.clear();
        int n = channel.read(buffer);
        if (n < 0) {
          break;
        }
        buffer.flip();
        digest.update(buffer);
        read += n;
        progress.accept(read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Encodes consecutive pieces of a text in a charset into a digest, without a byte order mark.
   * Chars pass through a buffer, so a surrogate pair split between two pieces encodes as one code
   * point. Use once; not thread-safe.
   */
  public static final class TextDigester {
    /** Chars encoded at a time. */
    static final int BUFFER_CHARS = 8192;

    private final MessageDigest digest;
    private final CharsetEncoder encoder;
    private final CharBuffer pending = CharBuffer.allocate(BUFFER_CHARS);
    private final ByteBuffer encoded = ByteBuffer.allocate(4 * BUFFER_CHARS);

    /**
     * Creates a digester.
     *
     * @param charset encodes the text; a char it cannot encode fails {@link #add} or {@link
     *     #finish}
     * @param digest a fresh digest
     */
    public TextDigester(Charset charset, MessageDigest digest) {
      this.digest = digest;
      encoder =
          charset
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /**
     * Adds the next piece.
     *
     * @param text holds the piece
     * @param start the piece's first index
     * @param end the index after its last char
     * @throws CharacterCodingException when a char cannot be encoded
     */
    public void add(CharSequence text, int start, int end) throws CharacterCodingException {
      for (int i = start; i < end; i++) {
        pending.put(text.charAt(i));
        if (!pending.hasRemaining()) {
          encode(false);
        }
      }
    }

    /**
     * Encodes what is left and returns the digest.
     *
     * @return the digest in lower-case hex
     * @throws CharacterCodingException when a char, such as a lone surrogate at the end, cannot be
     *     encoded
     */
    public String finish() throws CharacterCodingException {
      encode(true);
      while (encoder.flush(encoded).isOverflow()) {
        drain();
      }
      drain();
      return HexFormat.of().formatHex(digest.digest());
    }

    private void encode(boolean end) throws CharacterCodingException {
      pending.flip();
      while (true) {
        CoderResult result = encoder.encode(pending, encoded, end);
        if (result.isError()) {
          result.throwException();
        }
        if (!result.isOverflow()) {
          break;
        }
        drain();
      }
      pending.compact();
    }

    private void drain() {
      encoded.flip();
      digest.update(encoded);
      encoded.clear();
    }
  }
}
