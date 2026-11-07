package io.github.ajdailey.qcaret.core.search;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.BinaryDetector;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.io.HiddenFiles;
import io.github.ajdailey.qcaret.core.text.EncodingDetection;
import io.github.ajdailey.qcaret.core.text.EncodingDetector;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Searches the files under a directory. The calling thread (a {@code qcaret-search-} worker) walks
 * the tree without following symbolic links; each accepted file is read and decoded on a {@code
 * qcaret-io-} thread, at most {@value #READERS} at a time, and matched by {@link TextSearch} on the
 * CPU pool, which digests a file only when the {@link Sink} receives it. Results reach the {@link
 * Sink} on those threads, in no fixed order.
 *
 * <ul>
 *   <li>A file over {@code maxFileBytes} is skipped as {@link Skip#TOO_LARGE}; a file {@link
 *       BinaryDetector} calls binary as {@link Skip#BINARY}; a file or directory that cannot be
 *       read as {@link Skip#UNREADABLE}. A read reserves five bytes per file byte from the {@link
 *       MemoryBudget} until its matching ends; a refusal skips the file as {@link Skip#NO_MEMORY}.
 *   <li>The encoding comes from {@link EncodingDetector}; a malformed sequence decodes to U+FFFD so
 *       the rest of the file is still searched.
 *   <li>Cancel stops the walk; reads in flight end at their next {@value #BLOCK_BYTES}-byte block
 *       and matches at their next check, and {@link #run} returns once they have.
 * </ul>
 */
public final class FileSearch {
  /** Files read and matched at once. */
  public static final int READERS = 4;

  /** Bytes reserved per file byte while it is read, decoded and matched. */
  static final int BYTES_RESERVED = 5;

  private FileSearch() {}

  /** Why a file was not searched. */
  public enum Skip {
    /** Larger than {@code maxFileBytes}. */
    TOO_LARGE,
    /** Binary. */
    BINARY,
    /** The file or its directory could not be read. */
    UNREADABLE,
    /** The memory budget refused the read. */
    NO_MEMORY
  }

  /**
   * How files are matched.
   *
   * @param matcher the query
   * @param filter the masks
   * @param fallback the charset detection falls back to
   * @param maxFileBytes the largest file read
   * @param maxLineChars the longest line searched
   * @param budget charged while a file is read and matched
   */
  public record Options(
      TextMatcher matcher,
      FileFilter filter,
      Charset fallback,
      long maxFileBytes,
      int maxLineChars,
      MemoryBudget budget) {}

  /**
   * One searched file.
   *
   * @param path the file
   * @param encoding the encoding it was decoded with
   * @param stamp its stamp, read before its bytes
   * @param digest the SHA-256 of the bytes searched; null when the outcome has no hits, skipped
   *     lines or regex failure
   * @param outcome its matches and skipped lines
   */
  public record FileHits(
      Path path,
      TextEncoding encoding,
      FileStamp stamp,
      byte[] digest,
      TextSearch.Outcome outcome) {}

  /** Receives the results; called on worker threads, concurrently. */
  public interface Sink {
    /**
     * Returns how many more matches the search may report.
     *
     * @return 0 stops matching further files
     */
    int room();

    /**
     * A file was searched and has matches, skipped lines or a regex failure.
     *
     * @param hits what it gave
     */
    void found(FileHits hits);

    /**
     * A file was not searched.
     *
     * @param file the file or directory
     * @param reason why
     */
    void skipped(Path file, Skip reason);

    /**
     * Files were searched.
     *
     * @param searched files searched or skipped so far
     * @param total files the walk accepted, or -1 while it runs
     */
    void progress(int searched, int total);
  }

  /**
   * Walks a directory and searches its files; returns when every file is done or the search is
   * canceled.
   *
   * @param workers runs the reads and matches
   * @param root the directory's real path; a link at the root is not walked
   * @param recursive false to search only the files directly in {@code root}
   * @param searchedElsewhere real paths not read, such as open documents searched from their text
   * @param options the query and limits
   * @param sink receives the results
   * @param token cancels the search
   */
  @WorkerThread
  public static void run(
      final Workers workers,
      final Path root,
      boolean recursive,
      final Set<Path> searchedElsewhere,
      final Options options,
      final Sink sink,
      final CancellationToken token) {
    final Semaphore readers = new Semaphore(READERS);
    final AtomicInteger accepted = new AtomicInteger();
    final AtomicInteger done = new AtomicInteger();
    try {
      Files.walkFileTree(
          root,
          EnumSet.noneOf(FileVisitOption.class),
          recursive ? Integer.MAX_VALUE : 1,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              token.check();
              if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
              }
              Path name = dir.getFileName();
              if (name == null
                  || !options.filter().acceptsDirectory(name)
                  || (!options.filter().hidden() && HiddenFiles.hidden(dir))) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              token.check();
              Path name = file.getFileName();
              if (!attrs.isRegularFile()
                  || name == null
                  || !options.filter().acceptsFile(name)
                  || (!options.filter().hidden() && HiddenFiles.hidden(file))
                  || searchedElsewhere.contains(file)) {
                return FileVisitResult.CONTINUE;
              }
              if (sink.room() <= 0) {
                return FileVisitResult.TERMINATE;
              }
              accepted.incrementAndGet();
              submit(workers, file, attrs.size(), options, sink, token, readers, done);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
              token.check();
              sink.skipped(file, Skip.UNREADABLE);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      sink.skipped(root, Skip.UNREADABLE);
    } catch (CancellationException e) {
      // The walk stops; the reads in flight end below.
    } catch (DirectoryIteratorException e) {
      sink.skipped(root, Skip.UNREADABLE);
    }
    sink.progress(done.get(), accepted.get());
    acquireAll(readers);
    sink.progress(done.get(), accepted.get());
  }

  /** Waits until no read or match is in flight; uninterruptible, since each ends on its own. */
  private static void acquireAll(Semaphore readers) {
    boolean interrupted = false;
    while (true) {
      try {
        if (readers.tryAcquire(READERS, 50, TimeUnit.MILLISECONDS)) {
          readers.release(READERS);
          break;
        }
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Waits for a reader slot, then reads the file on an IO thread and matches it on the pool. */
  private static void submit(
      final Workers workers,
      final Path file,
      final long size,
      final Options options,
      final Sink sink,
      final CancellationToken token,
      final Semaphore readers,
      final AtomicInteger done) {
    while (true) {
      token.check();
      try {
        if (readers.tryAcquire(50, TimeUnit.MILLISECONDS)) {
          break;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException("interrupted");
      }
    }
    final long reserve = size * BYTES_RESERVED;
    if (size > options.maxFileBytes()) {
      finish(sink, file, Skip.TOO_LARGE, readers, done, options, 0);
      return;
    }
    if (options.budget() != null && !options.budget().reserve(reserve)) {
      finish(sink, file, Skip.NO_MEMORY, readers, done, options, 0);
      return;
    }
    final long reserved = options.budget() == null ? 0 : reserve;
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                read(workers, file, options, sink, token, readers, done, reserved);
              }
            });
    if (!started) {
      finish(sink, file, null, readers, done, options, reserved);
      throw new CancellationException("workers closed");
    }
  }

  /** Reads and decodes a file, then hands it to the pool for matching. */
  @WorkerThread
  private static void read(
      Workers workers,
      final Path file,
      final Options options,
      final Sink sink,
      final CancellationToken token,
      final Semaphore readers,
      final AtomicInteger done,
      final long reserved) {
    Skip skip = null;
    boolean handed = false;
    try {
      token.check();
      final Decoded decoded = decode(file, options, token);
      skip = decoded.skip;
      if (skip == null) {
        workers.executeCpu(
            new Runnable() {
              @Override
              public void run() {
                match(file, decoded, options, sink, token, readers, done, reserved);
              }
            });
        handed = true;
      }
    } catch (IOException e) {
      skip = Skip.UNREADABLE;
    } catch (CancellationException | RejectedExecutionException e) {
      // Canceled, or the workers closed: the file counts as done.
    } finally {
      if (!handed) {
        finish(sink, file, skip, readers, done, options, reserved);
      }
    }
  }

  @WorkerThread
  private static void match(
      Path file,
      Decoded decoded,
      Options options,
      Sink sink,
      CancellationToken token,
      Semaphore readers,
      AtomicInteger done,
      long reserved) {
    try {
      int room = sink.room();
      if (room > 0) {
        TextSearch.Outcome outcome =
            TextSearch.search(
                options.matcher(), decoded.text, null, room, options.maxLineChars(), token);
        if (reported(outcome)) {
          byte[] digest = digest(file, decoded.bytes, token);
          sink.found(new FileHits(file, decoded.encoding, decoded.stamp, digest, outcome));
        }
      }
    } catch (CancellationException e) {
      // Canceled; the file counts as done.
    } finally {
      finish(sink, file, null, readers, done, options, reserved);
    }
  }

  private static void finish(
      Sink sink,
      Path file,
      Skip skip,
      Semaphore readers,
      AtomicInteger done,
      Options options,
      long reserved) {
    if (skip != null) {
      sink.skipped(file, skip);
    }
    if (reserved > 0) {
      options.budget().release(reserved);
    }
    sink.progress(done.incrementAndGet(), -1);
    readers.release();
  }

  /**
   * One file searched on its own.
   *
   * @param hits what it gave, its outcome possibly without hits; null when skipped
   * @param skip why it was not searched, or null
   */
  public record Single(FileHits hits, Skip skip) {}

  /**
   * Reads, decodes and matches one file on the calling thread, as the walk does each file it
   * accepts.
   *
   * @param file the file's real path
   * @param options the query and limits
   * @param room the most matches reported
   * @param token checked while reading and matching
   * @return the hits, or why the file was skipped
   * @throws CancellationException when canceled
   */
  @WorkerThread
  public static Single searchFile(Path file, Options options, int room, CancellationToken token) {
    long reserved = 0;
    try {
      long size = Files.size(file);
      if (size > options.maxFileBytes()) {
        return new Single(null, Skip.TOO_LARGE);
      }
      if (options.budget() != null) {
        if (!options.budget().reserve(size * BYTES_RESERVED)) {
          return new Single(null, Skip.NO_MEMORY);
        }
        reserved = size * BYTES_RESERVED;
      }
      token.check();
      Decoded decoded = decode(file, options, token);
      if (decoded.skip != null) {
        return new Single(null, decoded.skip);
      }
      TextSearch.Outcome outcome =
          TextSearch.search(
              options.matcher(), decoded.text, null, room, options.maxLineChars(), token);
      byte[] digest = reported(outcome) ? digest(file, decoded.bytes, token) : null;
      return new Single(new FileHits(file, decoded.encoding, decoded.stamp, digest, outcome), null);
    } catch (IOException e) {
      return new Single(null, Skip.UNREADABLE);
    } finally {
      if (reserved > 0) {
        options.budget().release(reserved);
      }
    }
  }

  /** Reports whether an outcome has hits, skipped lines or a regex failure. */
  private static boolean reported(TextSearch.Outcome outcome) {
    return !outcome.hits().isEmpty() || outcome.skippedLines() > 0 || outcome.tooComplexLine() >= 0;
  }

  /** A file's text and the bytes it was decoded from, or why it is skipped. */
  record Decoded(String text, TextEncoding encoding, FileStamp stamp, byte[] bytes, Skip skip) {}

  /** Bytes read, strictly decoded, decoded or digested between cancellation checks. */
  static final int BLOCK_BYTES = 1 << 16;

  private static final int DECODE_CHARS = 8192;

  /** A step of {@link #decode} or {@link #digest(Path, byte[], CancellationToken)}. */
  enum Stage {
    READ,
    DETECT,
    DECODE,
    DIGEST
  }

  /** Called at the start of each block of a {@link Stage}, before its cancellation check. */
  interface Probe {
    void block(Path file, Stage stage);
  }

  /** Null outside tests. */
  private static volatile Probe probe;

  /** Sets the probe each {@link Stage} calls, or null for none. */
  static void setProbe(Probe p) {
    probe = p;
  }

  private static void probe(Path file, Stage stage) {
    Probe p = probe;
    if (p != null) {
      p.block(file, stage);
    }
  }

  /**
   * Reads a file, detects its encoding and decodes it, checking the token before each block of
   * every step. The binary check reads only the head.
   *
   * @throws CancellationException when canceled
   */
  @WorkerThread
  static Decoded decode(Path file, Options options, CancellationToken token) throws IOException {
    FileStamp stamp = FileStamp.read(file);
    byte[] bytes = read(file, options.maxFileBytes(), token);
    if (bytes == null) {
      return new Decoded(null, null, stamp, null, Skip.TOO_LARGE);
    }
    List<EncodingDetection> candidates =
        EncodingDetector.candidates(bytes, bytes.length, options.fallback());
    TextEncoding encoding = candidates.get(candidates.size() - 1).encoding();
    for (int i = 0; i < candidates.size() - 1; i++) {
      Charset charset = candidates.get(i).encoding().charset();
      if (decode(charset, CodingErrorAction.REPORT, bytes, 0, null, file, Stage.DETECT, token)) {
        encoding = candidates.get(i).encoding();
        break;
      }
    }
    if (BinaryDetector.isBinary(bytes, bytes.length, encoding.unitWidth() > 1)) {
      return new Decoded(null, encoding, stamp, null, Skip.BINARY);
    }
    int bom = encoding.bomBytes().length;
    StringBuilder text = new StringBuilder(bytes.length - bom);
    decode(
        encoding.charset(), CodingErrorAction.REPLACE, bytes, bom, text, file, Stage.DECODE, token);
    return new Decoded(text.toString(), encoding, stamp, bytes, null);
  }

  /**
   * Returns the SHA-256 of a file's bytes, checking the token before each block.
   *
   * @throws CancellationException when canceled
   */
  @WorkerThread
  static byte[] digest(Path file, byte[] bytes, CancellationToken token) {
    MessageDigest sha = sha256();
    for (int at = 0; at < bytes.length; at += BLOCK_BYTES) {
      probe(file, Stage.DIGEST);
      token.check();
      sha.update(bytes, at, Math.min(BLOCK_BYTES, bytes.length - at));
    }
    return sha.digest();
  }

  /** Reads a whole file block by block; returns null once it holds more than {@code max} bytes. */
  private static byte[] read(Path file, long max, CancellationToken token) throws IOException {
    long cap = Math.min(max + 1, Integer.MAX_VALUE - 8);
    try (InputStream in = Files.newInputStream(file)) {
      byte[] bytes = new byte[(int) Math.min(cap, Files.size(file))];
      int length = 0;
      while (true) {
        probe(file, Stage.READ);
        token.check();
        if (length == bytes.length) {
          int next = in.read();
          if (next < 0) {
            break;
          }
          if (length == cap) {
            return null;
          }
          bytes =
              Arrays.copyOf(
                  bytes, (int) Math.min(cap, Math.max(2L * length, length + BLOCK_BYTES)));
          bytes[length++] = (byte) next;
          continue;
        }
        int n = in.readNBytes(bytes, length, Math.min(BLOCK_BYTES, bytes.length - length));
        if (n == 0) {
          break;
        }
        length += n;
      }
      if (length > max) {
        return null;
      }
      return length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
    }
  }

  /**
   * Decodes {@code bytes} from {@code from} to the end block by block, appending the chars to
   * {@code out} when it is not null.
   *
   * @return false when {@code action} is {@code REPORT} and a sequence is malformed or unmappable
   */
  private static boolean decode(
      Charset charset,
      CodingErrorAction action,
      byte[] bytes,
      int from,
      StringBuilder out,
      Path file,
      Stage stage,
      CancellationToken token) {
    CharsetDecoder decoder =
        charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action);
    ByteBuffer in = ByteBuffer.wrap(bytes, from, bytes.length - from);
    CharBuffer chars = CharBuffer.allocate(DECODE_CHARS);
    int end = from;
    boolean last = false;
    while (!last) {
      probe(file, stage);
      token.check();
      end = (int) Math.min(bytes.length, (long) end + BLOCK_BYTES);
      last = end == bytes.length;
      in.limit(end);
      while (true) {
        CoderResult result = decoder.decode(in, chars, last);
        if (result.isError()) {
          return false;
        }
        drain(chars, out);
        if (result.isUnderflow()) {
          break;
        }
      }
    }
    while (true) {
      CoderResult result = decoder.flush(chars);
      if (result.isError()) {
        return false;
      }
      drain(chars, out);
      if (result.isUnderflow()) {
        return true;
      }
    }
  }

  private static void drain(CharBuffer chars, StringBuilder out) {
    if (out != null) {
      out.append(chars.array(), 0, chars.position());
    }
    chars.clear();
  }

  /**
   * Returns the SHA-256 of bytes.
   *
   * @param bytes the bytes
   * @return 32 bytes
   */
  public static byte[] digest(byte[] bytes) {
    return sha256().digest(bytes);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing", e);
    }
  }
}
