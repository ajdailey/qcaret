package io.github.ajdailey.qcaret.core.search;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.BinaryDetector;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.EncodingDetection;
import io.github.ajdailey.qcaret.core.text.EncodingDetector;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
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
import java.util.EnumSet;
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
 * CPU pool. Results reach the {@link Sink} on those threads, in no fixed order.
 *
 * <ul>
 *   <li>A file over {@code maxFileBytes} is skipped as {@link Skip#TOO_LARGE}; a file {@link
 *       BinaryDetector} calls binary as {@link Skip#BINARY}; a file or directory that cannot be
 *       read as {@link Skip#UNREADABLE}. A read reserves five bytes per file byte from the {@link
 *       MemoryBudget} until its matching ends; a refusal skips the file as {@link Skip#NO_MEMORY}.
 *   <li>The encoding comes from {@link EncodingDetector}; a malformed sequence decodes to U+FFFD so
 *       the rest of the file is still searched.
 *   <li>Cancel stops the walk; reads and matches in flight end at their next check, and {@link
 *       #run} returns once they have.
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
   * @param digest the SHA-256 of the bytes searched
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
                  || (!options.filter().hidden() && hidden(dir))) {
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
                  || (!options.filter().hidden() && hidden(file))
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

  private static boolean hidden(Path path) {
    Path name = path.getFileName();
    if (name != null && name.toString().startsWith(".")) {
      return true;
    }
    try {
      return Files.isHidden(path);
    } catch (IOException e) {
      return false;
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
      final Decoded decoded = decode(file, options);
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
        if (!outcome.hits().isEmpty()
            || outcome.skippedLines() > 0
            || outcome.tooComplexLine() >= 0) {
          sink.found(new FileHits(file, decoded.encoding, decoded.stamp, decoded.digest, outcome));
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
      Decoded decoded = decode(file, options);
      if (decoded.skip != null) {
        return new Single(null, decoded.skip);
      }
      TextSearch.Outcome outcome =
          TextSearch.search(
              options.matcher(), decoded.text, null, room, options.maxLineChars(), token);
      return new Single(
          new FileHits(file, decoded.encoding, decoded.stamp, decoded.digest, outcome), null);
    } catch (IOException e) {
      return new Single(null, Skip.UNREADABLE);
    } finally {
      if (reserved > 0) {
        options.budget().release(reserved);
      }
    }
  }

  /** A file's text, or why it is skipped. */
  private record Decoded(
      String text, TextEncoding encoding, FileStamp stamp, byte[] digest, Skip skip) {}

  @WorkerThread
  private static Decoded decode(Path file, Options options) throws IOException {
    FileStamp stamp = FileStamp.read(file);
    byte[] bytes = Files.readAllBytes(file);
    if (bytes.length > options.maxFileBytes()) {
      return new Decoded(null, null, stamp, null, Skip.TOO_LARGE);
    }
    EncodingDetection detection = EncodingDetector.detect(bytes, bytes.length, options.fallback());
    TextEncoding encoding = detection.encoding();
    if (BinaryDetector.isBinary(bytes, bytes.length, encoding.unitWidth() > 1)) {
      return new Decoded(null, encoding, stamp, null, Skip.BINARY);
    }
    int bom = encoding.bomBytes().length;
    String text;
    try {
      text =
          encoding
              .charset()
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE)
              .decode(ByteBuffer.wrap(bytes, bom, bytes.length - bom))
              .toString();
    } catch (CharacterCodingException e) {
      throw new IOException(e);
    }
    return new Decoded(text, encoding, stamp, digest(bytes), null);
  }

  /**
   * Returns the SHA-256 of bytes.
   *
   * @param bytes the bytes
   * @return 32 bytes
   */
  public static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing", e);
    }
  }
}
