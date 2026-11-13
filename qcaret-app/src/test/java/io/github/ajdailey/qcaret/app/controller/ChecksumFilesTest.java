package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checksum list files: Save As writes through {@code SafeSave} with its holds, Generate from Files
 * names files relative to the folder Save As offers, and Verify tells a missing file from one it
 * cannot reach.
 */
class ChecksumFilesTest {
  private static final String ABC_SHA256 =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @TempDir Path temp;

  private Harness harness;
  private Path dir;

  @BeforeEach
  void create() throws IOException {
    Files.createDirectory(temp.resolve("settings"));
    harness = new Harness(temp);
    dir = Files.createDirectory(temp.resolve("work")).toRealPath();
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private FakeView.Checksums finished() throws InterruptedException {
    final FakeView.Checksums shown = harness.view.checksums;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.status != null;
          }
        },
        "finished");
    return shown;
  }

  /** Hashes one file holding {@code abc}; returns the dialog. */
  private FakeView.Checksums generated() throws Exception {
    Path file = Files.writeString(dir.resolve("abc.txt"), "abc", StandardCharsets.US_ASCII);
    harness.application.execute(CommandId.SHA256_FILES);
    harness.view.openChooser.accept(List.of(file));
    return finished();
  }

  /** Runs Save As onto {@code target} and waits for {@code done}. */
  private void saveAs(FakeView.Checksums shown, Path target, BooleanSupplier done)
      throws InterruptedException {
    harness.view.saveChooser = null;
    shown.input.saveAs();
    assertNotNull(harness.view.saveChooser);
    harness.view.saveChooser.accept(target);
    harness.await(done, "saved");
    harness.awaitIdle("save");
  }

  private BooleanSupplier notice(final String text) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return harness.view.notices.contains(text);
      }
    };
  }

  /** Save As replaces the target with a new file; a reader of the old one sees it whole. */
  @Test
  void saveAsReplacesTheTarget() throws Exception {
    FakeView.Checksums shown = generated();
    final Path target = Files.writeString(dir.resolve("SHA256SUMS"), "old\n");
    Object before = Files.readAttributes(target, BasicFileAttributes.class).fileKey();
    assumeTrue(before != null);
    final String expected = ABC_SHA256 + "  abc.txt\n";
    saveAs(
        shown,
        target,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return Files.readString(target).equals(expected);
            } catch (IOException e) {
              return false;
            }
          }
        });
    assertNotEquals(before, Files.readAttributes(target, BasicFileAttributes.class).fileKey());
  }

  @Test
  void saveAsOntoAHeldLockFileIsRefused() throws Exception {
    FakeView.Checksums shown = generated();
    Path target = Files.writeString(dir.resolve("held.lock"), "lock\n");
    SafePaths.holdLock(target, "held by this process");
    try {
      saveAs(shown, target, notice(UiText.CHECKSUM_SAVE_FAILED.format("held by this process")));
    } finally {
      SafePaths.releaseLock(target);
    }
    assertEquals("lock\n", Files.readString(target));
  }

  @Test
  void saveAsIntoAFolderARenameHoldsIsRefused() throws Exception {
    FakeView.Checksums shown = generated();
    Path folder = Files.createDirectory(dir.resolve("held"));
    BrowserPath entry = new BrowserPath(folder, folder.toRealPath());
    DocumentsUnder under = harness.application.documents().under();
    Path target = folder.resolve("SHA256SUMS");
    under.hold(entry);
    try {
      saveAs(shown, target, notice(UiText.CHECKSUM_SAVE_RENAMING.text()));
    } finally {
      under.release(entry);
    }
    assertFalse(Files.exists(target));
  }

  /** Files from two folders take names below their common folder, so the saved list verifies. */
  @Test
  void filesFromSeveralFoldersVerifyFromTheOfferedFolder() throws Exception {
    Path a = Files.createDirectory(dir.resolve("a")).resolve("x.txt");
    Path b = Files.createDirectory(dir.resolve("b")).resolve("y.txt");
    Files.writeString(a, "abc", StandardCharsets.US_ASCII);
    Files.writeString(b, "abc", StandardCharsets.US_ASCII);
    harness.application.execute(CommandId.SHA256_FILES);
    harness.view.openChooser.accept(List.of(a, b));
    FakeView.Checksums shown = finished();
    assertEquals(List.of(ABC_SHA256 + "  a/x.txt", ABC_SHA256 + "  b/y.txt"), shown.lines);
    final Path saved = dir.resolve("SHA256SUMS");
    saveAs(
        shown,
        saved,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Files.exists(saved);
          }
        });
    assertEquals(dir, harness.view.saveDirectory);
    harness.application.execute(CommandId.VERIFY_CHECKSUMS);
    FakeView.Checksums verified = harness.view.checksums;
    verified.status = null;
    verified.input.choose();
    harness.view.openChooser.accept(List.of(saved));
    finished();
    assertEquals(List.of("a/x.txt: OK", "b/y.txt: OK"), verified.lines);
  }

  /** A folder without search permission hides whether the file exists: unreadable, not MISSING. */
  @Test
  void aFileUnderAFolderWithoutSearchPermissionIsUnreadable() throws Exception {
    assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Path locked = Files.createDirectory(dir.resolve("locked"));
    Files.writeString(locked.resolve("x.txt"), "abc", StandardCharsets.US_ASCII);
    Path list = dir.resolve("SHA256SUMS");
    Files.writeString(
        list,
        ABC_SHA256 + "  locked/x.txt\n" + ABC_SHA256 + "  gone.txt\n",
        StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      // Root searches any folder.
      assumeFalse(Files.isExecutable(locked));
      harness.application.execute(CommandId.VERIFY_CHECKSUMS);
      FakeView.Checksums shown = harness.view.checksums;
      shown.status = null;
      shown.input.choose();
      harness.view.openChooser.accept(List.of(list));
      finished();
      assertEquals(
          List.of(
              UiText.CHECKSUM_UNREADABLE.format("locked/x.txt", "access denied"),
              UiText.CHECKSUM_MISSING.format("gone.txt")),
          shown.lines);
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
