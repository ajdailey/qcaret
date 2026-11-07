package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link QuitPrompts}: every answer first, then the saves, the capture and the closes. */
class QuitPromptsTest {
  @TempDir Path temp;

  private Windows windows;
  private ApplicationController first;
  private ApplicationController second;

  @BeforeEach
  void setUp() throws Exception {
    windows = new Windows(temp, false);
    first = windows.first();
    SessionStore store = store();
    first.startSession(store, store.load(), true);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !windows.set.session().restoring();
          }
        },
        "the session started");
    windows.settle("start");
    first.documents().closeAll(null);
    second = windows.second();
  }

  @AfterEach
  void tearDown() throws Exception {
    windows.close();
  }

  private SessionStore store() {
    return new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
  }

  private Path file(String name, String text) throws IOException {
    return Files.writeString(temp.resolve(name), text).toRealPath();
  }

  /** Opens a file in a window, edits it and returns its document. */
  private Document dirty(ApplicationController window, Path file) throws Exception {
    windows.open(window, file);
    window.documents().activeEditor().type("x");
    return window.documents().activeDocument();
  }

  private Document untitled(ApplicationController window) {
    window.documents().newDocument();
    window.documents().activeEditor().type("x");
    return window.documents().activeDocument();
  }

  private void quit() {
    first.windowActivated();
    first.execute(CommandId.QUIT);
  }

  private FakeView view(ApplicationController window) {
    return windows.view(window);
  }

  private void awaitDisposed() throws InterruptedException {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.views.get(0).disposed;
          }
        },
        "quit");
  }

  /** Runs the queue until the quit stopped, which a save's result reports. */
  private void awaitStopped() throws InterruptedException {
    windows.settle("the save ended");
    assertFalse(windows.set.isQuitting());
    assertEquals(2, windows.set.windows().size());
    first.execute(CommandId.QUIT);
    assertNotNull(view(first).unsaved, "a new quit asks again");
  }

  private static List<String> tabs(Session.Window window) {
    List<String> paths = new ArrayList<String>();
    for (Session.Tab tab : window.tabs()) {
      paths.add(tab.path());
    }
    return paths;
  }

  @Test
  void aCancelAfterASaveAnswerWritesNothingAndLeavesEveryWindowAsItWas() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    Path c = file("c.txt", "c\n");
    Document one = dirty(first, a);
    windows.open(first, c);
    Document shown = first.documents().activeDocument();
    Document two = dirty(second, b);
    first.windowActivated();

    first.execute(CommandId.QUIT);
    assertSame(one, first.documents().activeDocument());
    view(first).unsaved.accept(UnsavedChoice.SAVE);
    assertNotNull(view(second).unsaved);
    int raised = view(first).raised;
    view(second).unsaved.accept(UnsavedChoice.CANCEL);
    windows.settle("cancel");

    assertFalse(windows.set.isQuitting());
    assertEquals("a\n", Files.readString(a));
    assertTrue(one.isDirty());
    assertTrue(two.isDirty());
    assertEquals(2, first.documents().count());
    assertSame(shown, first.documents().activeDocument());
    assertSame(first, windows.set.target());
    assertEquals(raised + 1, view(first).raised);
  }

  @Test
  void savesRunAfterTheLastAnswer() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    dirty(first, a);
    dirty(second, b);
    quit();
    view(first).unsaved.accept(UnsavedChoice.SAVE);
    windows.settle("no save runs");
    assertEquals("a\n", Files.readString(a));

    view(second).unsaved.accept(UnsavedChoice.DISCARD);
    awaitDisposed();

    assertEquals("xa\n", Files.readString(a));
    assertEquals("b\n", Files.readString(b));
  }

  @Test
  void anUntitledDocumentSavedAtThePromptIsATabOfTheNextSession() throws Exception {
    Path a = file("a.txt", "a\n");
    windows.open(first, a);
    untitled(second);
    quit();
    view(second).unsaved.accept(UnsavedChoice.SAVE);
    Path saved = temp.resolve("saved.txt");
    view(second).saveChooser.accept(saved);
    awaitDisposed();

    Session session = store().load();
    assertEquals(List.of(a.toString()), tabs(session.windows().get(0)));
    assertEquals(List.of(saved.toString()), tabs(session.windows().get(1)));
  }

  @Test
  void theTabSelectedBeforeThePromptsIsTheSessionsActiveTab() throws Exception {
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    dirty(first, a);
    windows.open(first, b);
    quit();
    view(first).unsaved.accept(UnsavedChoice.DISCARD);
    awaitDisposed();
    assertEquals(1, store().load().windows().get(0).active());
  }

  @Test
  void aFailedSaveStopsTheQuitAndKeepsEveryDirtyBuffer() throws Exception {
    Document one = untitled(first);
    Document two = untitled(second);
    Path directory = Files.createDirectory(temp.resolve("taken"));
    quit();
    view(first).unsaved.accept(UnsavedChoice.SAVE);
    view(second).unsaved.accept(UnsavedChoice.DISCARD);
    view(first).unsaved = null;
    view(first).saveChooser.accept(directory);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !view(first).infos.isEmpty();
          }
        },
        "the failure");
    awaitStopped();
    assertTrue(one.isDirty());
    assertTrue(two.isDirty());
    assertEquals(2, windows.set.dirtyBuffers().size());
  }

  @Test
  void aCanceledChooserStopsTheQuit() throws Exception {
    untitled(first);
    quit();
    view(first).unsaved.accept(UnsavedChoice.SAVE);
    view(first).unsaved = null;
    view(first).saveChooser.accept(null);
    awaitStopped();
  }

  @Test
  void aDeclinedReplacePromptStopsTheQuit() throws Exception {
    Path held = file("held.txt", "held\n");
    untitled(first);
    dirty(second, held);
    quit();
    view(first).unsaved.accept(UnsavedChoice.SAVE);
    view(second).unsaved.accept(UnsavedChoice.DISCARD);
    view(first).unsaved = null;
    view(first).saveChooser.accept(held);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view(first).replaceDeclined != null;
          }
        },
        "the replace prompt");
    view(first).replaceDeclined.run();
    awaitStopped();
    assertEquals("held\n", Files.readString(held));
    assertEquals(1, second.documents().dirtyDocuments().size());
  }

  @Test
  void aCanceledConflictStopsTheQuit() throws Exception {
    final Path a = file("a.txt", "a\n");
    dirty(first, a);
    Files.writeString(a, "changed outside\n");
    Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
    quit();
    view(first).unsaved.accept(UnsavedChoice.SAVE);
    view(first).unsaved = null;
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view(first).changeAnswer != null;
          }
        },
        "the conflict prompt");
    view(first).changeAnswer.accept(ChangeChoice.KEEP);
    awaitStopped();
    assertEquals("changed outside\n", Files.readString(a));
  }

  @Test
  void aSecondQuitAndAWindowCloseDuringAPromptDoNothing() throws Exception {
    untitled(first);
    quit();
    view(first).unsaved = null;
    first.execute(CommandId.QUIT);
    second.closeWindow();
    assertNull(view(first).unsaved);
    assertEquals(2, windows.set.windows().size());
  }

  @Test
  void anEditAfterDontSaveAsksAgain() throws Exception {
    untitled(first);
    untitled(second);
    quit();
    view(first).unsaved.accept(UnsavedChoice.DISCARD);
    first.documents().activeEditor().type("more");
    view(first).unsaved = null;
    view(second).unsaved.accept(UnsavedChoice.DISCARD);
    assertNotNull(view(first).unsaved);
    assertFalse(windows.set.isQuitting());
    view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(windows.set.isQuitting());
  }

  @Test
  void anEditDuringASaveIsAskedAboutAfterIt() throws Exception {
    final Path a = file("a.txt", "a\n");
    Document document = dirty(first, a);
    GatedCharset gate = new GatedCharset();
    document.setEncoding(TextEncoding.of(gate, false));
    gate.armEncoder();
    quit();
    view(first).unsaved.accept(UnsavedChoice.SAVE);
    view(first).unsaved = null;
    assertTrue(gate.awaitHeld());
    first.documents().activeEditor().type("y");
    gate.release();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view(first).unsaved != null;
          }
        },
        "asked again");
    assertEquals("xa\n", Files.readString(a));
    view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(windows.set.isQuitting());
  }

  /** Quits with a changed file, answers Save and Compare, and leaves the conflict prompt open. */
  private Path compareAtAQuitConflict() throws Exception {
    Path a = file("conflict.txt", "a\n");
    dirty(first, a);
    Files.writeString(a, "changed outside\n");
    Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
    first.windowActivated();
    first.execute(CommandId.QUIT);
    windows.view(first).unsaved.accept(UnsavedChoice.SAVE);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.view(first).changeAnswer != null;
          }
        },
        "the conflict prompt");
    windows.view(first).changeAnswer.accept(ChangeChoice.COMPARE);
    windows.settle("the comparison");
    return a;
  }

  @Test
  void compareThenOverwriteAtAQuitConflictStillQuits() throws Exception {
    final Path a = compareAtAQuitConflict();
    windows.view(first).unsaved = null;
    first.execute(CommandId.QUIT);
    assertNull(windows.view(first).unsaved, "Compare ended the quit under the open prompt");
    windows.view(first).changeAnswer.accept(ChangeChoice.OVERWRITE);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return windows.set.isQuitting();
          }
        },
        "the quit");
    assertEquals("xa\n", Files.readString(a));
  }

  @Test
  void compareThenCancelAtAQuitConflictStopsTheQuitOnce() throws Exception {
    compareAtAQuitConflict();
    windows.view(first).changeAnswer.accept(ChangeChoice.CANCEL);
    windows.settle("the cancel");
    assertFalse(windows.set.isQuitting());
    windows.view(first).unsaved = null;
    first.execute(CommandId.QUIT);
    assertNotNull(windows.view(first).unsaved, "a new Quit does not ask");
    windows.view(first).unsaved.accept(UnsavedChoice.CANCEL);
  }
}
