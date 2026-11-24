package io.github.ajdailey.qcaret.core.io;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.util.os.DeviceNames;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
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
    /** No file has the name: the lookup reported it absent. */
    MISSING,
    /**
     * The file could not be read or looked up (a folder on the way denies search), is not a regular
     * file, or {@link #file} refuses the name.
     */
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
   * Resolves an entry's name against the list's folder; an absolute local name stands as written. A
   * device name and a UNC name on another share are refused where the system opens them ({@link
   * DeviceNames#opensDevice}, {@link DeviceNames#checkShare}).
   *
   * @param folder the folder holding the list
   * @param entry the line
   * @return the file
   * @throws IOException naming the reason when the name is refused or not a valid path
   */
  public static Path file(Path folder, ChecksumList.Entry entry) throws IOException {
    checkName(folder.toString(), entry.name(), OsTraits.current());
    try {
      return folder.resolve(entry.name());
    } catch (InvalidPathException e) {
      throw new IOException("not a valid path", e);
    }
  }

  /**
   * Refuses a name as {@link #file} does.
   *
   * @param folder the folder holding the list, as text
   * @param name the entry's name
   * @param os the system the name is for
   * @throws IOException naming the reason
   */
  static void checkName(String folder, String name, OsTraits os) throws IOException {
    SafePaths.checkForm(name, os);
    os.deviceNames().checkShare(folder, name);
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
    // Not Files.exists: it reports false when a folder on the way denies search.
    try {
      String actual = Checksums.ofFile(file(folder, entry), digest, token, progress);
      return new Result(entry, actual.equals(entry.hex()) ? Status.OK : Status.FAILED, null);
    } catch (NoSuchFileException e) {
      return new Result(entry, Status.MISSING, null);
    } catch (IOException e) {
      return new Result(entry, Status.UNREADABLE, e);
    }
  }
}
