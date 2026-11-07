package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Input method composition outside undo, block-preserving edits and the tab settings. */
class PreeditTest {
  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  @Test
  void compositionTextStaysOutOfUndoAndTheDirtyState() {
    final Document document = document("ab");
    final List<DocumentChange.Cause> causes = new ArrayList<DocumentChange.Cause>();
    document.addListener(
        new DocumentListener() {
          @Override
          public void textChanged(DocumentChange change) {
            causes.add(change.cause());
          }

          @Override
          public void dirtyChanged(boolean dirty) {
            // Not recorded.
          }
        });
    document.preedit(1, "ka");
    document.preedit(1, "か");
    assertEquals("aかb", document.text().toString());
    assertArrayEquals(new int[] {1, 1}, document.preeditRange());
    assertFalse(document.isDirty());
    assertFalse(document.canUndo());
    assertThrows(
        IllegalStateException.class,
        new Executable() {
          @Override
          public void execute() {
            document.apply(EditBatch.replace(0, 0, "x"));
          }
        });
    document.clearPreedit();
    assertEquals("ab", document.text().toString());
    assertNull(document.preeditRange());
    document.apply(EditBatch.replace(1, 0, "か"));
    assertTrue(document.canUndo());
    assertEquals(
        List.of(
            DocumentChange.Cause.PREEDIT,
            DocumentChange.Cause.PREEDIT,
            DocumentChange.Cause.PREEDIT,
            DocumentChange.Cause.EDIT),
        causes);
  }

  @Test
  void undoRemovesTheCompositionFirst() {
    Document document = document("ab");
    SelectionModel model = document.newSelectionModel();
    document.apply(EditBatch.replace(2, 0, "c"), model, SelectionSet.caret(3));
    document.preedit(3, "zz");
    assertTrue(document.undo(model));
    assertEquals("ab", document.text().toString());
    assertNull(document.preeditRange());
  }

  @Test
  void aCompositionReplacesOnlyItsOwnText() {
    Document document = document("hello");
    document.preedit(2, "ab");
    document.preedit(0, "abc");
    assertEquals("heabcllo", document.text().toString());
    assertArrayEquals(new int[] {2, 3}, document.preeditRange());
    document.preedit(0, "");
    assertEquals("hello", document.text().toString());
    assertNull(document.preeditRange());
  }

  @Test
  void anUpdateAfterAClearStartsANewComposition() {
    Document document = document("hello");
    document.preedit(2, "k");
    document.clearPreedit();
    document.preedit(2, "ka");
    assertEquals("hekallo", document.text().toString());
    document.preedit(2, "");
    assertEquals("hello", document.text().toString());
    assertFalse(document.isDirty());
  }

  @Test
  void aSelectionSurvivesACompositionAtItsStart() {
    Document document = document("hello world");
    SelectionModel model = document.newSelectionModel();
    SelectionSet selected = SelectionSet.of(List.of(Selection.of(0, 5)), 0);
    model.set(selected);
    document.preedit(0, "n");
    document.preedit(0, "");
    assertEquals("hello world", document.text().toString());
    assertEquals(selected, model.selections());
  }

  @Test
  void anEditInBlockModeKeepsTheBlock() {
    Document document = document("abc\nd");
    SelectionModel model = document.newSelectionModel();
    model.setBlock(new BlockSelection(0, 1, 1, 1), 4);
    BlockSelection after = new BlockSelection(0, 2, 1, 2);
    document.applyInBlock(
        EditBatch.of(
            EditBatch.Kind.TYPING, List.of(new Replacement(1, 0, "x"), new Replacement(5, 0, "x"))),
        model,
        after);
    assertEquals("axbc\ndx", document.text().toString());
    assertEquals(after, model.block());
    assertEquals(2, model.selections().size());
  }

  @Test
  void tabSettingsArePerDocumentAndBounded() {
    final Document document = document("");
    assertEquals(Document.DEFAULT_TAB_WIDTH, document.tabWidth());
    document.setTabWidth(8);
    document.setInsertSpaces(true);
    assertEquals(8, document.tabWidth());
    assertTrue(document.insertSpaces());
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            document.setTabWidth(Document.MAX_TAB_WIDTH + 1);
          }
        });
  }
}
