package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;

/** Looks up user accounts for owner and ACL checks. */
public final class Accounts {
  private Accounts() {}

  /**
   * Looks up a user by name, qualified with {@code USERDOMAIN} first where that variable is set: a
   * bare name resolves to a local account before a domain account of that name.
   *
   * @param system the file system whose lookup service resolves the name
   * @param name the account name, as in {@code user.name}
   * @return the user
   * @throws IOException when neither form resolves, as under a service account or with a domain
   *     controller out of reach
   */
  public static UserPrincipal user(FileSystem system, String name) throws IOException {
    UserPrincipalLookupService lookup = system.getUserPrincipalLookupService();
    String domain = System.getenv("USERDOMAIN");
    if (domain != null) {
      try {
        return lookup.lookupPrincipalByName(domain + "\\" + name);
      } catch (IOException e) {
        // The bare name below still applies.
      }
    }
    return lookup.lookupPrincipalByName(name);
  }
}
