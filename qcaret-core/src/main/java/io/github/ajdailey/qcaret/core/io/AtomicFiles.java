package io.github.ajdailey.qcaret.core.io;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces a file in one step: the content goes to a temporary file in the target's directory,
 * owner-only where POSIX permissions exist, which then moves over the target with {@code
 * ATOMIC_MOVE}. A reader sees the old file or the new one, never a partial write. Nothing goes to
 * {@code java.io.tmpdir}.
 */
public final class AtomicFiles {
  private static final Set<PosixFilePermission> OWNER_FILE =
      PosixFilePermissions.fromString("rw-------");

  /** Bytes read from a stream at a time. */
  private static final int COPY_BUFFER = 64 * 1024;

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
   * permissions (Windows), the temporary file's ACL is replaced by one entry for its owner before
   * the content is written.
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
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
      }
    }
  }

  /** Leaves one ACL entry, full access for the owner, where POSIX permissions do not exist. */
  private static void ownerOnlyAcl(Path temp) throws IOException {
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    AclFileAttributeView view = Files.getFileAttributeView(temp, AclFileAttributeView.class);
    if (view == null) {
      return;
    }
    AclEntry owner =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(view.getOwner())
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
    view.setAcl(List.of(owner));
  }
}
