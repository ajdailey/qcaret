package io.github.ajdailey.qcaret.app.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** {@link EndpointCheck}: which {@code instance.endpoint} files the handoff reads. */
class EndpointCheckTest {
  private static final String USER = System.getProperty("user.name");
  private static final String TEXT =
      "version=1\nport=1\ntoken=" + "00".repeat(Protocol.SECRET_BYTES) + "\n";

  @TempDir Path temp;

  private Path endpoint() {
    return temp.resolve(Endpoint.FILE);
  }

  private Path writeOwnerOnly() throws IOException {
    AtomicFiles.writeOwnerOnly(endpoint(), TEXT.getBytes(StandardCharsets.US_ASCII));
    return endpoint();
  }

  private boolean posix() {
    return temp.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /** Asserts the reason, and that the read throws {@link EndpointCheck.Refused} with it. */
  private void assertRefused(String reason) throws IOException {
    assertEquals(reason, EndpointCheck.refusal(endpoint(), USER));
    EndpointCheck.Refused refused =
        assertThrows(
            EndpointCheck.Refused.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                Endpoint.read(temp);
              }
            });
    assertEquals(reason, refused.getReason());
    assertNull(InstanceClient.endpointId(temp));
  }

  @Test
  void anOwnerOnlyEndpointIsRead() throws Exception {
    writeOwnerOnly();
    assertNull(EndpointCheck.refusal(endpoint(), USER));
    assertEquals(1, Endpoint.read(temp).port());
  }

  @Test
  void eachGroupOrOtherPermissionIsRefused() throws Exception {
    assumeTrue(posix(), "no POSIX permissions");
    Path file = writeOwnerOnly();
    for (PosixFilePermission extra :
        EnumSet.complementOf(
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE))) {
      Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
      permissions.add(extra);
      Files.setPosixFilePermissions(file, permissions);
      assertRefused("instance.endpoint has group or other permissions");
    }
  }

  @Test
  void anEndpointOfAnotherAccountIsRefused() throws Exception {
    assumeTrue(posix(), "no POSIX permissions");
    writeOwnerOnly();
    assertEquals(
        "instance.endpoint belongs to another account",
        EndpointCheck.refusal(endpoint(), "root".equals(USER) ? "daemon" : "root"));
    assertNotNull(EndpointCheck.refusal(endpoint(), "qcaret-mock-nobody"));
  }

  @Test
  void aLinkIsRefusedEvenToAnOwnerOnlyFile() throws Exception {
    Path target = temp.resolve("target");
    AtomicFiles.writeOwnerOnly(target, TEXT.getBytes(StandardCharsets.US_ASCII));
    try {
      Files.createSymbolicLink(endpoint(), target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "no symbolic links: " + e);
    }
    assertRefused("instance.endpoint is a symbolic link");
  }

  @Test
  void aDirectoryIsRefused() throws Exception {
    Files.createDirectory(endpoint());
    assertRefused("instance.endpoint is not a regular file");
  }

  /** A launch against a live primary whose endpoint is refused names the reason in its failure. */
  @Test
  void aRefusedEndpointFailsTheHandoffWithTheReason() throws Exception {
    assumeTrue(posix(), "no POSIX permissions");
    try (InstanceServer held = InstanceServer.claim(temp)) {
      assertNotNull(held);
      Files.setPosixFilePermissions(endpoint(), PosixFilePermissions.fromString("rw-r-----"));
      final StartupRequest request =
          new StartupRequest(
              List.of(new FileRequest(temp.resolve("a.log").toAbsolutePath())),
              null,
              null,
              null,
              null,
              null,
              false);
      IOException failure =
          assertThrows(
              IOException.class,
              new Executable() {
                @Override
                public void execute() throws Throwable {
                  InstanceClient.handOff(temp, request);
                }
              });
      assertEquals(
          "No trusted endpoint file: instance.endpoint has group or other permissions",
          failure.getMessage());
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aWindowsEntryForAnotherAccountIsRefused() throws Exception {
    Path file = writeOwnerOnly();
    UserPrincipal users = group("Authenticated Users");
    AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    List<AclEntry> acl = new ArrayList<AclEntry>(view.getAcl());
    acl.add(
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(users)
            .setPermissions(AclEntryPermission.READ_DATA)
            .build());
    view.setAcl(acl);
    assertRefused("instance.endpoint has an ACL entry for another account");
  }

  /**
   * An elevated process can make {@code Administrators} the owner, as Windows does for a file it
   * creates; the check takes only the user. Skipped where the process cannot set that owner.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aWindowsEndpointOwnedByAdministratorsIsRefused() throws Exception {
    Path file = writeOwnerOnly();
    UserPrincipal administrators = group("Administrators");
    AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    try {
      view.setOwner(administrators);
    } catch (IOException e) {
      assumeTrue(false, "cannot make Administrators the owner: " + e);
    }
    assertTrue(view.getOwner().equals(administrators));
    assertRefused("instance.endpoint belongs to another account");
  }

  private UserPrincipal group(String name) {
    try {
      return temp.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(name);
    } catch (IOException e) {
      assumeTrue(false, "no group named " + name + ": " + e);
      return null;
    }
  }
}
