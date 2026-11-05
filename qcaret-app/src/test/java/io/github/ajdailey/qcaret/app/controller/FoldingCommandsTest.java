package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.IndentRules;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fold commands, carets around collapsed regions, split views and smart indent through the
 * controllers.
 */
class FoldingCommandsTest {
  private static final String TEXT =
      "class A {\n  void f() {\n    one();\n    two();\n  }\n  int g;\n}\ntail\n";

  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;
  private CommandStates states;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    documents = harness.application.documents();
    states = new CommandStates(harness.application);
    harness.open(Files.writeString(temp.resolve("fold.txt"), TEXT));
    awaitRegions(documents.activeEditor());
  }

  @AfterEach
  void close() throws InterruptedException {
    documents.dispose();
    harness.close();
  }

  private void awaitRegions(final EditorController editor) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.projection().foldRegionCount() > 0;
          }
        },
        "fold regions");
  }

  private void caret(EditorController editor, int line, int column) {
    editor.goTo(line, column);
  }

  private String shown(EditorController editor) {
    DisplayProjection p = editor.projection();
    return p.textRange(0, p.length());
  }

  @Test
  void indentationGivesTheRegionsAndTheCommandsFoldThem() {
    EditorController editor = documents.activeEditor();
    DisplayProjection p = editor.projection();
    assertEquals(2, p.foldRegionCount());
    assertEquals(5, p.foldEnd(0));
    assertEquals(3, p.foldEnd(1));
    assertTrue(states.enabled(CommandId.FOLD));
    assertFalse(states.enabled(CommandId.UNFOLD_ALL));

    caret(editor, 2, 4);
    harness.application.execute(CommandId.FOLD);
    assertTrue(p.collapsed(1));
    assertEquals("class A {\n  void f() {\n  }\n  int g;\n}\ntail\n", shown(editor));
    // The caret leaves the hidden lines for the end of the region's first line.
    assertEquals(SelectionSet.caret(TEXT.indexOf(" {\n    one") + 2), selections(editor));
    assertTrue(states.enabled(CommandId.UNFOLD));

    harness.application.execute(CommandId.TOGGLE_FOLD);
    assertFalse(p.collapsed(1));
    harness.application.execute(CommandId.FOLD_ALL);
    assertEquals(2, p.collapsedCount());
    assertEquals("class A {\n}\ntail\n", shown(editor));
    harness.application.execute(CommandId.UNFOLD);
    assertTrue(p.collapsed(1));
    harness.application.execute(CommandId.UNFOLD_ALL);
    assertEquals(0, p.collapsedCount());
    assertEquals(TEXT, shown(editor));
  }

  @Test
  void movesStepOverCollapsedLinesAndOtherCommandsReveal() {
    EditorController editor = documents.activeEditor();
    DisplayProjection p = editor.projection();
    p.foldAt(1);
    int headEnd = TEXT.indexOf("{\n    one") + 1;
    editor.selections().set(SelectionSet.caret(headEnd));
    editor.execute(CommandId.CARET_RIGHT);
    assertEquals(TEXT.indexOf("  }"), caretOf(editor));
    editor.execute(CommandId.CARET_LEFT);
    assertEquals(headEnd, caretOf(editor));
    editor.execute(CommandId.CARET_DOWN);
    assertEquals(4, p.lineAt(caretOf(editor)));
    editor.execute(CommandId.CARET_UP);
    assertEquals(1, p.lineAt(caretOf(editor)));
    assertTrue(p.collapsed(1));

    caret(editor, 3, 4);
    assertFalse(p.collapsed(1));
    assertEquals(TEXT, shown(editor));
  }

  @Test
  void anEditInHiddenLinesExpandsTheRegion() {
    EditorController editor = documents.activeEditor();
    DisplayProjection p = editor.projection();
    p.foldAt(1);
    int hidden = TEXT.indexOf("two");
    int line6 = TEXT.indexOf("tail");
    // A secondary caret in hidden lines edits there; the region expands.
    editor
        .selections()
        .set(SelectionSet.of(List.of(Selection.caret(line6), Selection.caret(hidden)), 0));
    editor.type("x");
    assertEquals(0, p.collapsedCount());
    assertTrue(harness.application.documents().activeDocument().text().toString().contains("xtwo"));
  }

  @Test
  void splitViewsFoldIndependently() throws InterruptedException {
    EditorController first = documents.activeEditor();
    first.projection().foldAt(0);
    harness.application.execute(CommandId.SPLIT_VERTICAL);
    EditorController second = documents.activeEditor();
    awaitRegions(second);
    assertEquals(0, second.projection().collapsedCount());
    assertTrue(first.projection().collapsed(0));
    harness.application.execute(CommandId.FOLD_ALL);
    assertEquals(2, second.projection().collapsedCount());
    assertEquals(1, first.projection().collapsedCount());
    harness.application.execute(CommandId.UNSPLIT);
    assertEquals(2, documents.activeEditor().projection().collapsedCount());
  }

  @Test
  void enterKeepsTheIndentationAndOpensALevelAfterAnOpener() {
    EditorController editor = documents.activeEditor();
    editor.document().setInsertSpaces(true);
    editor.document().setTabWidth(2);
    caret(editor, 2, 10);
    editor.newline();
    assertEquals("    one();\n    ", lines(editor, 2, 3));
    caret(editor, 1, 12);
    editor.newline();
    assertEquals("  void f() {\n    \n    one();", lines(editor, 1, 3));
  }

  @Test
  void enterBetweenBracketsPutsTheCloserOnItsOwnLine() throws Exception {
    harness.open(Files.writeString(temp.resolve("pair.txt"), "\tif (x) {}\n"));
    EditorController editor = documents.activeEditor();
    editor.document().setInsertSpaces(false);
    editor.selections().set(SelectionSet.caret(9));
    editor.newline();
    assertEquals("\tif (x) {\n\t\t\n\t}\n", editor.document().text().toString());
    assertEquals(SelectionSet.caret(12), selections(editor));
    editor.undo();
    assertEquals("\tif (x) {}\n", editor.document().text().toString());
  }

  @Test
  void aCloserOnABlankLineOutdents() throws Exception {
    harness.open(Files.writeString(temp.resolve("close.txt"), "if (x) {\n    a();\n    \n"));
    EditorController editor = documents.activeEditor();
    editor.document().setInsertSpaces(true);
    editor.document().setTabWidth(4);
    caret(editor, 2, 4);
    editor.type("}");
    assertEquals("if (x) {\n    a();\n}\n", editor.document().text().toString());
    caret(editor, 1, 0);
    editor.type(")");
    assertEquals("if (x) {\n)    a();\n}\n", editor.document().text().toString());
    editor.setIndentRules(
        new IndentRules() {
          @Override
          public boolean increase(CharSequence before) {
            return false;
          }

          @Override
          public boolean decrease(CharSequence line) {
            return false;
          }
        });
    caret(editor, 2, 1);
    editor.newline();
    assertEquals("if (x) {\n)    a();\n}\n\n", editor.document().text().toString());
  }

  private static int caretOf(EditorController editor) {
    return editor.selections().selections().primary().caret();
  }

  private static SelectionSet selections(EditorController editor) {
    return editor.selections().selections();
  }

  private static String lines(EditorController editor, int from, int to) {
    DisplayProjection p = editor.projection();
    int start = p.lineStart(from);
    int end = to + 1 < p.lineCount() ? p.lineStart(to + 1) - 1 : p.modelLength();
    return p.modelText(start, end);
  }
}
