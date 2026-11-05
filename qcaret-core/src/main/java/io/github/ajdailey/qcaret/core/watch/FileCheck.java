package io.github.ajdailey.qcaret.core.watch;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Compares the file at a path with a {@link FileBaseline}. The path is the one the user opened and
 * is read through symbolic links at every check, so a link retargeted to another file compares that
 * file. The stamp decides alone when it equals the baseline's and the baseline is not {@link
 * FileBaseline#racy}; otherwise the SHA-256 decides, so a touched file with the same bytes is
 * unchanged and a file truncated and regrown to the same size within one clock tick is changed.
 */
public final class FileCheck {
  /** Digests read while the stamp keeps changing before the last one is reported. */
  static final int SETTLE_ATTEMPTS = 3;

  private FileCheck() {}

  /** What a check found. */
  public enum State {
    /** The bytes equal the baseline's; {@link Result#current} may carry a newer stamp. */
    UNCHANGED,
    /** Other bytes, or a file where the baseline had none. */
    CHANGED,
    /** No regular file at the path. */
    MISSING
  }

  /**
   * One check's outcome.
   *
   * @param state what was found
   * @param current the file's baseline now; the given baseline when nothing was read, null when
   *     missing
   */
  public record Result(State state, FileBaseline current) {}

  /**
   * Checks a file. A file the baseline did not have (null) and that is still absent is unchanged.
   *
   * @param path the path the user opened
   * @param baseline what the document last matched, or null when no file existed
   * @param token checked between digest blocks
   * @return the result
   * @throws IOException when the attributes or the bytes cannot be read
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static Result check(Path path, FileBaseline baseline, CancellationToken token)
      throws IOException {
    FileStamp stamp = regularStamp(path);
    if (stamp == null) {
      return new Result(baseline == null ? State.UNCHANGED : State.MISSING, null);
    }
    if (baseline != null && stamp.equals(baseline.stamp()) && !baseline.racy()) {
      return new Result(State.UNCHANGED, baseline);
    }
    FileBaseline current = read(path, stamp, token);
    if (current == null) {
      return new Result(baseline == null ? State.UNCHANGED : State.MISSING, null);
    }
    boolean same = baseline != null && current.digest().equals(baseline.digest());
    return new Result(same ? State.UNCHANGED : State.CHANGED, current);
  }

  /**
   * Reads a file's baseline: its stamp, then its digest, again while the stamp changes during the
   * digest, at most {@value #SETTLE_ATTEMPTS} times.
   *
   * @param path the file
   * @param token checked between digest blocks
   * @return the baseline, or null when no regular file is there
   * @throws IOException when the file cannot be read
   */
  @WorkerThread
  public static FileBaseline read(Path path, CancellationToken token) throws IOException {
    FileStamp stamp = regularStamp(path);
    return stamp == null ? null : read(path, stamp, token);
  }

  @WorkerThread
  private static FileBaseline read(Path path, FileStamp first, CancellationToken token)
      throws IOException {
    FileStamp stamp = first;
    for (int attempt = 1; ; attempt++) {
      long recorded = System.currentTimeMillis();
      String digest;
      try {
        digest = Digests.of(path, token);
      } catch (NoSuchFileException e) {
        return null;
      }
      FileStamp after = regularStamp(path);
      if (after == null) {
        return null;
      }
      if (after.equals(stamp) || attempt == SETTLE_ATTEMPTS) {
        return new FileBaseline(stamp, digest, recorded);
      }
      stamp = after;
    }
  }

  /** Returns the stamp of a regular file, following links; null for anything else or nothing. */
  private static FileStamp regularStamp(Path path) throws IOException {
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(path, BasicFileAttributes.class);
    } catch (NoSuchFileException e) {
      return null;
    }
    if (!attributes.isRegularFile()) {
      return null;
    }
    return new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
  }
}
