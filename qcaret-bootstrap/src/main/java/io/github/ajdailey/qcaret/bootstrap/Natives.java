package io.github.ajdailey.qcaret.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Places SWT's native libraries in a directory and verifies them against the SHA-256 digests the
 * build stored in the universal jar. Every start verifies every library before SWT loads one.
 *
 * <p>A writable directory is created owner-only and a missing or mismatched library is extracted
 * from the fragment: written to a temp file in the directory, checked, then moved over its name
 * with {@code ATOMIC_MOVE}. When that move fails and the name already holds a matching library,
 * placed by a concurrent start, extraction succeeds. A read-only directory (a bundle's {@code
 * lib/swt}) is verified only.
 */
final class Natives {
  private static final int BUFFER = 64 * 1024;
  private static final Set<PosixFilePermission> OWNER_DIR =
      PosixFilePermissions.fromString("rwx------");

  /** Moves an extracted temp file over its library name. */
  interface Mover {
    /**
     * Moves the file.
     *
     * @param temp the verified temp file
     * @param file the library's name in the directory
     * @throws IOException when the move fails
     */
    void move(Path temp, Path file) throws IOException;
  }

  private static final Mover ATOMIC =
      new Mover() {
        @Override
        public void move(Path temp, Path file) throws IOException {
          Files.move(
              temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
      };

  private Natives() {}

  /** A library that does not match its digest after extraction, or in a read-only directory. */
  static final class MismatchException extends Exception {
    private static final long serialVersionUID = 1L;

    MismatchException(String message) {
      super(message);
    }
  }

  /**
   * Verifies, and where the directory is writable extracts, every library.
   *
   * @param dir the natives directory; created when writable and absent
   * @param writable false for a bundle directory, which is never written
   * @param jar the universal jar, holding the digests
   * @param fragmentRoot root of the target's nested SWT fragment, holding the libraries
   * @param target the target
   * @param names the library names from the index, already validated
   * @throws MismatchException when a library does not match after one extraction, or a read-only
   *     directory holds a missing or mismatched library
   * @throws IOException when the directory or a library cannot be read or written
   */
  static void prepare(
      Path dir,
      boolean writable,
      FileSystem jar,
      Path fragmentRoot,
      Target target,
      List<String> names)
      throws MismatchException, IOException {
    prepare(dir, writable, jar, fragmentRoot, target, names, ATOMIC);
  }

  /**
   * Verifies, and where the directory is writable extracts, every library, moving each extracted
   * file into place with the given mover.
   *
   * @param dir the natives directory; created when writable and absent
   * @param writable false for a bundle directory, which is never written
   * @param jar the universal jar, holding the digests
   * @param fragmentRoot root of the target's nested SWT fragment, holding the libraries
   * @param target the target
   * @param names the library names from the index, already validated
   * @param mover moves a verified temp file over its name
   * @throws MismatchException when a library does not match after one extraction, or a read-only
   *     directory holds a missing or mismatched library
   * @throws IOException when the directory or a library cannot be read or written
   */
  static void prepare(
      Path dir,
      boolean writable,
      FileSystem jar,
      Path fragmentRoot,
      Target target,
      List<String> names,
      Mover mover)
      throws MismatchException, IOException {
    if (writable) {
      createOwnerOnly(dir);
    }
    for (String name : names) {
      byte[] expected = expectedDigest(jar, target, name);
      Path file = child(dir, name);
      if (matches(file, expected)) {
        continue;
      }
      if (!writable) {
        throw new MismatchException(mismatchMessage(name, dir));
      }
      extract(fragmentRoot, name, dir, file, expected, mover);
      if (!matches(file, expected)) {
        throw new MismatchException(mismatchMessage(name, dir));
      }
    }
  }

  /**
   * Returns the message for a library that does not match this build.
   *
   * @param name the library name
   * @param dir the natives directory
   * @return the message, without a line terminator
   */
  static String mismatchMessage(String name, Path dir) {
    return "SWT native " + name + " in " + dir + " does not match this QCaret build.";
  }

  /**
   * Resolves a validated name inside a directory.
   *
   * @param dir the directory
   * @param name a name matching {@code [A-Za-z0-9._-]+}
   * @return the file
   * @throws IOException when the name is invalid or resolves outside the directory
   */
  static Path child(Path dir, String name) throws IOException {
    if (!BootIndex.validName(name)) {
      throw new IOException("Invalid native library name");
    }
    Path base = dir.toAbsolutePath().normalize();
    Path file = base.resolve(name).normalize();
    if (!file.startsWith(base) || file.equals(base)) {
      throw new IOException("Native library name resolves outside " + base);
    }
    return file;
  }

  /**
   * Creates a directory and its missing parents owner-only (POSIX {@code rwx------}). On Windows
   * the directories inherit the ACL of the per-user parent.
   *
   * @param dir the directory
   * @throws IOException when it cannot be created or is not a directory
   */
  static void createOwnerOnly(Path dir) throws IOException {
    if (posix(dir)) {
      FileAttribute<Set<PosixFilePermission>> attr =
          PosixFilePermissions.asFileAttribute(OWNER_DIR);
      Files.createDirectories(dir, attr);
      Files.setPosixFilePermissions(dir, OWNER_DIR);
    } else {
      Files.createDirectories(dir);
    }
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(dir + " is not a directory");
    }
  }

  private static boolean posix(Path dir) {
    return dir.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  private static byte[] expectedDigest(FileSystem jar, Target target, String name)
      throws IOException {
    Path entry = jar.getPath(BootIndex.digest(target, name));
    if (!Files.isRegularFile(entry) || Files.size(entry) > 256) {
      throw new IOException("No digest for SWT native " + name + " in this jar");
    }
    String hex = new String(Files.readAllBytes(entry), StandardCharsets.US_ASCII).strip();
    if (hex.length() != 64) {
      throw new IOException("Malformed digest for SWT native " + name);
    }
    try {
      return HexFormat.of().parseHex(hex);
    } catch (IllegalArgumentException e) {
      throw new IOException("Malformed digest for SWT native " + name, e);
    }
  }

  private static boolean matches(Path file, byte[] expected) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    MessageDigest digest = sha256();
    byte[] buffer = new byte[BUFFER];
    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      int n = in.read(buffer);
      while (n >= 0) {
        digest.update(buffer, 0, n);
        n = in.read(buffer);
      }
    }
    return MessageDigest.isEqual(digest.digest(), expected);
  }

  private static void extract(
      Path fragmentRoot, String name, Path dir, Path file, byte[] expected, Mover mover)
      throws IOException, MismatchException {
    Path source = fragmentRoot.resolve(name);
    if (!Files.isRegularFile(source)) {
      throw new IOException("SWT native " + name + " is missing from the fragment in this jar");
    }
    Path temp = Files.createTempFile(dir, name, ".tmp");
    boolean moved = false;
    try {
      MessageDigest digest = sha256();
      byte[] buffer = new byte[BUFFER];
      try (InputStream in = Files.newInputStream(source);
          OutputStream out = Files.newOutputStream(temp)) {
        int n = in.read(buffer);
        while (n >= 0) {
          digest.update(buffer, 0, n);
          out.write(buffer, 0, n);
          n = in.read(buffer);
        }
      }
      if (!MessageDigest.isEqual(digest.digest(), expected)) {
        throw new MismatchException(
            "SWT native " + name + " in this jar does not match its build digest.");
      }
      if (posix(temp)) {
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
      }
      try {
        mover.move(temp, file);
        moved = true;
      } catch (IOException e) {
        // On Windows the replace fails while another start has the library loaded.
        if (!matches(file, expected)) {
          throw e;
        }
      }
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
      }
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
    }
  }
}
