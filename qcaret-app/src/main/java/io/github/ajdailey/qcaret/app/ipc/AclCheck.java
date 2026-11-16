package io.github.ajdailey.qcaret.app.ipc;

import io.github.ajdailey.qcaret.core.io.Accounts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Checks a settings directory on a file system with ACLs and no POSIX permissions (Windows). The
 * directory must be owned by a trusted account, and no allow entry that applies to it may give an
 * untrusted account a right in {@link #WRITE}. Trusted are the user, and the owner of the user's
 * profile directory and every account its allow entries give such a right: on a default profile
 * {@code SYSTEM} and {@code Administrators}, whose names Windows localizes, so they are not looked
 * up by name. The caller passes the profile from {@code USERPROFILE}, not {@code user.home}, which
 * a launcher or test can set to any directory.
 */
final class AclCheck {
  /** Rights that let an account replace, remove or re-permit the endpoint file. */
  static final Set<AclEntryPermission> WRITE =
      EnumSet.of(
          AclEntryPermission.WRITE_DATA,
          AclEntryPermission.APPEND_DATA,
          AclEntryPermission.DELETE,
          AclEntryPermission.DELETE_CHILD,
          AclEntryPermission.WRITE_ACL,
          AclEntryPermission.WRITE_OWNER);

  private AclCheck() {}

  /**
   * Checks the directory.
   *
   * @param directory the settings directory
   * @param user the current user's name
   * @param profile the user's profile directory
   * @return null when the handoff may use the directory, else the reason, naming the step that
   *     failed and its exception
   */
  static String refusal(Path directory, String user, Path profile) {
    Set<UserPrincipal> trusted;
    try {
      AclFileAttributeView view = Files.getFileAttributeView(profile, AclFileAttributeView.class);
      trusted = writers(view);
      trusted.add(view.getOwner());
    } catch (IOException e) {
      return "the ACL of the profile directory " + profile + " cannot be read: " + e;
    }
    try {
      trusted.add(Accounts.user(directory.getFileSystem(), user));
    } catch (IOException e) {
      return "the user name " + user + " does not resolve: " + e;
    }
    UserPrincipal owner;
    Set<UserPrincipal> writers;
    try {
      AclFileAttributeView view =
          Files.getFileAttributeView(
              directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      owner = view.getOwner();
      writers = writers(view);
    } catch (IOException e) {
      return "the ACL of the settings directory cannot be read: " + e;
    }
    if (!trusted.contains(owner)) {
      return "the settings directory belongs to another user";
    }
    if (!trusted.containsAll(writers)) {
      return "the settings directory is writable by other users";
    }
    return null;
  }

  /** Accounts an allow entry that applies to the object itself gives a right in {@link #WRITE}. */
  private static Set<UserPrincipal> writers(AclFileAttributeView view) throws IOException {
    Set<UserPrincipal> writers = new HashSet<UserPrincipal>();
    for (AclEntry entry : view.getAcl()) {
      if (entry.type() == AclEntryType.ALLOW
          && !entry.flags().contains(AclEntryFlag.INHERIT_ONLY)
          && !Collections.disjoint(entry.permissions(), WRITE)) {
        writers.add(entry.principal());
      }
    }
    return writers;
  }
}
