package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A window close with {@code session.snapshot} on moving the unsaved tabs ({@link
 * UnsavedTabsMove}), and a tab moved out of a window with unsaved tabs asking nothing.
 */
class UnsavedTabsMoveTest {
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
    windows.close();
  }

  private Document open(ApplicationController window, String name, String text) throws Exception {
    windows.open(window, Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8));
    return window.documents().activeDocument();
  }

  private Document untitled(ApplicationController window, String text) {
    window.documents().newDocument();
    window.documents().activeEditor().type(text);
    return window.documents().activeDocument();
  }

  private void snapshot(boolean on) {
    Settings running = first.settings();
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
                    on,
                    o.tabWidth(),
                    o.insertSpaces(),
                    o.rightMargin(),
                    o.dateFormat(),
                    o.editorConfig(),
                    o.modelines(),
                    o.closeWindowWithLastTab(),
                    o.closeShowsLastUsed(),
                    o.newLineEnding(),
                    o.newEncoding(),
                    o.workspaceDefault(),
                    o.fontZoom(),
                    o.sessionRestore(),
                    o.strictJson(),
                    o.previewRenderer(),
                    o.markdownSyncScroll(),
                    o.previewZoom(),
                    o.markdownOpenMode())));
  }

  /** The user's report: unsaved tabs in A, a new tab dragged out to B, then A closed. */
  @Test
  void closingTheSourceAfterADragOutMovesItsUnsavedTabsWithoutAPrompt() throws Exception {
    snapshot(true);
    Document clean = open(first, "clean.txt", "clean\n");
    Document edited = open(first, "edited.txt", "edited\n");
    first.documents().activeEditor().type("x");
    Document draft = untitled(first, "draft");
    String draftName = first.documents().nameOf(draft);
    Document dragged = untitled(first, "dragged");
    first.moveTabToNewWindow(windows.view(first).selected.input, 700, 10);
    ApplicationController second = windows.controllers.get(1);
    assertEquals(List.of(dragged), second.documents().openDocuments());
    assertNull(windows.view(first).unsaved, "the drag asked");

    first.closeWindow();
    windows.ui.runPending();

    assertNull(windows.view(first).unsaved, "the close asked");
    assertEquals(List.of(second), set.windows());
    assertTrue(windows.view(first).disposed);
    assertEquals(List.of(dragged, edited, draft), second.documents().openDocuments());
    assertFalse(second.documents().isOpen(clean));
    assertTrue(edited.isDirty());
    assertTrue(draft.isDirty());
    assertEquals("xedited\n", edited.text().toString());
    assertEquals("draft", draft.text().toString());
    assertEquals(draftName, second.documents().nameOf(draft));
    assertEquals(temp.resolve("edited.txt").toRealPath(), second.documents().pathOf(edited));
    assertSame(second, set.target());
    assertTrue(windows.view(second).notices.isEmpty(), windows.view(second).notices.toString());
  }

  @Test
  void withTheSettingOffClosingTheSourceStillAsks() throws Exception {
    Document edited = open(first, "edited.txt", "edited\n");
    first.documents().activeEditor().type("x");
    untitled(first, "draft");
    untitled(first, "dragged");
    first.moveTabToNewWindow(windows.view(first).selected.input, 700, 10);
    ApplicationController second = windows.controllers.get(1);
    assertNull(windows.view(first).unsaved, "the drag asked");

    first.closeWindow();

    FakeView view = windows.view(first);
    assertNotNull(view.unsaved, "the close asked");
    assertEquals(first.documents().nameOf(edited), view.unsavedName);
    view.unsaved.accept(UnsavedChoice.CANCEL);
    assertEquals(2, set.windows().size());
    assertEquals(2, first.documents().count());
    assertEquals(1, second.documents().count());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void movingATabOutOfAWindowWithUnsavedTabsAsksNothing(boolean on) throws Exception {
    snapshot(on);
    open(first, "edited.txt", "edited\n");
    first.documents().activeEditor().type("x");
    untitled(first, "draft");
    Document dropped = untitled(first, "dropped");
    first.moveTabToNewWindow(windows.view(first).selected.input, 700, 10);
    Document moved = untitled(first, "moved");
    first.execute(CommandId.MOVE_TO_NEW_WINDOW);
    windows.ui.runPending();
    assertNull(windows.view(first).unsaved);
    assertEquals(3, set.windows().size());
    assertEquals(List.of(dropped), windows.controllers.get(1).documents().openDocuments());
    assertEquals(List.of(moved), windows.controllers.get(2).documents().openDocuments());
    assertEquals(2, first.documents().count());
    for (FakeView view : windows.views) {
      assertNull(view.unsaved);
    }
  }

  @Test
  void theTabsGoToTheOtherWindowActivatedLast() throws Exception {
    snapshot(true);
    ApplicationController second = windows.second();
    ApplicationController third = windows.second();
    Document draft = untitled(second, "draft");
    third.windowActivated();
    first.windowActivated();
    second.windowActivated();
    second.closeWindow();
    windows.ui.runPending();
    assertNull(windows.view(second).unsaved);
    assertEquals(List.of(draft), first.documents().openDocuments());
    assertEquals(0, third.documents().count());
  }

  @Test
  void aTabWhoseSaveRunsStaysAndIsAskedAbout() throws Exception {
    snapshot(true);
    ApplicationController second = windows.second();
    Document draft = untitled(second, "draft");
    Document saving = open(second, "saving.txt", "text\n");
    GatedCharset gate = new GatedCharset();
    try {
      second.documents().saveWithEncoding(gate);
      second.documents().activeEditor().type("x");
      gate.armEncoder();
      second.documents().save();
      assertTrue(gate.awaitHeld());
      assertFalse(second.documents().canMove(saving));

      second.closeWindow();

      FakeView view = windows.view(second);
      assertNotNull(view.unsaved, "the close asked");
      assertEquals(second.documents().nameOf(saving), view.unsavedName);
      assertTrue(first.documents().isOpen(draft));
      assertTrue(view.notices.isEmpty(), view.notices.toString());
      view.unsaved.accept(UnsavedChoice.DISCARD);
      windows.ui.runPending();
      assertEquals(List.of(first), set.windows());
      assertEquals(List.of(draft), first.documents().openDocuments());
    } finally {
      gate.release();
    }
    windows.settle("the canceled writer");
  }
}
