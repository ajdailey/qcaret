package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.recovery.RecoveryFile;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.recovery.RecoveryTexts;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.view.RecoveryRow;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.LoadReservation;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recovery lifecycle against real files: writes, deletes, a process end that keeps the files,
 * the dialog's choices and the snapshot quit (D6). A harness stands in for a dead instance by
 * quitting through the shutdown path with a dirty buffer.
 */
class RecoveryControllerTest {
  @TempDir Path temp;

  private Path settings;
  private final List<Harness> open = new ArrayList<Harness>();

  @BeforeEach
  void create() throws IOException {
    settings = Files.createDirectories(temp.resolve("settings"));
  }

  @AfterEach
  void close() throws InterruptedException {
    for (Harness harness : open) {
      if (!harness.view.disposed) {
        harness.close();
      }
    }
  }

  private Harness start(boolean restore) throws InterruptedException {
    final Harness harness = new Harness(temp);
    open.add(harness);
    SessionStore store = new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    harness.application.startSession(store, store.load(), restore);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.recovery().idle()
                && (!restore || !harness.view.tabs.isEmpty());
          }
        },
        "started");
    harness.awaitIdle("start");
    return harness;
  }

  private Path file(String name, String text) throws IOException {
    return Files.writeString(temp.resolve(name), text).toRealPath();
  }

  private List<Path> recoveryFiles() throws IOException {
    List<Path> found = new ArrayList<Path>();
    Path root = settings.resolve(RecoveryStore.DIRECTORY);
    if (!Files.isDirectory(root)) {
      return found;
    }
    try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
      for (Path directory : directories) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.qcr")) {
          for (Path file : files) {
            found.add(file);
          }
        }
      }
    }
    return found;
  }

  private void awaitFiles(Harness harness, final int count) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return recoveryFiles().size() == count;
            } catch (IOException e) {
              return false;
            }
          }
        },
        count + " recovery files");
  }

  private static void awaitIdle(final Harness harness) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.recovery().idle();
          }
        },
        "the recovery queue ran");
  }

  private static void edit(Harness harness, String text) {
    harness.application.documents().activeEditor().type(text);
  }

  /** Ends a harness the way the shutdown hook does: no prompt, the buffers still open. */
  private static void end(Harness harness) throws InterruptedException {
    harness.close();
  }

  @Test
  void aDirtyBufferIsWrittenAndASaveDeletesIt() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness harness = start(false);
    harness.open(a);
    edit(harness, "mock ");
    harness.ui.runLongTimers();
    awaitFiles(harness, 1);
    RecoveryFile.Loaded loaded =
        RecoveryFile.load(recoveryFiles().get(0), LoadReservation.UNMETERED);
    assertEquals("mock alpha\n", RecoveryTexts.of(loaded));
    assertEquals(a.toString(), loaded.header().path());
    harness.application.documents().save();
    awaitFiles(harness, 0);
    assertEquals("mock alpha\n", Files.readString(a));
  }

  @Test
  void anUnchangedRevisionIsNotWrittenAgain() throws Exception {
    Harness harness = start(false);
    harness.application.documents().newDocument();
    harness.ui.runPending();
    edit(harness, "mock");
    harness.ui.runLongTimers();
    awaitFiles(harness, 1);
    long first = Files.getLastModifiedTime(recoveryFiles().get(0)).toMillis();
    Files.setLastModifiedTime(
        recoveryFiles().get(0), java.nio.file.attribute.FileTime.fromMillis(first - 5000));
    harness.ui.runLongTimers();
    awaitIdle(harness);
    assertEquals(first - 5000, Files.getLastModifiedTime(recoveryFiles().get(0)).toMillis());
  }

  @Test
  void anUndoBackToTheSavedTextDeletesTheFile() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness harness = start(false);
    harness.open(a);
    edit(harness, "x");
    harness.ui.runLongTimers();
    awaitFiles(harness, 1);
    harness.application.documents().activeEditor().undo();
    harness.ui.runLongTimers();
    awaitFiles(harness, 0);
  }

  @Test
  void closingWithDiscardDeletesTheFile() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness harness = start(false);
    harness.open(a);
    edit(harness, "x");
    harness.ui.runLongTimers();
    awaitFiles(harness, 1);
    harness.application.documents().closeActive();
    harness.view.unsaved.accept(UnsavedChoice.DISCARD);
    awaitFiles(harness, 0);
  }

  @Test
  void anOrderedQuitDeletesTheInstanceDirectory() throws Exception {
    Harness harness = start(false);
    harness.application.documents().newDocument();
    harness.ui.runPending();
    harness.application.requestQuit();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.disposed;
          }
        },
        "quit");
    harness.workers.close();
    try (DirectoryStream<Path> left =
        Files.newDirectoryStream(settings.resolve(RecoveryStore.DIRECTORY))) {
      assertFalse(left.iterator().hasNext());
    }
  }

  @Test
  void aQuitWithoutAskingKeepsTheBufferAndTheNextStartOffersIt() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness first = start(false);
    first.open(a);
    edit(first, "mock ");
    end(first);
    assertEquals(1, recoveryFiles().size());
    assertEquals("alpha\n", Files.readString(a));

    Harness second = start(false);
    second.await(offered(second), "the dialog");
    List<RecoveryRow> rows = second.view.recovery.rows();
    assertEquals(1, rows.size());
    assertEquals(a.toString(), rows.get(0).name());
    assertEquals(UiText.RECOVERY_UNCHANGED.text(), rows.get(0).state());
    second.view.recovery.restore(new int[] {0});
    second.await(rowsLeft(second, 0), "restored");
    second.awaitIdle("restore");
    awaitFiles(second, 1);
    Document document = second.application.documents().activeDocument();
    assertEquals("mock alpha\n", document.text().toString());
    assertTrue(document.isDirty());
    assertEquals(a, second.application.documents().activePath());
    assertEquals("alpha\n", Files.readString(a), "a restore never writes the original");

    second.application.documents().save();
    awaitFiles(second, 0);
    assertEquals("mock alpha\n", Files.readString(a));
  }

  private static BooleanSupplier offered(final Harness harness) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return harness.view.recovery != null;
      }
    };
  }

  private static BooleanSupplier rowsLeft(final Harness harness, final int count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return harness.view.recovery.rows().size() == count;
      }
    };
  }

  @Test
  void anUntitledBufferReturnsWithItsTitleAndAChangedOriginalShowsThePrompt() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness first = start(false);
    first.application.documents().newDocument();
    first.ui.runPending();
    edit(first, "mock draft");
    first.open(a);
    edit(first, "mine ");
    end(first);
    Files.writeString(a, "changed by another program\n");

    Harness second = start(false);
    second.await(offered(second), "the dialog");
    List<RecoveryRow> rows = second.view.recovery.rows();
    assertEquals(2, rows.size());
    int file = rows.get(0).name().equals(a.toString()) ? 0 : 1;
    assertEquals(UiText.RECOVERY_CHANGED.text(), rows.get(file).state());
    assertEquals(UiText.RECOVERY_NO_FILE.text(), rows.get(1 - file).state());
    second.view.recovery.restore(new int[] {0, 1});
    second.await(rowsLeft(second, 0), "restored");
    second.awaitIdle("restore");
    assertTrue(second.application.documents().labels().contains("Untitled 1"));
    second.application.documents().checkFiles();
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !second.view.changePrompts.isEmpty();
          }
        },
        "the change prompt");
    assertEquals("changed by another program\n", Files.readString(a));
  }

  @Test
  void aDamagedFileIsReportedAndKeptAndDiscardDeletesTheOthers() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Path b = file("b.txt", "beta\n");
    Harness first = start(false);
    first.open(a);
    edit(first, "x");
    first.open(b);
    edit(first, "y");
    end(first);
    List<Path> files = recoveryFiles();
    assertEquals(2, files.size());
    Path damaged = files.get(0);
    byte[] bytes = Files.readAllBytes(damaged);
    bytes[bytes.length - 75] ^= 1;
    Files.write(damaged, bytes);

    Harness second = start(false);
    second.await(offered(second), "the dialog");
    List<RecoveryRow> rows = second.view.recovery.rows();
    int bad = rows.get(0).damaged() ? 0 : 1;
    assertTrue(rows.get(bad).damaged());
    assertEquals(UiText.RECOVERY_DAMAGED.text(), rows.get(bad).state());
    second.view.recovery.restore(new int[] {bad});
    assertEquals(
        UiText.RECOVERY_DAMAGED_KEPT.text(),
        second.view.notices.get(second.view.notices.size() - 1));
    assertTrue(Files.exists(damaged));
    second.view.recovery.discard(new int[] {1 - bad});
    assertEquals(1, second.view.recovery.rows().size());
    awaitFiles(second, 1);
    second.view.recovery.later();
    assertEquals(List.of(), second.view.recovery.rows());
    awaitIdle(second);
    assertTrue(Files.exists(damaged), "Later keeps the file");
  }

  @Test
  void aCleanTabGivesWayToItsRecoveredBuffer() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness first = start(false);
    first.open(a);
    edit(first, "mine ");
    end(first);

    Harness second = start(false);
    second.open(a);
    second.await(offered(second), "the dialog");
    second.view.recovery.restore(new int[] {0});
    second.await(rowsLeft(second, 0), "restored");
    assertEquals(1, second.application.documents().count());
    assertEquals("mine alpha\n", second.application.documents().activeDocument().text().toString());
  }

  @Test
  void aDirtyTabKeepsItsTextAndTheRow() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness first = start(false);
    first.open(a);
    edit(first, "mine ");
    end(first);

    Harness second = start(false);
    second.open(a);
    edit(second, "other ");
    second.await(offered(second), "the dialog");
    second.view.recovery.restore(new int[] {0});
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.view.notices.contains(UiText.RECOVERY_TAB_DIRTY.format(a));
          }
        },
        "the notice");
    assertEquals(1, second.view.recovery.rows().size());
    assertEquals(
        "other alpha\n", second.application.documents().activeDocument().text().toString());
    second.view.recovery.later();
    assertNotNull(second.view.recovery);
  }

  /** Returns the instance directory other than {@code other}, the running harness's own. */
  private Path ownDirectory(Path other) throws IOException {
    try (DirectoryStream<Path> directories =
        Files.newDirectoryStream(settings.resolve(RecoveryStore.DIRECTORY))) {
      for (Path directory : directories) {
        if (!directory.equals(other)) {
          return directory;
        }
      }
    }
    throw new IOException("no instance directory");
  }

  /** Makes the move onto a buffer's file fail: a non-empty directory stands in its place. */
  private static Path blockWrite(Path directory, String id) throws IOException {
    Path blocked = directory.resolve(id + RecoveryFile.SUFFIX);
    Files.createDirectories(blocked.resolve("block"));
    return blocked;
  }

  private static void unblock(Path blocked) throws IOException {
    Files.delete(blocked.resolve("block"));
    Files.delete(blocked);
  }

  private static BooleanSupplier noticeShown(final Harness harness) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return harness.view.notices.contains(UiText.RECOVERY_WRITE_FAILED.text());
      }
    };
  }

  @Test
  void aFailedWriteIsRetriedAtTheNextIntervalWithOneNotice() throws Exception {
    Harness harness = start(false);
    harness.application.documents().newDocument();
    harness.ui.runPending();
    edit(harness, "mock");
    String id = harness.application.documents().dirtyBuffers().get(0).id();
    Path blocked = blockWrite(ownDirectory(null), id);
    harness.ui.runLongTimers();
    harness.await(noticeShown(harness), "the notice");
    awaitIdle(harness);
    harness.ui.runPending();
    harness.ui.runLongTimers();
    awaitIdle(harness);
    harness.ui.runPending();
    assertEquals(
        1,
        java.util.Collections.frequency(harness.view.notices, UiText.RECOVERY_WRITE_FAILED.text()));
    unblock(blocked);
    harness.ui.runLongTimers();
    awaitFiles(harness, 1);
    assertEquals("mock", RecoveryTexts.read(recoveryFiles().get(0)));
  }

  @Test
  void aFailedWriteOfARestoredBufferKeepsItsOldFileUntilARetryWrites() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness first = start(false);
    first.open(a);
    edit(first, "mine ");
    end(first);
    Path old = recoveryFiles().get(0);

    Harness second = start(false);
    second.await(offered(second), "the dialog");
    Path own = ownDirectory(old.getParent());
    Path blocked = blockWrite(own, "b1");
    second.view.recovery.restore(new int[] {0});
    second.await(rowsLeft(second, 0), "restored");
    second.await(noticeShown(second), "the notice");
    awaitIdle(second);
    assertEquals("mine alpha\n", second.application.documents().activeDocument().text().toString());
    assertTrue(Files.exists(old), "a failed write keeps the only recovered copy");

    unblock(blocked);
    second.ui.runPending();
    second.ui.runLongTimers();
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !Files.exists(old);
          }
        },
        "the old file went");
    assertTrue(Files.exists(own.resolve("b1" + RecoveryFile.SUFFIX)));
  }

  @Test
  void aSavedBufferDeletesTheOldFileAFailedWriteKept() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness first = start(false);
    first.open(a);
    edit(first, "mine ");
    end(first);
    Path old = recoveryFiles().get(0);

    Harness second = start(false);
    second.await(offered(second), "the dialog");
    Path blocked = blockWrite(ownDirectory(old.getParent()), "b1");
    second.view.recovery.restore(new int[] {0});
    second.await(rowsLeft(second, 0), "restored");
    second.await(noticeShown(second), "the notice");
    awaitIdle(second);
    unblock(blocked);
    second.application.documents().save();
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !Files.exists(old);
          }
        },
        "the old file went");
    assertEquals("mine alpha\n", Files.readString(a));
  }

  @Test
  void aSnapshotBufferKeepsItsFileWhenRecoveryIsOff() throws Exception {
    Harness first = start(true);
    setSnapshot(first);
    edit(first, "mock draft");
    first.application.requestQuit();
    awaitQuit(first);
    Path root = settings.resolve(RecoveryStore.DIRECTORY);
    Files.delete(root);
    Files.writeString(root, "not a directory");

    Harness second = start(true);
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "mock draft"
                .equals(second.application.documents().activeDocument().text().toString());
          }
        },
        "the buffer");
    awaitIdle(second);
    second.ui.runPending();
    assertTrue(Files.exists(RecoveryStore.snapshotFile(settings, "b1")));
  }

  @Test
  void anIntervalChangeRearmsTheTimer() throws Exception {
    Harness harness = start(false);
    assertEquals(List.of(30_000), harness.ui.longTimerDelays());
    Settings running = harness.application.settings();
    running.set(running.current().withOptions(options(running.current(), 45, false)));
    assertEquals(List.of(45_000), harness.ui.longTimerDelays());
  }

  @Test
  void aReadOnlySettingsDirectoryTurnsRecoveryOff() throws Exception {
    assumeTrue(settings.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Set<PosixFilePermission> before = Files.getPosixFilePermissions(settings);
    Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeFalse(Files.isWritable(settings), "the user ignores permissions");
      Harness harness = start(false);
      harness.application.documents().newDocument();
      harness.ui.runPending();
      edit(harness, "mock");
      harness.ui.runLongTimers();
      awaitIdle(harness);
      harness.ui.runPending();
      assertEquals(List.of(), recoveryFiles());
      assertEquals("mock", harness.application.documents().activeDocument().text().toString());
      harness.close();
    } finally {
      Files.setPosixFilePermissions(settings, before);
    }
  }

  private void setSnapshot(Harness harness) {
    Settings running = harness.application.settings();
    running.set(running.current().withOptions(options(running.current(), 30, true)));
  }

  private static Options options(Preferences current, int seconds, boolean snapshot) {
    Options o = current.options();
    return new Options(
        o.completionChars(),
        o.autoClose(),
        o.maxHighlightLineChars(),
        o.fontSize(),
        o.pollSeconds(),
        o.autoReloadClean(),
        o.backupDays(),
        o.searchMaxLineChars(),
        o.excludedDirectories(),
        seconds,
        o.recoveryKeepDays(),
        snapshot,
        o.tabWidth(),
        o.insertSpaces());
  }

  private void awaitQuit(final Harness harness) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.disposed;
          }
        },
        "quit");
    harness.workers.close();
  }

  @Test
  void aSnapshotQuitAsksNothingAndTheNextStartRestoresTheBuffers() throws Exception {
    Path a = file("a.txt", "alpha\n");
    Harness first = start(true);
    setSnapshot(first);
    edit(first, "mock draft");
    first.open(a);
    edit(first, "mine ");
    first.application.requestQuit();
    awaitQuit(first);
    assertNull(first.view.unsavedName);
    assertEquals("alpha\n", Files.readString(a));
    assertEquals(0, recoveryFiles().size());

    Harness second = start(true);
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return second.application.documents().count() == 2;
          }
        },
        "both tabs");
    second.awaitIdle("restore");
    assertNull(second.view.recovery);
    DocumentController documents = second.application.documents();
    assertEquals(1, documents.activeIndex());
    assertEquals("mine alpha\n", documents.activeDocument().text().toString());
    assertTrue(documents.activeDocument().isDirty());
    documents.select(0);
    second.ui.runPending();
    assertEquals("mock draft", documents.activeDocument().text().toString());
    assertTrue(documents.activeDocument().isDirty());
    awaitFiles(second, 2);
    second.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try (DirectoryStream<Path> left =
                Files.newDirectoryStream(settings.resolve(RecoveryStore.SNAPSHOT))) {
              return !left.iterator().hasNext();
            } catch (IOException e) {
              return false;
            }
          }
        },
        "the snapshot files went");
  }

  @Test
  void aSnapshotInstanceThatDoesNotOwnTheSessionAsks() throws Exception {
    Harness harness = start(false);
    setSnapshot(harness);
    harness.application.documents().newDocument();
    harness.ui.runPending();
    edit(harness, "mock");
    harness.application.requestQuit();
    harness.ui.runPending();
    assertNotNull(harness.view.unsavedName);
    harness.view.unsaved.accept(UnsavedChoice.CANCEL);
    assertFalse(harness.application.isQuitting());
  }
}
