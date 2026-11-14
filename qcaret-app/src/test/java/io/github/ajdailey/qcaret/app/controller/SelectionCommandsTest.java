package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Select All Occurrences, Skip Occurrence, Expand Selection, Select Line, Undo and Redo Selection,
 * Delete Word, Title and Swap Case and Transpose through the editor controller. Text is written
 * with its selections marked: {@code |} a caret, {@code [} and {@code ]} a range's anchor and
 * caret; the primary is the first unless {@code *} follows another.
 */
class SelectionCommandsTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private ManualUiDispatcher ui;
  private FakeView view;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final List<String> notices = new ArrayList<String>();
  private Document document;
  private SelectionModel selections;
  private EditorController editor;
  private FakeView.FakeTab tab;

  @BeforeEach
  void setUp() {
    ui = new ManualUiDispatcher();
    view = new FakeView(ui);
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
  }

  @AfterEach
  void tearDown() {
    editor.dispose();
    workers.close();
    assertNull(failure.get());
  }

  private void open(String marked) {
    if (editor != null) {
      editor.dispose();
    }
    StringBuilder text = new StringBuilder();
    List<Selection> list = new ArrayList<Selection>();
    int primary = 0;
    int anchor = -1;
    for (char c : marked.toCharArray()) {
      switch (c) {
        case '|' -> list.add(Selection.caret(text.length()));
        case '[' -> anchor = text.length();
        case ']' -> list.add(Selection.of(anchor, text.length()));
        case '*' -> primary = list.size() - 1;
        default -> text.append(c);
      }
    }
    document =
        Document.of(
            text,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    selections = document.newSelectionModel();
    DisplayProjection projection =
        new DisplayProjection(
            document, DisplayProjection.layout(document.text(), 0, new CancellationToken()));
    editor =
        new EditorController(
            projection,
            selections,
            view,
            workers,
            ui,
            new Runnable() {
              @Override
              public void run() {
                // No save runs.
              }
            });
    tab = (FakeView.FakeTab) view.addTab(projection, selections, editor, null, 0);
    editor.attach(tab);
    editor.setNoticeListener(
        new Consumer<String>() {
          @Override
          public void accept(String notice) {
            notices.add(notice);
          }
        });
    selections.set(SelectionSet.of(list, primary));
  }

  /**
   * Returns the text with the selections marked, the primary followed by {@code *} unless first.
   */
  private String marked() {
    String text = document.text().toString();
    SelectionSet set = selections.selections();
    StringBuilder out = new StringBuilder();
    for (int i = 0; i <= text.length(); i++) {
      for (int k = 0; k < set.size(); k++) {
        Selection s = set.selections().get(k);
        String star = k == set.primaryIndex() && k > 0 ? "*" : "";
        if (s.isEmpty() && s.caret() == i) {
          out.append('|').append(star);
        } else if (!s.isEmpty() && s.start() == i) {
          out.append('[');
        } else if (!s.isEmpty() && s.end() == i) {
          out.append(']').append(star);
        }
      }
      if (i < text.length()) {
        out.append(text.charAt(i));
      }
    }
    return out.toString();
  }

  private void run(CommandId... commands) {
    for (CommandId command : commands) {
      assertTrue(editor.execute(command), command.name());
    }
  }

  private void awaitSelections(final int count) throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return selections.selections().size() == count;
              }
            },
            BOUND));
  }

  /** Waits until the view's last busy state is false. */
  private void awaitIdle() throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                List<Boolean> busy = tab.shown.busy;
                return !busy.isEmpty() && !busy.get(busy.size() - 1);
              }
            },
            BOUND));
  }

  private static String large() {
    return "fo|o " + "-".repeat(SelectionCommands.SYNC_CHARS) + " foo";
  }

  @Test
  void selectAllOccurrencesFromACaretTakesWholeWords() {
    open("x foo food fo|o.foo xfoo");
    run(CommandId.SELECT_ALL_OCCURRENCES);
    assertEquals("x [foo] food [foo]*.[foo] xfoo", marked());
    editor.type("y");
    assertEquals("x y food y.y xfoo", document.text().toString());
  }

  @Test
  void wordCommandsKeepCombiningMarksWithTheirWord() {
    open("x |cafe\u0301 y");
    run(CommandId.WORD_RIGHT);
    assertEquals("x cafe\u0301 |y", marked());
    run(CommandId.WORD_LEFT);
    assertEquals("x |cafe\u0301 y", marked());
    run(CommandId.ADD_NEXT_OCCURRENCE);
    assertEquals("x [cafe\u0301] y", marked());
    open("x |cafe\u0301 y");
    run(CommandId.DELETE_WORD_FORWARD);
    assertEquals("x |y", marked());
    open("cafe\u0301 |cafe cafe\u0301");
    run(CommandId.SELECT_ALL_OCCURRENCES);
    assertEquals("cafe\u0301 [cafe] cafe\u0301", marked());
  }

  @Test
  void selectAllOccurrencesFromARangeTakesEveryLiteralMatch() {
    open("[ab]c ab.ab");
    run(CommandId.SELECT_ALL_OCCURRENCES);
    assertEquals("[ab]c [ab].[ab]", marked());
  }

  @Test
  void occurrenceCommandsSkipAMatchInsideACrLf() throws InterruptedException {
    open("a[\r]b\r\nc\rd");
    run(CommandId.SELECT_ALL_OCCURRENCES);
    assertEquals("a[\r]b\r\nc[\r]d", marked());
    open("a[\r]b\r\nc\rd");
    run(CommandId.SKIP_OCCURRENCE);
    assertEquals("a\rb\r\nc[\r]d", marked());
    open("a[\n]b\r\nc\nd");
    run(CommandId.ADD_NEXT_OCCURRENCE);
    awaitSelections(2);
    assertEquals("a[\n]b\r\nc[\n]*d", marked());
  }

  @Test
  void touchingOccurrencesStaySeparateSelections() throws InterruptedException {
    open("[ab]ab");
    run(CommandId.SELECT_ALL_OCCURRENCES);
    assertEquals("[ab][ab]", marked());
    editor.type("x");
    assertEquals("x|x|", marked());
    open("[ab]ab");
    run(CommandId.ADD_NEXT_OCCURRENCE);
    awaitSelections(2);
    assertEquals("[ab][ab]*", marked());
    open("[ab]ab");
    run(CommandId.SELECT_ALL_OCCURRENCES, CommandId.SELECT_RIGHT);
    assertEquals(1, selections.selections().size());
  }

  @Test
  void selectAllOccurrencesPastTheLimitSelectsNothing() {
    open("|x " + "x ".repeat(SelectionCommands.MAX_OCCURRENCES));
    run(CommandId.SELECT_ALL_OCCURRENCES);
    assertEquals(1, selections.selections().size());
    assertEquals(
        List.of(UiText.OCCURRENCES_LIMIT.format(SelectionCommands.MAX_OCCURRENCES)), notices);
    open("|x " + "x ".repeat(SelectionCommands.MAX_OCCURRENCES - 1));
    run(CommandId.SELECT_ALL_OCCURRENCES);
    assertEquals(SelectionCommands.MAX_OCCURRENCES, selections.selections().size());
  }

  @Test
  void aLargeTextIsSearchedOnAWorker() throws InterruptedException {
    open(large());
    run(CommandId.SELECT_ALL_OCCURRENCES);
    awaitSelections(2);
    awaitIdle();
    assertEquals(List.of(true, false), tab.shown.busy);
  }

  @Test
  void aWorkerResultForAnOlderRevisionIsDroppedWithANotice() throws InterruptedException {
    open(large());
    run(CommandId.SELECT_ALL_OCCURRENCES);
    editor.type("x");
    awaitIdle();
    assertEquals(List.of(UiText.OCCURRENCES_STALE.text()), notices);
    assertEquals(1, selections.selections().size());
  }

  @Test
  void escCancelsAWorkerSearch() throws InterruptedException {
    open(large());
    run(CommandId.SELECT_ALL_OCCURRENCES, CommandId.CANCEL);
    awaitIdle();
    assertEquals(1, selections.selections().size());
    assertEquals(List.of(), notices);
  }

  @Test
  void skipOccurrenceReplacesTheLastAddedOne() throws InterruptedException {
    open("[foo] bar foo foo");
    run(CommandId.ADD_NEXT_OCCURRENCE);
    awaitSelections(2);
    assertEquals("[foo] bar [foo]* foo", marked());
    run(CommandId.SKIP_OCCURRENCE);
    assertEquals("[foo] bar foo [foo]*", marked());
    run(CommandId.SKIP_OCCURRENCE);
    assertEquals("[foo] bar foo foo", marked());
    open("ba|r foo");
    run(CommandId.SKIP_OCCURRENCE);
    assertEquals("[bar] foo", marked());
  }

  @Test
  void expandSelectionWidensAndUndoSelectionStepsBack() {
    open("f(a, b|c)\ng");
    run(CommandId.EXPAND_SELECTION);
    assertEquals("f(a, [bc])\ng", marked());
    run(CommandId.EXPAND_SELECTION, CommandId.EXPAND_SELECTION);
    assertEquals("f[(a, bc)]\ng", marked());
    run(CommandId.UNDO_SELECTION, CommandId.UNDO_SELECTION);
    assertEquals("f(a, [bc])\ng", marked());
    run(CommandId.UNDO_SELECTION);
    assertEquals("f(a, b|c)\ng", marked());
    run(CommandId.REDO_SELECTION);
    assertEquals("f(a, [bc])\ng", marked());
    assertTrue(editor.selectionHistory().canRedo());
  }

  @Test
  void aBracketBeyondTheScanIsSoughtOnAWorker() throws InterruptedException {
    String blanks = " ".repeat(EditorController.BRACKET_SCAN + 10);
    open("(" + blanks + "|" + blanks + ")");
    run(CommandId.EXPAND_SELECTION);
    awaitIdle();
    assertEquals("([" + blanks + blanks + "])", marked());
  }

  @Test
  void selectLineTakesTheLinesAndUndoSelectionReturnsTheCarets() {
    open("a|b\ncd|\nef");
    run(CommandId.SELECT_LINE);
    assertEquals("[ab\ncd\n]ef", marked());
    run(CommandId.SELECT_LINE);
    assertEquals("[ab\ncd\nef]", marked());
    run(CommandId.UNDO_SELECTION);
    assertEquals("[ab\ncd\n]ef", marked());
    run(CommandId.UNDO_SELECTION);
    assertEquals("a|b\ncd|\nef", marked());
  }

  @Test
  void anExtensionIsOneEntryAndAnEditClearsTheHistory() {
    open("a|bcd");
    run(CommandId.SELECT_RIGHT, CommandId.SELECT_RIGHT, CommandId.CARET_RIGHT);
    assertEquals("abc|d", marked());
    run(CommandId.UNDO_SELECTION);
    assertEquals("a[bc]d", marked());
    run(CommandId.UNDO_SELECTION);
    assertEquals("a|bcd", marked());
    run(CommandId.REDO_SELECTION);
    editor.type("x");
    assertFalse(editor.selectionHistory().canUndo());
    assertFalse(editor.selectionHistory().canRedo());
  }

  @Test
  void undoSelectionKeepsAtMostTheLimit() {
    open("|" + "x".repeat(SelectionHistory.LIMIT + 10));
    for (int i = 0; i < SelectionHistory.LIMIT + 5; i++) {
      run(CommandId.CARET_RIGHT);
    }
    int steps = 0;
    while (editor.selectionHistory().undo()) {
      steps++;
    }
    assertEquals(SelectionHistory.LIMIT, steps);
    assertEquals(5, selections.selections().primary().caret());
  }

  @Test
  void wordDeletesCaseAndTransposeApplyAtEveryCaretAsOneUndoStep() {
    open("one two| one two|");
    run(CommandId.DELETE_WORD_BACK);
    assertEquals("one | one |", marked());
    run(CommandId.UNDO);
    assertEquals("one two| one two|", marked());
    run(CommandId.WORD_LEFT, CommandId.DELETE_WORD_FORWARD);
    assertEquals("one |one |", marked());
    open("ab|c xy|z");
    run(CommandId.TRANSPOSE);
    assertEquals("acb| xzy|", marked());
    run(CommandId.TITLE_CASE);
    assertEquals("Acb| Xzy|", marked());
    run(CommandId.SWAP_CASE);
    assertEquals("aCB| xZY|", marked());
    run(CommandId.UNDO, CommandId.UNDO, CommandId.UNDO);
    assertEquals("abc xyz", document.text().toString());
  }

  @Test
  void aReadOnlyDocumentKeepsItsText() {
    open("ab|c de|f");
    document.setReadOnly(true);
    run(
        CommandId.DELETE_WORD_BACK,
        CommandId.DELETE_WORD_FORWARD,
        CommandId.TITLE_CASE,
        CommandId.SWAP_CASE,
        CommandId.TRANSPOSE);
    assertEquals("abc def", document.text().toString());
  }
}
