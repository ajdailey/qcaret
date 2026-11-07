package io.github.ajdailey.qcaret.core.io;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Saves a user file without leaving it partly written. The content goes to a temporary file {@code
 * .<name>.<random>.qcaret-tmp} in the target's directory, forced to disk, then moved over the
 * target with {@code ATOMIC_MOVE}. Replacing a file, the temporary file is owner-only while it is
 * written, takes the target's group first and its POSIX permissions or DOS attributes before the
 * move; a new file takes the default mode. A symbolic link is resolved first, dangling or not, so
 * the link stays a link.
 *
 * <p>The target is written in place (truncate, write, force) instead when it has more than one hard
 * link, its owner is not the user the temporary file is created as, that user cannot give the
 * temporary file its group, its directory is not writable, or the move still fails after {@value
 * #MOVE_ATTEMPTS} attempts (Windows sharing violations). An in-place write that fails partway
 * leaves the target partly written, and a cancel does not stop one that has begun.
 */
public final class SafeSave {
  /** Move attempts before the in-place fallback. */
  static final int MOVE_ATTEMPTS = 5;

  private static final long MOVE_RETRY_MILLIS = 100;
  private static final int MAX_LINKS = 40;
  private static final Set<PosixFilePermission> OWNER_ONLY =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private static final SecureRandom RANDOM = new SecureRandom();

  private SafeSave() {}

  /**
   * Saves a file.
   *
   * @param target the file as the user named it; its parent directory must exist
   * @param baseline the stamp from the load or the last save, or null for a new file; a target that
   *     exists with another stamp fails the save
   * @param content writes the bytes, BOM included; may run twice, and need not check {@code token}
   * @param token checked before the target is touched and on each write to the temporary file; a
   *     cancel then leaves the target unchanged, and a cancel during an in-place write has no
   *     effect
   * @return the saved file's stamp, the next save's baseline
   * @throws ExternalModificationException when the target changed since {@code baseline}; nothing
   *     is written
   * @throws CancellationException when canceled before the target is replaced
   * @throws AccessDeniedException when the target exists and is not writable; nothing is written
   * @throws IOException when the target is the file named to {@link SafePaths#holdLock}, or the
   *     write fails; an atomic save leaves the target unchanged
   */
  @WorkerThread
  public static FileStamp save(
      Path target, FileStamp baseline, ContentWriter content, CancellationToken token)
      throws IOException {
    FileStamp current = FileStamp.read(target);
    if (baseline != null && current != null && !baseline.equals(current)) {
      throw new ExternalModificationException(String.valueOf(target.getFileName()), current);
    }
    Path real = current != null ? target.toRealPath() : linkEnd(target);
    if (current != null) {
      SafePaths.checkNotHeld(real);
      if (!Files.isWritable(real)) {
        throw new AccessDeniedException(null, null, "The file is not writable");
      }
    }
    Path dir = real.getParent();
    if (dir == null) {
      throw new IOException("No parent directory");
    }
    token.check();
    if (current != null && (hardLinked(real) || !Files.isWritable(dir))) {
      return inPlace(real, content, token);
    }
    Path temp = dir.resolve("." + real.getFileName() + "." + randomHex() + ".qcaret-tmp");
    createTemp(temp, current != null);
    boolean moved = false;
    try {
      if (current != null && !(sameOwner(real, temp) && sameGroup(real, temp))) {
        Files.delete(temp);
        return inPlace(real, content, token);
      }
      write(temp, content, token, StandardOpenOption.WRITE);
      if (current != null) {
        copyAttributes(real, temp);
      }
      token.check();
      moved = move(temp, real);
      if (!moved) {
        return inPlace(real, content, token);
      }
      return FileStamp.read(real);
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
      }
    }
  }

  /** Returns where a missing target's links end, itself when it is not a link. */
  private static Path linkEnd(Path target) throws IOException {
    Path end = target.toAbsolutePath();
    for (int links = 0; Files.isSymbolicLink(end); links++) {
      if (links == MAX_LINKS) {
        throw new FileSystemLoopException(target.toString());
      }
      end = end.resolveSibling(Files.readSymbolicLink(end));
    }
    return end;
  }

  private static void createTemp(Path temp, boolean replacing) throws IOException {
    if (replacing && temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    } else {
      Files.createFile(temp);
    }
  }

  /** The truncate is the last point a cancel can stop: past it the old content is gone. */
  private static FileStamp inPlace(Path real, ContentWriter content, CancellationToken token)
      throws IOException {
    token.check();
    write(real, content, null, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    return FileStamp.read(real);
  }

  /** Writes and forces a file; a non-null {@code token} is checked on each write. */
  private static void write(
      Path file, ContentWriter content, CancellationToken token, StandardOpenOption... options)
      throws IOException {
    try (FileChannel channel = FileChannel.open(file, options)) {
      OutputStream out = Channels.newOutputStream(channel);
      content.write(token == null ? out : new CheckedOutput(out, token));
      out.flush();
      channel.force(true);
    }
  }

  /**
   * Moves the temporary file over the target, retrying an access or sharing failure.
   *
   * @return false when every attempt failed with an access or sharing error
   */
  private static boolean move(Path temp, Path real) throws IOException {
    for (int attempt = 1; ; attempt++) {
      try {
        Files.move(temp, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return true;
      } catch (AtomicMoveNotSupportedException e) {
        return false;
      } catch (AccessDeniedException e) {
        if (attempt == MOVE_ATTEMPTS) {
          return false;
        }
      } catch (FileSystemException e) {
        if (attempt == MOVE_ATTEMPTS) {
          return false;
        }
      }
      try {
        Thread.sleep(MOVE_RETRY_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException("Interrupted while saving");
      }
    }
  }

  private static boolean hardLinked(Path real) throws IOException {
    if (!real.getFileSystem().supportedFileAttributeViews().contains("unix")) {
      return false;
    }
    Object links = Files.getAttribute(real, "unix:nlink");
    return links instanceof Integer count && count > 1;
  }

  private static boolean sameOwner(Path real, Path temp) throws IOException {
    if (!real.getFileSystem().supportedFileAttributeViews().contains("owner")) {
      return true;
    }
    UserPrincipal owner = Files.getOwner(real);
    return owner.equals(Files.getOwner(temp));
  }

  /** Gives the temporary file the target's group; false when the user cannot. True on Windows. */
  private static boolean sameGroup(Path real, Path temp) throws IOException {
    PosixFileAttributeView view = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
    if (view == null) {
      return true;
    }
    GroupPrincipal group = Files.readAttributes(real, PosixFileAttributes.class).group();
    if (group.equals(view.readAttributes().group())) {
      return true;
    }
    try {
      view.setGroup(group);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static void copyAttributes(Path real, Path temp) throws IOException {
    PosixFileAttributeView posix =
        Files.getFileAttributeView(real, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      Files.setPosixFilePermissions(temp, posix.readAttributes().permissions());
      return;
    }
    DosFileAttributeView dos = Files.getFileAttributeView(temp, DosFileAttributeView.class);
    if (dos != null) {
      DosFileAttributes from = Files.readAttributes(real, DosFileAttributes.class);
      dos.setHidden(from.isHidden());
      dos.setSystem(from.isSystem());
      dos.setArchive(true);
    }
  }

  private static String randomHex() {
    byte[] bytes = new byte[6];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** Stops a write to the temporary file on a cancel. */
  private static final class CheckedOutput extends OutputStream {
    private final OutputStream out;
    private final CancellationToken token;

    CheckedOutput(OutputStream out, CancellationToken token) {
      this.out = out;
      this.token = token;
    }

    @Override
    public void write(int b) throws IOException {
      token.check();
      out.write(b);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      token.check();
      out.write(bytes, offset, length);
    }
  }
}
