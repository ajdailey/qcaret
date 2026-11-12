package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Closing a window's last tab: an untitled document takes its place, or the window closes. */
class LastTabTest {
  @TempDir Path temp;

  private Windows windows;
  private WindowSet set;
  private ApplicationController first;

  @BeforeEach
  void setUp() {
    windows = new Windows(temp, false);
    set = windows.set;
    first = windows.first();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (!windows.views.get(0).disposed) {
      windows.close();
    }
  }

  private Path file(String name) throws Exception {
    return Files.writeString(temp.resolve(name), "text\n", StandardCharsets.UTF_8).toRealPath();
  }

  private void closeWindowWithLastTab() {
    Preferences current = set.settings().current();
    Options o = current.options();
    set.settings()
        .set(
            current.withOptions(
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
                    o.sessionSnapshot(),
                    o.tabWidth(),
                    o.insertSpaces(),
                    o.rightMargin(),
                    o.dateFormat(),
                    o.editorConfig(),
                    o.modelines(),
                    true)));
  }

  @Test
  void closingTheOnlyTabOpensASelectedUntitledDocument() throws Exception {
    Path a = file("a.txt");
    windows.open(first, a);
    first.execute(CommandId.CLOSE);
    assertEquals(List.of("Untitled 1"), first.documents().labels());
    assertNotNull(first.documents().activeDocument());
    assertSame(windows.view(first).tabs.get(0), windows.view(first).selected);
  }

  @Test
  void theTabCloseButtonOnTheOnlyDirtyTabOpensAnUntitledDocumentAfterThePrompt() {
    first.documents().newDocument();
    first.documents().activeEditor().type("x");
    windows.view(first).tabs.get(0).input.closeRequested();
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals(List.of("Untitled 2"), first.documents().labels());
    assertFalse(first.documents().activeDocument().isDirty());
  }

  @Test
  void aPristineUntitledDocumentAloneStaysOpen() {
    first.documents().newDocument();
    Document pristine = first.documents().activeDocument();
    first.execute(CommandId.CLOSE);
    windows.view(first).tabs.get(0).input.closeRequested();
    first.execute(CommandId.CLOSE_ALL);
    assertSame(pristine, first.documents().activeDocument());
    assertEquals(List.of("Untitled 1"), first.documents().labels());
    assertNull(windows.view(first).unsaved);
  }

  @Test
  void closeAllLeavesOneUntitledDocument() throws Exception {
    windows.open(first, file("a.txt"));
    first.documents().newDocument();
    first.documents().activeEditor().type("x");
    windows.open(first, file("b.txt"));
    first.execute(CommandId.CLOSE_ALL);
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals(List.of("Untitled 2"), first.documents().labels());
  }

  @Test
  void reopenClosedTabWorksAfterTheLastClose() throws Exception {
    Path a = file("a.txt");
    windows.open(first, a);
    first.execute(CommandId.CLOSE);
    first.execute(CommandId.REOPEN_CLOSED);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return first.documents().count() == 2;
          }
        },
        "the reopened tab");
    windows.settle("reopen");
    assertEquals(List.of("Untitled 1", a.toString()), first.documents().labels());
  }

  @Test
  void aTabMovedOutLeavesItsWindowEmpty() throws Exception {
    windows.open(first, file("a.txt"));
    ApplicationController target =
        set.moveDocument(
            first,
            first.documents().activeDocument(),
            null,
            new WindowSet.Placement(0, null, null));
    assertNotNull(target);
    assertEquals(0, first.documents().count());
    assertEquals(2, set.windows().size());
  }

  @Test
  void aWindowCloseAndAQuitOpenNoUntitledDocument() throws Exception {
    ApplicationController second = windows.second();
    windows.open(second, file("a.txt"));
    second.closeWindow();
    windows.ui.runPending();
    assertEquals(0, second.documents().count());
    assertEquals(List.of(first), set.windows());
    windows.open(first, file("b.txt"));
    first.execute(CommandId.QUIT);
    assertTrue(set.isQuitting());
    assertEquals(0, first.documents().count());
  }

  @Test
  void withTheOptionTheLastTabClosesItsWindow() throws Exception {
    closeWindowWithLastTab();
    ApplicationController second = windows.second();
    windows.open(second, file("a.txt"));
    second.execute(CommandId.CLOSE);
    windows.ui.runPending();
    assertEquals(List.of(first), set.windows());
    assertEquals(0, second.documents().count());
    assertFalse(set.isQuitting());
  }

  @Test
  void withTheOptionTheLastTabOfTheLastWindowQuitsAfterTheUnsavedPrompt() {
    closeWindowWithLastTab();
    first.documents().newDocument();
    first.documents().activeEditor().type("x");
    first.execute(CommandId.CLOSE);
    windows.view(first).unsaved.accept(UnsavedChoice.CANCEL);
    assertFalse(set.isQuitting());
    assertEquals(1, first.documents().count());
    first.execute(CommandId.CLOSE);
    windows.view(first).unsaved.accept(UnsavedChoice.DISCARD);
    assertTrue(set.isQuitting());
    assertEquals(0, first.documents().count());
  }

  @Test
  void theUntitledDocumentIsNotSavedInTheSession() throws Exception {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    SessionStore store = new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    final Windows session = new Windows(temp, true);
    try {
      session.first().startSession(store, store.load(), true);
      session.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return !session.set.session().restoring();
            }
          },
          "the restore ended");
      session.settle("restore");
      ApplicationController window = session.first();
      window.documents().closeAll(null);
      session.open(window, file("a.txt"));
      window.execute(CommandId.CLOSE);
      assertEquals(1, window.documents().count());
      assertNull(window.documents().pathOf(window.documents().activeDocument()));
    } finally {
      session.close();
    }
    Session saved = store.load();
    assertEquals(1, saved.windows().size());
    assertEquals(List.of(), saved.windows().get(0).tabs());
  }
}
