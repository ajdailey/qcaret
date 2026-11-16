package io.github.ajdailey.qcaret.app.ipc;

import io.github.ajdailey.qcaret.core.util.Streams;
import io.github.ajdailey.qcaret.core.util.os.EnvPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Reads {@code instance.endpoint} only when no other account can have written it or read its token:
 * a regular file, opened without following links, owned by the current user and open to no other
 * account. On POSIX the file has no group or other permission; where there are ACLs instead
 * (Windows), every ACL entry is for an account {@link AclCheck} trusts. A file system with neither
 * is accepted. Java reads no owner or ACL through an open channel, so the checks run before and
 * after the read, and a file whose identity, size or modification time changed between them is
 * refused.
 */
final class EndpointCheck {
  /** A refused endpoint file: the handoff treats it as missing. */
  static final class Refused extends NoSuchFileException {
    private static final long serialVersionUID = 1L;

    Refused(Path file, String reason) {
      super(file.toString(), null, reason);
    }
  }

  private static final Set<PosixFilePermission> OTHERS =
      EnumSet.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);

  private EndpointCheck() {}

  /**
   * Reads the endpoint file after checking it.
   *
   * @param file the endpoint file
   * @param user the current user's name
   * @param limit the most bytes
   * @return the content
   * @throws NoSuchFileException when there is no file; {@link Refused}, naming the failed step,
   *     when it is not trusted
   * @throws IOException when it cannot be read or is over the limit
   */
  static byte[] read(Path file, String user, int limit) throws IOException {
    BasicFileAttributes before = attributes(file);
    refuse(file, refusal(file, before, user));
    byte[] bytes;
    try (InputStream in =
        Channels.newInputStream(
            Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      bytes = Streams.readLimited(in, limit);
    }
    BasicFileAttributes after = attributes(file);
    if (!Objects.equals(before.fileKey(), after.fileKey())
        || before.size() != after.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
      refuse(file, "instance.endpoint changed while it was read");
    }
    refuse(file, refusal(file, after, user));
    return bytes;
  }

  /**
   * Checks the endpoint file without reading it.
   *
   * @param file the endpoint file
   * @param user the current user's name
   * @return null when it is trusted, else the reason, naming the failed step
   * @throws NoSuchFileException when there is no file
   */
  static String refusal(Path file, String user) throws NoSuchFileException {
    try {
      return refusal(file, attributes(file), user);
    } catch (Refused e) {
      return e.getReason();
    }
  }

  private static BasicFileAttributes attributes(Path file) throws NoSuchFileException {
    try {
      return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw e;
    } catch (IOException e) {
      throw new Refused(file, "the attributes of instance.endpoint cannot be read: " + e);
    }
  }

  private static void refuse(Path file, String reason) throws Refused {
    if (reason != null) {
      throw new Refused(file, reason);
    }
  }

  private static String refusal(Path file, BasicFileAttributes attributes, String user) {
    if (attributes.isSymbolicLink()) {
      return "instance.endpoint is a symbolic link";
    }
    if (!attributes.isRegularFile()) {
      return "instance.endpoint is not a regular file";
    }
    Set<String> views = file.getFileSystem().supportedFileAttributeViews();
    if (views.contains("posix")) {
      return posixRefusal(file, user);
    }
    if (!views.contains("acl")) {
      return null;
    }
    Path profile = EnvPaths.absolute(System.getenv("USERPROFILE"));
    if (profile == null) {
      return "USERPROFILE is not an absolute path";
    }
    return AclCheck.refusal(file, user, profile);
  }

  private static String posixRefusal(Path file, String user) {
    PosixFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      return "the owner and permissions of instance.endpoint cannot be read: " + e;
    }
    UserPrincipal me;
    try {
      me = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(user);
    } catch (IOException e) {
      return "the user name " + user + " does not resolve: " + e;
    }
    if (!attributes.owner().equals(me)) {
      return "instance.endpoint belongs to another account";
    }
    Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
    permissions.addAll(attributes.permissions());
    permissions.retainAll(OTHERS);
    if (!permissions.isEmpty()) {
      return "instance.endpoint has group or other permissions";
    }
    return null;
  }
}
