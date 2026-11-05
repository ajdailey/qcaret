package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Go Back and Go Forward across jumps, edits, tab switches and closed documents. */
class NavigationHistoryTest {
  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private Path file(String name) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 60; i++) {
      text.append(name).append(" line ").append(i).append('\n');
    }
    return Files.writeString(temp.resolve(name), text.toString());
  }

  private DocumentController documents() {
    return harness.application.documents();
  }

  private EditorController editor() {
    return documents().activeEditor();
  }

  private int line() {
    EditorController editor = editor();
    return editor.document().text().lineAt(editor.selections().selections().primary().caret());
  }

  private void caretAt(int line) {
    EditorController editor = editor();
    editor.selections().set(SelectionSet.caret(editor.document().text().offsetOfLine(line)));
  }

  private void run(CommandId command) throws InterruptedException {
    harness.application.execute(command);
    harness.awaitIdle(command.name());
  }

  private String active() {
    return String.valueOf(documents().activePath().getFileName());
  }

  @Test
  void goToLineJumpsGoBackAndForwardAndFollowEdits() throws Exception {
    harness.open(file("a.txt"));
    NavigationHistory history = harness.application.history();
    assertFalse(history.canBack());
    caretAt(10);
    editor().goTo(30, 0);
    caretAt(31);
    assertTrue(history.canBack());
    editor().selections().set(SelectionSet.caret(0));
    editor().type("new 1\nnew 2\n");
    caretAt(40);
    run(CommandId.GO_BACK);
    assertEquals(12, line());
    assertTrue(history.canForward());
    run(CommandId.GO_FORWARD);
    assertEquals(40, line());
    assertFalse(history.canForward());
    run(CommandId.GO_BACK);
    assertEquals(12, line());
    editor().goTo(5, 0);
    assertFalse(history.canForward());
    run(CommandId.GO_BACK);
    assertEquals(12, line());
  }

  @Test
  void ordinaryCaretMovesRecordNothing() throws Exception {
    harness.open(file("a.txt"));
    caretAt(3);
    run(CommandId.CARET_DOWN);
    caretAt(20);
    assertFalse(harness.application.history().canBack());
    caretAt(40);
    run(CommandId.TOGGLE_BOOKMARK);
    caretAt(2);
    run(CommandId.NEXT_BOOKMARK);
    assertEquals(40, line());
    run(CommandId.GO_BACK);
    assertEquals(2, line());
  }

  @Test
  void aTabSwitchRecordsTheDocumentLeftOnce() throws Exception {
    harness.open(file("a.txt"));
    caretAt(7);
    harness.open(file("b.txt"));
    caretAt(9);
    documents().select(0);
    harness.ui.runPending();
    assertEquals("a.txt", active());
    run(CommandId.GO_BACK);
    assertEquals("b.txt", active());
    assertEquals(9, line());
    run(CommandId.GO_BACK);
    assertEquals("a.txt", active());
    assertEquals(7, line());
    run(CommandId.GO_FORWARD);
    assertEquals("b.txt", active());
    run(CommandId.GO_FORWARD);
    assertEquals("a.txt", active());
    assertEquals(7, line());
  }

  @Test
  void aClosedFileReopensAtItsLineAndAnUntitledOneIsSkipped() throws Exception {
    harness.open(file("a.txt"));
    caretAt(25);
    documents().newDocument();
    editor().type("untitled text");
    harness.open(file("b.txt"));
    documents().select(1);
    harness.ui.runPending();
    documents().closeAll(null);
    harness.ui.runPending();
    harness.view.unsaved.accept(UnsavedChoice.DISCARD);
    harness.awaitIdle("closed");
    assertEquals(0, documents().count());
    harness.open(file("c.txt"));
    run(CommandId.GO_BACK);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().count() == 2;
          }
        },
        "b.txt reopened");
    harness.awaitIdle("reopen");
    assertEquals("b.txt", active());
    run(CommandId.GO_BACK);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().count() == 3;
          }
        },
        "a.txt reopened");
    harness.awaitIdle("reopen");
    assertEquals("a.txt", active());
    assertEquals(25, line());
  }

  @Test
  void aMissingFileIsDroppedWithANotice() throws Exception {
    Path a = file("a.txt");
    harness.open(a);
    caretAt(4);
    harness.open(file("b.txt"));
    documents().select(0);
    documents().closeActive();
    harness.awaitIdle("closed");
    Files.delete(a);
    caretAt(6);
    run(CommandId.GO_BACK);
    assertEquals(0, line());
    run(CommandId.GO_BACK);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.contains(
                "a.txt no longer exists; its position was dropped");
          }
        },
        "missing notice");
    assertEquals(1, documents().count());
  }

  @Test
  void theHistoryKeepsItsLimit() throws Exception {
    harness.open(file("a.txt"));
    for (int i = 0; i < NavigationHistory.LIMIT + 30; i++) {
      editor().goTo(i % 2 == 0 ? 10 : 50, 0);
    }
    assertEquals(NavigationHistory.LIMIT, harness.application.history().size());
  }
}
