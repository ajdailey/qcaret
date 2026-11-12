package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** View > Indent Guides, View > Modified Line Marks and Edit > Line > Wrap Paragraph. */
class EditorAidsTest {
  @TempDir Path temp;

  private Harness harness;
  private Path file;

  @BeforeEach
  void create() throws IOException {
    Files.createDirectory(temp.resolve("settings"));
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private EditorController open(String text) throws Exception {
    file = temp.resolve("a.txt");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    harness.open(file);
    return harness.application.documents().activeEditor();
  }

  private FakeView.Shown shown() {
    return harness.view.selected.shown;
  }

  /** Waits for marks of the document's current revision and returns them. */
  private LineChanges marks(final EditorController editor) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown().lineChanges.revision() == editor.document().revision();
          }
        },
        "marks");
    return shown().lineChanges;
  }

  private static void select(EditorController editor, int anchor, int caret) {
    editor.selections().set(SelectionSet.of(List.of(Selection.of(anchor, caret)), 0));
  }

  @Test
  void theIndentGuidesToggleReachesTheViewAndIsSaved() throws Exception {
    CommandStates states = new CommandStates(harness.application);
    assertFalse(harness.view.indentGuides);
    assertFalse(states.checked(CommandId.INDENT_GUIDES));
    assertTrue(states.enabled(CommandId.INDENT_GUIDES));
    harness.application.execute(CommandId.INDENT_GUIDES);
    assertTrue(harness.view.indentGuides);
    assertTrue(states.checked(CommandId.INDENT_GUIDES));
    assertTrue(harness.application.preferences().state().indentGuides());
    harness.application.execute(CommandId.INDENT_GUIDES);
    assertFalse(harness.view.indentGuides);
  }

  @Test
  void marksFollowInsertChangeDeleteUndoAndSave() throws Exception {
    EditorController editor = open("a\nb\nc\n");
    assertSame(LineChanges.NONE, shown().lineChanges);
    harness.application.execute(CommandId.LINE_CHANGES);
    assertTrue(new CommandStates(harness.application).checked(CommandId.LINE_CHANGES));
    assertFalse(marks(editor).any());

    editor.selections().set(SelectionSet.caret(1));
    editor.newline();
    editor.type("x");
    assertEquals(LineChanges.ADDED, marks(editor).kind(1));
    while (editor.document().isDirty()) {
      editor.undo();
    }
    assertFalse(marks(editor).any());

    select(editor, 2, 3);
    editor.type("B");
    LineChanges changed = marks(editor);
    assertEquals(LineChanges.CHANGED, changed.kind(1));
    assertEquals(0, changed.kind(0) | changed.kind(2));

    harness.application.execute(CommandId.SAVE);
    harness.awaitIdle("save");
    assertFalse(marks(editor).any());
    assertFalse(editor.document().isDirty());

    select(editor, 2, 4);
    editor.backspace();
    assertEquals("a\nc\n", editor.document().text().toString());
    assertEquals(LineChanges.REMOVED_ABOVE, marks(editor).kind(1));

    harness.application.execute(CommandId.LINE_CHANGES);
    assertSame(LineChanges.NONE, shown().lineChanges);
    assertFalse(harness.application.preferences().state().lineChanges());
  }

  @Test
  void reloadClearsTheMarks() throws Exception {
    final EditorController editor = open("a\nb\n");
    harness.application.execute(CommandId.LINE_CHANGES);
    editor.selections().set(SelectionSet.caret(0));
    editor.type("z");
    assertEquals(LineChanges.CHANGED, marks(editor).kind(0));
    Files.writeString(file, "a\nb\nc\n", StandardCharsets.UTF_8);
    harness.application.execute(CommandId.RELOAD);
    harness.view.reloadAccept.run();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !editor.document().isDirty();
          }
        },
        "reloaded");
    assertEquals("a\nb\nc\n", editor.document().text().toString());
    assertFalse(marks(editor).any());
  }

  @Test
  void wrapParagraphWrapsAtTheRightMarginAsOneUndoStep() throws Exception {
    String line = "word ".repeat(19) + "end";
    final EditorController editor = open("  " + line + "\n\nnext\n");
    editor.selections().set(SelectionSet.caret(5));
    assertTrue(new CommandStates(harness.application).enabled(CommandId.WRAP_PARAGRAPH));
    harness.application.execute(CommandId.WRAP_PARAGRAPH);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.document().text().lineCount() == 5;
          }
        },
        "wrapped");
    String first = editor.document().text().line(0);
    assertEquals("  " + "word ".repeat(14) + "word", first);
    assertEquals("  word word word word end", editor.document().text().line(1));
    editor.undo();
    assertEquals("  " + line + "\n\nnext\n", editor.document().text().toString());
  }
}
