package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.SequenceForm;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.NumberSequence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Insert Number Sequence, Open Path at Caret, Copy XML and JSON Path, and Full Screen through the
 * application.
 */
class CaretCommandsTest {
  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() throws IOException {
    Files.createDirectory(temp.resolve("settings"));
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private EditorController open(String name, String text) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
    harness.open(file);
    return harness.application.documents().activeEditor();
  }

  private static String text(EditorController editor) {
    return editor.document().text().toString();
  }

  private String lastNotice() {
    List<String> notices = harness.view.notices;
    return notices.isEmpty() ? null : notices.get(notices.size() - 1);
  }

  private static void carets(EditorController editor, int... offsets) {
    Selection[] list = new Selection[offsets.length];
    for (int i = 0; i < offsets.length; i++) {
      list[i] = Selection.caret(offsets[i]);
    }
    editor.selections().set(SelectionSet.of(List.of(list), 0));
  }

  // Insert Number Sequence.

  @Test
  void aSequenceFillsTheCaretsInTextOrderAsOneUndoStep() throws Exception {
    EditorController editor = open("a.txt", "x\ny\nz\n");
    editor.selections().set(SelectionSet.of(List.of(Selection.caret(4), Selection.of(2, 3)), 0));
    harness.application.execute(CommandId.INSERT_NUMBER_SEQUENCE);
    assertEquals(new SequenceForm("1", "1", 0, 0), harness.view.sequenceShown);
    harness.view.sequenceAnswer.accept(new SequenceForm(" -1 ", "2", 2, 0));
    assertEquals("x\n-1\n01z\n", text(editor));
    editor.undo();
    assertEquals("x\ny\nz\n", text(editor));
    assertFalse(editor.document().canUndo());
    NumberSequence saved = harness.application.preferences().state().numberSequence();
    assertEquals(new NumberSequence(-1, 2, 2, NumberSequence.Base.DECIMAL), saved);
    harness.application.execute(CommandId.INSERT_NUMBER_SEQUENCE);
    assertEquals(new SequenceForm("-1", "2", 2, 0), harness.view.sequenceShown);
  }

  @Test
  void aColumnSelectionTakesOneNumberPerRowFromTheTop() throws Exception {
    EditorController editor = open("a.txt", "ab\ncd\n\nef\n");
    editor.selections().setBlock(new BlockSelection(3, 1, 0, 1), 4);
    harness.application.execute(CommandId.INSERT_NUMBER_SEQUENCE);
    harness.view.sequenceAnswer.accept(new SequenceForm("0xfe", "1", 0, 1));
    assertEquals("aFEb\ncFFd\n 100\ne101f\n", text(editor));
    editor.undo();
    assertEquals("ab\ncd\n\nef\n", text(editor));
  }

  @Test
  void aSequenceLeavingSixtyFourBitsInsertsNothing() throws Exception {
    EditorController editor = open("a.txt", "a\nb\n");
    carets(editor, 0, 2);
    harness.application.execute(CommandId.INSERT_NUMBER_SEQUENCE);
    harness.view.sequenceAnswer.accept(new SequenceForm("9223372036854775807", "1", 0, 0));
    assertEquals("a\nb\n", text(editor));
    assertEquals(UiText.NUMBER_SEQUENCE_OVERFLOW.text(), lastNotice());
    assertFalse(editor.document().canUndo());
  }

  @Test
  void theDialogValidatorRefusesFieldsOutsideASequence() throws Exception {
    EditorController editor = open("a.txt", "a\n");
    harness.application.execute(CommandId.INSERT_NUMBER_SEQUENCE);
    Predicate<SequenceForm> valid = harness.view.sequenceValid;
    assertTrue(valid.test(new SequenceForm("-0x8000000000000000", "-1", 64, 2)));
    assertFalse(valid.test(new SequenceForm("9223372036854775808", "1", 0, 0)));
    assertFalse(valid.test(new SequenceForm("1", "1.5", 0, 0)));
    assertFalse(valid.test(new SequenceForm("", "1", 0, 0)));
    assertFalse(valid.test(new SequenceForm("1", "1", 65, 0)));
    assertFalse(valid.test(new SequenceForm("1", "1", 0, 3)));
    assertFalse(valid.test(new SequenceForm("1", "1", 0, -1)));
    harness.view.sequenceAnswer.accept(new SequenceForm("x", "1", 0, 0));
    assertEquals("a\n", text(editor));
    assertEquals(
        NumberSequence.DEFAULTS, harness.application.preferences().state().numberSequence());
  }

  // Open Path at Caret.

  /** Runs Open Path at Caret and waits for its check to end. */
  private void openPath() throws InterruptedException {
    final int before = harness.view.activations.get();
    harness.application.execute(CommandId.OPEN_PATH_AT_CARET);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.activations.get() > before && harness.view.active.get() == 0;
          }
        },
        "path checked");
    harness.awaitIdle("open path");
  }

  @Test
  void aRelativePathOpensFromTheDocumentsFolderAtItsLine() throws Exception {
    Path sub = Files.createDirectory(temp.resolve("sub"));
    Files.writeString(sub.resolve("t.txt"), "one\ntwo\n  three\n", StandardCharsets.UTF_8);
    EditorController editor = open("a.txt", "see sub/t.txt:3:4 there\n");
    carets(editor, 6);
    openPath();
    assertEquals(2, harness.application.documents().count());
    assertEquals(
        sub.resolve("t.txt").toRealPath(),
        harness.application.documents().activePath().toRealPath());
    EditorController opened = harness.application.documents().activeEditor();
    assertEquals(SelectionSet.caret(11), opened.selections().selections());
    harness.application.documents().selectNext(true);
    assertEquals(editor, harness.application.documents().activeEditor());
    carets(editor, 9);
    openPath();
    assertEquals(2, harness.application.documents().count());
  }

  @Test
  void anAbsolutePathOpensAndAMissingFileOrAUrlShowsANotice() throws Exception {
    Path target = Files.writeString(temp.resolve("abs.txt"), "x", StandardCharsets.UTF_8);
    String absolute = target.toRealPath().toString();
    EditorController editor =
        open("a.txt", "\"" + absolute + "\" missing.txt https://example.invalid/x  \n");
    carets(editor, 2);
    openPath();
    assertEquals(target.toRealPath(), harness.application.documents().activePath().toRealPath());
    harness.application.documents().selectNext(false);
    assertEquals(editor, harness.application.documents().activeEditor());
    int missing = text(editor).indexOf("missing") + 2;
    carets(editor, missing);
    openPath();
    assertEquals(2, harness.application.documents().count());
    Path folder = harness.application.documents().activePath().toAbsolutePath().getParent();
    assertEquals(UiText.OPEN_PATH_MISSING.format(folder.resolve("missing.txt")), lastNotice());
    carets(editor, text(editor).indexOf("example"));
    openPath();
    assertEquals(UiText.OPEN_PATH_URL.format("https://example.invalid/x"), lastNotice());
    carets(editor, text(editor).length() - 2);
    openPath();
    assertEquals(UiText.OPEN_PATH_NONE.text(), lastNotice());
    assertEquals(2, harness.application.documents().count());
  }

  @Test
  void anUntitledDocumentResolvesAgainstTheWorkspace() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("ws"));
    Files.writeString(folder.resolve("w.txt"), "w", StandardCharsets.UTF_8);
    harness.application.documents().newDocument();
    EditorController editor = harness.application.documents().activeEditor();
    editor.type("w.txt");
    openPath();
    assertEquals(UiText.OPEN_PATH_MISSING.format("w.txt"), lastNotice());
    harness.application.workspace().set(folder);
    harness.awaitIdle("workspace");
    openPath();
    assertEquals(
        folder.resolve("w.txt").toRealPath(),
        harness.application.documents().activePath().toRealPath());
  }

  // Copy XML Path and Copy JSON Path.

  /** Runs a path command and waits for the editor's busy cursor to go off. */
  private void copyPath(CommandId command) throws InterruptedException {
    final List<Boolean> busy = harness.view.selected.shown.busy;
    final int before = busy.size();
    harness.application.execute(command);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return busy.size() >= before + 2 && !busy.get(busy.size() - 1);
          }
        },
        command + " ended");
    harness.ui.runPending();
  }

  @Test
  void thePathAtTheCaretIsCopiedWithANotice() throws Exception {
    EditorController editor = open("a.xml", "<a>\n  <b/>\n  <b k=\"v\"/>\n</a>\n");
    carets(editor, text(editor).indexOf("k="));
    copyPath(CommandId.COPY_XML_PATH);
    assertEquals("/a/b[2]/@k", harness.view.clipboard);
    assertEquals(UiText.PATH_COPIED.format("/a/b[2]/@k"), lastNotice());
    editor.selections().set(SelectionSet.caret(0));
    editor.type("{\"x\": [0, {\"y\": 1}]}");
    carets(editor, text(editor).indexOf("1}"));
    copyPath(CommandId.COPY_JSON_PATH);
    assertEquals("$.x[1].y", harness.view.clipboard);
    assertTrue(editor.document().canUndo());
  }

  @Test
  void malformedTextBeforeTheCaretShowsTheFormatErrorAndCopiesNothing() throws Exception {
    EditorController editor = open("a.json", "[1,,\t2]");
    harness.view.clipboard = null;
    carets(editor, 6);
    copyPath(CommandId.COPY_JSON_PATH);
    assertNull(harness.view.clipboard);
    assertTrue(lastNotice().startsWith("JSON error at line 1, column 4: "), lastNotice());
    assertEquals(SelectionSet.caret(6), editor.selections().selections());
  }

  // Full Screen.

  @Test
  void fullScreenTogglesTheWindowAndItsCheck() throws Exception {
    CommandStates states = new CommandStates(harness.application);
    assertTrue(states.enabled(CommandId.FULL_SCREEN));
    assertFalse(states.checked(CommandId.FULL_SCREEN));
    harness.application.execute(CommandId.FULL_SCREEN);
    assertTrue(harness.view.fullScreen);
    assertTrue(states.checked(CommandId.FULL_SCREEN));
    harness.application.execute(CommandId.FULL_SCREEN);
    assertFalse(harness.view.fullScreen);
    assertFalse(states.checked(CommandId.FULL_SCREEN));
  }
}
