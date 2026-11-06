package io.github.ajdailey.qcaret.app.recovery;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import io.github.ajdailey.qcaret.core.io.ContentWriter;
import io.github.ajdailey.qcaret.core.io.Digests;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * One buffer's recovery file, format version 1: the line {@code QCARET-RECOVERY 1}, {@code
 * key=value} header lines in UTF-8 with backslash escapes, a blank line, the body, a line break and
 * {@code END <SHA-256 of the body in hex>}. The body is UTF-8 unless the text holds an unpaired
 * surrogate, which UTF-8 cannot carry: then UTF-16BE, written and read one char at a time because
 * the JDK's coder replaces such a char.
 *
 * <p>The text streams through the file in chunks, so no copy of it exists as one array while
 * writing. Every method blocks and runs on a worker.
 */
public final class RecoveryFile {
  /** The file name suffix. */
  public static final String SUFFIX = ".qcr";

  /** The body encoding of a text without unpaired surrogates. */
  public static final String UTF_8 = "UTF-8";

  /** The body encoding of a text with an unpaired surrogate. */
  public static final String UTF_16BE = "UTF-16BE";

  private static final String MAGIC = "QCARET-RECOVERY 1";
  private static final String END = "END ";
  private static final int DIGEST_CHARS = 64;

  /** Bytes after the body: the line break, {@code END }, the digest and a line break. */
  private static final int TRAILER_BYTES = 1 + END.length() + DIGEST_CHARS + 1;

  private static final int MAX_HEADER_BYTES = 4 << 20;
  private static final int BLOCK_BYTES = 1 << 16;

  /** The file is not a complete recovery file, or its trailer digest does not match the body. */
  public static final class DamagedException extends IOException {
    private static final long serialVersionUID = 1L;

    DamagedException(String message) {
      super(message);
    }
  }

  /**
   * A file's header.
   *
   * @param id the buffer id, a plain file name
   * @param path the file's absolute path, empty for an untitled buffer
   * @param title the untitled title
   * @param created ISO-8601 time of the buffer's first write
   * @param written ISO-8601 time of this write
   * @param revision the document revision
   * @param encoding the charset of the original file
   * @param bom true when the original file has a byte order mark
   * @param lineEnding {@code LF}, {@code CRLF} or {@code CR}
   * @param baseSize the original file's size, -1 for none
   * @param baseModified ISO-8601 modification time of the original file, empty for none
   * @param baseSha256 SHA-256 of the original file in hex, empty for none
   * @param language the grammar the user chose, empty for detection
   * @param selection {@code anchor,caret} pairs, {@code ;}-separated, the primary first
   * @param bodyEncoding {@link #UTF_8} or {@link #UTF_16BE}; set by {@link #write}
   */
  public record Header(
      String id,
      String path,
      String title,
      String created,
      String written,
      long revision,
      String encoding,
      boolean bom,
      String lineEnding,
      long baseSize,
      String baseModified,
      String baseSha256,
      String language,
      String selection,
      String bodyEncoding) {
    Header withBodyEncoding(String value) {
      return new Header(
          id,
          path,
          title,
          created,
          written,
          revision,
          encoding,
          bom,
          lineEnding,
          baseSize,
          baseModified,
          baseSha256,
          language,
          selection,
          value);
    }
  }

  /**
   * A read file.
   *
   * @param header the header
   * @param text the buffer's text
   */
  public record Loaded(Header header, StringBuilder text) {}

  private RecoveryFile() {}

  /**
   * Writes a file through a temporary file and an atomic move.
   *
   * @param file the target
   * @param header the header; {@code bodyEncoding} is ignored
   * @param text the buffer's text
   * @throws IOException when the write fails; the target is then unchanged
   */
  public static void write(Path file, Header header, final TextSnapshot text) throws IOException {
    final boolean wide = hasUnpairedSurrogate(text);
    final Header complete = header.withBodyEncoding(wide ? UTF_16BE : UTF_8);
    AtomicFiles.writeOwnerOnly(
        file,
        new ContentWriter() {
          @Override
          public void write(OutputStream out) throws IOException {
            out.write(headerBytes(complete));
            MessageDigest sha = Digests.sha256();
            DigestOutputStream digesting = new DigestOutputStream(out, sha);
            try {
              writeBody(text, wide, digesting);
            } catch (UncheckedIOException e) {
              throw e.getCause();
            }
            digesting.flush();
            String trailer = "\n" + END + Digests.hex(sha) + "\n";
            out.write(trailer.getBytes(StandardCharsets.US_ASCII));
          }
        });
  }

  /** Reports whether the text holds a surrogate without its partner; pairs may span two chunks. */
  static boolean hasUnpairedSurrogate(TextSnapshot text) {
    final boolean[] found = new boolean[1];
    final boolean[] high = new boolean[1];
    text.readChunks(
        0,
        text.length(),
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int start, int end) {
            for (int i = start; i < end; i++) {
              char c = chunk.charAt(i);
              if (high[0] != Character.isLowSurrogate(c)) {
                found[0] = true;
              }
              high[0] = Character.isHighSurrogate(c);
            }
          }
        });
    return found[0] || high[0];
  }

  private static void writeBody(TextSnapshot text, boolean wide, final OutputStream out)
      throws IOException {
    if (wide) {
      final byte[] block = new byte[BLOCK_BYTES];
      text.readChunks(
          0,
          text.length(),
          new ChunkVisitor() {
            @Override
            public void chunk(CharSequence chunk, int start, int end) {
              try {
                int n = 0;
                for (int i = start; i < end; i++) {
                  char c = chunk.charAt(i);
                  block[n++] = (byte) (c >>> 8);
                  block[n++] = (byte) c;
                  if (n == block.length) {
                    out.write(block, 0, n);
                    n = 0;
                  }
                }
                out.write(block, 0, n);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }
          });
      return;
    }
    // The writer holds a high surrogate across calls, so a pair may span two chunks.
    final Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    text.readChunks(
        0,
        text.length(),
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int start, int end) {
            try {
              writer.append(chunk, start, end);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }
        });
    writer.flush();
  }

  private static byte[] headerBytes(Header h) {
    StringBuilder text = new StringBuilder(MAGIC).append('\n');
    line(text, "id", h.id());
    line(text, "path", h.path());
    line(text, "title", h.title());
    line(text, "created", h.created());
    line(text, "written", h.written());
    line(text, "revision", Long.toString(h.revision()));
    line(text, "encoding", h.encoding());
    line(text, "bom", Boolean.toString(h.bom()));
    line(text, "lineEnding", h.lineEnding());
    line(text, "base.size", Long.toString(h.baseSize()));
    line(text, "base.modified", h.baseModified());
    line(text, "base.sha256", h.baseSha256());
    line(text, "language", h.language());
    line(text, "selection", h.selection());
    line(text, "bodyEncoding", h.bodyEncoding());
    return text.append('\n').toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void line(StringBuilder text, String key, String value) {
    text.append(key).append('=');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> text.append("\\\\");
        case '\n' -> text.append("\\n");
        case '\r' -> text.append("\\r");
        default -> text.append(c);
      }
    }
    text.append('\n');
  }

  private static String unescape(String value) {
    StringBuilder text = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length()) {
        char next = value.charAt(++i);
        text.append(next == 'n' ? '\n' : next == 'r' ? '\r' : next);
      } else {
        text.append(c);
      }
    }
    return text.toString();
  }

  /**
   * Reads a file's header and checks the body against the trailer digest.
   *
   * @param file the file
   * @return the header
   * @throws DamagedException when the file is not complete or the digest does not match
   * @throws IOException when the file cannot be read
   */
  public static Header check(Path file) throws IOException {
    return read(file, null);
  }

  /**
   * Reads a file's header without reading the body.
   *
   * @param file the file
   * @return the header
   * @throws DamagedException when the header does not parse
   * @throws IOException when the file cannot be read
   */
  public static Header header(Path file) throws IOException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      return parseHeader(in, new long[1]);
    }
  }

  /**
   * Reads a file.
   *
   * @param file the file
   * @return the header and the text
   * @throws DamagedException when the file is not complete or the digest does not match
   * @throws IOException when the file cannot be read
   */
  public static Loaded load(Path file) throws IOException {
    StringBuilder text = new StringBuilder();
    Header header = read(file, text);
    return new Loaded(header, text);
  }

  private static Header read(Path file, StringBuilder text) throws IOException {
    long size = Files.size(file);
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      long[] consumed = new long[1];
      Header header = parseHeader(in, consumed);
      long body = size - consumed[0] - TRAILER_BYTES;
      if (body < 0) {
        throw new DamagedException("Truncated");
      }
      MessageDigest sha = Digests.sha256();
      if (UTF_16BE.equals(header.bodyEncoding())) {
        readWide(in, body, sha, text);
      } else {
        readUtf8(in, body, sha, text);
      }
      String trailer = new String(in.readNBytes(TRAILER_BYTES), StandardCharsets.US_ASCII);
      if (!trailer.equals("\n" + END + Digests.hex(sha) + "\n")) {
        throw new DamagedException("Digest mismatch");
      }
      return header;
    }
  }

  private static Header parseHeader(InputStream in, long[] consumed) throws IOException {
    String first = readLine(in, consumed);
    if (!MAGIC.equals(first)) {
      throw new DamagedException("Not a recovery file");
    }
    Map<String, String> keys = new HashMap<String, String>();
    while (true) {
      String line = readLine(in, consumed);
      if (line == null) {
        throw new DamagedException("Truncated");
      }
      if (line.isEmpty()) {
        break;
      }
      int equals = line.indexOf('=');
      if (equals <= 0) {
        throw new DamagedException("Malformed header");
      }
      keys.put(line.substring(0, equals), unescape(line.substring(equals + 1)));
    }
    String id = keys.get("id");
    String bodyEncoding = keys.get("bodyEncoding");
    if (id == null || !(UTF_8.equals(bodyEncoding) || UTF_16BE.equals(bodyEncoding))) {
      throw new DamagedException("Malformed header");
    }
    try {
      return new Header(
          id,
          keys.getOrDefault("path", ""),
          keys.getOrDefault("title", ""),
          keys.getOrDefault("created", ""),
          keys.getOrDefault("written", ""),
          Long.parseLong(keys.getOrDefault("revision", "0")),
          keys.getOrDefault("encoding", UTF_8),
          "true".equals(keys.get("bom")),
          keys.getOrDefault("lineEnding", "LF"),
          Long.parseLong(keys.getOrDefault("base.size", "-1")),
          keys.getOrDefault("base.modified", ""),
          keys.getOrDefault("base.sha256", ""),
          keys.getOrDefault("language", ""),
          keys.getOrDefault("selection", ""),
          bodyEncoding);
    } catch (NumberFormatException e) {
      throw new DamagedException("Malformed header");
    }
  }

  /** Reads up to a line feed; null at the end of the stream or past the header's limit. */
  private static String readLine(InputStream in, long[] consumed) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    while (consumed[0] < MAX_HEADER_BYTES) {
      int b = in.read();
      if (b < 0) {
        return null;
      }
      consumed[0]++;
      if (b == '\n') {
        return line.toString(StandardCharsets.UTF_8);
      }
      line.write(b);
    }
    return null;
  }

  private static void readUtf8(InputStream in, long length, MessageDigest sha, StringBuilder text)
      throws IOException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer bytes = ByteBuffer.allocate(BLOCK_BYTES);
    CharBuffer chars = CharBuffer.allocate(BLOCK_BYTES);
    long left = length;
    while (left > 0) {
      int want = (int) Math.min(left, bytes.remaining());
      int read = in.readNBytes(bytes.array(), bytes.position(), want);
      if (read < want) {
        throw new DamagedException("Truncated");
      }
      sha.update(bytes.array(), bytes.position(), read);
      left -= read;
      bytes.position(bytes.position() + read);
      bytes.flip();
      decode(decoder, bytes, chars, left == 0, text);
      bytes.compact();
    }
    if (length == 0) {
      bytes.flip();
      decode(decoder, bytes, chars, true, text);
    }
    if (decoder.flush(chars).isError()) {
      throw new DamagedException("Not UTF-8");
    }
    drain(chars, text);
  }

  private static void decode(
      CharsetDecoder decoder, ByteBuffer bytes, CharBuffer chars, boolean last, StringBuilder text)
      throws DamagedException {
    while (true) {
      CoderResult result = decoder.decode(bytes, chars, last);
      if (result.isError()) {
        throw new DamagedException("Not UTF-8");
      }
      drain(chars, text);
      if (result.isUnderflow()) {
        return;
      }
    }
  }

  private static void drain(CharBuffer chars, StringBuilder text) {
    chars.flip();
    if (text != null) {
      text.append(chars);
    }
    chars.clear();
  }

  private static void readWide(InputStream in, long length, MessageDigest sha, StringBuilder text)
      throws IOException {
    if (length % 2 != 0) {
      throw new DamagedException("Odd body length");
    }
    byte[] block = new byte[BLOCK_BYTES];
    long left = length;
    while (left > 0) {
      int want = (int) Math.min(left, block.length);
      if (in.readNBytes(block, 0, want) < want) {
        throw new DamagedException("Truncated");
      }
      sha.update(block, 0, want);
      left -= want;
      if (text != null) {
        for (int i = 0; i < want; i += 2) {
          text.append((char) ((block[i] & 0xff) << 8 | block[i + 1] & 0xff));
        }
      }
    }
  }
}
