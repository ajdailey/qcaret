package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
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
 * Edit > Format XML and JSON outcomes beside the edit: the notice of a run that changes nothing,
 * and the error mark in the view and the overview strip, set on an error and cleared by an edit or
 * a run that completes.
 */
class FormatOutcomeTest {
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

  private int files;

  private EditorController open(String text) throws Exception {
    Path file = temp.resolve("f" + files++ + ".txt");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    harness.open(file);
    return harness.application.documents().activeEditor();
  }

  private FakeView.Shown shown() {
    return harness.view.selected.shown;
  }

  /** Runs a command and waits until the editor's busy cursor goes off. */
  private void run(CommandId command) throws InterruptedException {
    final List<Boolean> busy = shown().busy;
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

  private String lastNotice() {
    List<String> notices = harness.view.notices;
    return notices.isEmpty() ? null : notices.get(notices.size() - 1);
  }

  @Test
  void aRunThatChangesNothingSaysWhy() throws Exception {
    open("<?xml version=\"1.0\"?>\n<root>\nhello\n<a>\n<b>x</b>\n</a>\n</root>\n");
    run(CommandId.PRETTY_PRINT_XML);
    assertEquals(
        "Nothing to change: <root> at line 2 holds text, so its content stays as written",
        lastNotice());
    open("<root>\nhello\n<a/>\n</root>\n");
    run(CommandId.MINIFY_XML);
    assertEquals(
        "Nothing to change: <root> at line 1 holds text, so its content stays as written",
        lastNotice());
  }

  @Test
  void anInvisibleCharIsNamed() throws Exception {
    open("<root>\n\u00a0<a/>\n</root>");
    run(CommandId.PRETTY_PRINT_XML);
    assertEquals(
        "Nothing to change: <root> at line 1 holds U+00A0 NO-BREAK SPACE as text, so its content"
            + " stays as written",
        lastNotice());
  }

  @Test
  void preserveAndCdataAreNamed() throws Exception {
    EditorController editor =
        open("<r>\n\t<p xml:space=\"preserve\"><a/></p>\n\t<c><![CDATA[x]]><d/></c>\n</r>");
    run(CommandId.PRETTY_PRINT_XML);
    assertEquals(
        "Nothing to change: <p> at line 2 has xml:space=\"preserve\", so its content stays as"
            + " written",
        lastNotice());
    int c = editor.document().text().toString().indexOf("<c>");
    editor
        .selections()
        .set(
            SelectionSet.of(
                List.of(Selection.of(c, editor.document().text().toString().indexOf("</c>") + 4)),
                0));
    run(CommandId.MINIFY_XML);
    assertEquals(
        "Nothing to change: <c> at line 3 holds CDATA, so its content stays as written",
        lastNotice());
  }

  @Test
  void formattedTextSaysSo() throws Exception {
    open("<a>\n\t<b/>\n</a>");
    run(CommandId.PRETTY_PRINT_XML);
    assertEquals("Already formatted", lastNotice());
    open("[1,2]");
    run(CommandId.MINIFY_JSON);
    assertEquals("Already minified", lastNotice());
    open("{\n\t\"a\": 1\n}");
    run(CommandId.PRETTY_PRINT_JSON);
    assertEquals("Already formatted", lastNotice());
  }

  @Test
  void anErrorMarksItsTokenUntilTheNextEdit() throws Exception {
    String text = "<root>\n<aattr=\"1\"/>\n</root>";
    EditorController editor = open(text);
    editor.overviewResized(30);
    run(CommandId.PRETTY_PRINT_XML);
    // The parser reports the char after the joined name; the mark covers both.
    assertEquals(text.indexOf('='), editor.selections().selections().primary().caret());
    assertArrayEquals(new int[] {text.indexOf("aattr"), text.indexOf('=') + 1}, shown().errorMark);
    awaitOverviewError(true);
    editor.selections().set(SelectionSet.caret(0));
    editor.type(" ");
    assertArrayEquals(new int[] {-1, -1}, shown().errorMark);
    awaitOverviewError(false);
  }

  @Test
  void aRunThatCompletesClearsTheMarkAndAnotherErrorReplacesIt() throws Exception {
    EditorController editor = open("[1,]");
    run(CommandId.CHECK_JSON);
    assertArrayEquals(new int[] {3, 4}, shown().errorMark);
    editor.selections().set(SelectionSet.of(List.of(Selection.of(1, 2)), 0));
    run(CommandId.CHECK_JSON);
    assertEquals("The JSON is well-formed", lastNotice());
    assertArrayEquals(new int[] {-1, -1}, shown().errorMark);
    editor.selections().set(SelectionSet.caret(0));
    run(CommandId.CHECK_XML);
    assertArrayEquals(new int[] {0, 1}, shown().errorMark);
    run(CommandId.CHECK_JSON);
    assertArrayEquals(new int[] {3, 4}, shown().errorMark);
  }

  private void awaitOverviewError(final boolean marked) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            boolean any = false;
            for (byte row : shown().overview.rows()) {
              any |= (row & OverviewMarks.ERROR) != 0;
            }
            return any == marked && shown().overview.height() == 30;
          }
        },
        "overview error mark " + marked);
  }
}
