package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.FindListener;
import io.github.ajdailey.qcaret.app.view.FindState;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The find bar's controller against a fake view, with searches on real workers. */
class FindControllerTest {
  @TempDir Path temp;

  private Harness harness;
  private FindController find;
  private FindState last;
  private int focusRequests;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    find = harness.application.find();
    find.addListener(
        new FindListener() {
          @Override
          public void findStateChanged(FindState state) {
            harness.ui.checkUiThread();
            last = state;
          }

          @Override
          public void focusRequested() {
            focusRequests++;
          }
        });
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private EditorController open(String text) throws Exception {
    harness.open(Files.writeString(temp.resolve("find.txt"), text, StandardCharsets.UTF_8));
    EditorController editor = harness.application.documents().activeEditor();
    editor.viewportChanged(0, editor.projection().length());
    return editor;
  }

  private void settle() throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !find.state().running();
          }
        },
        "search ends");
    harness.ui.runPending();
  }

  private void awaitMatches(final int[] expected) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Arrays.equals(expected, harness.view.selected.matches);
          }
        },
        "matches " + Arrays.toString(expected));
  }

  private static SelectionSet range(int start, int end) {
    return SelectionSet.of(List.of(Selection.of(start, end)), 0);
  }

  @Test
  void typingFindsAndHighlightsWhileOpen() throws Exception {
    EditorController editor = open("alpha beta alpha\nALPHA\n");
    harness.application.execute(CommandId.FIND);
    assertTrue(last.open());
    assertFalse(last.replace());
    assertEquals(1, focusRequests);
    find.setText("alpha");
    settle();
    assertEquals(range(0, 5), editor.selections().selections());
    awaitMatches(new int[] {0, 5, 11, 16, 17, 22});
    find.setOptions(true, false, false);
    awaitMatches(new int[] {0, 5, 11, 16});
    find.close();
    assertFalse(last.open());
    assertArrayEquals(new int[0], harness.view.selected.matches);
  }

  @Test
  void escDropsExtraCaretsFirstThenClosesTheBar() throws Exception {
    EditorController editor = open("alpha beta alpha\n");
    find.open(false);
    find.setText("alpha");
    settle();
    awaitMatches(new int[] {0, 5, 11, 16});
    editor.selections().set(SelectionSet.of(List.of(Selection.caret(0), Selection.caret(6)), 0));
    editor.execute(CommandId.CANCEL);
    assertEquals(SelectionSet.caret(0), editor.selections().selections());
    assertTrue(last.open());
    editor.execute(CommandId.CANCEL);
    assertFalse(last.open());
    assertArrayEquals(new int[0], harness.view.selected.matches);
  }

  @Test
  void nextAndPreviousWrapWithANotice() throws Exception {
    EditorController editor = open("alpha beta alpha\nALPHA\n");
    find.open(false);
    find.setText("alpha");
    settle();
    find.findNext();
    settle();
    assertEquals(range(11, 16), editor.selections().selections());
    harness.application.execute(CommandId.FIND_NEXT);
    settle();
    assertEquals(range(17, 22), editor.selections().selections());
    find.findNext();
    settle();
    assertEquals(range(0, 5), editor.selections().selections());
    assertEquals(UiText.FIND_WRAPPED_TOP.text(), last.notice());
    harness.application.execute(CommandId.FIND_PREVIOUS);
    settle();
    assertEquals(range(17, 22), editor.selections().selections());
    assertEquals(UiText.FIND_WRAPPED_BOTTOM.text(), last.notice());
    find.setText("gamma");
    settle();
    assertEquals(UiText.FIND_NOT_FOUND.text(), last.notice());
    assertTrue(last.error());
  }

  @Test
  void findNextWithoutTextOpensTheBar() throws Exception {
    open("text\n");
    harness.application.execute(CommandId.FIND_NEXT);
    assertTrue(last.open());
    assertEquals(1, focusRequests);
  }

  @Test
  void aSelectionWithinALineSeedsTheText() throws Exception {
    EditorController editor = open("one two\nthree\n");
    editor.selections().set(range(4, 7));
    find.open(true);
    settle();
    assertEquals("two", last.text());
    assertTrue(last.replace());
    editor.selections().set(range(2, 10));
    find.open(false);
    assertEquals("two", last.text());
  }

  @Test
  void aResultForAnOlderRevisionIsDropped() throws Exception {
    EditorController editor = open("abc abc\n");
    find.open(false);
    editor.selections().set(SelectionSet.caret(7));
    find.setText("abc");
    editor.type("!");
    settle();
    assertEquals(SelectionSet.caret(8), editor.selections().selections());
  }

  @Test
  void regexFailuresShowInTheBar() throws Exception {
    open("ab".repeat(200_000) + "\n");
    find.open(false);
    find.setOptions(false, false, true);
    find.setText("(a");
    assertTrue(last.error());
    assertTrue(last.notice().startsWith("Invalid pattern"), last.notice());
    find.setText("(a|b)*c");
    settle();
    assertEquals(UiText.FIND_TOO_COMPLEX.text(), last.notice());
    assertTrue(last.error());
  }

  @Test
  void replaceThenReplaceAllAreOneUndoStepEach() throws Exception {
    EditorController editor = open("cat cat cat\n");
    find.open(true);
    find.setText("cat");
    find.setReplacement("dog");
    settle();
    assertEquals(range(0, 3), editor.selections().selections());
    find.replace();
    settle();
    settle();
    assertEquals("dog cat cat\n", editor.document().text().toString());
    assertEquals(range(4, 7), editor.selections().selections());
    find.replaceAll();
    settle();
    assertEquals("dog dog dog\n", editor.document().text().toString());
    assertEquals(UiText.FIND_REPLACED.format(2), last.notice());
    editor.undo();
    assertEquals("dog cat cat\n", editor.document().text().toString());
    editor.undo();
    assertEquals("cat cat cat\n", editor.document().text().toString());
  }

  @Test
  void replaceAllDuringACompositionDropsTheCompositionAndReplaces() throws Exception {
    EditorController editor = open("cat cat\n");
    editor.preeditFromView(0, 0, "ca");
    find.open(true);
    find.setText("cat");
    find.setReplacement("dog");
    settle();
    find.replaceAll();
    settle();
    assertEquals("dog dog\n", editor.document().text().toString());
    assertNull(editor.document().preeditRange());
  }

  @Test
  void replaceAllAfterAnEditReportsTheDroppedResult() throws Exception {
    EditorController editor = open("cat cat\n");
    find.open(true);
    find.setText("cat");
    find.setReplacement("dog");
    settle();
    editor.selections().set(SelectionSet.caret(0));
    find.replaceAll();
    editor.type("x");
    settle();
    assertEquals("xcat cat\n", editor.document().text().toString());
    assertEquals(UiText.FIND_REPLACE_ALL_STALE.text(), last.notice());
    assertTrue(last.error());
  }

  @Test
  void replaceAfterAnEditReportsTheDroppedResult() throws Exception {
    EditorController editor = open("cat cat\n");
    find.open(true);
    find.setText("cat");
    find.setReplacement("dog");
    settle();
    find.replace();
    editor.selections().set(SelectionSet.caret(7));
    editor.type("x");
    settle();
    assertEquals("cat catx\n", editor.document().text().toString());
    assertEquals(UiText.FIND_REPLACE_STALE.text(), last.notice());
    assertTrue(last.error());
  }

  @Test
  void regexReplacementExpandsGroupsAndTakesTheLineEnding() throws Exception {
    EditorController editor = open("a=1\r\nb=2\r\n");
    find.open(true);
    find.setOptions(true, false, true);
    find.setText("(\\w)=(\\d)");
    find.setReplacement("$2\\n$1");
    settle();
    find.replaceAll();
    settle();
    assertEquals("1\r\na\r\n2\r\nb\r\n", editor.document().text().toString());
    find.setReplacement("$9");
    find.replaceAll();
    assertTrue(last.notice().startsWith("Invalid replacement"), last.notice());
  }

  @Test
  void aReadOnlyDocumentCannotBeReplaced() throws Exception {
    EditorController editor = open("cat\n");
    find.open(true);
    find.setText("cat");
    settle();
    assertTrue(last.replaceEnabled());
    editor.document().setReadOnly(true);
    find.viewsChanged();
    assertFalse(last.replaceEnabled());
    find.replaceAll();
    settle();
    assertEquals("cat\n", editor.document().text().toString());
  }

  @Test
  void everyViewOfTheDocumentHighlightsItsVisibleRange() throws Exception {
    EditorController first = open("xx yy xx\nyy xx\n");
    find.open(false);
    find.setText("xx");
    awaitMatches(new int[] {0, 2, 6, 8, 12, 14});
    harness.application.execute(CommandId.SPLIT_VERTICAL);
    final EditorController second = harness.application.documents().activeEditor();
    final FakeView.FakeEditor view = harness.view.selected.splits.get(0);
    second.viewportChanged(9, 15);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Arrays.equals(new int[] {12, 14}, view.matches);
          }
        },
        "second view's matches");
    first.selections().set(SelectionSet.caret(0));
    first.type("_");
    assertArrayEquals(new int[] {1, 3, 7, 9, 13, 15}, harness.view.selected.matches);
    awaitMatches(new int[] {1, 3, 7, 9, 13, 15});
  }

  private UiState saved() {
    return harness.application.settings().current().state();
  }

  @Test
  void findNextAndPreviousSaveTheTextAndTypingDoesNot() throws Exception {
    open("alpha beta alpha\n");
    find.open(false);
    find.setText("al");
    settle();
    find.setText("alpha");
    settle();
    assertEquals(List.of(), saved().texts());
    find.findNext();
    settle();
    assertEquals(List.of("alpha"), saved().texts());
    find.setText("beta");
    settle();
    find.findPrevious();
    settle();
    find.setText("alpha");
    settle();
    assertEquals(List.of("beta", "alpha"), last.textHistory());
    find.findNext();
    settle();
    assertEquals(List.of("alpha", "beta"), saved().texts());
    assertEquals(List.of("alpha", "beta"), last.textHistory());
  }

  @Test
  void closingTheBarSavesATextThatCompiles() throws Exception {
    open("alpha\n");
    find.open(false);
    find.setOptions(false, false, true);
    find.setText("(");
    settle();
    find.close();
    assertEquals(List.of(), saved().texts());
    find.open(false);
    find.setText("alp+");
    settle();
    find.close();
    assertEquals(List.of("alp+"), saved().texts());
  }

  @Test
  void theHistoryKeepsTwentyAndLeavesOutALongEntry() throws Exception {
    open("x\n");
    find.open(false);
    for (int i = 0; i < UiState.MAX_HISTORY + 5; i++) {
      find.setText("text " + i);
      settle();
      find.findNext();
      settle();
    }
    find.setText("y".repeat(UiState.MAX_ENTRY_CHARS + 1));
    settle();
    find.findNext();
    settle();
    assertEquals(UiState.MAX_HISTORY, saved().texts().size());
    assertEquals("text 24", saved().texts().get(0));
    assertEquals("text 5", saved().texts().get(UiState.MAX_HISTORY - 1));
  }

  @Test
  void replaceAndReplaceAllSaveBothTexts() throws Exception {
    open("alpha alpha\n");
    find.open(true);
    find.setText("alpha");
    settle();
    find.setReplacement("beta");
    find.replace();
    settle();
    assertEquals(List.of("beta"), saved().replacements());
    assertEquals(List.of("alpha"), saved().texts());
    find.setReplacement("gamma");
    find.replaceAll();
    settle();
    assertEquals(List.of("gamma", "beta"), last.replaceHistory());
    find.setText("beta");
    settle();
    find.setReplacement("");
    find.replaceAll();
    settle();
    assertEquals(List.of("gamma", "beta"), saved().replacements());
    assertEquals(List.of("beta", "alpha"), saved().texts());
  }

  @Test
  void aRefusedReplaceSavesNothing() throws Exception {
    EditorController editor = open("alpha\n");
    find.open(true);
    find.setText("alpha");
    settle();
    editor.document().setReadOnly(true);
    find.setReplacement("beta");
    find.replace();
    find.replaceAll();
    assertEquals(List.of(), saved().texts());
    assertEquals(List.of(), saved().replacements());
  }

  @Test
  void theFindBarAndHyperSearchShareTheTextHistory() throws Exception {
    open("alpha\n");
    find.open(false);
    find.setText("alpha");
    settle();
    find.findNext();
    settle();
    harness.application.execute(CommandId.HYPERSEARCH);
    assertEquals(List.of("alpha"), harness.view.searchForm.textHistory());
    Preferences current = harness.application.settings().current();
    UiState state = current.state();
    harness
        .application
        .settings()
        .set(
            current.withState(
                state.withHistory(List.of("beta", "alpha"), state.directories(), state.masks())));
    assertEquals(List.of("beta", "alpha"), last.textHistory());
  }

  @Test
  void clearedHistoriesReachAnOpenBarAndTheFieldsStay() throws Exception {
    open("alpha\n");
    find.open(true);
    find.setText("alpha");
    settle();
    find.setReplacement("beta");
    find.replaceAll();
    settle();
    assertEquals(List.of("beta"), last.replaceHistory());
    Preferences current = harness.application.settings().current();
    harness.application.settings().set(current.withState(current.state().withoutHistory()));
    assertEquals(List.of(), last.textHistory());
    assertEquals(List.of(), last.replaceHistory());
    assertEquals("alpha", last.text());
    assertEquals("beta", last.replacement());
  }

  @Test
  void shiftedDropsRangesAnEditTouches() {
    int[] ranges = {0, 2, 5, 7, 10, 12};
    List<TextEdit> edits =
        List.of(new TextEdit(3, "", "abc", 0, 0, 0), new TextEdit(6, "x", "", 0, 0, 0));
    assertArrayEquals(new int[] {0, 2, 12, 14}, FindController.shifted(ranges, edits));
    assertArrayEquals(
        new int[] {3, 5},
        FindController.shifted(new int[] {0, 2}, List.of(new TextEdit(0, "", "abc", 0, 0, 0))));
  }
}
