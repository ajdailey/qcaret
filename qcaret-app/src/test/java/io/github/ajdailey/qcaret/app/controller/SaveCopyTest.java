package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File > Save a Copy As: the document keeps its file, dirty state and history; the copy's bytes
 * match a Save As of the same text; a copy over another tab's file reaches that tab as an external
 * change; a failed write leaves the target as it was. Esc cancels a running copy; a close, Don't
 * Save and a quit let it finish; after {@link RunningCopies#BOUND} the quit asks Quit or Wait.
 */
class SaveCopyTest {
  /** ISO-8859-1 whose next encoder request, once armed, blocks until released. */
  private static final class Gate extends Charset {
    final AtomicBoolean armed = new AtomicBoolean();
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);

    Gate() {
      super("X-QCARET-COPY-GATE", null);
    }

    @Override
    public boolean contains(Charset other) {
      return StandardCharsets.ISO_8859_1.contains(other);
    }

    @Override
    public CharsetDecoder newDecoder() {
      return StandardCharsets.ISO_8859_1.newDecoder();
    }

    @Override
    public CharsetEncoder newEncoder() {
      if (armed.compareAndSet(true, false)) {
        reached.countDown();
        try {
          released.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return StandardCharsets.ISO_8859_1.newEncoder();
    }
  }

  @TempDir Path temp;

  private Harness harness;
  private Path dir;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    dir = Files.createDirectory(temp.resolve("work")).toRealPath();
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private DocumentController documents() {
    return harness.application.documents();
  }

  /** Runs Save a Copy As onto {@code target} and waits until the copy ends. */
  private void copyTo(final Path target) throws InterruptedException {
    final int notices = harness.view.notices.size();
    final int infos = harness.view.infos.size();
    harness.view.saveChooser = null;
    harness.application.execute(CommandId.SAVE_COPY_AS);
    assertNotNull(harness.view.saveChooser);
    harness.view.saveChooser.accept(target);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() > notices || harness.view.infos.size() > infos;
          }
        },
        "the copy's notice");
    harness.awaitIdle("copy");
  }

  @Test
  void aCopyWithUnsavedEditsLeavesTheDocumentDirtyOnItsFile() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    harness.open(a);
    documents().activeEditor().type("mine ");
    Document document = documents().activeDocument();
    Path b = dir.resolve("b.txt");
    copyTo(b);
    assertEquals("mine one\n", Files.readString(b));
    assertEquals("one\n", Files.readString(a));
    assertTrue(document.isDirty());
    assertTrue(document.canUndo());
    assertEquals(a, documents().activePath());
    assertEquals(1, documents().count());
    assertEquals(b, harness.application.recentFiles().paths().get(0));
    assertTrue(
        harness.view.notices.contains(UiText.SAVE_COPY_DONE.format("a.txt", b)),
        harness.view.notices.toString());
  }

  @Test
  void theCopyMatchesSaveAsOfTheSameText() throws Exception {
    Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*]\ntrim_trailing_whitespace = true\ninsert_final_newline = true\n");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.write(new byte[] {(byte) 0xff, (byte) 0xfe});
    bytes.write("one  \r\ntwo".getBytes(StandardCharsets.UTF_16LE));
    Path a = Files.write(dir.resolve("a.txt"), bytes.toByteArray());
    harness.open(a);
    documents().activeEditor().type("mine ");
    Document document = documents().activeDocument();
    Path copy = dir.resolve("copy.txt");
    copyTo(copy);
    assertEquals("mine one  \r\ntwo", document.text().toString());
    assertTrue(document.isDirty());

    Path saved = dir.resolve("saved.txt");
    documents().saveAs();
    harness.view.saveChooser.accept(saved);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.isDirty();
          }
        },
        "Save As");
    harness.awaitIdle("Save As");
    byte[] expected = Files.readAllBytes(saved);
    assertEquals(
        "mine one\r\ntwo\r\n",
        new String(expected, 2, expected.length - 2, StandardCharsets.UTF_16LE));
    assertArrayEquals(expected, Files.readAllBytes(copy));
  }

  @Test
  void aCopyOverAnotherTabsFileReachesThatTabAsAnExternalChange() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Path b = Files.writeString(dir.resolve("b.txt"), "two\n");
    harness.open(b);
    harness.open(a);
    documents().setPollSeconds(3600);
    documents().activeEditor().type("mine ");
    final Document other = documents().documentAt(b);
    copyTo(b);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "mine one\n".equals(other.text().toString());
          }
        },
        "the other tab reloaded");
    harness.awaitIdle("reload");
    assertFalse(other.isDirty());
    assertEquals(a, documents().activePath());
    assertTrue(documents().activeDocument().isDirty());
  }

  @Test
  void theDocumentsOwnFileRunsSave() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    harness.open(a);
    documents().activeEditor().type("mine ");
    final Document document = documents().activeDocument();
    harness.application.execute(CommandId.SAVE_COPY_AS);
    harness.view.saveChooser.accept(a);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.isDirty();
          }
        },
        "saved");
    harness.awaitIdle("save");
    assertEquals("mine one\n", Files.readString(a));
  }

  @Test
  void aFailedCopyLeavesTheTargetAsItWasAndReports() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Path b = Files.writeString(dir.resolve("b.txt"), "old\n");
    if (b.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(b, PosixFilePermissions.fromString("r--r--r--"));
    } else {
      Files.setAttribute(b, "dos:readonly", true);
    }
    assumeFalse(Files.isWritable(b), "the user writes every file");
    harness.open(a);
    documents().activeEditor().type("mine ");
    int infos = harness.view.infos.size();
    copyTo(b);
    assertEquals("old\n", Files.readString(b));
    assertEquals(infos + 1, harness.view.infos.size(), harness.view.infos.toString());
    assertTrue(
        harness.view.infos.get(infos).contains(UiText.SAVE_FAILED.format("b.txt", "")),
        harness.view.infos.toString());
    assertTrue(documents().activeDocument().isDirty());
    assertEquals(List.of(a), harness.application.recentFiles().paths());
  }

  /** Opens a file in a gate charset, so a copy's write can be held. */
  private Gate openGated(Path file) throws Exception {
    harness.open(file);
    final Gate gate = new Gate();
    documents().reopenWithEncoding(gate);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().activeDocument().encoding().charset().equals(gate);
          }
        },
        "reopened");
    harness.awaitIdle("reopen");
    return gate;
  }

  /** Starts Save a Copy As onto {@code target} and waits until the gate holds its write. */
  private void startCopy(final Gate gate, Path target) throws InterruptedException {
    gate.armed.set(true);
    harness.view.saveChooser = null;
    harness.application.execute(CommandId.SAVE_COPY_AS);
    assertNotNull(harness.view.saveChooser);
    harness.view.saveChooser.accept(target);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return gate.reached.getCount() == 0;
          }
        },
        "the write started");
  }

  @Test
  void aCanceledCopyLeavesTheTargetAndTheDocumentAsTheyWere() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Path b = Files.writeString(dir.resolve("b.txt"), "old\n");
    Gate gate = openGated(a);
    documents().activeEditor().type("mine ");
    Document document = documents().activeDocument();
    int infos = harness.view.infos.size();
    int notices = harness.view.notices.size();
    startCopy(gate, b);
    documents().activeEditor().cancel();
    gate.released.countDown();
    harness.awaitIdle("canceled copy");
    assertEquals("old\n", Files.readString(b));
    assertTrue(document.isDirty());
    assertEquals(a, documents().activePath());
    assertEquals(infos, harness.view.infos.size(), harness.view.infos.toString());
    assertEquals(notices, harness.view.notices.size(), harness.view.notices.toString());
    assertEquals(List.of(a), harness.application.recentFiles().paths());
  }

  /** Save a Copy As, then Don't Save at the close, keeps the edits in the copy. */
  @Test
  void dontSaveOnTheTabLetsItsRunningCopyFinishAndReport() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Gate gate = openGated(a);
    documents().activeEditor().type("mine ");
    Path b = dir.resolve("b.txt");
    startCopy(gate, b);
    documents().closeActive();
    assertNotNull(harness.view.unsaved, "the close asks");
    harness.view.unsaved.accept(UnsavedChoice.DISCARD);
    gate.released.countDown();
    harness.awaitIdle("copy after close");
    assertEquals("mine one\n", Files.readString(b));
    assertEquals("one\n", Files.readString(a));
    assertTrue(
        harness.view.notices.contains(UiText.SAVE_COPY_DONE.format("a.txt", b)),
        harness.view.notices.toString());
  }

  @Test
  void quitWaitsForARunningCopyAndReportsIt() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Gate gate = openGated(a);
    Path b = dir.resolve("b.txt");
    startCopy(gate, b);
    harness.application.requestQuit();
    harness.ui.runPending();
    assertFalse(harness.application.isQuitting(), "the quit did not wait for the copy");
    gate.released.countDown();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.disposed;
          }
        },
        "quit");
    assertEquals("one\n", Files.readString(b));
    assertTrue(
        harness.view.notices.contains(UiText.SAVE_COPY_DONE.format("a.txt", b)),
        harness.view.notices.toString());
  }

  @Test
  void aCopyFailingDuringQuitStopsTheQuitAndReports() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Gate gate = openGated(a);
    Path out = Files.createDirectory(dir.resolve("out"));
    startCopy(gate, out.resolve("b.txt"));
    harness.application.requestQuit();
    harness.ui.runPending();
    Files.delete(out);
    final int infos = harness.view.infos.size();
    gate.released.countDown();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.infos.size() > infos;
          }
        },
        "the failure");
    harness.awaitIdle("failed copy");
    assertTrue(
        harness.view.infos.get(infos).contains(UiText.SAVE_FAILED.format("b.txt", "")),
        harness.view.infos.toString());
    assertFalse(harness.application.isQuitting());
    assertFalse(harness.view.disposed);
    assertEquals(1, documents().count());
  }

  @Test
  void quitAtTheUnfinishedCopyPromptGoesOn() throws Exception {
    quitPastTheBound(true);
  }

  @Test
  void waitAtTheUnfinishedCopyPromptWaitsAnotherBound() throws Exception {
    quitPastTheBound(false);
  }

  @Test
  void quitAtTheUnfinishedCopyPromptGoesOnInASnapshotQuit() throws Exception {
    snapshotSession();
    quitPastTheBound(true);
  }

  @Test
  void waitAtTheUnfinishedCopyPromptWaitsAnotherBoundInASnapshotQuit() throws Exception {
    snapshotSession();
    quitPastTheBound(false);
  }

  /** Owns a session with {@code session.snapshot} on, so a quit writes a snapshot. */
  private void snapshotSession() throws Exception {
    SessionStore store =
        new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
    harness.application.startSession(store, store.load(), false);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.recovery().idle();
          }
        },
        "started");
    harness.awaitIdle("start");
    Settings running = harness.application.settings();
    Options o = running.current().options();
    running.set(
        running
            .current()
            .withOptions(
                new Options(
                    o.completionChars(),
                    o.autoClose(),
                    o.maxHighlightLineChars(),
                    o.fontSize(),
                    o.pollSeconds(),
                    o.autoReloadClean(),
                    o.backupDays(),
                    o.searchMaxLineChars(),
                    o.excludedDirectories(),
                    o.recoveryIntervalSeconds(),
                    o.recoveryKeepDays(),
                    true,
                    o.tabWidth(),
                    o.insertSpaces(),
                    o.rightMargin(),
                    o.dateFormat(),
                    o.editorConfig(),
                    o.modelines(),
                    o.closeWindowWithLastTab())));
  }

  /**
   * Quits while a copy's write is held. The quit waits on a {@link RunningCopies#BOUND} timer; when
   * it fires, the prompt names the copy. Quit goes on at once; Wait arms the timer again, which
   * asks again, and the copy then ending closes the prompt and completes the quit.
   */
  private void quitPastTheBound(boolean quit) throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Gate gate = openGated(a);
    Path b = dir.resolve("b.txt");
    startCopy(gate, b);
    try {
      harness.application.requestQuit();
      harness.ui.runPending();
      assertFalse(harness.application.isQuitting(), "the quit did not wait for the copy");
      assertEquals(0, harness.view.unfinishedAsked);
      harness.ui.runLongTimers();
      assertEquals(1, harness.view.unfinishedAsked);
      assertEquals(List.of(b.toString()), harness.view.unfinishedCopies);
      assertFalse(harness.application.isQuitting());
      if (quit) {
        harness.view.unfinishedQuit.run();
        assertTrue(harness.application.isQuitting(), "Quit went on");
      } else {
        harness.view.unfinishedWait.run();
        harness.ui.runPending();
        assertFalse(harness.application.isQuitting(), "Wait kept waiting");
        harness.ui.runLongTimers();
        assertEquals(2, harness.view.unfinishedAsked, "Wait armed the bound again");
        assertFalse(harness.application.isQuitting());
      }
    } finally {
      gate.released.countDown();
    }
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.disposed;
          }
        },
        "quit");
    if (!quit) {
      assertNull(harness.view.unfinishedWait, "the copy ending closed the prompt");
      assertEquals("one\n", Files.readString(b));
      assertTrue(
          harness.view.notices.contains(UiText.SAVE_COPY_DONE.format("a.txt", b)),
          harness.view.notices.toString());
    }
  }
}
