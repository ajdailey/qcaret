package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A Quit's save of a document another save is writing in place, which a cancel does not stop: the
 * quit goes on when a window close's save closed the document, and writes again over a save it
 * replaced. A window close while a quit saves or writes its snapshot does nothing. A hard link
 * makes {@link SafeSave} write in place.
 */
class QuitAndCloseSavesTest {
  /**
   * ISO-8859-1 whose first encoder request inside {@link SafeSave}, once armed, blocks until
   * released: the request that writes the file, after the save's cancel check.
   */
  private static final class WriteGate extends Charset {
    final AtomicBoolean armed = new AtomicBoolean();
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);

    WriteGate() {
      super("X-QCARET-WRITE-GATE", null);
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
      if (writing() && armed.compareAndSet(true, false)) {
        reached.countDown();
        try {
          released.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return StandardCharsets.ISO_8859_1.newEncoder();
    }

    private static boolean writing() {
      for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
        if (frame.getClassName().equals(SafeSave.class.getName())) {
          return true;
        }
      }
      return false;
    }
  }

  @TempDir Path temp;

  private Windows windows;
  private WindowSet set;
  private ApplicationController first;
  private ApplicationController second;

  @BeforeEach
  void create() throws Exception {
    Files.createDirectory(temp.resolve("settings"));
    windows = new Windows(temp, false);
    set = windows.set;
    first = windows.first();
    second = windows.second();
  }

  @AfterEach
  void close() throws InterruptedException {
    windows.close();
  }

  private void await(BooleanSupplier condition, String what) throws InterruptedException {
    windows.await(condition, what);
  }

  /** Opens a file in {@code window} in the gate charset and types into it. */
  private WriteGate openDirty(final ApplicationController window, Path file) throws Exception {
    windows.open(window, file);
    final WriteGate gate = new WriteGate();
    window.documents().reopenWithEncoding(gate);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.documents().activeDocument().encoding().charset().equals(gate);
          }
        },
        "reopened");
    windows.settle("reopen");
    window.documents().activeEditor().type("x ");
    return gate;
  }

  private void awaitQuitEnded() throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return set.isQuitting() || !set.quitRunning();
          }
        },
        "the quit quit or stopped");
  }

  /** The window close's save writes the file, closes the document and cancels the quit's save. */
  @Test
  void aQuitSaveEndedByAWindowClosesSaveGoesOn() throws Exception {
    Path a = Files.writeString(temp.resolve("a.txt"), "one\n");
    Files.createLink(temp.resolve("link.txt"), a);
    WriteGate gate = openDirty(first, a);
    FakeView view = windows.view(first);
    gate.armed.set(true);
    try {
      set.requestClose(first);
      assertNotNull(view.unsaved, "the window close asks");
      view.unsaved.accept(UnsavedChoice.SAVE);
      assertTrue(gate.reached.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "close saves");
      view.unsaved = null;
      set.requestQuit();
      assertNotNull(view.unsaved, "the quit asks");
      view.unsaved.accept(UnsavedChoice.SAVE);
    } finally {
      gate.released.countDown();
    }
    awaitQuitEnded();
    assertTrue(set.isQuitting(), "the quit stopped");
    assertEquals("x one\n", Files.readString(a, StandardCharsets.ISO_8859_1));
  }

  /** The quit's save writes again over the stamp of the save it replaced; no conflict is asked. */
  @Test
  void aQuitSaveReplacingARunningInPlaceSaveWritesAgain() throws Exception {
    Path a = Files.writeString(temp.resolve("a.txt"), "one\n");
    Files.createLink(temp.resolve("link.txt"), a);
    WriteGate gate = openDirty(first, a);
    FakeView view = windows.view(first);
    gate.armed.set(true);
    try {
      first.documents().save();
      assertTrue(gate.reached.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "Save writes");
      set.requestQuit();
      assertNotNull(view.unsaved, "the quit asks");
      view.unsaved.accept(UnsavedChoice.SAVE);
    } finally {
      gate.released.countDown();
    }
    awaitQuitEnded();
    assertEquals(List.of(), view.changePrompts, "a conflict was asked");
    assertTrue(set.isQuitting(), "the quit stopped");
    assertEquals("x one\n", Files.readString(a, StandardCharsets.ISO_8859_1));
  }

  @Test
  void aWindowCloseDuringTheQuitsSaveDoesNothing() throws Exception {
    Path a = Files.writeString(temp.resolve("a.txt"), "one\n");
    WriteGate gate = openDirty(first, a);
    FakeView view = windows.view(first);
    gate.armed.set(true);
    try {
      set.requestQuit();
      assertNotNull(view.unsaved, "the quit asks");
      view.unsaved.accept(UnsavedChoice.SAVE);
      assertTrue(gate.reached.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "quit saves");
      view.unsaved = null;
      set.requestClose(first);
      windows.ui.runPending();
      assertNull(view.unsaved, "the window close asked");
      assertTrue(set.windows().contains(first));
    } finally {
      gate.released.countDown();
    }
    awaitQuitEnded();
    assertTrue(set.isQuitting(), "the quit stopped");
    assertEquals("x one\n", Files.readString(a, StandardCharsets.ISO_8859_1));
  }

  /** The snapshot's result waits on the UI queue while the window close runs. */
  @Test
  void aWindowCloseDuringTheSnapshotWriteDoesNothing() throws Exception {
    SessionStore store =
        new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
    first.startSession(store, store.load(), true);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.recovery().idle() && !set.session().restoring();
          }
        },
        "started");
    windows.settle("start");
    snapshotOn(first.settings());
    second.documents().newDocument();
    second.documents().activeEditor().type("mock");
    FakeView view = windows.view(second);
    view.unsaved = null;
    set.requestQuit();
    assertTrue(set.quitRunning(), "the snapshot quit runs");
    set.requestClose(second);
    assertNull(view.unsaved, "the window close asked");
    assertTrue(set.windows().contains(second));
    awaitQuitEnded();
    assertTrue(set.isQuitting(), "the snapshot quit stopped");
  }

  private static void snapshotOn(Settings running) {
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
                    o.closeWindowWithLastTab(),
                    o.closeShowsLastUsed(),
                    Options.DEFAULTS.newLineEnding(),
                    Options.DEFAULTS.newEncoding())));
  }
}
