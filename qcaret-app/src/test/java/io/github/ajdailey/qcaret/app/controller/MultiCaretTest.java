package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Caret moves, block selection, the selection commands and input method composition through the
 * editor controller. Text is written with its selections marked: {@code |} a caret, {@code [} and
 * {@code ]} a range's anchor and caret; the primary is the first unless {@code *} follows another.
 */
class MultiCaretTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private ManualUiDispatcher ui;
  private FakeView view;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Document document;
  private SelectionModel selections;
  private EditorController editor;
  private FakeView.FakeTab tab;
  private DisplayProjection projection;

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
    workers.close();
    assertNull(failure.get());
  }

  private void open(String marked) {
    open(marked, 0);
  }

  private void open(String marked, int wrap) {
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
    projection =
        new DisplayProjection(
            document, DisplayProjection.layout(document.text(), wrap, new CancellationToken()));
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
          out.append(s.anchor() < s.caret() ? "[" : "]");
        } else if (!s.isEmpty() && s.end() == i) {
          out.append(s.anchor() < s.caret() ? "]" : "[").append(star);
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

  @Test
  void everyCaretMovesAndSelects() {
    open("a|bc\nd|ef");
    run(CommandId.CARET_RIGHT);
    assertEquals("ab|c\nde|f", marked());
    run(CommandId.SELECT_RIGHT);
    assertEquals("ab[c]\nde[f]", marked());
    run(CommandId.CARET_LEFT);
    assertEquals("ab|c\nde|f", marked());
    run(CommandId.SELECT_LINE_START);
    assertEquals("]ab[c\n]de[f", marked());
    run(CommandId.LINE_END, CommandId.CARET_UP);
    assertEquals("|abc|\ndef", marked());
    assertTrue(tab.caretShown > 0);
  }

  @Test
  void verticalMovesKeepTheColumnAndPagesUseTheViewsRows() {
    open("abcde|f\nab\nabcdef\n1\n2\n3\n4\n5\n6");
    run(CommandId.CARET_DOWN);
    assertEquals("abcdef\nab|\nabcdef\n1\n2\n3\n4\n5\n6", marked());
    run(CommandId.CARET_DOWN);
    assertEquals("abcdef\nab\nabcde|f\n1\n2\n3\n4\n5\n6", marked());
    run(CommandId.PAGE_DOWN);
    assertEquals("abcdef\nab\nabcdef\n1\n2\n3\n4\n5|\n6", marked());
    run(CommandId.SELECT_DOCUMENT_START);
    assertEquals(0, selections.selections().primary().caret());
    run(CommandId.DOCUMENT_END, CommandId.CARET_DOWN);
    assertEquals(document.text().length(), selections.selections().primary().caret());
  }

  @Test
  void blockKeysReachIntoVirtualSpaceAndTypingPads() {
    open("abcd|ef\nab\nabcdef");
    run(CommandId.BLOCK_DOWN, CommandId.BLOCK_DOWN, CommandId.BLOCK_RIGHT);
    assertEquals(new BlockSelection(0, 4, 2, 5), selections.block());
    editor.type("X");
    assertEquals("abcdXf\nab  X\nabcdXf", document.text().toString());
    assertEquals(new BlockSelection(0, 5, 2, 5), selections.block());
    editor.type("Y");
    editor.undo();
    assertEquals("abcdef\nab\nabcdef", document.text().toString());
    assertEquals(3, selections.selections().size());
  }

  @Test
  void aMouseDragSelectsABlockByTheViewsColumns() {
    open("|abcdefgh\nab\nabcdefgh");
    // The fake layout is a 10 px grid.
    editor.blockFromView(40, 0, 61, 25);
    assertEquals(new BlockSelection(0, 4, 2, 6), selections.block());
    editor.copy();
    assertEquals("ef\n\nef", view.clipboard);
    editor.extendBlockFromView(10, 15);
    assertEquals(new BlockSelection(0, 4, 1, 1), selections.block());
    editor.cut();
    assertEquals("bcd\nb", view.clipboard);
    assertEquals("aefgh\na\nabcdefgh", document.text().toString());
    assertEquals(new BlockSelection(0, 1, 1, 1), selections.block());
  }

  @Test
  void columnModeMakesShiftArrowsSelectABlock() {
    open("ab|c\nabc");
    run(CommandId.COLUMN_MODE);
    assertTrue(tab.columnMode);
    run(CommandId.SELECT_DOWN, CommandId.SELECT_LEFT);
    assertEquals(new BlockSelection(0, 2, 1, 1), selections.block());
    run(CommandId.COLUMN_MODE);
    assertFalse(tab.columnMode);
  }

  @Test
  void addCaretAboveBelowAndTheClickToggle() {
    open("abc\nab|c\nabc");
    run(CommandId.ADD_CARET_ABOVE, CommandId.ADD_CARET_BELOW);
    assertEquals("ab|c\nab|c\nab|*c", marked());
    editor.toggleCaretFromView(6);
    assertEquals("ab|c\nabc\nab|*c", marked());
    editor.toggleCaretFromView(0);
    assertEquals("|ab|c\nabc\nab|c", marked());
    run(CommandId.CANCEL);
    assertEquals(1, selections.selections().size());
  }

  @Test
  void nextOccurrenceSelectsTheWordThenAddsMatchesFromAWorker() throws InterruptedException {
    open("fo|o bar foo foo");
    run(CommandId.ADD_NEXT_OCCURRENCE);
    assertEquals("[foo] bar foo foo", marked());
    for (int i = 0; i < 2; i++) {
      final int count = selections.selections().size() + 1;
      run(CommandId.ADD_NEXT_OCCURRENCE);
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
    assertEquals("[foo] bar [foo] [foo]*", marked());
    editor.type("x");
    assertEquals("x bar x x", document.text().toString());
  }

  @Test
  void lineCommandsAndUndoRestoreEveryCaret() {
    open("o|ne\nt|wo\nthree");
    run(CommandId.MOVE_LINES_DOWN);
    assertEquals("three\no|ne\nt|wo", marked());
    run(CommandId.DUPLICATE, CommandId.UPPER_CASE);
    assertEquals("three\nO|NE\none\nT|WO\ntwo", marked());
    editor.undo();
    editor.undo();
    editor.undo();
    assertEquals("o|ne\nt|wo\nthree", marked());
    run(CommandId.SPLIT_INTO_LINES, CommandId.SELECT_DOCUMENT_END, CommandId.SPLIT_INTO_LINES);
    assertEquals(3, selections.selections().size());
  }

  @Test
  void pasteGivesEachCaretOneLineWhenTheCountsMatch() {
    open("a|\nb|\nc");
    view.clipboard = "1\r\n2";
    editor.paste();
    assertEquals("a1|\nb2|\nc", marked());
    view.clipboard = "x\ny\nz";
    editor.paste();
    assertEquals("a1x\ny\nz|\nb2x\ny\nz|\nc", marked());
  }

  /** Ends a composition as every platform commits: the widget removes it, then sends keys. */
  private void commit(String composed) {
    editor.preeditFromView("");
    for (char c : composed.toCharArray()) {
      editor.type(String.valueOf(c));
    }
  }

  private void assertViewMatchesDocument() {
    assertEquals(document.text().lineCount(), projection.rowCount(), "rows against lines");
    assertEquals(
        document.text().toString(), projection.textRange(0, projection.length()), "display text");
  }

  @Test
  void compositionTextIsTransientAndTheCommitIsOneUndoStep() {
    open("ab|");
    editor.preeditFromView("k");
    editor.preeditFromView("ka");
    assertEquals("abka", document.text().toString());
    assertFalse(document.isDirty());
    assertFalse(document.canUndo());
    commit("ka");
    assertEquals("abka|", marked());
    assertTrue(document.isDirty());
    editor.undo();
    assertEquals("ab|", marked());
    editor.preeditFromView("zz");
    editor.type("q");
    assertEquals("abq|", marked());
  }

  /** A closing tab's widget loses the focus after the close, and the input method ends then. */
  @Test
  void aDisposedEditorDropsInputMethodText() {
    open("ab|");
    editor.preeditFromView("k");
    document.clearPreedit();
    editor.dispose();
    editor.preeditFromView("");
    editor.preeditFromView("ka");
    editor.type("ka");
    assertEquals("ab", document.text().toString());
  }

  @Test
  void aCanceledCompositionOverASelectionLeavesTheTextAndTheSelection() {
    open("[hello] world");
    editor.preeditFromView("n");
    assertEquals("nhello world", document.text().toString());
    editor.preeditFromView("");
    assertEquals("[hello] world", marked());
    assertFalse(document.isDirty());
    assertFalse(document.canUndo());
  }

  @Test
  void aCommitOverASelectionReplacesItAsOneUndoStep() {
    open("[hello] world");
    editor.preeditFromView("ka");
    commit("ka");
    assertEquals("ka| world", marked());
    editor.undo();
    assertEquals("[hello] world", marked());
  }

  @Test
  void undoAfterACompositionOverASelectionUndoesTheRightText() {
    open("abc def|");
    editor.type("x");
    selections.set(SelectionSet.of(List.of(Selection.of(0, 4)), 0));
    editor.preeditFromView("n");
    editor.preeditFromView("");
    editor.execute(CommandId.UNDO);
    assertEquals("abc def", document.text().toString());
  }

  @Test
  void aCompositionAcrossAVirtualBreakLeavesNoCharBehind() {
    open("abcdefg|", 8);
    editor.preeditFromView("ab");
    assertEquals("abcdefgab", document.text().toString());
    assertTrue(projection.rowCount() > 1, "the composition wraps");
    editor.preeditFromView("");
    assertEquals("abcdefg", document.text().toString());
    assertFalse(document.isDirty());
  }

  @Test
  void undoAfterAWrappedCompositionUndoesTheRightText() {
    open("abcdefg|", 8);
    editor.type("h");
    editor.execute(CommandId.CARET_LEFT);
    editor.preeditFromView("xy");
    editor.preeditFromView("");
    editor.execute(CommandId.UNDO);
    assertEquals("abcdefg", document.text().toString());
  }

  @Test
  void aCompositionUpdateAfterTheModelClearedItRemovesNoRealText() {
    open("he|llo");
    editor.preeditFromView("k");
    document.clearPreedit();
    editor.preeditFromView("ka");
    editor.preeditFromView("");
    assertEquals("hello", document.text().toString());
    assertFalse(document.isDirty());
  }

  @Test
  void undoInBlockModeWithTheBlockLinesGoneLeavesBlockMode() {
    open("one|");
    view.clipboard = "\nx\ny\nz";
    editor.execute(CommandId.PASTE);
    selections.setBlock(new BlockSelection(2, 0, 3, 1), document.tabWidth());
    editor.execute(CommandId.UNDO);
    assertEquals("one", document.text().toString());
    assertNull(selections.block());
    assertViewMatchesDocument();
  }

  @Test
  void deleteLineInBlockModeOnTheLastLinesLeavesBlockMode() {
    open("a\nb\nc\nd|");
    selections.setBlock(new BlockSelection(2, 0, 3, 1), document.tabWidth());
    editor.execute(CommandId.DELETE_LINE);
    assertNull(selections.block());
    assertViewMatchesDocument();
  }

  @Test
  void aBlockDeleteThatJoinsACrAndAnLfKeepsTheLinesLeft() {
    open("a\rb\n|");
    selections.setBlock(new BlockSelection(1, 0, 2, 1), document.tabWidth());
    editor.execute(CommandId.DELETE_FORWARD);
    assertEquals("a\r\n", document.text().toString());
    assertEquals(new BlockSelection(1, 0, 1, 0), selections.block());
    assertViewMatchesDocument();
  }

  @Test
  void typingAfterASortKeepsASurrogatePairWhole() throws InterruptedException {
    open("\uD83D\uDE00\na|b");
    final long before = document.revision();
    editor.execute(CommandId.SORT_LINES_ASCENDING);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return document.revision() != before;
              }
            },
            BOUND),
        "sorted");
    editor.type("x");
    assertEquals("ab\nx|\uD83D\uDE00", marked());
  }

  @Test
  void aSurrogatePairTypedOneCharAtATimeStaysWholeOnEveryBlockRow() {
    open("ab\n\tx|");
    selections.setBlock(new BlockSelection(0, 3, 1, 3), document.tabWidth());
    editor.type("\uD83D");
    assertEquals("ab\n\tx", document.text().toString());
    editor.type("\uDE00");
    assertEquals("ab \uD83D\uDE00\n\uD83D\uDE00\tx", document.text().toString());
  }

  @Test
  void aVerticalMoveToAWrappedRowsEndStopsBeforeASurrogatePair() {
    open("ab\uD83D\uDE00cdef|", 4);
    selections.set(SelectionSet.of(List.of(new Selection(8, 8, 9)), 0));
    editor.execute(CommandId.CARET_UP);
    assertEquals("ab|\uD83D\uDE00cdef", marked());
  }

  @Test
  void deleteForwardSkipsACaretInsideThePairTheCaretBeforeItDeletes() {
    open("|\uD83D|\uDE00x");
    editor.execute(CommandId.DELETE_FORWARD);
    assertEquals("|x", marked());
    open("a|\r|\nx");
    editor.execute(CommandId.DELETE_FORWARD);
    assertEquals("a|x", marked());
  }

  @Test
  void goToClampsToTheTextAndTabsFollowTheDocument() {
    open("a\n\tbc\nd|");
    editor.goTo(1, 4);
    assertEquals("a\n\t|bc\nd", marked());
    editor.goTo(99, 0);
    assertEquals("a\n\tbc\n|d", marked());
    document.setTabWidth(2);
    editor.tabWidthChanged();
    assertEquals(2, tab.tabWidth);
    assertEquals(2, editor.projection().tabWidth());
    editor.goTo(1, 2);
    assertEquals("a\n\t|bc\nd", marked());
  }
}
