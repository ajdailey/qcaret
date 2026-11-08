package io.github.ajdailey.qcaret.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Create, rename, count and delete over temp trees, links and a delete that fails partway. */
class FileOperationsTest {
  @TempDir Path temp;

  private static List<String> names(Path directory) throws IOException {
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        names.add(String.valueOf(entry.getFileName()));
      }
    }
    return names;
  }

  /** Makes a link, or skips the test where the user may not create one (Windows). */
  private static Path link(Path link, Path target) {
    try {
      return Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "no symbolic links: " + e);
      return null;
    }
  }

  @Test
  void createMakesAFileOrAFolderAndRefusesATakenName() throws Exception {
    final Path dir = Files.createDirectory(temp.resolve("ws"));
    Path file = FileOperations.create(dir, "a.txt", false);
    assertTrue(Files.isRegularFile(file));
    assertEquals(0, Files.size(file));
    assertTrue(Files.isDirectory(FileOperations.create(dir, "sub", true)));
    assertThrows(
        FileAlreadyExistsException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            FileOperations.create(dir, "a.txt", true);
          }
        });
  }

  @Test
  void renameRefusesATakenNameAndHandlesACaseOnlyChange() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("ws"));
    final Path a = Files.writeString(dir.resolve("a.txt"), "a");
    Files.writeString(dir.resolve("b.txt"), "b");
    assertThrows(
        FileAlreadyExistsException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            FileOperations.rename(a, "b.txt");
          }
        });
    assertEquals("a", Files.readString(a));

    Path upper = FileOperations.rename(a, "A.txt");
    assertEquals(dir.resolve("A.txt"), upper);
    assertTrue(names(dir).contains("A.txt"), names(dir).toString());
    assertFalse(names(dir).contains("a.txt"), names(dir).toString());
    assertEquals("a", Files.readString(upper));
    assertEquals(2, names(dir).size(), "no temporary name left: " + names(dir));

    Path folder = Files.createDirectory(dir.resolve("src"));
    Files.writeString(folder.resolve("in.txt"), "in");
    Path moved = FileOperations.rename(folder, "lib");
    assertEquals("in", Files.readString(moved.resolve("in.txt")));
    assertFalse(Files.exists(folder));
  }

  @Test
  void aNameTakenByAnotherEntryInOtherCaseIsRefusedWhereCaseMatters() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("ws"));
    final Path lower = Files.writeString(dir.resolve("c.txt"), "lower");
    assumeFalse(Files.exists(dir.resolve("C.txt")), "case-insensitive file system");
    final Path upper = Files.writeString(dir.resolve("C.txt"), "upper");
    Path other = Files.writeString(dir.resolve("d.txt"), "d");
    assertThrows(
        FileAlreadyExistsException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            FileOperations.rename(lower, "C.txt");
          }
        });
    assertEquals("upper", Files.readString(upper));
    assertEquals(dir.resolve("D.txt"), FileOperations.rename(other, "D.txt"));
  }

  @Test
  void deleteRemovesATreeAndCountsItFirst() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("ws").resolve("tree").resolve("deep"));
    Files.writeString(tree.resolve("one.txt"), "1");
    Files.writeString(tree.getParent().resolve("two.txt"), "2");
    Files.createDirectory(tree.getParent().resolve("empty"));
    Path top = tree.getParent();
    assertEquals(
        new FileOperations.Counts(2, 2), FileOperations.count(top, new CancellationToken()));
    FileOperations.Deleted deleted = FileOperations.delete(top, new CancellationToken());
    assertTrue(deleted.done(), String.valueOf(deleted));
    assertFalse(Files.exists(top));
    assertTrue(Files.isDirectory(temp.resolve("ws")));
  }

  @Test
  void deleteRemovesALinkToAnOutsideDirectoryAndNotItsTarget() throws Exception {
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Path kept = Files.writeString(outside.resolve("kept.txt"), "kept");
    Path ws = Files.createDirectory(temp.resolve("ws"));
    Path folder = Files.createDirectory(ws.resolve("folder"));
    Path inner = link(folder.resolve("inner-link"), outside);
    Path top = link(ws.resolve("top-link"), outside);

    assertFalse(FileOperations.plainDirectory(top));
    assertEquals(
        new FileOperations.Counts(1, 0), FileOperations.count(folder, new CancellationToken()));
    assertTrue(FileOperations.delete(top, new CancellationToken()).done());
    assertFalse(Files.exists(top, LinkOption.NOFOLLOW_LINKS));
    assertTrue(FileOperations.delete(folder, new CancellationToken()).done());
    assertFalse(Files.exists(inner, LinkOption.NOFOLLOW_LINKS));
    assertFalse(Files.exists(folder));
    assertEquals("kept", Files.readString(kept));
  }

  @Test
  void aFailingDeleteStopsAndReportsWhatRemains() throws Exception {
    Path top = Files.createDirectories(temp.resolve("ws").resolve("top"));
    Path locked = Files.createDirectory(top.resolve("locked"));
    Path held = Files.writeString(locked.resolve("held.txt"), "held");
    Files.writeString(locked.resolve("other.txt"), "other");
    if (File.separatorChar == '\\') {
      try (InputStream open = new FileInputStream(held.toFile())) {
        assertNotNull(open);
        checkStopped(top, held);
      }
      return;
    }
    assumeTrue(
        Files.getFileStore(locked).supportsFileAttributeView("posix"), "POSIX permissions only");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeFalse(Files.isWritable(locked), "the user writes every directory");
      checkStopped(top, null);
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  private static void checkStopped(Path top, Path failed) throws IOException {
    FileOperations.Deleted deleted = FileOperations.delete(top, new CancellationToken());
    assertFalse(deleted.done());
    assertNotNull(deleted.error());
    if (failed != null) {
      assertEquals(failed, deleted.failed());
    } else {
      assertTrue(deleted.failed().startsWith(top.resolve("locked")), deleted.toString());
    }
    assertTrue(Files.isDirectory(top));
    assertTrue(deleted.remaining().folders() >= 1, deleted.toString());
    assertTrue(deleted.remaining().files() >= 1, deleted.toString());
  }

  @Test
  void deletingAMissingEntryIsDone() {
    FileOperations.Deleted deleted =
        FileOperations.delete(temp.resolve("gone"), new CancellationToken());
    assertTrue(deleted.done());
    assertNull(deleted.error());
  }

  @Test
  void anIndexTakesOneFileOutAndPutsOneIn() throws Exception {
    Path root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    Files.writeString(root.resolve("a.txt"), "a");
    Files.createDirectory(root.resolve("src"));
    Files.writeString(root.resolve("src").resolve("b.txt"), "b");
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    FileIndex index =
        FileIndex.build(
            root, WalkRules.of(""), FileIndex.MAX_FILES, budget, new CancellationToken());
    FileIndex next =
        index.withChange(root.resolve("a.txt"), root.resolve("src").resolve("c.txt"), budget);
    assertNotNull(next);
    List<String> paths = new ArrayList<String>();
    for (int i = 0; i < next.size(); i++) {
      paths.add(next.path(i));
    }
    Collections.sort(paths);
    assertEquals(List.of("src/b.txt", "src/c.txt"), paths);
    assertEquals(index.charged() + next.charged(), budget.reserved());
    assertNull(index.withChange(null, temp.resolve("outside.txt"), budget));
  }
}
