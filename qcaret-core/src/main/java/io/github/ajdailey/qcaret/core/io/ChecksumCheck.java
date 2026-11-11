package io.github.ajdailey.qcaret.core.io;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.function.LongConsumer;

/** Checks one {@link ChecksumList.Entry} against its file. */
public final class ChecksumCheck {
  /** A line's outcome. */
  public enum Status {
    /** The file's digest matches. */
    OK,
    /** The file's digest differs. */
    FAILED,
    /** No file has the name. */
    MISSING,
    /** The file exists and could not be read, or the name is not a valid path. */
    UNREADABLE
  }

  /**
   * A line's outcome.
   *
   * @param entry the line
   * @param status the outcome
   * @param error why the file could not be read, for {@link Status#UNREADABLE}; else null
   */
  public record Result(ChecksumList.Entry entry, Status status, Exception error) {}

  private ChecksumCheck() {}

  /**
   * Resolves an entry's name against the list's folder; an absolute name stands as written.
   *
   * @param folder the folder holding the list
   * @param entry the line
   * @return the file
   * @throws InvalidPathException when the name is not a valid path on this platform
   */
  public static Path file(Path folder, ChecksumList.Entry entry) {
    return folder.resolve(entry.name());
  }

  /**
   * Digests an entry's file and compares it with the expected digest.
   *
   * @param folder the folder holding the list
   * @param entry the line
   * @param digest a fresh digest of the entry's algorithm
   * @param token checked before each buffer
   * @param progress receives the bytes read so far in this file
   * @return the outcome
   * @throws java.util.concurrent.CancellationException once the token is canceled
   */
  @WorkerThread
  public static Result verify(
      Path folder,
      ChecksumList.Entry entry,
      MessageDigest digest,
      CancellationToken token,
      LongConsumer progress) {
    Path file;
    try {
      file = file(folder, entry);
    } catch (InvalidPathException e) {
      return new Result(entry, Status.UNREADABLE, e);
    }
    if (!Files.exists(file)) {
      return new Result(entry, Status.MISSING, null);
    }
    try {
      String actual = Checksums.ofFile(file, digest, token, progress);
      return new Result(entry, actual.equals(entry.hex()) ? Status.OK : Status.FAILED, null);
    } catch (NoSuchFileException e) {
      return new Result(entry, Status.MISSING, null);
    } catch (IOException e) {
      return new Result(entry, Status.UNREADABLE, e);
    }
  }
}
