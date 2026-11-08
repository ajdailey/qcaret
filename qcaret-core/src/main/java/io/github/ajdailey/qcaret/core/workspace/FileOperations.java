package io.github.ajdailey.qcaret.core.workspace;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.concurrent.CancellationException;

/**
 * The workspace browser's file operations: create, rename, count and permanent delete. None follows
 * a symbolic link or a Windows junction: a link is renamed, counted and deleted as the link itself.
 */
public final class FileOperations {
  private FileOperations() {}

  /**
   * A folder's contents.
   *
   * @param files files and links
   * @param folders directories below the folder
   */
  public record Counts(long files, long folders) {}

  /**
   * How a delete ended.
   *
   * @param failed the entry that could not be deleted, or null when everything went
   * @param error why, or null
   * @param remaining what is left of the target, the target itself not counted; zero when it is
   *     gone or when it was not a directory
   */
  public record Deleted(Path failed, String error, Counts remaining) {
    /**
     * Reports whether the target is gone.
     *
     * @return true when nothing failed
     */
    public boolean done() {
      return failed == null;
    }
  }

  /**
   * Creates an empty file or a directory.
   *
   * @param directory the parent
   * @param name a plain name
   * @param folder true for a directory
   * @return the new entry
   * @throws FileAlreadyExistsException when the name is taken
   * @throws IOException when the entry cannot be created
   */
  @WorkerThread
  public static Path create(Path directory, String name, boolean folder) throws IOException {
    Path target = directory.resolve(name);
    return folder ? Files.createDirectory(target) : Files.createFile(target);
  }

  /**
   * Renames an entry in its directory. When the name resolves to an existing entry but no entry has
   * exactly that name, as a case-only change on a case-insensitive file system does, the rename
   * goes in two steps through a temporary name, since {@link Files#move} returns without renaming
   * when source and target are the same file; a second step that meets another entry puts the
   * source back.
   *
   * @param source the entry
   * @param name the new plain name
   * @return the renamed entry
   * @throws FileAlreadyExistsException when another entry has the name
   * @throws IOException when the rename fails; the entry keeps its name
   */
  @WorkerThread
  public static Path rename(Path source, String name) throws IOException {
    Path target = source.resolveSibling(name);
    String old = String.valueOf(source.getFileName());
    if (old.equals(name)) {
      return source;
    }
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return Files.move(source, target);
    }
    Path parent = source.getParent();
    if (parent == null || listed(parent, name)) {
      throw new FileAlreadyExistsException(target.toString());
    }
    Path hop = hop(source);
    Files.move(source, hop);
    try {
      return Files.move(hop, target);
    } catch (IOException e) {
      Files.move(hop, source);
      throw e;
    }
  }

  /** Reports whether a directory holds an entry of exactly this name, case included. */
  @WorkerThread
  private static boolean listed(Path directory, String name) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        if (String.valueOf(entry.getFileName()).equals(name)) {
          return true;
        }
      }
    } catch (DirectoryIteratorException e) {
      throw e.getCause();
    }
    return false;
  }

  /** Returns a free temporary name beside an entry. */
  @WorkerThread
  private static Path hop(Path source) {
    long stamp = System.nanoTime();
    Path hop = source.resolveSibling(".qcaret-rename-" + Long.toHexString(stamp));
    while (Files.exists(hop, LinkOption.NOFOLLOW_LINKS)) {
      stamp++;
      hop = source.resolveSibling(".qcaret-rename-" + Long.toHexString(stamp));
    }
    return hop;
  }

  /**
   * Reports whether an entry is a directory the operations descend into: not a link, not a
   * junction.
   *
   * @param path the entry
   * @return true for a plain directory
   * @throws IOException when the entry cannot be read
   */
  @WorkerThread
  public static boolean plainDirectory(Path path) throws IOException {
    return descends(
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
  }

  /** A junction is a directory that is also "other"; a link is never a directory here. */
  private static boolean descends(BasicFileAttributes attrs) {
    return attrs.isDirectory() && !attrs.isOther() && !attrs.isSymbolicLink();
  }

  /**
   * Counts a folder's contents; an unreadable directory counts as one folder with nothing in it.
   *
   * @param folder a plain directory
   * @param token checked per entry
   * @return the counts, the folder itself not counted
   * @throws IOException when the folder cannot be read
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static Counts count(final Path folder, final CancellationToken token) throws IOException {
    final long[] counts = new long[2];
    Files.walkFileTree(
        folder,
        EnumSet.noneOf(FileVisitOption.class),
        Integer.MAX_VALUE,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            token.check();
            if (dir.equals(folder)) {
              return FileVisitResult.CONTINUE;
            }
            if (!descends(attrs)) {
              counts[0]++;
              return FileVisitResult.SKIP_SUBTREE;
            }
            counts[1]++;
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            token.check();
            counts[0]++;
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException error) {
            token.check();
            if (file.equals(folder)) {
              return FileVisitResult.TERMINATE;
            }
            counts[1]++;
            return FileVisitResult.CONTINUE;
          }
        });
    return new Counts(counts[0], counts[1]);
  }

  /**
   * Deletes an entry permanently: a file or a link alone, a plain directory with its tree, children
   * first. Stops at the first entry that cannot be deleted.
   *
   * @param target the entry
   * @param token checked per entry; a cancel stops like a failure
   * @return how it ended
   */
  @WorkerThread
  public static Deleted delete(final Path target, final CancellationToken token) {
    final Path[] at = {target};
    try {
      if (!plainDirectory(target)) {
        Files.delete(target);
        return new Deleted(null, null, new Counts(0, 0));
      }
      Files.walkFileTree(
          target,
          EnumSet.noneOf(FileVisitOption.class),
          Integer.MAX_VALUE,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              token.check();
              if (descends(attrs)) {
                return FileVisitResult.CONTINUE;
              }
              at[0] = dir;
              Files.deleteIfExists(dir);
              return FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              token.check();
              at[0] = file;
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error)
                throws IOException {
              if (error != null) {
                at[0] = dir;
                throw error;
              }
              at[0] = dir;
              Files.deleteIfExists(dir);
              return FileVisitResult.CONTINUE;
            }
          });
      return new Deleted(null, null, new Counts(0, 0));
    } catch (NoSuchFileException e) {
      if (at[0].equals(target)) {
        return new Deleted(null, null, new Counts(0, 0));
      }
      return stopped(target, e.getFile() == null ? at[0] : Path.of(e.getFile()), e, token);
    } catch (FileSystemException e) {
      return stopped(target, e.getFile() == null ? at[0] : Path.of(e.getFile()), e, token);
    } catch (IOException | CancellationException e) {
      return stopped(target, at[0], e, token);
    }
  }

  @WorkerThread
  private static Deleted stopped(
      Path target, Path failed, Exception error, CancellationToken token) {
    Counts left = new Counts(0, 0);
    try {
      if (!token.isCanceled() && plainDirectory(target)) {
        left = count(target, new CancellationToken());
      }
    } catch (IOException e) {
      left = new Counts(0, 0);
    }
    String reason = error.getClass().getSimpleName();
    if (error instanceof FileSystemException fs && fs.getReason() != null) {
      reason = fs.getReason();
    } else if (!(error instanceof FileSystemException) && error.getMessage() != null) {
      reason = error.getMessage();
    }
    return new Deleted(failed, reason, left);
  }
}
