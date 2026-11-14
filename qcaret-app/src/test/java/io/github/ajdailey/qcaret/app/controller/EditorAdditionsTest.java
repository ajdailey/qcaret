package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.editor.TextStatistics;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** View > Right Margin, Tools > Document Statistics and Edit > Insert Date and Time. */
class EditorAdditionsTest {
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

  private EditorController open(String name, byte[] bytes) throws Exception {
    Path file = temp.resolve(name);
    Files.write(file, bytes);
    harness.open(file);
    return harness.application.documents().activeEditor();
  }

  private void select(EditorController editor, Selection... list) {
    editor.selections().set(SelectionSet.of(List.of(list), 0));
  }

  private FakeView.Statistics counted() throws InterruptedException {
    final FakeView.Statistics shown = harness.view.statistics;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.report != null || shown.message != null;
          }
        },
        "counted");
    return shown;
  }

  // Right margin.

  @Test
  void theRightMarginToggleShowsTheColumnAndSavesTheChoice() throws Exception {
    assertEquals(0, harness.view.rightMargin);
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.checked(CommandId.RIGHT_MARGIN));
    assertTrue(states.enabled(CommandId.RIGHT_MARGIN));
    harness.application.execute(CommandId.RIGHT_MARGIN);
    assertEquals(80, harness.view.rightMargin);
    assertTrue(states.checked(CommandId.RIGHT_MARGIN));
    assertTrue(harness.application.preferences().state().rightMargin());
    PreferencesInput input = preferences();
    PreferencesForm f = input.form();
    assertEquals("80", f.rightMargin());
    assertEquals(
        PreferencesForm.Group.EDITOR, input.apply(withMargin(f, "0", f.dateFormat())).group());
    assertEquals(
        PreferencesForm.Group.EDITOR, input.apply(withMargin(f, "1001", f.dateFormat())).group());
    assertNull(input.apply(withMargin(f, "100", f.dateFormat())));
    assertEquals(100, harness.view.rightMargin);
    harness.application.execute(CommandId.RIGHT_MARGIN);
    assertEquals(0, harness.view.rightMargin);
  }

  private PreferencesInput preferences() throws InterruptedException {
    harness.view.preferences = null;
    harness.application.execute(CommandId.PREFERENCES);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.preferences != null;
          }
        },
        "dialog");
    return harness.view.preferences;
  }

  private static PreferencesForm withMargin(PreferencesForm f, String margin, String format) {
    return new PreferencesForm(
        f.completionChars(),
        f.autoClose(),
        f.maxHighlightLineChars(),
        f.tabWidth(),
        f.insertSpaces(),
        f.pollSeconds(),
        f.autoReloadClean(),
        f.recoveryIntervalSeconds(),
        f.recoveryKeepDays(),
        f.sessionSnapshot(),
        f.backupDays(),
        f.searchMaxLineChars(),
        f.excludedDirectories(),
        f.theme(),
        f.fontSize(),
        f.shortcuts(),
        f.themeFile(),
        f.fontFamily(),
        margin,
        format,
        f.editorConfig(),
        f.modelines(),
        f.editorColors(),
        false,
        false,
        f.newLineEnding(),
        f.newEncoding(),
        f.view(),
        "");
  }

  // Document statistics.

  @Test
  void statisticsCountTheDocumentWithItsBomAndTheSelections() throws Exception {
    byte[] text = "one two\nthree \u00e9t\u00e9".getBytes(StandardCharsets.UTF_8);
    byte[] bytes = new byte[text.length + 3];
    bytes[0] = (byte) 0xef;
    bytes[1] = (byte) 0xbb;
    bytes[2] = (byte) 0xbf;
    System.arraycopy(text, 0, bytes, 3, text.length);
    EditorController editor = open("a.txt", bytes);
    // "two", the break and "th"; then "t" and the accented e.
    select(editor, Selection.caret(0), Selection.of(4, 10), Selection.of(15, 17));
    assertTrue(new CommandStates(harness.application).enabled(CommandId.DOCUMENT_STATISTICS));
    harness.application.execute(CommandId.DOCUMENT_STATISTICS);
    FakeView.Statistics shown = counted();
    assertEquals(0, shown.progress.get(0).intValue());
    assertEquals("a.txt", shown.report.name());
    assertEquals("UTF-8", shown.report.encoding());
    assertEquals(new TextStatistics(2, 4, 17, 14, text.length + 3), shown.report.document());
    assertEquals(new TextStatistics(3, 3, 8, 7, 9), shown.report.selection());
  }

  @Test
  void statisticsWithoutASelectionShowNoSelectionCounts() throws Exception {
    open("a.txt", "x".getBytes(StandardCharsets.UTF_8));
    harness.application.execute(CommandId.DOCUMENT_STATISTICS);
    assertNull(counted().report.selection());
  }

  @Test
  void statisticsInUtf16CountTwoBytesPerUnit() throws Exception {
    byte[] bytes = {(byte) 0xff, (byte) 0xfe, 'a', 0, ' ', 0, 'b', 0};
    open("u.txt", bytes);
    harness.application.execute(CommandId.DOCUMENT_STATISTICS);
    FakeView.Statistics shown = counted();
    assertEquals("UTF-16LE", shown.report.encoding());
    assertEquals(new TextStatistics(1, 2, 3, 2, 8), shown.report.document());
  }

  /** x-JISAutoDetect decodes but has no encoder: every count but the bytes is shown. */
  @Test
  void statisticsInACharsetWithNoEncoderShowUnknownBytes() throws Exception {
    final Charset auto = Charset.forName("x-JISAutoDetect");
    assumeFalse(auto.canEncode());
    open("a.txt", "one two\n".getBytes(StandardCharsets.US_ASCII));
    harness.application.documents().reopenWithEncoding(auto);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness
                .application
                .documents()
                .activeDocument()
                .encoding()
                .charset()
                .equals(auto);
          }
        },
        "reopened");
    harness.awaitIdle("reopen");
    select(harness.application.documents().activeEditor(), Selection.of(0, 3));
    harness.application.execute(CommandId.DOCUMENT_STATISTICS);
    final FakeView.Statistics shown = harness.view.statistics;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.report != null || shown.message != null || !harness.failures.isEmpty();
          }
        },
        "counted");
    assertTrue(harness.failures.isEmpty(), harness.failures.toString());
    assertEquals(
        new TextStatistics(2, 2, 8, 6, TextStatistics.UNKNOWN_BYTES), shown.report.document());
    assertEquals(
        new TextStatistics(1, 1, 3, 3, TextStatistics.UNKNOWN_BYTES), shown.report.selection());
  }

  @Test
  void anEditDuringTheCountDropsTheResultAndRefreshCountsAgain() throws Exception {
    EditorController editor = open("a.txt", "one".getBytes(StandardCharsets.UTF_8));
    select(editor, Selection.caret(3));
    harness.application.execute(CommandId.DOCUMENT_STATISTICS);
    editor.type(" two");
    FakeView.Statistics shown = counted();
    assertEquals(UiText.STATISTICS_CHANGED.text(), shown.message);
    shown.message = null;
    shown.input.refresh();
    assertEquals(2, counted().report.document().words());
  }

  @Test
  void closingTheDialogCancelsTheCount() throws Exception {
    StringBuilder b = new StringBuilder();
    while (b.length() < 2_000_000) {
      b.append("lorem ipsum dolor\n");
    }
    open("large.txt", b.toString().getBytes(StandardCharsets.UTF_8));
    harness.application.execute(CommandId.DOCUMENT_STATISTICS);
    FakeView.Statistics shown = harness.view.statistics;
    shown.input.closed();
    harness.awaitIdle("count");
    harness.ui.runPending();
    assertNull(shown.report);
    assertNull(shown.message);
    // Opening again counts in a new dialog.
    harness.application.execute(CommandId.DOCUMENT_STATISTICS);
    FakeView.Statistics again = counted();
    assertEquals(b.length() / 18 + 1, again.report.document().lines());
    for (int i = 1; i < again.progress.size(); i++) {
      assertTrue(again.progress.get(i) > again.progress.get(i - 1), again.progress.toString());
    }
  }

  @Test
  void statisticsWithoutADocumentSayNoDocument() throws Exception {
    harness.application.execute(CommandId.DOCUMENT_STATISTICS);
    assertEquals(UiText.STATISTICS_NO_DOCUMENT.text(), harness.view.statistics.message);
    assertFalse(new CommandStates(harness.application).enabled(CommandId.DOCUMENT_STATISTICS));
  }

  // Insert date and time.

  private DateTimeInsert fixed() {
    return new DateTimeInsert(
        harness.application.documents(),
        harness.application.settings(),
        harness.view,
        harness.workers,
        harness.ui,
        Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC));
  }

  private void awaitRows() throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.dateRows != null;
          }
        },
        "rows");
  }

  @Test
  void insertDateAndTimeReplacesEverySelectionAsOneUndoStep() throws Exception {
    EditorController editor = open("a.txt", "ab\ncd\n".getBytes(StandardCharsets.UTF_8));
    select(editor, Selection.caret(1), Selection.of(3, 5));
    DateTimeInsert insert = fixed();
    insert.choose();
    awaitRows();
    List<String> rows = harness.view.dateRows;
    assertEquals(1 + DateTimeInsert.COMMON.size(), rows.size());
    assertEquals(
        UiText.DATE_FORMAT_ROW.format("2026-03-04 05:06", "yyyy-MM-dd HH:mm"), rows.get(0));
    assertEquals(0, harness.view.dateSelected);
    harness.view.dateChooser.accept(1);
    assertEquals("a2026-03-04b\n2026-03-04\n", editor.document().text().toString());
    assertEquals("yyyy-MM-dd", harness.application.preferences().state().lastDateFormat());
    editor.undo();
    assertEquals("ab\ncd\n", editor.document().text().toString());
    harness.view.dateRows = null;
    insert.choose();
    awaitRows();
    assertEquals(1, harness.view.dateSelected);
  }

  @Test
  void theCommandOffersThePreferenceFormatFirst() throws Exception {
    open("a.txt", new byte[0]);
    PreferencesInput input = preferences();
    PreferencesForm f = input.form();
    assertEquals("yyyy-MM-dd HH:mm", f.dateFormat());
    assertEquals(
        PreferencesForm.Group.EDITOR,
        input.apply(withMargin(f, f.rightMargin(), "yyyy-MM-dd {")).group());
    assertEquals(
        PreferencesForm.Group.EDITOR, input.apply(withMargin(f, f.rightMargin(), " ")).group());
    assertNull(input.apply(withMargin(f, f.rightMargin(), "HH:mm")));
    assertTrue(new CommandStates(harness.application).enabled(CommandId.INSERT_DATE_TIME));
    harness.application.execute(CommandId.INSERT_DATE_TIME);
    awaitRows();
    assertTrue(harness.view.dateRows.get(0).endsWith("(HH:mm)"), harness.view.dateRows.get(0));
    assertEquals(DateTimeInsert.COMMON.size() + 1, harness.view.dateRows.size());
  }

  @Test
  void aReadOnlyDocumentGetsNoDate() throws Exception {
    EditorController editor = open("a.txt", "x".getBytes(StandardCharsets.UTF_8));
    editor.document().setReadOnly(true);
    assertFalse(new CommandStates(harness.application).enabled(CommandId.INSERT_DATE_TIME));
    fixed().insert("yyyy");
    assertEquals("x", editor.document().text().toString());
  }

  @Test
  void theCommonFormatsAreValid() {
    for (String pattern : DateTimeInsert.COMMON) {
      assertTrue(Options.validDateFormat(pattern), pattern);
    }
    assertNotNull(DateTimeInsert.patterns("HH:mm"));
    assertEquals(DateTimeInsert.COMMON.size(), DateTimeInsert.patterns("HH:mm:ss").size());
  }
}
