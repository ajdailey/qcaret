package io.github.ajdailey.qcaret.core.io;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces a file in one step: the content goes to a temporary file in the target's directory,
 * owner-only where POSIX permissions exist, which then moves over the target with {@code
 * ATOMIC_MOVE}. A reader sees the old file or the new one, never a partial write. Nothing goes to
 * {@code java.io.tmpdir}. Where POSIX permissions do not exist (Windows) the move is tried for up
 * to a quarter of a second: a target open elsewhere without delete sharing, or being replaced by
 * another writer, refuses the move for that moment.
 */
public final class AtomicFiles {
  private static final Set<PosixFilePermission> OWNER_FILE =
      PosixFilePermissions.fromString("rw-------");

  /** Bytes read from a stream at a time. */
  private static final int COPY_BUFFER = 64 * 1024;

  /** Tries of a move on a file system without POSIX permissions. */
  private static final int MOVE_ATTEMPTS = 10;

  private static final long MOVE_RETRY_MILLIS = 25;

  /** The step {@link #tryMove} repeats. */
  interface Move {
    /**
     * Moves the file.
     *
     * @throws IOException when the move fails
     */
    void run() throws IOException;
  }

  private AtomicFiles() {}

  /**
   * Writes a file atomically.
   *
   * @param file the target, in an existing directory
   * @param content the bytes
   * @throws IOException when the temporary file cannot be written or moved; the target is then
   *     unchanged and the temporary file deleted
   */
  public static void write(Path file, byte[] content) throws IOException {
    write(file, content, null, false);
  }

  /**
   * Writes a file atomically from a stream, which is read to its end and not closed.
   *
   * @param file the target, in an existing directory
   * @param content the bytes
   * @throws IOException when the stream, the temporary file or the move fails; the target is then
   *     unchanged and the temporary file deleted
   */
  public static void write(Path file, InputStream content) throws IOException {
    write(file, null, content, false);
  }

  /**
   * Writes a secret atomically: as {@link #write}, and where the file system has ACLs but no POSIX
   * permissions (Windows), the temporary file's ACL is replaced by one entry for the current user
   * before the content is written.
   *
   * @param file the target, in an existing directory
   * @param content the bytes
   * @throws IOException when the temporary file cannot be written, restricted or moved; the target
   *     is then unchanged and the temporary file deleted
   */
  public static void writeOwnerOnly(Path file, byte[] content) throws IOException {
    write(file, content, null, true);
  }

  /**
   * Writes a secret atomically from a writer and forces it to disk before the move: as {@link
   * #writeOwnerOnly(Path, byte[])}, for content too large to hold as one array.
   *
   * @param file the target, in an existing directory
   * @param content writes the bytes
   * @throws IOException when the writer, the temporary file or the move fails; the target is then
   *     unchanged and the temporary file deleted
   */
  public static void writeOwnerOnly(Path file, final ContentWriter content) throws IOException {
    Path temp = createTemp(file);
    boolean moved = false;
    try {
      ownerOnlyAcl(temp);
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE);
          OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel))) {
        content.write(out);
        out.flush();
        channel.force(true);
      }
      replace(temp, file);
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
      }
    }
  }

  private static Path createTemp(Path file) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    if (dir == null) {
      throw new IOException("No parent directory");
    }
    if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return Files.createTempFile(
          dir, ".qcaret-", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_FILE));
    }
    return Files.createTempFile(dir, ".qcaret-", ".tmp");
  }

  private static void write(Path file, byte[] content, InputStream source, boolean restrict)
      throws IOException {
    Path temp = createTemp(file);
    boolean moved = false;
    try {
      if (restrict) {
        ownerOnlyAcl(temp);
      }
      try (OutputStream out = Files.newOutputStream(temp)) {
        if (source == null) {
          out.write(content);
        } else {
          byte[] buffer = new byte[COPY_BUFFER];
          int read;
          while ((read = source.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
          }
        }
      }
      replace(temp, file);
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
      }
    }
  }

  private static void replace(final Path temp, final Path file) throws IOException {
    boolean posix = temp.getFileSystem().supportedFileAttributeViews().contains("posix");
    tryMove(
        new Move() {
          @Override
          public void run() throws IOException {
            Files.move(
                temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          }
        },
        posix ? 1 : MOVE_ATTEMPTS);
  }

  /**
   * Runs a move, again after a file system refusal until the attempts are used.
   *
   * @param move the move
   * @param attempts the most tries
   * @throws IOException the last refusal, or any other failure at once
   */
  static void tryMove(Move move, int attempts) throws IOException {
    int attempt = 1;
    while (true) {
      try {
        move.run();
        return;
      } catch (FileSystemException e) {
        if (attempt >= attempts) {
          throw e;
        }
        attempt++;
        try {
          Thread.sleep(MOVE_RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  /** Leaves one ACL entry, full access for the user, where POSIX permissions do not exist. */
  private static void ownerOnlyAcl(Path temp) throws IOException {
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    AclFileAttributeView view = Files.getFileAttributeView(temp, AclFileAttributeView.class);
    if (view == null) {
      return;
    }
    AclEntry entry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(currentUser(temp.getFileSystem(), view))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
    view.setAcl(List.of(entry));
  }

  /**
   * Returns the user running this process. A new file's owner is not that user in an elevated
   * process, where Windows makes {@code BUILTIN\Administrators} the owner. The name is qualified
   * first because a bare name resolves to a local account before a domain account of that name.
   *
   * @return the user; the file's owner when no name resolves, as under a service account or with a
   *     domain controller out of reach
   */
  private static UserPrincipal currentUser(FileSystem system, AclFileAttributeView view)
      throws IOException {
    UserPrincipalLookupService lookup = system.getUserPrincipalLookupService();
    String name = System.getProperty("user.name");
    String domain = System.getenv("USERDOMAIN");
    if (domain != null) {
      try {
        return lookup.lookupPrincipalByName(domain + "\\" + name);
      } catch (IOException e) {
        // The bare name below still applies.
      }
    }
    try {
      return lookup.lookupPrincipalByName(name);
    } catch (IOException e) {
      return view.getOwner();
    }
  }
}
