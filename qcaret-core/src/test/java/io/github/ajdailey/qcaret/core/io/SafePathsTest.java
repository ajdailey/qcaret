package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class SafePathsTest {
  @TempDir Path temp;

  @AfterEach
  void release() {
    SafePaths.releaseLock(temp.resolve("instance.lock"));
    SafePaths.releaseLock(temp.resolve("owner.lock"));
  }

  @Test
  void rejectsTheHeldLockFileUntilItIsReleased() throws Exception {
    final Path lock = Files.createFile(temp.resolve("instance.lock"));
    SafePaths.holdLock(lock, "mock refusal");
    IOException refused =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws IOException {
                SafePaths.userFile(lock);
              }
            });
    assertEquals("mock refusal", refused.getMessage());
    SafePaths.releaseLock(lock);
    assertEquals(lock.toRealPath(), SafePaths.userFile(lock));
  }

  @Test
  void holdsEachLockUntilItsOwnRelease() throws Exception {
    final Path instance = Files.createFile(temp.resolve("instance.lock"));
    final Path owner = Files.createFile(temp.resolve("owner.lock"));
    SafePaths.holdLock(instance, "mock instance");
    SafePaths.holdLock(owner, "mock owner");
    IOException refused =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws IOException {
                SafePaths.userFile(owner);
              }
            });
    assertEquals("mock owner", refused.getMessage());
    SafePaths.releaseLock(instance);
    assertEquals(instance.toRealPath(), SafePaths.userFile(instance));
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            SafePaths.userFile(owner);
          }
        });
  }

  @Test
  void rejectsALinkToTheHeldLockFile() throws Exception {
    Path lock = Files.createFile(temp.resolve("instance.lock"));
    assumeTrue(Files.readAttributes(lock, BasicFileAttributes.class).fileKey() != null);
    final Path hard = Files.createLink(temp.resolve("hard"), lock);
    final Path soft = Files.createSymbolicLink(temp.resolve("soft"), lock);
    SafePaths.holdLock(lock, "mock refusal");
    for (final Path link : List.of(hard, soft)) {
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws IOException {
              SafePaths.userFile(link);
            }
          });
    }
  }

  @Test
  void acceptsOtherFilesWhileALockIsHeld() throws Exception {
    Path other = Files.createFile(temp.resolve("mock.txt"));
    SafePaths.holdLock(Files.createFile(temp.resolve("instance.lock")), "mock refusal");
    assertEquals(other.toRealPath(), SafePaths.userFile(other));
  }

  private static void rejects(final String path) {
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            SafePaths.userFile(path);
          }
        },
        path);
  }

  @Test
  void existingFileResolvesToRealPath() throws Exception {
    Path file = Files.createFile(temp.resolve("notes.txt"));
    Path dotted = temp.resolve("sub").resolve("..").resolve("notes.txt");
    assertEquals(file.toRealPath(), SafePaths.userFile(dotted.toString()));
  }

  @Test
  void missingFileNeedsParentDirectory() throws Exception {
    assertEquals(
        temp.toRealPath().resolve("later.txt"),
        SafePaths.userFile(temp.resolve("later.txt").toString()));
    rejects(temp.resolve("nodir").resolve("x.txt").toString());
  }

  @Test
  void rejectsDirectoryNulAndEmpty() {
    rejects(temp.toString());
    rejects("a\0b");
    rejects("");
  }

  @Test
  void rejectsFifo() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    Path fifo = temp.resolve("pipe");
    Process mkfifo;
    try {
      mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start();
    } catch (IOException e) {
      assumeTrue(false, "no mkfifo");
      return;
    }
    assumeTrue(mkfifo.waitFor() == 0 && Files.exists(fifo), "mkfifo failed");
    rejects(fifo.toString());
  }

  @Test
  void rejectsDevice() {
    Path device = Path.of("/dev/null");
    assumeTrue(Files.exists(device), "no /dev/null");
    rejects(device.toString());
  }

  @Test
  void windowsDeviceForms() {
    for (String path :
        List.of(
            "CON",
            "nul.txt",
            "C:\\logs\\COM1",
            "\\\\.\\PhysicalDrive0",
            "\\\\?\\GLOBALROOT\\x",
            "lpt9.txt")) {
      assertTrue(SafePaths.windowsDevice(path), path);
    }
    for (String path : List.of("C:\\docs\\console.txt", "context", "COM10", "notes.txt")) {
      assertFalse(SafePaths.windowsDevice(path), path);
    }
  }

  @Test
  void settingsDirectoryIsOwnerOnly() throws Exception {
    Path dir = SafePaths.settingsDirectory(temp.resolve("a").resolve("settings"));
    assertTrue(Files.isDirectory(dir));
    assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
  }

  @Test
  void childStaysInside() throws Exception {
    assertEquals(temp.resolve("qcaret.conf"), SafePaths.child(temp, "qcaret.conf"));
    for (final String name : List.of("..", "../x", "a/b", "", ".")) {
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              SafePaths.child(temp, name);
            }
          },
          name);
    }
  }
}
