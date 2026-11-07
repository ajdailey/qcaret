package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class SafeSaveTest {
  @TempDir Path temp;

  private static ContentWriter text(final String content) {
    return new ContentWriter() {
      @Override
      public void write(OutputStream out) throws IOException {
        out.write(content.getBytes(StandardCharsets.UTF_8));
      }
    };
  }

  private boolean posix() {
    return temp.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  private List<String> names() throws IOException {
    List<String> out = new ArrayList<String>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(temp)) {
      for (Path p : files) {
        out.add(p.getFileName().toString());
      }
    }
    out.sort(null);
    return out;
  }

  @Test
  void replacesTheFileAtomicallyAndKeepsItsPermissions() throws Exception {
    Path file = temp.resolve("a.txt");
    Files.writeString(file, "old");
    if (posix()) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
    }
    FileStamp baseline = FileStamp.read(file);
    FileStamp saved = SafeSave.save(file, baseline, text("new"), new CancellationToken());
    assertEquals("new", Files.readString(file));
    assertEquals(FileStamp.read(file), saved);
    assertEquals(List.of("a.txt"), names());
    if (posix()) {
      assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
      assertNotEquals(baseline.fileKey(), saved.fileKey());
    }
  }

  @Test
  void createsANewFile() throws Exception {
    Path file = temp.resolve("new.txt");
    SafeSave.save(file, null, text("x"), new CancellationToken());
    assertEquals("x", Files.readString(file));
  }

  /** A replace of the lock file leaves the lock on the old file: the next launch takes the new. */
  @Test
  void theHeldLockFileIsNeverWritten() throws Exception {
    final Path lock = Files.createFile(temp.resolve("instance.lock"));
    final FileStamp baseline = FileStamp.read(lock);
    SafePaths.holdLock(lock, "mock refusal");
    try {
      IOException refused =
          assertThrows(
              IOException.class,
              new Executable() {
                @Override
                public void execute() throws IOException {
                  SafeSave.save(lock, baseline, text("mock"), new CancellationToken());
                }
              });
      assertEquals("mock refusal", refused.getMessage());
      assertEquals(0, Files.size(lock));
      assertEquals(List.of("instance.lock"), names());
    } finally {
      SafePaths.releaseLock(lock);
    }
  }

  @Test
  void anExternalChangeStopsTheSaveBeforeAnyWrite() throws Exception {
    final Path file = temp.resolve("a.txt");
    Files.writeString(file, "old");
    final FileStamp baseline = FileStamp.read(file);
    Files.writeString(file, "theirs, longer");
    ExternalModificationException e =
        assertThrows(
            ExternalModificationException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                SafeSave.save(file, baseline, text("mine"), new CancellationToken());
              }
            });
    assertEquals(FileStamp.read(file), e.current());
    assertEquals("theirs, longer", Files.readString(file));
    assertEquals(List.of("a.txt"), names());
  }

  @Test
  void aCancelBeforeTheMoveLeavesTheTargetAndNoTemporaryFile() throws Exception {
    final Path file = temp.resolve("a.txt");
    Files.writeString(file, "old");
    final FileStamp baseline = FileStamp.read(file);
    final CancellationToken token = new CancellationToken();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            SafeSave.save(
                file,
                baseline,
                new ContentWriter() {
                  @Override
                  public void write(OutputStream out) throws IOException {
                    out.write('x');
                    token.cancel();
                  }
                },
                token);
          }
        });
    assertEquals("old", Files.readString(file));
    assertEquals(List.of("a.txt"), names());
  }

  @Test
  void aSymbolicLinkStaysALink() throws Exception {
    Path target = temp.resolve("target.txt");
    Files.writeString(target, "old");
    Path link = temp.resolve("link.txt");
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "no symbolic links: " + e);
    }
    SafeSave.save(link, FileStamp.read(link), text("new"), new CancellationToken());
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("new", Files.readString(target));
  }

  @Test
  void aHardLinkedFileIsWrittenInPlace() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("unix"));
    Path file = temp.resolve("a.txt");
    Files.writeString(file, "old content");
    Path other = temp.resolve("b.txt");
    Files.createLink(other, file);
    FileStamp baseline = FileStamp.read(file);
    FileStamp saved = SafeSave.save(file, baseline, text("new"), new CancellationToken());
    assertEquals("new", Files.readString(other));
    assertEquals(baseline.fileKey(), saved.fileKey());
    assertFalse(names().size() > 2);
  }

  @Test
  void aFileWithoutWritePermissionIsNotReplaced() throws Exception {
    final Path file = temp.resolve("a.txt");
    Files.writeString(file, "old");
    if (posix()) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
    } else {
      Files.setAttribute(file, "dos:readonly", true);
    }
    assumeFalse(Files.isWritable(file), "the user writes every file");
    final FileStamp baseline = FileStamp.read(file);
    IOException refused =
        assertThrows(
            AccessDeniedException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                SafeSave.save(file, baseline, text("new"), new CancellationToken());
              }
            });
    assertEquals("The file is not writable", refused.getMessage());
    assertEquals("old", Files.readString(file));
    assertEquals(List.of("a.txt"), names());
  }

  /** Returns the mode of the temporary file, read while the content is written. */
  private String modeWhileWritten(Path file) throws Exception {
    final AtomicReference<Set<PosixFilePermission>> seen =
        new AtomicReference<Set<PosixFilePermission>>();
    SafeSave.save(
        file,
        null,
        new ContentWriter() {
          @Override
          public void write(OutputStream out) throws IOException {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(temp, ".*qcaret-tmp")) {
              for (Path p : files) {
                seen.set(Files.getPosixFilePermissions(p));
              }
            }
            out.write('x');
          }
        },
        new CancellationToken());
    return PosixFilePermissions.toString(seen.get());
  }

  @Test
  void theTemporaryFileIsOwnerOnlyWhileAFileIsReplaced() throws Exception {
    assumeTrue(posix());
    Path file = temp.resolve("secret.txt");
    Files.writeString(file, "old");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    assertEquals("rw-------", modeWhileWritten(file));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
    assertEquals("rw-------", modeWhileWritten(file));
    assertEquals("rw-rw-r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }

  @Test
  void aNewFileTakesTheDefaultMode() throws Exception {
    assumeTrue(posix());
    Path plain = Files.createFile(temp.resolve("plain.txt"));
    Path file = temp.resolve("new.txt");
    SafeSave.save(file, null, text("x"), new CancellationToken());
    assertEquals(Files.getPosixFilePermissions(plain), Files.getPosixFilePermissions(file));
  }

  /** Moves a file to a group of the user other than its current one; null without a second. */
  private static GroupPrincipal otherGroup(Path file) throws Exception {
    Process id = new ProcessBuilder("id", "-Gn").redirectErrorStream(true).start();
    String names = new String(id.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    id.waitFor();
    PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
    GroupPrincipal current = view.readAttributes().group();
    for (String name : names.trim().split("\\s+")) {
      try {
        GroupPrincipal group =
            file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(name);
        if (!group.equals(current)) {
          view.setGroup(group);
          return group;
        }
      } catch (IOException e) {
        // Not a group this file can take.
      }
    }
    return null;
  }

  @Test
  void anAtomicSaveKeepsTheGroup() throws Exception {
    assumeTrue(posix());
    Path file = temp.resolve("shared.txt");
    Files.writeString(file, "old");
    GroupPrincipal group = otherGroup(file);
    assumeTrue(group != null, "the user has no second group");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----"));
    FileStamp baseline = FileStamp.read(file);
    FileStamp saved = SafeSave.save(file, baseline, text("new"), new CancellationToken());
    assertNotEquals(baseline.fileKey(), saved.fileKey());
    assertEquals(
        group,
        Files.getFileAttributeView(file, PosixFileAttributeView.class).readAttributes().group());
    assertEquals("rw-rw----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }

  private Path link(String name, Path to) throws Exception {
    Path link = temp.resolve(name);
    try {
      Files.createSymbolicLink(link, to);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "no symbolic links: " + e);
    }
    return link;
  }

  @Test
  void aSaveThroughADanglingLinkKeepsTheLink() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("real"));
    Path gone = dir.resolve("gone.txt");
    Path link = link("link.txt", gone);
    SafeSave.save(link, null, text("new"), new CancellationToken());
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("new", Files.readString(gone));
  }

  @Test
  void aRelativeDanglingLinkResolvesFromItsDirectory() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("real"));
    Path link = link("link.txt", Path.of("real", "gone.txt"));
    SafeSave.save(link, null, text("new"), new CancellationToken());
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("new", Files.readString(dir.resolve("gone.txt")));
  }

  @Test
  void aChainOfLinksEndingNowhereKeepsEveryLink() throws Exception {
    Path gone = temp.resolve("gone.txt");
    Path last = link("last.txt", gone);
    Path first = link("first.txt", Path.of("last.txt"));
    SafeSave.save(first, null, text("new"), new CancellationToken());
    assertTrue(Files.isSymbolicLink(first));
    assertTrue(Files.isSymbolicLink(last));
    assertEquals("new", Files.readString(gone));
  }

  @Test
  void binaryDetection() {
    byte[] text = "plain\ttext\r\n\f".getBytes(StandardCharsets.US_ASCII);
    assertFalse(BinaryDetector.isBinary(text, text.length, false));
    byte[] nul = {'a', 0, 'b'};
    assertTrue(BinaryDetector.isBinary(nul, nul.length, false));
    assertFalse(BinaryDetector.isBinary(nul, nul.length, true));
    byte[] controls = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 0x1b, 0x1b};
    assertTrue(BinaryDetector.isBinary(controls, controls.length, false));
    assertFalse(BinaryDetector.isBinary(controls, 10, false));
  }
}
