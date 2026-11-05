package io.github.ajdailey.qcaret.core.search;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.io.BinaryDetector;
import io.github.ajdailey.qcaret.core.io.ContentWriter;
import io.github.ajdailey.qcaret.core.io.ExternalModificationException;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Replaces search matches in one file on disk, for a directory or workspace replace. {@link
 * #replace} runs these steps on a worker; any skip leaves the file untouched.
 *
 * <ol>
 *   <li>{@link #load}: read the stamp, then the bytes. Skip a missing, unreadable or read-only
 *       file, one over the size limit, one whose SHA-256 differs from the bytes searched, a binary
 *       file, one that does not decode strictly in the encoding it was searched with, and one whose
 *       text does not encode back to the same bytes.
 *   <li>{@link #replacements}: each match is matched again on exactly its range; one that does not
 *       match is dropped. Line breaks in a replacement become the file's dominant line ending.
 *   <li>Encode the new text with {@code REPORT}: an unmappable char skips the file. The BOM is
 *       kept.
 *   <li>Record the original bytes in the run's {@link ReplaceBackups}, then write through {@link
 *       SafeSave} with the stamp read in step 1 as the baseline. A change between the read and the
 *       write's stamp check skips the file; a change after that check is overwritten.
 * </ol>
 *
 * <p>The file, its decoded text and the new bytes are held at once: {@value #BYTES_RESERVED} bytes
 * per file byte are reserved from the {@link MemoryBudget} while a file is replaced.
 */
public final class FileReplace {
  /** Bytes reserved per file byte while a file is replaced. */
  static final int BYTES_RESERVED = 6;

  private FileReplace() {}

  /** Why a file was left as it was. */
  public enum Skip {
    /** The file does not exist. */
    MISSING,
    /** The bytes differ from the bytes searched. */
    CHANGED,
    /** The file is not writable. */
    READ_ONLY,
    /** The file could not be read. */
    UNREADABLE,
    /** Larger than the size limit. */
    TOO_LARGE,
    /** Binary. */
    BINARY,
    /** The bytes do not decode in the encoding searched with. */
    MALFORMED,
    /** The text does not encode back to the same bytes. */
    NO_ROUND_TRIP,
    /** No match is left to replace. */
    NO_MATCHES,
    /** A replacement has a char the encoding cannot write. */
    UNMAPPABLE,
    /** The memory budget refused the read. */
    NO_MEMORY
  }

  /**
   * A file to replace in.
   *
   * @param file the file's real path
   * @param digest the SHA-256 of the bytes searched
   * @param encoding the encoding it was searched with
   * @param spans the matches to replace as ascending, disjoint offset pairs in the searched text
   */
  public record Target(Path file, byte[] digest, TextEncoding encoding, int[] spans) {}

  /**
   * A file read for a replace.
   *
   * @param text the decoded text, or null on a skip
   * @param bytes the file's bytes, or null on a skip before they were checked
   * @param stamp the stamp read before the bytes
   * @param skip why the file cannot be replaced in, or null
   */
  public record Loaded(String text, byte[] bytes, FileStamp stamp, Skip skip) {}

  /**
   * What a replace did.
   *
   * @param skip why the file was left as it was, or null
   * @param replaced matches replaced
   * @param dropped matches the query did not match again
   * @param error the failed write, or null
   */
  public record Outcome(Skip skip, int replaced, int dropped, IOException error) {
    /**
     * Reports whether the file was written.
     *
     * @return true when changed
     */
    public boolean changed() {
      return skip == null && error == null;
    }
  }

  /**
   * Reads a file and checks that it is the text searched; see the class steps.
   *
   * @param file the file
   * @param digest the SHA-256 of the bytes searched
   * @param encoding the encoding searched with
   * @param maxBytes the largest file read
   * @return the text, or why the file is skipped
   */
  @WorkerThread
  public static Loaded load(Path file, byte[] digest, TextEncoding encoding, long maxBytes) {
    FileStamp stamp;
    byte[] bytes;
    try {
      stamp = FileStamp.read(file);
      if (stamp == null) {
        return new Loaded(null, null, null, Skip.MISSING);
      }
      if (stamp.size() > maxBytes) {
        return new Loaded(null, null, stamp, Skip.TOO_LARGE);
      }
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      return new Loaded(null, null, null, Files.exists(file) ? Skip.UNREADABLE : Skip.MISSING);
    }
    if (!MessageDigest.isEqual(digest, FileSearch.digest(bytes))) {
      return new Loaded(null, bytes, stamp, Skip.CHANGED);
    }
    if (BinaryDetector.isBinary(bytes, bytes.length, encoding.unitWidth() > 1)) {
      return new Loaded(null, bytes, stamp, Skip.BINARY);
    }
    byte[] bom = encoding.bomBytes();
    if (bytes.length < bom.length || !Arrays.equals(bom, 0, bom.length, bytes, 0, bom.length)) {
      return new Loaded(null, bytes, stamp, Skip.MALFORMED);
    }
    String text;
    try {
      text =
          encoding
              .charset()
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, bom.length, bytes.length - bom.length))
              .toString();
    } catch (CharacterCodingException e) {
      return new Loaded(null, bytes, stamp, Skip.MALFORMED);
    }
    try {
      if (!Arrays.equals(encode(text, encoding), bytes)) {
        return new Loaded(null, bytes, stamp, Skip.NO_ROUND_TRIP);
      }
    } catch (CharacterCodingException e) {
      return new Loaded(null, bytes, stamp, Skip.NO_ROUND_TRIP);
    }
    return new Loaded(text, bytes, stamp, null);
  }

  /**
   * Computes the replacement of each span that the query still matches exactly. Line breaks in a
   * replacement become {@code ending}.
   *
   * @param text the text
   * @param spans ascending, disjoint offset pairs
   * @param matcher the query
   * @param template the replacement
   * @param ending the line ending of the text
   * @param token checked while matching
   * @return the replacements by offset; a span the query does not match again has none
   * @throws CancellationException when canceled
   * @throws PatternTooComplexException when the regex overflows the stack
   */
  @WorkerThread
  public static List<Replacement> replacements(
      CharSequence text,
      int[] spans,
      TextMatcher matcher,
      ReplaceTemplate template,
      LineEnding ending,
      CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>(spans.length / 2);
    for (int i = 0; i + 1 < spans.length; i += 2) {
      int start = spans[i];
      int end = spans[i + 1];
      if (end > text.length()) {
        continue;
      }
      String with = matcher.replacementAt(text, start, end, template, token);
      if (with != null) {
        out.add(new Replacement(start, end - start, convert(with, ending)));
      }
    }
    return out;
  }

  /**
   * Returns the text with replacements applied.
   *
   * @param text the text
   * @param replacements ascending, disjoint replacements
   * @return the new text
   */
  public static String apply(CharSequence text, List<Replacement> replacements) {
    StringBuilder out = new StringBuilder(text.length() + 16);
    int at = 0;
    for (Replacement r : replacements) {
      out.append(text, at, r.offset()).append(r.text());
      at = r.end();
    }
    return out.append(text, at, text.length()).toString();
  }

  /**
   * Reports whether every replacement encodes in a charset.
   *
   * @param replacements the replacements
   * @param encoding the encoding
   * @return false when a replacement has a char the charset cannot write
   */
  public static boolean encodable(List<Replacement> replacements, TextEncoding encoding) {
    CharsetEncoder encoder = encoding.charset().newEncoder();
    for (Replacement r : replacements) {
      if (!encoder.canEncode(r.text())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reports whether a text, encoded with its BOM, has the given SHA-256: an open document still
   * holds the bytes searched in its file.
   *
   * @param text the text
   * @param encoding the encoding
   * @param digest the SHA-256 of the bytes searched
   * @return false when the bytes differ or a char cannot be encoded
   */
  @WorkerThread
  public static boolean holds(CharSequence text, TextEncoding encoding, byte[] digest) {
    try {
      return MessageDigest.isEqual(digest, FileSearch.digest(encode(text, encoding)));
    } catch (CharacterCodingException e) {
      return false;
    }
  }

  /**
   * Replaces the matches of a file; see the class steps.
   *
   * @param target the file and its matches
   * @param matcher the query
   * @param template the replacement
   * @param backups the run's backups, which receive the original bytes
   * @param maxBytes the largest file read
   * @param budget charged while the file is replaced, or null
   * @param token checked before the write; a cancel leaves the file untouched
   * @return what happened
   * @throws CancellationException when canceled
   * @throws PatternTooComplexException when the regex overflows the stack
   */
  @WorkerThread
  public static Outcome replace(
      Target target,
      TextMatcher matcher,
      ReplaceTemplate template,
      ReplaceBackups backups,
      long maxBytes,
      MemoryBudget budget,
      CancellationToken token) {
    Path file = target.file();
    long reserved = 0;
    try {
      FileStamp first = FileStamp.read(file);
      if (first == null) {
        return skipped(Skip.MISSING);
      }
      if (!Files.isWritable(file)) {
        return skipped(Skip.READ_ONLY);
      }
      if (first.size() <= maxBytes && budget != null) {
        reserved = first.size() * BYTES_RESERVED;
        if (!budget.reserve(reserved)) {
          reserved = 0;
          return skipped(Skip.NO_MEMORY);
        }
      }
    } catch (IOException e) {
      return skipped(Skip.UNREADABLE);
    }
    try {
      return write(target, matcher, template, backups, maxBytes, token);
    } finally {
      if (reserved > 0) {
        budget.release(reserved);
      }
    }
  }

  @WorkerThread
  private static Outcome write(
      Target target,
      TextMatcher matcher,
      ReplaceTemplate template,
      ReplaceBackups backups,
      long maxBytes,
      CancellationToken token) {
    TextEncoding encoding = target.encoding();
    Loaded loaded = load(target.file(), target.digest(), encoding, maxBytes);
    if (loaded.skip() != null) {
      return skipped(loaded.skip());
    }
    String text = loaded.text();
    LineEnding ending = LineEndingStats.count(text, 0, text.length()).dominant(LineEnding.LF);
    List<Replacement> replacements =
        replacements(text, target.spans(), matcher, template, ending, token);
    int dropped = target.spans().length / 2 - replacements.size();
    if (replacements.isEmpty()) {
      return new Outcome(Skip.NO_MATCHES, 0, dropped, null);
    }
    final byte[] bytes;
    try {
      bytes = encode(apply(text, replacements), encoding);
    } catch (CharacterCodingException e) {
      return new Outcome(Skip.UNMAPPABLE, 0, dropped, null);
    }
    token.check();
    int entry;
    try {
      entry = backups.record(target.file(), loaded.bytes(), FileSearch.digest(bytes));
    } catch (IOException e) {
      return new Outcome(null, 0, dropped, e);
    }
    try {
      SafeSave.save(
          target.file(),
          loaded.stamp(),
          new ContentWriter() {
            @Override
            public void write(OutputStream out) throws IOException {
              out.write(bytes);
            }
          },
          token);
      return new Outcome(null, replacements.size(), dropped, null);
    } catch (ExternalModificationException e) {
      backups.dropQuietly(entry);
      return new Outcome(Skip.CHANGED, 0, dropped, null);
    } catch (IOException e) {
      backups.dropQuietly(entry);
      return new Outcome(null, 0, dropped, e);
    } catch (CancellationException e) {
      backups.dropQuietly(entry);
      throw e;
    }
  }

  private static Outcome skipped(Skip skip) {
    return new Outcome(skip, 0, 0, null);
  }

  /** Encodes a text with its BOM. */
  static byte[] encode(CharSequence text, TextEncoding encoding) throws CharacterCodingException {
    ByteBuffer body =
        encoding
            .charset()
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text));
    byte[] bom = encoding.bomBytes();
    byte[] out = new byte[bom.length + body.remaining()];
    System.arraycopy(bom, 0, out, 0, bom.length);
    body.get(out, bom.length, body.remaining());
    return out;
  }

  /** Converts every CR, LF and CR LF of a replacement to one line ending. */
  static String convert(String text, LineEnding ending) {
    if (text.indexOf('\r') < 0 && text.indexOf('\n') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length() + 8);
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n') {
        out.append(ending.text());
        i += c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }
}
