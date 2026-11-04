package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.TextCursor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.BinaryDetector;
import io.github.ajdailey.qcaret.core.io.ContentWriter;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import io.github.ajdailey.qcaret.core.text.EncodingDetection;
import io.github.ajdailey.qcaret.core.text.EncodingDetector;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Loads a file into a {@link Document} and saves a {@link DocumentSnapshot} through {@link
 * SafeSave}. Both run on a worker, check their {@link CancellationToken} per block, and decode or
 * encode strictly: a malformed or unmappable sequence fails them instead of being replaced.
 */
public final class DocumentFiles {
  /** Largest file loaded, in bytes. */
  public static final long MAX_BYTES = Integer.MAX_VALUE - 8;

  private static final int BLOCK_BYTES = 1 << 20;
  private static final int BLOCK_CHARS = 1 << 16;

  private DocumentFiles() {}

  /**
   * Loads a file: reads it whole, chooses the encoding ({@link EncodingDetector}, or the forced
   * charset), rejects a binary file unless allowed, decodes it and checks that the text encodes
   * back to the same bytes.
   *
   * @param file the file, validated by the caller
   * @param options the forced charset, fallback and undo bounds
   * @param token canceled by a close of the document or the window
   * @return the document and what the load found
   * @throws BinaryFileException for a binary file when {@code options.allowBinary()} is false
   * @throws TextCodingException when the bytes do not decode in the chosen charset
   * @throws IOException when the file cannot be read or exceeds {@link #MAX_BYTES}
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static LoadedFile load(Path file, LoadOptions options, CancellationToken token)
      throws IOException {
    FileStamp stamp = FileStamp.read(file);
    byte[] bytes = read(file, token);
    EncodingDetection detection =
        options.forced() != null
            ? EncodingDetector.forced(options.forced(), bytes, bytes.length)
            : EncodingDetector.detect(bytes, bytes.length, options.fallback());
    TextEncoding encoding = detection.encoding();
    boolean binary = BinaryDetector.isBinary(bytes, bytes.length, encoding.unitWidth() > 1);
    if (binary && !options.allowBinary()) {
      throw new BinaryFileException(String.valueOf(file.getFileName()));
    }
    int bom = encoding.bomBytes().length;
    StringBuilder text = decode(bytes, bom, encoding, token);
    boolean roundTrips = encodesTo(text, encoding, bytes, bom, token);
    Document document = Document.of(text, encoding, options.empty(), options.limits());
    return new LoadedFile(document, stamp, detection, binary, roundTrips);
  }

  /**
   * Saves a snapshot: checks that every char encodes, then writes the BOM and the text through
   * {@link SafeSave}. The line breaks are written as the text holds them.
   *
   * @param file the target as the user named it
   * @param snapshot the version to write and its encoding
   * @param baseline the stamp from the load or the last save, or null for a new file
   * @param token canceled by a close of the document or Esc; a cancel before the target is replaced
   *     leaves it unchanged
   * @return the saved file's stamp
   * @throws TextCodingException when a char cannot be encoded; no file is touched
   * @throws io.github.ajdailey.qcaret.core.io.ExternalModificationException when the target changed
   *     since {@code baseline}
   * @throws IOException when the write fails
   */
  @WorkerThread
  public static FileStamp save(
      Path file, final DocumentSnapshot snapshot, FileStamp baseline, final CancellationToken token)
      throws IOException {
    final TextEncoding encoding = snapshot.encoding();
    encode(snapshot.text(), encoding, null, token);
    return SafeSave.save(
        file,
        baseline,
        new ContentWriter() {
          @Override
          public void write(OutputStream out) throws IOException {
            out.write(encoding.bomBytes());
            encode(snapshot.text(), encoding, out, token);
          }
        },
        token);
  }

  private static byte[] read(Path file, CancellationToken token) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size > MAX_BYTES) {
        throw new IOException("File too large: " + size + " bytes");
      }
      ByteBuffer buffer = ByteBuffer.allocate((int) size);
      while (buffer.hasRemaining()) {
        token.check();
        int limit = Math.min(buffer.capacity(), buffer.position() + BLOCK_BYTES);
        buffer.limit(limit);
        if (channel.read(buffer) < 0) {
          break;
        }
      }
      if (buffer.position() < buffer.capacity()) {
        byte[] shorter = new byte[buffer.position()];
        System.arraycopy(buffer.array(), 0, shorter, 0, shorter.length);
        return shorter;
      }
      return buffer.array();
    }
  }

  private static StringBuilder decode(
      byte[] bytes, int from, TextEncoding encoding, CancellationToken token)
      throws TextCodingException {
    CharsetDecoder decoder =
        encoding
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = ByteBuffer.wrap(bytes, from, bytes.length - from);
    CharBuffer out = CharBuffer.allocate(BLOCK_CHARS);
    StringBuilder text = new StringBuilder((int) Math.min(MAX_BYTES, (long) bytes.length));
    boolean flushing = false;
    while (true) {
      token.check();
      CoderResult result = flushing ? decoder.flush(out) : decoder.decode(in, out, true);
      if (result.isError()) {
        throw new TextCodingException("Not valid text", in.position(), encoding.charset().name());
      }
      out.flip();
      text.append(out);
      out.clear();
      if (result.isUnderflow()) {
        if (flushing) {
          return text;
        }
        flushing = true;
      }
    }
  }

  /** Reports whether {@code text} encodes to {@code bytes[from, length)}. */
  private static boolean encodesTo(
      CharSequence text, TextEncoding encoding, byte[] bytes, int from, CancellationToken token) {
    CharsetEncoder encoder =
        encoding
            .charset()
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    CharBuffer in = CharBuffer.wrap(text);
    ByteBuffer out = ByteBuffer.allocate(BLOCK_BYTES);
    int at = from;
    boolean flushing = false;
    while (true) {
      token.check();
      CoderResult result = flushing ? encoder.flush(out) : encoder.encode(in, out, true);
      if (result.isError()) {
        return false;
      }
      out.flip();
      int n = out.remaining();
      if (at + n > bytes.length || ByteBuffer.wrap(bytes, at, n).compareTo(out) != 0) {
        return false;
      }
      at += n;
      out.clear();
      if (result.isUnderflow()) {
        if (flushing) {
          return at == bytes.length;
        }
        flushing = true;
      }
    }
  }

  /**
   * Encodes a text block by block, to {@code out} or, when it is null, only to check every char.
   */
  private static void encode(
      TextSnapshot text, TextEncoding encoding, OutputStream out, CancellationToken token)
      throws IOException {
    CharsetEncoder encoder =
        encoding
            .charset()
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    TextCursor cursor = text.cursor(0);
    CharBuffer in = CharBuffer.allocate(BLOCK_CHARS);
    ByteBuffer bytes = ByteBuffer.allocate(BLOCK_CHARS * 4);
    long consumed = 0;
    boolean end = false;
    while (true) {
      token.check();
      while (in.hasRemaining() && cursor.hasNext()) {
        in.put(cursor.next());
      }
      end = !cursor.hasNext();
      in.flip();
      int before = in.position();
      CoderResult result = encoder.encode(in, bytes, end);
      if (result.isError()) {
        throw new TextCodingException(
            "Cannot encode", consumed + in.position(), encoding.charset().name());
      }
      consumed += in.position() - before;
      in.compact();
      drain(bytes, out);
      if (end && result.isUnderflow()) {
        break;
      }
    }
    while (encoder.flush(bytes).isOverflow()) {
      drain(bytes, out);
    }
    drain(bytes, out);
  }

  private static void drain(ByteBuffer bytes, OutputStream out) throws IOException {
    bytes.flip();
    if (out != null) {
      out.write(bytes.array(), 0, bytes.limit());
    }
    bytes.clear();
  }
}
