package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two views of one document: own selections and rows, one text, closed with the tab. */
class SplitViewTest {
  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;
  private CommandStates states;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    documents = harness.application.documents();
    states = new CommandStates(harness.application);
    harness.open(Files.writeString(temp.resolve("split.txt"), "one\ntwo\nthree\n"));
  }

  @AfterEach
  void close() throws InterruptedException {
    documents.dispose();
    harness.close();
  }

  @Test
  void eachViewKeepsItsOwnSelectionsOverOneText() {
    EditorController first = documents.activeEditor();
    first.selections().set(SelectionSet.of(List.of(Selection.of(4, 7)), 0));
    harness.application.execute(CommandId.SPLIT_VERTICAL);
    EditorController second = documents.activeEditor();
    FakeView.FakeTab tab = harness.view.selected;
    assertNotSame(first, second);
    assertSame(first.document(), second.document());
    assertEquals(1, tab.splits.size());
    assertTrue(tab.sideBySide);
    assertEquals(first.selections().selections(), second.selections().selections());
    assertTrue(states.checked(CommandId.SPLIT_VERTICAL));
    assertTrue(states.enabled(CommandId.UNSPLIT));

    second.selections().set(SelectionSet.caret(0));
    second.type("x");
    assertEquals("xone\ntwo\nthree\n", first.document().text().toString());
    assertEquals(SelectionSet.of(List.of(Selection.of(5, 8)), 0), first.selections().selections());
    assertEquals(first.document().text().length(), first.projection().modelLength());
    assertEquals(second.projection().length(), first.projection().length());

    harness.application.execute(CommandId.SPLIT_HORIZONTAL);
    assertFalse(tab.sideBySide);
    assertTrue(states.checked(CommandId.SPLIT_HORIZONTAL));
    assertEquals(1, tab.splits.size());
  }

  @Test
  void focusPicksTheViewCommandsActOn() {
    EditorController first = documents.activeEditor();
    harness.application.execute(CommandId.SPLIT_HORIZONTAL);
    EditorController second = documents.activeEditor();
    harness.view.selected.input.viewFocused(first);
    assertSame(first, documents.activeEditor());
    harness.application.execute(CommandId.FOCUS_OTHER_VIEW);
    assertSame(second, documents.activeEditor());
    assertEquals(1, harness.view.selected.splits.get(0).focused);
  }

  @Test
  void unsplitKeepsTheFocusedViewAndStopsTheOther() {
    EditorController first = documents.activeEditor();
    harness.application.execute(CommandId.SPLIT_VERTICAL);
    EditorController second = documents.activeEditor();
    harness.application.execute(CommandId.UNSPLIT);
    assertSame(second, documents.activeEditor());
    assertEquals(List.of(second.view()), harness.view.selected.splits);
    assertFalse(states.enabled(CommandId.UNSPLIT));
    int stopped = first.projection().modelLength();
    second.type("y");
    assertEquals(stopped, first.projection().modelLength());
    assertEquals(stopped + 1, second.projection().modelLength());
  }

  @Test
  void closingTheTabStopsBothViewsAndWrapReachesBoth() {
    EditorController first = documents.activeEditor();
    harness.application.execute(CommandId.SPLIT_VERTICAL);
    EditorController second = documents.activeEditor();
    harness.application.execute(CommandId.WORD_WRAP);
    assertTrue(first.projection().wrap());
    assertTrue(second.projection().wrap());
    final int length = first.document().text().length();
    second.type("z");
    harness.application.execute(CommandId.CLOSE);
    harness.view.unsaved.accept(UnsavedChoice.DISCARD);
    assertFalse(documents.isOpen(first.document()));
    first.document().apply(EditBatch.replace(0, 0, "w"));
    assertEquals(length + 1, first.projection().modelLength());
    assertEquals(length + 1, second.projection().modelLength());
  }
}
