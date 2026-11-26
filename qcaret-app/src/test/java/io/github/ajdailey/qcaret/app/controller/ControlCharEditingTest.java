package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.RowBreaks;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A control char through the editor controller: one model char that caret moves, Backspace, Delete
 * and copy treat whole, and two columns for vertical moves, Go to Line, block selection and wrap.
 */
class ControlCharEditingTest {
  private FakeView view;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Document document;
  private SelectionModel selections;
  private EditorController editor;

  @BeforeEach
  void setUp() {
    view = new FakeView(new ManualUiDispatcher());
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

  /** Opens {@code text} with the caret at {@code |} or the selection between {@code [ ]}. */
  private void open(String marked) {
    int caret = marked.indexOf('|');
    int anchor = marked.indexOf('[');
    String text = marked.replace("|", "").replace("[", "").replace("]", "");
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
            new ManualUiDispatcher(),
            new Runnable() {
              @Override
              public void run() {
                // No save runs.
              }
            });
    FakeView.FakeTab tab = (FakeView.FakeTab) view.addTab(projection, selections, editor, null, 0);
    editor.attach(tab);
    selections.set(
        SelectionSet.of(
            List.of(
                caret >= 0
                    ? Selection.caret(caret)
                    : Selection.of(anchor, marked.indexOf(']') - 1)),
            0));
  }

  private String text() {
    return document.text().toString();
  }

  private int caret() {
    return selections.selections().primary().caret();
  }

  private void run(CommandId... commands) {
    for (CommandId command : commands) {
      assertTrue(editor.execute(command), command.name());
    }
  }

  @Test
  void aCaretStepsOverAControlInOnePressAndBackspaceAndDeleteRemoveItWhole() {
    open("a|\u0001b");
    run(CommandId.CARET_RIGHT);
    assertEquals(2, caret());
    run(CommandId.CARET_LEFT);
    assertEquals(1, caret());
    run(CommandId.CARET_RIGHT, CommandId.DELETE_BACK);
    assertEquals("ab", text());
    open("a|\u0081b");
    run(CommandId.DELETE_FORWARD);
    assertEquals("ab", text());
  }

  @Test
  void aVerticalMoveCountsTheControlAsTwoColumns() {
    open("xxx|x\na\u0001bc");
    run(CommandId.CARET_DOWN);
    assertEquals(5 + 2, caret());
    open("xxxx\na\u0001b|c");
    run(CommandId.CARET_UP);
    assertEquals(4, caret());
  }

  @Test
  void copyPutsTheOriginalCharOnTheClipboard() {
    open("a[\u001b]b");
    run(CommandId.COPY);
    assertEquals("\u001b", view.clipboard);
  }

  @Test
  void goToLineAtAColumnInsideTheControlStopsBeforeIt() {
    open("|a\u007fb");
    editor.goTo(0, 2);
    assertEquals(1, caret());
    editor.goTo(0, 3);
    assertEquals(2, caret());
  }

  @Test
  void aBlockStepsOverTheControlAsTwoColumns() {
    open("|a\u0001b\nabcd");
    run(CommandId.BLOCK_RIGHT, CommandId.BLOCK_RIGHT);
    assertEquals(new BlockSelection(0, 0, 0, 3), selections.block());
    Selection primary = selections.selections().primary();
    assertEquals("a\u0001", text().substring(primary.start(), primary.end()));
  }

  @Test
  void aRowHoldsAtMostTheControlCapWithoutWrap() {
    String line = "x" + "\u0081".repeat(RowBreaks.MAX_ROW_CONTROLS + 10);
    assertArrayEquals(
        new int[] {1 + RowBreaks.MAX_ROW_CONTROLS}, RowBreaks.compute(line, 0, 16_384, 4));
    open("|" + line + "\nplain");
    assertEquals(3, editor.projection().rowCount());
  }

  @Test
  void wrapCountsTheControlAsTwoColumns() {
    assertArrayEquals(new int[] {2, 4}, RowBreaks.compute("ab\u0001cd", 3, 16_384, 4));
    assertArrayEquals(new int[] {1}, RowBreaks.compute("\u0081\u0081", 3, 16_384, 4));
  }
}
