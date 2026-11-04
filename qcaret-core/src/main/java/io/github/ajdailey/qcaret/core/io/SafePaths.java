package io.github.ajdailey.qcaret.core.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation for every path that comes from outside the process. An editor opens whatever file its
 * user names, so a user file path is canonicalized and its file type checked rather than contained;
 * files QCaret owns are contained in their directory.
 */
public final class SafePaths {
  private static final Pattern WINDOWS_RESERVED =
      Pattern.compile(
          "(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]|CONIN\\$|CONOUT\\$)(\\..*)?",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CHILD_NAME = Pattern.compile("[A-Za-z0-9._-]+");
  private static final Set<PosixFilePermission> OWNER_DIR =
      PosixFilePermissions.fromString("rwx------");

  private SafePaths() {}

  /**
   * Validates a user file path from the command line, a handoff, the recent list or a session. The
   * path is made absolute and normalized; an existing path resolves to its real path and must be a
   * regular file (a FIFO blocks the reader and a device never ends); a missing path needs an
   * existing parent directory, where a save creates the file.
   *
   * @param path the path as given; a relative path resolves against the working directory
   * @return the real path of an existing file, or the absolute path of a missing one under its
   *     parent's real path
   * @throws IOException naming the reason when the path is rejected
   */
  public static Path userFile(Path path) throws IOException {
    checkForm(path.toString());
    Path absolute = path.toAbsolutePath().normalize();
    if (Files.exists(absolute)) {
      Path real = absolute.toRealPath();
      if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException(real + " is not a regular file");
      }
      return real;
    }
    Path parent = absolute.getParent();
    Path name = absolute.getFileName();
    if (parent == null || name == null || !Files.isDirectory(parent)) {
      throw new IOException(absolute + " does not exist and has no parent directory");
    }
    return parent.toRealPath().resolve(name);
  }

  /**
   * Validates a user file path given as text.
   *
   * @param path the path as given
   * @return as {@link #userFile(Path)}
   * @throws IOException when the text is not a path or the path is rejected
   */
  public static Path userFile(String path) throws IOException {
    checkForm(path);
    try {
      return userFile(Path.of(path));
    } catch (InvalidPathException e) {
      throw new IOException("Not a valid path: " + e.getReason(), e);
    }
  }

  /**
   * Validates a settings directory: made absolute and normalized, created with owner-only
   * permissions where the file system supports POSIX permissions, then resolved to its real path.
   *
   * @param path the directory as given
   * @return the real path of the directory
   * @throws IOException when it cannot be created or is not a directory
   */
  public static Path settingsDirectory(Path path) throws IOException {
    checkForm(path.toString());
    Path absolute = path.toAbsolutePath().normalize();
    if (absolute.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createDirectories(absolute, PosixFilePermissions.asFileAttribute(OWNER_DIR));
    } else {
      Files.createDirectories(absolute);
    }
    Path real = absolute.toRealPath();
    if (!Files.isDirectory(real)) {
      throw new IOException(real + " is not a directory");
    }
    return real;
  }

  /**
   * Resolves a fixed file name inside a directory QCaret owns.
   *
   * @param base the directory
   * @param name a name matching {@code [A-Za-z0-9._-]+}
   * @return the file, which lies inside base
   * @throws IOException when the name is not a plain name or resolves outside base
   */
  public static Path child(Path base, String name) throws IOException {
    if (name == null
        || !CHILD_NAME.matcher(name).matches()
        || name.equals(".")
        || name.equals("..")) {
      throw new IOException("Not a plain file name");
    }
    Path normalized = base.toAbsolutePath().normalize();
    Path file = normalized.resolve(name).normalize();
    if (!file.startsWith(normalized) || file.equals(normalized)) {
      throw new IOException("File name resolves outside " + normalized);
    }
    return file;
  }

  /**
   * Reports whether a path names a Windows device or uses a device or raw namespace prefix: {@code
   * \\.\}, {@code \\?\}, or a component that is a reserved device name such as {@code CON} or
   * {@code COM1}, with or without an extension.
   *
   * @param path the path text
   * @return true when Windows would open a device rather than a file
   */
  static boolean windowsDevice(String path) {
    String text = path.replace('/', '\\');
    if (text.startsWith("\\\\.\\") || text.startsWith("\\\\?\\")) {
      return true;
    }
    for (String part : text.split("[\\\\:]")) {
      String name = part.strip();
      if (WINDOWS_RESERVED.matcher(name).matches()) {
        return true;
      }
    }
    return false;
  }

  private static void checkForm(String path) throws IOException {
    if (path == null || path.isEmpty()) {
      throw new IOException("Empty path");
    }
    if (path.indexOf('\0') >= 0) {
      throw new IOException("Path contains a NUL character");
    }
    if (File.separatorChar == '\\' && windowsDevice(path)) {
      throw new IOException("Path names a Windows device");
    }
  }
}
