package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class DocumentTest {
  private static final TextEncoding UTF8 = TextEncoding.of(StandardCharsets.UTF_8, false);

  private long now;
  private final LongSupplier clock =
      new LongSupplier() {
        @Override
        public long getAsLong() {
          return now;
        }
      };

  private Document document(String text) {
    return new Document(text, UTF8, LineEnding.LF, UndoLimits.DEFAULTS, clock);
  }

  /** Records every event a document fires. */
  private static final class Recorder implements DocumentListener {
    final List<DocumentChange> changes = new ArrayList<DocumentChange>();
    final List<Boolean> dirty = new ArrayList<Boolean>();
    int saved;

    @Override
    public void textChanged(DocumentChange change) {
      changes.add(change);
    }

    @Override
    public void textSaved() {
      saved++;
    }

    @Override
    public void dirtyChanged(boolean value) {
      dirty.add(value);
    }
  }

  private static EditBatch typing(int... offsetsThenChar) {
    List<Replacement> out = new ArrayList<Replacement>();
    char c = (char) offsetsThenChar[offsetsThenChar.length - 1];
    for (int i = 0; i < offsetsThenChar.length - 1; i++) {
      out.add(new Replacement(offsetsThenChar[i], 0, String.valueOf(c)));
    }
    return EditBatch.of(EditBatch.Kind.TYPING, out);
  }

  private void type(Document doc, SelectionModel carets, String text) {
    for (int i = 0; i < text.length(); i++) {
      int at = carets.selections().primary().caret();
      doc.apply(
          EditBatch.of(
              EditBatch.Kind.TYPING, List.of(new Replacement(at, 0, text.substring(i, i + 1)))),
          carets,
          SelectionSet.caret(at + 1));
      now += 100_000_000L;
    }
  }

  @Test
  void aBatchIsOneChangeWithLinesInTheTextBeforeIt() {
    Document doc = document("one\ntwo\r\nthree\rfour");
    Recorder recorder = new Recorder();
    doc.addListener(recorder);
    doc.apply(
        EditBatch.of(
            EditBatch.Kind.COMMAND,
            List.of(new Replacement(14, 1, "X\nY"), new Replacement(0, 3, "1"))));
    assertEquals("1\ntwo\r\nthreeX\nYfour", doc.text().toString());
    assertEquals(1, doc.revision());
    assertEquals(1, recorder.changes.size());
    DocumentChange change = recorder.changes.get(0);
    assertEquals(1, change.revision());
    assertEquals(DocumentChange.Cause.EDIT, change.cause());
    assertEquals(new TextEdit(0, "one", "1", 0, 0, 0), change.edits().get(0));
    assertEquals(new TextEdit(14, "\r", "X\nY", 2, 1, 1), change.edits().get(1));
    assertEquals(List.of(Boolean.TRUE), recorder.dirty);
  }

  @Test
  void overlappingReplacementsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            EditBatch.of(
                EditBatch.Kind.COMMAND,
                List.of(new Replacement(2, 3, "a"), new Replacement(4, 0, "b")));
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            EditBatch.of(
                EditBatch.Kind.COMMAND,
                List.of(new Replacement(2, 0, "a"), new Replacement(2, 0, "b")));
          }
        });
    final Document doc = document("abc");
    assertThrows(
        IndexOutOfBoundsException.class,
        new Executable() {
          @Override
          public void execute() {
            doc.apply(EditBatch.replace(2, 2, ""));
          }
        });
  }

  @Test
  void typingCoalescesUntilAPauseAMoveOrWhitespaceAfterAWord() {
    Document doc = document("");
    SelectionModel carets = doc.newSelectionModel();
    type(doc, carets, "abc def");
    assertEquals("abc def", doc.text().toString());
    doc.undo(carets);
    assertEquals("abc", doc.text().toString());
    assertEquals(SelectionSet.caret(3), carets.selections());
    doc.undo(carets);
    assertEquals("", doc.text().toString());
    assertFalse(doc.canUndo());
    doc.redo(carets);
    doc.redo(carets);
    assertEquals("abc def", doc.text().toString());
    assertEquals(SelectionSet.caret(7), carets.selections());

    type(doc, carets, "g");
    now += Document.COALESCE_NANOS;
    type(doc, carets, "h");
    carets.set(SelectionSet.caret(9));
    type(doc, carets, "i");
    doc.undo(carets);
    assertEquals("abc defgh", doc.text().toString());
    doc.undo(carets);
    assertEquals("abc defg", doc.text().toString());
    doc.undo(carets);
    assertEquals("abc def", doc.text().toString());
  }

  @Test
  void backspaceAndDeleteCoalesceWithTheirOwnKind() {
    Document doc = document("abcdef");
    SelectionModel carets = doc.newSelectionModel();
    carets.set(SelectionSet.caret(4));
    for (int at = 4; at > 1; at--) {
      doc.apply(
          EditBatch.of(EditBatch.Kind.BACKSPACE, List.of(new Replacement(at - 1, 1, ""))),
          carets,
          SelectionSet.caret(at - 1));
    }
    assertEquals("aef", doc.text().toString());
    for (int i = 0; i < 2; i++) {
      doc.apply(
          EditBatch.of(EditBatch.Kind.DELETE, List.of(new Replacement(1, 1, ""))),
          carets,
          SelectionSet.caret(1));
    }
    assertEquals("a", doc.text().toString());
    doc.undo(carets);
    assertEquals("aef", doc.text().toString());
    doc.undo(carets);
    assertEquals("abcdef", doc.text().toString());
    assertEquals(SelectionSet.caret(4), carets.selections());
  }

  @Test
  void aMultiCaretKeystrokeIsOneStepAndConsecutiveOnesCoalesce() {
    Document doc = document("ab\ncd\nef");
    SelectionModel carets = doc.newSelectionModel();
    carets.set(
        SelectionSet.of(List.of(Selection.caret(1), Selection.caret(4), Selection.caret(7)), 0));
    doc.apply(
        typing(1, 4, 7, 'x'),
        carets,
        SelectionSet.of(List.of(Selection.caret(2), Selection.caret(6), Selection.caret(10)), 0));
    doc.apply(
        typing(2, 6, 10, 'y'),
        carets,
        SelectionSet.of(List.of(Selection.caret(3), Selection.caret(8), Selection.caret(13)), 0));
    assertEquals("axyb\ncxyd\nexyf", doc.text().toString());
    doc.undo(carets);
    assertEquals("ab\ncd\nef", doc.text().toString());
    assertEquals(3, carets.selections().size());
    assertEquals(4, carets.selections().selections().get(1).caret());
  }

  @Test
  void aGroupIsOneStepAndANewEditDiscardsRedo() {
    Document doc = document("abc");
    Recorder recorder = new Recorder();
    doc.addListener(recorder);
    doc.beginGroup(null);
    doc.apply(EditBatch.replace(0, 1, "A"));
    doc.beginGroup(null);
    doc.apply(EditBatch.replace(2, 1, "C"));
    doc.endGroup();
    doc.endGroup();
    doc.apply(EditBatch.replace(1, 1, "B"));
    doc.undo(null);
    doc.undo(null);
    assertEquals("abc", doc.text().toString());
    assertEquals(6, doc.revision());
    assertEquals(DocumentChange.Cause.UNDO, recorder.changes.get(4).cause());
    doc.redo(null);
    assertEquals("AbC", doc.text().toString());
    doc.apply(EditBatch.replace(0, 0, "z"));
    assertFalse(doc.canRedo());
    assertEquals(9, doc.revision());
  }

  @Test
  void undoingBackToTheSavedStateClearsDirty() {
    Document doc = document("x");
    SelectionModel carets = doc.newSelectionModel();
    Recorder recorder = new Recorder();
    doc.addListener(recorder);
    type(doc, carets, "ab");
    DocumentSnapshot saved = doc.snapshot();
    doc.markSaved(saved);
    assertFalse(doc.isDirty());
    type(doc, carets, "cd");
    assertTrue(doc.isDirty());
    doc.undo(carets);
    assertEquals("abx", doc.text().toString());
    assertFalse(doc.isDirty());
    doc.undo(carets);
    assertTrue(doc.isDirty());
    doc.redo(carets);
    assertFalse(doc.isDirty());
    doc.setEncoding(TextEncoding.of(StandardCharsets.UTF_16LE, true));
    assertTrue(doc.isDirty());
    doc.markSaved(doc.snapshot());
    assertFalse(doc.isDirty());
    assertEquals(
        List.of(
            Boolean.TRUE,
            Boolean.FALSE,
            Boolean.TRUE,
            Boolean.FALSE,
            Boolean.TRUE,
            Boolean.FALSE,
            Boolean.TRUE,
            Boolean.FALSE),
        recorder.dirty);
  }

  @Test
  void theSavedTextIsTheInitialTextUntilASaveAndSurvivesMarkUnsaved() {
    Document doc = document("x");
    SelectionModel carets = doc.newSelectionModel();
    Recorder recorder = new Recorder();
    doc.addListener(recorder);
    assertEquals("x", doc.savedText().toString());
    type(doc, carets, "ab");
    assertEquals("x", doc.savedText().toString());
    DocumentSnapshot saved = doc.snapshot();
    type(doc, carets, "c");
    doc.markSaved(saved);
    assertEquals(1, recorder.saved);
    assertEquals("abx", doc.savedText().toString());
    assertTrue(doc.isDirty());
    doc.markUnsaved();
    assertEquals("abx", doc.savedText().toString());
    assertEquals(1, recorder.saved);
  }

  @Test
  void theSavedStateIsUnreachableOnceItsStepIsDropped() {
    Document doc = new Document("", UTF8, LineEnding.LF, new UndoLimits(2, Long.MAX_VALUE), clock);
    doc.markSaved(doc.snapshot());
    for (int i = 0; i < 3; i++) {
      doc.apply(EditBatch.replace(0, 0, "x"));
    }
    assertEquals(2, doc.undoSteps());
    doc.undo(null);
    doc.undo(null);
    assertFalse(doc.canUndo());
    assertEquals("x", doc.text().toString());
    assertTrue(doc.isDirty());
  }

  @Test
  void theByteBoundDropsOldStepsButKeepsTheNewest() {
    Document doc = new Document("", UTF8, LineEnding.LF, new UndoLimits(100, 39), clock);
    doc.apply(EditBatch.replace(0, 0, "0123456789"));
    doc.apply(EditBatch.replace(0, 0, "0123456789"));
    assertEquals(1, doc.undoSteps());
    doc.apply(EditBatch.replace(0, 0, "0123456789012345678901234567890123456789"));
    assertEquals(1, doc.undoSteps());
    assertEquals(80, doc.undoBytes());
  }

  @Test
  void readOnlyListenerEditsAndOtherThreadsAreRefused() throws Exception {
    final Document doc = document("abc");
    doc.setReadOnly(true);
    assertThrows(
        IllegalStateException.class,
        new Executable() {
          @Override
          public void execute() {
            doc.apply(EditBatch.replace(0, 0, "x"));
          }
        });
    doc.setReadOnly(false);
    doc.addListener(
        new DocumentListener() {
          @Override
          public void textChanged(DocumentChange change) {
            doc.apply(EditBatch.replace(0, 0, "y"));
          }

          @Override
          public void dirtyChanged(boolean dirty) {
            // Not asserted.
          }
        });
    assertThrows(
        IllegalStateException.class,
        new Executable() {
          @Override
          public void execute() {
            doc.apply(EditBatch.replace(0, 0, "x"));
          }
        });
    final AtomicReference<Throwable> other = new AtomicReference<Throwable>();
    Thread thread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  doc.setLineEnding(LineEnding.CRLF);
                } catch (IllegalStateException e) {
                  other.set(e);
                }
              }
            });
    thread.start();
    thread.join();
    assertTrue(other.get() instanceof IllegalStateException);
  }

  @Test
  void anchorsAndPositionsFollowTheirBias() {
    Document doc = document("abcdef");
    Anchor before = doc.anchor(2, Bias.BEFORE);
    Anchor after = doc.anchor(2, Bias.AFTER);
    Anchor inside = doc.anchor(4, Bias.AFTER);
    doc.apply(
        EditBatch.of(
            EditBatch.Kind.COMMAND,
            List.of(new Replacement(2, 0, "XY"), new Replacement(3, 2, "Z"))));
    assertEquals("abXYcZf", doc.text().toString());
    assertEquals(2, before.offset());
    assertEquals(4, after.offset());
    assertEquals(6, inside.offset());
    assertEquals(4, doc.positions().map(0, 2, Bias.AFTER));
    assertEquals(6, doc.positions().map(0, 5, Bias.BEFORE));
    after.dispose();
    doc.apply(EditBatch.replace(0, 0, "!"));
    assertEquals(4, after.offset());
    assertEquals(3, before.offset());
  }

  @Test
  void thePositionMapIsBoundedByBatches() {
    Document doc = document("");
    for (int i = 0; i <= PositionMap.MAX_BATCHES; i++) {
      doc.apply(EditBatch.replace(0, 0, "x"));
    }
    assertEquals(PositionMap.UNMAPPABLE, doc.positions().map(0, 0, Bias.BEFORE));
    assertEquals(PositionMap.MAX_BATCHES, doc.positions().map(1, 0, Bias.AFTER));
  }

  @Test
  void selectionsOfOtherModelsAreMappedAndMerged() {
    Document doc = document("0123456789");
    SelectionModel mine = doc.newSelectionModel();
    SelectionModel other = doc.newSelectionModel();
    final List<SelectionModel> fired = new ArrayList<SelectionModel>();
    other.addListener(
        new SelectionListener() {
          @Override
          public void selectionsChanged(SelectionModel model) {
            fired.add(model);
          }
        });
    other.set(SelectionSet.of(List.of(Selection.of(2, 4), Selection.caret(6)), 1));
    fired.clear();
    doc.apply(EditBatch.replace(3, 3, ""), mine, SelectionSet.caret(3));
    assertEquals(SelectionSet.of(List.of(Selection.of(2, 3)), 0), other.selections());
    assertEquals(List.of(other), fired);
    assertEquals(SelectionSet.caret(3), mine.selections());
  }

  @Test
  void aSelectionSetSortsAndMergesTouchingSelections() {
    SelectionSet set =
        SelectionSet.of(
            List.of(Selection.caret(9), Selection.of(5, 2), Selection.caret(5), Selection.of(0, 1)),
            2);
    assertEquals(
        List.of(Selection.of(0, 1), Selection.of(5, 2), Selection.caret(9)), set.selections());
    assertEquals(1, set.primaryIndex());
  }

  @Test
  void aBlockSelectionPadsShortLinesAndCountsTabsAndWideChars() {
    Document doc = document("a\tb\nxy\n中文z");
    BlockSelection block = new BlockSelection(0, 1, 2, 4);
    List<BlockRow> rows = block.rows(doc.text(), 4);
    assertEquals(new BlockRow(0, 1, 2, 0), rows.get(0));
    assertEquals(new BlockRow(1, 5, 6, 0), rows.get(1));
    assertEquals(new BlockRow(2, 7, 9, 0), rows.get(2));
    List<BlockRow> past = new BlockSelection(0, 6, 1, 6).rows(doc.text(), 4);
    assertEquals(new BlockRow(0, 3, 3, 1), past.get(0));
    assertEquals(new BlockRow(1, 6, 6, 4), past.get(1));
    SelectionModel model = doc.newSelectionModel();
    model.setBlock(block, 4);
    assertEquals(3, model.selections().size());
    doc.apply(EditBatch.replace(0, 0, "top\n"));
    assertEquals(new BlockSelection(1, 1, 3, 4), model.block());
    assertEquals(3, model.selections().size());
  }
}
