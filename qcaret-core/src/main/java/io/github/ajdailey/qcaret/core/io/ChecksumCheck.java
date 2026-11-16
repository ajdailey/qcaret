package io.github.ajdailey.qcaret.core.io;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
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
   * Resolves an entry's name against the list's folder; an absolute local name stands as written.
   * Where names open devices (Windows), a device name or namespace ({@code CON}, {@code \\.\},
   * {@code \\?\}) is refused, and so is a UNC name ({@code \\server\share\...}) unless the folder
   * is on that share: opening a UNC name connects to its host with the user's credentials.
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
    if (os.opensDeviceNames()) {
      String share = uncShare(name);
      if (share != null && !sameShare(share, uncShare(folder))) {
        throw new IOException("names a network share the list is not on");
      }
    }
  }

  /**
   * Returns the {@code \\server\share} prefix of a UNC path, with either separator.
   *
   * @param path the path text
   * @return the prefix with backslashes; the whole text when it starts with two separators but
   *     names no share; null when it does not start with two separators
   */
  static String uncShare(String path) {
    String text = path.replace('/', '\\');
    if (!text.startsWith("\\\\")) {
      return null;
    }
    int server = text.indexOf('\\', 2);
    int share = server < 0 ? -1 : text.indexOf('\\', server + 1);
    return share < 0 ? text : text.substring(0, share);
  }

  /**
   * Compares two share prefixes, ignoring the case of ASCII letters only: a Unicode case mapping
   * could match a different host's name.
   *
   * @param a a prefix
   * @param b a prefix, or null
   * @return true when they name the same share
   */
  static boolean sameShare(String a, String b) {
    if (b == null || a.length() != b.length()) {
      return false;
    }
    for (int i = 0; i < a.length(); i++) {
      if (asciiLower(a.charAt(i)) != asciiLower(b.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static char asciiLower(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
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
