package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.MarkdownOpenMode;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.PreviewRenderer;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.document.EditBatch;
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

/**
 * Edit > Format XML and JSON through the application: worker runs, notices, caret moves and undo.
 */
class FormatRunsTest {
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

  private EditorController open(String text) throws Exception {
    Path file = temp.resolve("a.txt");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    harness.open(file);
    return harness.application.documents().activeEditor();
  }

  private List<Boolean> busy() {
    return harness.view.selected.shown.busy;
  }

  /** Runs a command and waits until the editor's busy cursor goes off. */
  private void run(CommandId command) throws InterruptedException {
    final int before = busy().size();
    harness.application.execute(command);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return busy().size() >= before + 2 && !busy().get(busy().size() - 1);
          }
        },
        command + " ended");
    harness.ui.runPending();
  }

  private String text(EditorController editor) {
    return editor.document().text().toString();
  }

  private String lastNotice() {
    List<String> notices = harness.view.notices;
    return notices.isEmpty() ? null : notices.get(notices.size() - 1);
  }

  @Test
  void prettyPrintRunsOnAWorkerAsOneUndoStep() throws Exception {
    EditorController editor = open("<a><b>x</b></a>\n");
    run(CommandId.PRETTY_PRINT_XML);
    assertEquals("<a>\n\t<b>x</b>\n</a>\n", text(editor));
    assertEquals(List.of(true, false), busy());
    run(CommandId.MINIFY_XML);
    assertEquals("<a><b>x</b></a>\n", text(editor));
    editor.undo();
    assertEquals("<a>\n\t<b>x</b>\n</a>\n", text(editor));
    editor.undo();
    assertEquals("<a><b>x</b></a>\n", text(editor));
  }

  @Test
  void anErrorChangesNothingNamesTheStatusColumnAndMovesTheCaret() throws Exception {
    String text = "<a>\n\t<b></c></a>";
    EditorController editor = open(text);
    run(CommandId.PRETTY_PRINT_XML);
    assertEquals(text, text(editor));
    // The tab is four columns wide, as in the status bar.
    assertTrue(lastNotice().startsWith("XML error at line 2, column 10: "), lastNotice());
    assertEquals(SelectionSet.caret(text.indexOf("c>")), editor.selections().selections());
    assertFalse(editor.document().canUndo());
  }

  @Test
  void checkNamesTheFormatAndJsonComments() throws Exception {
    EditorController editor = open("{\"a\": [1, 2]}");
    run(CommandId.CHECK_JSON);
    assertEquals(UiText.FORMAT_WELL_FORMED.format("JSON"), lastNotice());
    assertEquals("The JSON is well-formed", lastNotice());
    editor.selections().set(SelectionSet.caret(0));
    editor.type("// c\n");
    run(CommandId.CHECK_JSON);
    assertEquals(UiText.FORMAT_WELL_FORMED_COMMENTS.text(), lastNotice());
    run(CommandId.CHECK_XML);
    assertTrue(lastNotice().startsWith("XML error at line 1, column 1: "), lastNotice());
  }

  @Test
  void strictJsonRefusesAComment() throws Exception {
    Preferences current = harness.application.settings().current();
    Options o = current.options();
    harness
        .application
        .settings()
        .set(
            current.withOptions(
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
                    o.sessionSnapshot(),
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
                    true,
                    PreviewRenderer.BASIC,
                    true,
                    100,
                    MarkdownOpenMode.SPLIT)));
    EditorController editor = open("[1 /* c */]");
    run(CommandId.PRETTY_PRINT_JSON);
    assertEquals("[1 /* c */]", text(editor));
    assertEquals(
        UiText.FORMAT_ERROR.format("JSON", 1, 4, "Comments are not allowed in strict JSON"),
        lastNotice());
  }

  @Test
  void minifyRefusesALineComment() throws Exception {
    EditorController editor = open("[1, // c\n 2]");
    run(CommandId.MINIFY_JSON);
    assertEquals("[1, // c\n 2]", text(editor));
    assertEquals(
        UiText.FORMAT_ERROR.format("JSON", 1, 5, "Minify cannot keep a // comment"), lastNotice());
  }

  @Test
  void aFailingSelectionFailsEveryRange() throws Exception {
    String text = "{\"a\":1}\n[1,]\n";
    EditorController editor = open(text);
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 7), Selection.of(8, 12)), 0));
    run(CommandId.PRETTY_PRINT_JSON);
    assertEquals(text, text(editor));
    assertEquals(UiText.FORMAT_ERROR.format("JSON", 2, 4, "Expected a value"), lastNotice());
    assertEquals(SelectionSet.caret(11), editor.selections().selections());
  }

  @Test
  void checkNamesASelectedFragment() throws Exception {
    EditorController editor = open("<a/>\n<b/>\n");
    run(CommandId.CHECK_XML);
    assertTrue(lastNotice().startsWith("XML error at line 2, column "), lastNotice());
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 9)), 0));
    run(CommandId.CHECK_XML);
    assertEquals(UiText.FORMAT_WELL_FORMED_FRAGMENT.text(), lastNotice());
  }

  @Test
  void eachSelectionFormatsAlone() throws Exception {
    EditorController editor = open("{\"a\":1}\n[2]\n");
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 7), Selection.of(8, 11)), 1));
    run(CommandId.PRETTY_PRINT_JSON);
    assertEquals("{\n\t\"a\": 1\n}\n[\n\t2\n]\n", text(editor));
    assertEquals(
        SelectionSet.of(List.of(Selection.of(0, 11), Selection.of(12, 18)), 1),
        editor.selections().selections());
  }

  @Test
  void aResultForAnOlderRevisionIsDroppedWithANotice() throws Exception {
    EditorController editor = open("[1,2]");
    harness.application.execute(CommandId.PRETTY_PRINT_JSON);
    // The result reaches the UI thread only when the test runs it, after this edit.
    editor.document().apply(EditBatch.replace(5, 0, " "));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return UiText.FORMAT_STALE.text().equals(lastNotice());
          }
        },
        "stale notice");
    assertEquals("[1,2] ", text(editor));
  }

  @Test
  void escCancelsARun() throws Exception {
    EditorController editor = open("[1,2]");
    harness.application.execute(CommandId.PRETTY_PRINT_JSON);
    editor.cancel();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !busy().isEmpty() && !busy().get(busy().size() - 1);
          }
        },
        "the run ended");
    harness.ui.runPending();
    assertEquals("[1,2]", text(editor));
    assertEquals(List.of(), harness.view.notices);
  }

  @Test
  void aReadOnlyDocumentChecksButDoesNotFormat() throws Exception {
    EditorController editor = open("[1,2]");
    editor.document().setReadOnly(true);
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.enabled(CommandId.PRETTY_PRINT_JSON));
    assertTrue(states.enabled(CommandId.CHECK_JSON));
    harness.application.execute(CommandId.PRETTY_PRINT_JSON);
    harness.ui.runPending();
    assertEquals(List.of(), busy());
    run(CommandId.CHECK_JSON);
    assertEquals("[1,2]", text(editor));
    assertEquals("The JSON is well-formed", lastNotice());
  }
}
