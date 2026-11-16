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
import java.util.List;
import java.util.Set;

/**
 * Checks {@code instance.endpoint} on a file system with ACLs and no POSIX permissions (Windows).
 * The file must be owned by the user, and every ACL entry must be for a trusted account. Trusted
 * are the user, and the owner of the user's profile directory and every account its allow entries
 * give a right in {@link #WRITE}: on a default profile {@code SYSTEM} and {@code Administrators},
 * whose names Windows localizes, so they are not looked up by name. The caller passes the profile
 * from {@code USERPROFILE}, not {@code user.home}, which a launcher or test can set to any
 * directory.
 */
final class AclCheck {
  /** Rights that make an account a writer of the profile directory. */
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
   * Checks the endpoint file.
   *
   * @param file the endpoint file
   * @param user the current user's name
   * @param profile the user's profile directory
   * @return null when the handoff may read the file, else the reason, naming the step that failed
   *     and its exception
   */
  static String refusal(Path file, String user, Path profile) {
    Set<UserPrincipal> trusted;
    try {
      AclFileAttributeView view = Files.getFileAttributeView(profile, AclFileAttributeView.class);
      trusted = writers(view);
      trusted.add(view.getOwner());
    } catch (IOException e) {
      return "the ACL of the profile directory " + profile + " cannot be read: " + e;
    }
    UserPrincipal me;
    try {
      me = Accounts.user(file.getFileSystem(), user);
    } catch (IOException e) {
      return "the user name " + user + " does not resolve: " + e;
    }
    trusted.add(me);
    UserPrincipal owner;
    List<AclEntry> acl;
    try {
      AclFileAttributeView view =
          Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      owner = view.getOwner();
      acl = view.getAcl();
    } catch (IOException e) {
      return "the ACL of instance.endpoint cannot be read: " + e;
    }
    if (!owner.equals(me)) {
      return "instance.endpoint belongs to another account";
    }
    for (AclEntry entry : acl) {
      if (!trusted.contains(entry.principal())) {
        return "instance.endpoint has an ACL entry for another account";
      }
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
