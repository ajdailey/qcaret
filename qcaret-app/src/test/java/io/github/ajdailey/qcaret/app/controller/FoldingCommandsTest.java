package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.IndentRules;
import io.github.ajdailey.qcaret.app.editor.PatternIndentRules;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
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

  private void awaitCollapsed(final DisplayProjection p, final int count)
      throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return p.collapsedCount() == count;
          }
        },
        count + " collapsed regions");
  }

  private void caret(EditorController editor, int line, int column) {
    editor.goTo(line, column);
  }

  private String shown(EditorController editor) {
    DisplayProjection p = editor.projection();
    return p.textRange(0, p.length());
  }

  @Test
  void aCompositionBelowACollapsedRegionLeavesTheTextUnchanged() throws InterruptedException {
    EditorController editor = documents.activeEditor();
    DisplayProjection p = editor.projection();
    p.foldAll();
    awaitCollapsed(p, p.foldRegionCount());
    caret(editor, 8, 1);
    editor.preeditFromView("ab");
    editor.preeditFromView("abc");
    editor.preeditFromView("");
    assertEquals(TEXT, editor.document().text().toString());
    assertFalse(editor.document().isDirty());
  }

  @Test
  void indentationGivesTheRegionsAndTheCommandsFoldThem() throws InterruptedException {
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
    awaitCollapsed(p, 2);
    assertEquals("class A {\n}\ntail\n", shown(editor));
    harness.application.execute(CommandId.UNFOLD);
    assertTrue(p.collapsed(1));
    harness.application.execute(CommandId.UNFOLD_ALL);
    assertEquals(0, p.collapsedCount());
    assertEquals(TEXT, shown(editor));
  }

  @Test
  void theFoldMarginAndToggleFoldFoldAnExpandedRegion() {
    EditorController editor = documents.activeEditor();
    DisplayProjection p = editor.projection();
    int headEnd = TEXT.indexOf(" {\n    one") + 2;
    caret(editor, 2, 4);
    editor.toggleFoldFromView(1);
    assertTrue(p.collapsed(1));
    assertEquals(SelectionSet.caret(headEnd), selections(editor));
    editor.toggleFoldFromView(1);
    assertEquals(0, p.collapsedCount());
    // A row past the last clamps to the last line, which starts no region.
    editor.toggleFoldFromView(99);
    assertEquals(0, p.collapsedCount());

    caret(editor, 2, 4);
    harness.application.execute(CommandId.TOGGLE_FOLD);
    assertTrue(p.collapsed(1));
    assertEquals(SelectionSet.caret(headEnd), selections(editor));
    harness.application.execute(CommandId.UNFOLD_ALL);

    // A block keeps its corners in the hidden lines.
    editor.selections().setBlock(new BlockSelection(2, 4, 3, 4), 4);
    harness.application.execute(CommandId.FOLD);
    assertTrue(p.collapsed(1));
    assertEquals(new BlockSelection(2, 4, 3, 4), editor.selections().block());
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
    awaitCollapsed(second.projection(), 2);
    assertEquals(1, first.projection().collapsedCount());
    harness.application.execute(CommandId.UNSPLIT);
    assertEquals(2, documents.activeEditor().projection().collapsedCount());
  }

  @Test
  void nextAndPreviousBookmarkExpandTheRegionHidingTheirLine() {
    EditorController editor = documents.activeEditor();
    DisplayProjection p = editor.projection();
    caret(editor, 3, 0);
    harness.application.execute(CommandId.TOGGLE_BOOKMARK);
    caret(editor, 0, 0);
    p.foldAt(1);
    harness.application.execute(CommandId.NEXT_BOOKMARK);
    assertEquals(TEXT.indexOf("    two"), caretOf(editor));
    assertFalse(p.collapsed(1));
    caret(editor, 6, 0);
    p.foldAt(1);
    harness.application.execute(CommandId.PREVIOUS_BOOKMARK);
    assertEquals(TEXT.indexOf("    two"), caretOf(editor));
    assertFalse(p.collapsed(1));
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
  void pythonEnterAfterReturnDedentsAndAfterAColonIndents() throws Exception {
    harness.open(Files.writeString(temp.resolve("mock.py"), "def f(x):\n    if x: return 1\n"));
    EditorController editor = documents.activeEditor();
    editor.document().setInsertSpaces(true);
    editor.document().setTabWidth(4);
    editor.setIndentRules(
        new PatternIndentRules(
            Pattern.compile(":\\s*(#.*)?$"),
            null,
            Pattern.compile("^\\s*(?:return|pass|break|continue|raise)\\b")));
    caret(editor, 1, 10);
    editor.newline();
    assertEquals("def f(x):\n    if x: \n        return 1\n", editor.document().text().toString());
    caret(editor, 2, 16);
    editor.newline();
    assertEquals(
        "def f(x):\n    if x: \n        return 1\n    \n", editor.document().text().toString());
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
          public boolean dedentNext(CharSequence before) {
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

  /** Occupies a CPU worker until the returned latch counts down; counts {@code started} down. */
  private CountDownLatch hold(final CountDownLatch started) {
    final CountDownLatch gate = new CountDownLatch(1);
    harness.workers.executeCpu(
        new Runnable() {
          @Override
          public void run() {
            started.countDown();
            try {
              gate.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    return gate;
  }

  @Test
  void aTransformEndingWhileFoldAllRunsLeavesTheEditorBusy() throws InterruptedException {
    DisplayProjection p = documents.activeEditor().projection();
    final List<Boolean> busy = harness.view.selected.shown.busy;
    assertEquals(List.of(), busy);
    CountDownLatch none = new CountDownLatch(0);
    CountDownLatch first = hold(none);
    CountDownLatch second = hold(none);
    // The CPU queue holds the transform, a third gate, then Fold All, behind both workers.
    harness.application.execute(CommandId.TRIM_TRAILING_WHITESPACE);
    CountDownLatch reached = new CountDownLatch(1);
    CountDownLatch third = hold(reached);
    harness.application.execute(CommandId.FOLD_ALL);
    try {
      first.countDown();
      // The freed worker ran the transform before it reached the third gate.
      assertTrue(reached.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "transform ran");
      harness.ui.runPending();
      assertEquals(0, p.collapsedCount());
      assertEquals(List.of(true), busy, "Fold All still holds the editor's busy cursor");
    } finally {
      second.countDown();
      third.countDown();
    }
    awaitCollapsed(p, 2);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !busy.get(busy.size() - 1);
          }
        },
        "idle");
    assertEquals(List.of(true, false), busy);
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
